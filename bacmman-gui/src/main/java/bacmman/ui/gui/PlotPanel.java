package bacmman.ui.gui;

import bacmman.configuration.parameters.*;
import bacmman.ui.GUI;
import bacmman.ui.PropertyUtils;
import bacmman.ui.gui.configuration.ConfigurationTreeGenerator;
import bacmman.ui.logger.ProgressLogger;
import bacmman.utils.*;
import bacmman.utils.Utils;
import com.intellij.uiDesigner.core.GridConstraints;
import com.intellij.uiDesigner.core.GridLayoutManager;
import org.jfree.chart.*;
import org.jfree.chart.annotations.XYLineAnnotation;
import org.jfree.chart.annotations.XYTextAnnotation;
import org.jfree.chart.axis.NumberAxis;
import org.jfree.chart.entity.ChartEntity;
import org.jfree.chart.entity.XYItemEntity;
import org.jfree.chart.plot.PlotOrientation;
import org.jfree.chart.plot.XYPlot;
import org.jfree.chart.renderer.xy.DeviationRenderer;
import org.jfree.chart.renderer.xy.XYLineAndShapeRenderer;
import org.jfree.chart.ui.TextAnchor;
import org.jfree.data.Range;
import org.jfree.data.general.Series;
import org.jfree.data.xy.*;
import org.json.simple.parser.ParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.awt.geom.Line2D;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class PlotPanel {
    public static final Logger logger = LoggerFactory.getLogger(PlotPanel.class);
    private JPanel mainPanel;
    private JButton updateButton;
    private JScrollPane parameterJSP;
    private JScrollPane plotJSP;
    ConfigurationTreeGenerator config;
    FileChooser defWD = new FileChooser("Directory", FileChooser.FileChooserOption.DIRECTORIES_ONLY, false).setRelativePath(false);
    SimpleListParameter<FileChooser> workingDirs = new SimpleListParameter<>("Working Directories", 0, defWD);
    TextParameter defTxt = new TextParameter("Keyword", "", true);
    SimpleListParameter<TextParameter> fileFilterInclude = new SimpleListParameter<>("File Filter Include", 0, defTxt);
    SimpleListParameter<TextParameter> fileFilterExclude = new SimpleListParameter<>("File Filter Exclude", defTxt);
    FileListParameter selectedFiles = new FileListParameter("Data Files");
    FloatParameter smoothScale = new FloatParameter("Smooth Scale", 2).setLowerBound(0);

    enum CHART_TYPE {LINE}

    EnumChoiceParameter<CHART_TYPE> chartType = new EnumChoiceParameter<>("Chart Type", CHART_TYPE.values(), CHART_TYPE.LINE);
    ColumnListParameter xColumn = new ColumnListParameter("X-Axis", false);
    ColumnListParameter yColumns = new ColumnListParameter("Y-Axis", true).setExcludeColFun(() -> xColumn.getSelectedItem() != null ? xColumn.getSelectedItems() : new String[0]);

    ConditionalParameter<CHART_TYPE> chartTypeCond = new ConditionalParameter<>(chartType).setActionParameters(CHART_TYPE.LINE, xColumn, yColumns, smoothScale);
    GroupParameter root = new GroupParameter("Parameters", workingDirs, fileFilterInclude, fileFilterExclude, selectedFiles, chartTypeCond);
    Color[] colorPalette = new Color[]{new Color(31, 119, 180), new Color(255, 127, 14), new Color(44, 160, 44), new Color(214, 39, 40), new Color(148, 103, 189), new Color(140, 86, 75), new Color(227, 119, 194), new Color(127, 127, 127), new Color(188, 189, 34), new Color(23, 190, 207)};
    ProgressLogger bacmmanLogger;
    JFreeChart lastPlot;
    int plotIdx;
    String name;

    // TODO: add right axis
    public PlotPanel(int plotIdx, int loadPlotIdx, ProgressLogger bacmmanLogger) {
        this.plotIdx = plotIdx;
        this.name = "Plot";
        if (plotIdx < 0) throw new IllegalArgumentException("Plot idx must be >=0");
        this.bacmmanLogger = bacmmanLogger;
        config = new ConfigurationTreeGenerator(null, root, v -> {
            updateButton.setEnabled(v);
        }, (s, l) -> {
        }, s -> {
        }, null, null).rootVisible(false);
        parameterJSP.setViewportView(config.getTree());
        TextParameter csv = defTxt.duplicate();
        csv.setValue(".csv");
        fileFilterInclude.insert(csv);
        updateButton.addActionListener(e -> updateChart());
        if (loadConfiguration(loadPlotIdx) && root.isValid()) updateChart();
    }

    public void setName(String name) {
        this.name = name;
        saveConfiguration();
    }

    public String getName() {
        return name;
    }

    public String getConfigDir() {
        if (GUI.hasInstance()) return GUI.getInstance().getWorkingDirectory();
        String wd = PropertyUtils.get(PropertyUtils.LOCAL_DATA_PATH, "");
        if (wd.isEmpty()) return workingDirs.getChildAt(0).getFirstSelectedFilePath();
        else return wd;
    }

    protected String getConfigFile() {
        return getConfigFile(plotIdx);
    }

    protected String getConfigFile(int plotIdx) {
        return Paths.get(getConfigDir()).resolve(".bacmman.plot" + plotIdx + ".json").toString();
    }

    public void saveConfiguration() {
        FileIO.writeToFile(getConfigFile(), Arrays.asList(name, root.toJSONEntry().toJSONString()), s -> s);
    }

    public void loadConfiguration() {
        loadConfiguration(plotIdx);
    }

    public boolean loadConfiguration(int plotIdx) {
        if (!new File(getConfigFile(plotIdx)).isFile()) return false;
        List<String> confList = FileIO.readFromFile(getConfigFile(plotIdx), s -> s, s -> {
        });
        if (confList.size() != 2) return false;
        name = confList.get(0);
        String conf = confList.get(1);
        try {
            root.initFromJSONEntry(JSONUtils.parseJSON(conf));
            return true;
        } catch (ParseException e) {

        }
        return false;
    }

    protected void updateChart() {
        saveConfiguration();
        switch (chartType.getSelectedEnum()) {
            case LINE:
            default: {
                String xCol = xColumn.getSelectedItem();
                List<String> columns = Arrays.asList(yColumns.getSelectedItems());
                List<String> allColumns = new ArrayList<>(columns);
                allColumns.add(xCol);
                boolean intervalSeries = smoothScale.getDoubleValue() >= 1;
                final IntervalXYDataset dataset = intervalSeries ? new YIntervalSeriesCollection() : new XYSeriesCollection();
                Arrays.stream(selectedFiles.getSelectedItems()).parallel().flatMap(f -> {
                    String name = Utils.removeExtension(Paths.get(f).getFileName().toString());
                    Map<String, double[]> cols = getColumns(f, allColumns);
                    double[] x = cols.get(xCol);
                    if (x != null) {
                        return columns.stream().map(col -> {
                            double[] vals = cols.get(col);
                            if (vals != null)
                                return getXYSeries(name + ":" + col, x, vals, smoothScale.getDoubleValue());
                            else return null;
                        }).filter(Objects::nonNull);
                    } else return Stream.empty();
                }).forEachOrdered(s -> {
                    if (intervalSeries) ((YIntervalSeriesCollection) dataset).addSeries((YIntervalSeries) s);
                    else ((XYSeriesCollection) dataset).addSeries((XYSeries) s);
                });
                Range domain = null, range = null;
                if (this.lastPlot != null) {
                    XYPlot plot = lastPlot.getXYPlot();
                    range = plot.getRangeAxis().getRange();
                    domain = plot.getDomainAxis().getRange();
                }
                JFreeChart xylineChart = ChartFactory.createXYLineChart(
                        "",
                        xCol,
                        "Values",
                        dataset,
                        PlotOrientation.VERTICAL,
                        true, true, false);
                XYPlot plot = xylineChart.getXYPlot();
                plot.getRangeAxis().setAutoRange(true);
                ((NumberAxis) plot.getRangeAxis()).setAutoRangeIncludesZero(false);
                if (range != null) plot.getRangeAxis().setRange(range);
                if (domain != null) plot.getDomainAxis().setRange(domain);
                ChartPanel chartPanel = new ChartPanel(xylineChart);
                chartPanel.setMinimumDrawWidth(300);
                chartPanel.setMinimumDrawHeight(300);
                chartPanel.setPreferredSize(new Dimension(600, 350));
                chartPanel.setHorizontalAxisTrace(true);
                chartPanel.setVerticalAxisTrace(true);
                XYLineAndShapeRenderer renderer = intervalSeries ? new DeviationRenderer(true, false) : new XYLineAndShapeRenderer(true, false);

                renderer.setAutoPopulateSeriesStroke(false);
                float stokeWidth = 2f;
                renderer.setDefaultStroke(new BasicStroke(stokeWidth));
                for (int i = 0; i < dataset.getSeriesCount(); ++i) {
                    renderer.setSeriesPaint(i, getColor(i));
                    if (intervalSeries) renderer.setSeriesFillPaint(i, getColor(i));
                }

                chartPanel.addChartMouseListener(new ChartMouseListener() {
                    XYTextAnnotation labelX, labelY;
                    XYLineAnnotation xLine, yLine;
                    int lastSelectedSeries = -1;

                    @Override
                    public void chartMouseClicked(ChartMouseEvent event) {
                        // select series
                        if (lastSelectedSeries >= 0)
                            renderer.setSeriesStroke(lastSelectedSeries, new BasicStroke(stokeWidth));
                        ChartEntity chartentity = event.getEntity();
                        if (chartentity instanceof XYItemEntity) {
                            XYItemEntity e = (XYItemEntity) chartentity;
                            int s = e.getSeriesIndex();
                            if (lastSelectedSeries != s) {
                                renderer.setSeriesStroke(s, new BasicStroke(stokeWidth + 2));
                                lastSelectedSeries = s;
                            }
                        }
                    }

                    @Override
                    public void chartMouseMoved(ChartMouseEvent event) {
                        if (labelX != null) {
                            renderer.removeAnnotation(labelX);
                            labelX = null;
                        }
                        if (labelY != null) {
                            renderer.removeAnnotation(labelY);
                            labelY = null;
                        }
                        if (xLine != null) {
                            renderer.removeAnnotation(xLine);
                            xLine = null;
                        }
                        if (yLine != null) {
                            renderer.removeAnnotation(yLine);
                            yLine = null;
                        }
                        ChartEntity chartentity = event.getEntity();
                        if (chartentity instanceof XYItemEntity) {
                            XYItemEntity e = (XYItemEntity) chartentity;
                            XYDataset d = e.getDataset();
                            Range xRange = plot.getDomainAxis().getRange();
                            Range yRange = plot.getRangeAxis().getRange();
                            int s = e.getSeriesIndex();
                            int i = e.getItem();
                            double x = d.getXValue(s, i);
                            boolean xRight = x >= xRange.getCentralValue();
                            labelX = new XYTextAnnotation(" " + (int) x + " ", x, yRange.getLowerBound());
                            labelX.setBackgroundPaint(Color.black);
                            labelX.setOutlinePaint(renderer.getSeriesPaint(s));
                            labelX.setOutlineStroke(new BasicStroke(stokeWidth + 1));
                            labelX.setOutlineVisible(true);
                            labelX.setPaint(Color.white);
                            labelX.setFont(renderer.getDefaultItemLabelFont().deriveFont(16f));
                            labelX.setTextAnchor(xRight ? TextAnchor.BOTTOM_RIGHT : TextAnchor.BOTTOM_LEFT);
                            renderer.addAnnotation(labelX);
                            double y = d.getYValue(s, i);
                            boolean yDown = y >= yRange.getLowerBound() + yRange.getLength() * 0.90;
                            labelY = new XYTextAnnotation(" " + Utils.format(y, 3) + " ", xRange.getLowerBound(), y);
                            labelY.setBackgroundPaint(Color.black);
                            labelY.setPaint(Color.white);
                            labelY.setFont(renderer.getDefaultItemLabelFont().deriveFont(16f));
                            labelY.setOutlinePaint(renderer.getSeriesPaint(s));
                            labelY.setOutlineStroke(new BasicStroke(stokeWidth + 1));
                            labelY.setOutlineVisible(true);
                            labelY.setTextAnchor(yDown ? TextAnchor.TOP_LEFT : TextAnchor.BOTTOM_LEFT);
                            renderer.addAnnotation(labelY);
                            xLine = new XYLineAnnotation(xRange.getLowerBound(), y, xRange.getUpperBound(), y, new BasicStroke(1), renderer.getSeriesPaint(s));
                            renderer.addAnnotation(xLine);
                            yLine = new XYLineAnnotation(x, yRange.getLowerBound(), x, yRange.getUpperBound(), new BasicStroke(1), renderer.getSeriesPaint(s));
                            renderer.addAnnotation(yLine);
                        }
                    }
                });
                plot.setRenderer(renderer);
                plotJSP.setViewportView(chartPanel);
                this.lastPlot = xylineChart;
            }
        }
    }

    protected Color getColor(int idx) {
        return colorPalette[idx % colorPalette.length];
    }

    protected Series getXYSeries(String name, double[] x, double[] y, double scale) {
        if (scale < 1) {
            XYSeries res = new XYSeries(name);
            for (int i = 0; i < x.length; ++i) res.add(x[i], y[i]);
            return res;
        } else {
            int sc = (int) (scale + 0.5);
            double div = Math.sqrt(2 * sc + 1);
            List<Double> std = SlidingOperator.performSlide(ArrayUtil.toList(y), sc, SlidingOperator.slidingStd());
            double s = Math.min(x.length / 2., smoothScale.getDoubleValue());
            ArrayUtil.gaussianSmooth(y, s);
            YIntervalSeries res = new YIntervalSeries(name);
            for (int i = 0; i < x.length; ++i) {
                double range = std.get(i) / div;
                res.add(x[i], y[i], y[i] - range, y[i] + range);
            }
            return res;
        }
    }

    protected Map<String, double[]> getColumns(String file, List<String> columns) {
        List<String> lines = FileIO.readFromFile(file, s -> s, s -> {
        });
        if (lines.size() <= 1) return Collections.emptyMap();
        List<String> allColumns = Arrays.asList(lines.remove(0).split(getParseRegex()));
        columns.removeIf(c -> !allColumns.contains(c));
        if (columns.isEmpty()) return Collections.emptyMap();
        int[] colIdx = columns.stream().mapToInt(allColumns::indexOf).toArray();
        Map<String, double[]> res = new HashMapGetCreate.HashMapGetCreateRedirected<>(s -> new double[lines.size()]);
        for (int i = 0; i < lines.size(); ++i) {
            String[] numbers = lines.get(i).split(getParseRegex());
            for (int j = 0; j < colIdx.length; ++j) {
                res.get(columns.get(j))[i] = Double.parseDouble(numbers[colIdx[j]]);
            }
        }
        return res;
    }

    protected String getParseRegex() {
        return ";";
    }

    protected Stream<String> workingDirs() {
        return workingDirs.getChildren().stream().filter(f -> f.getSelectedFilePath() != null).flatMap(f -> Arrays.stream(f.getSelectedFilePath())).distinct();
    }

    protected Stream<String> filter(boolean include) {
        return include ? fileFilterInclude.getChildren().stream().map(TextParameter::getValue) : fileFilterExclude.getChildren().stream().map(TextParameter::getValue);
    }

    public class FileListParameter extends AbstractChoiceParameterMultiple<String, FileListParameter> {
        public FileListParameter(String name) {
            super(name, s -> s, false, true);
        }

        @Override
        public String[] getChoiceList() {
            List<String> filterInclude = filter(true).collect(Collectors.toList());
            List<String> filterExclude = filter(false).collect(Collectors.toList());
            return workingDirs().flatMap(dir -> {
                try {
                    return Files.list(Paths.get(dir)).sorted();
                } catch (IOException e) {
                    return Stream.empty();
                }
            }).filter(p -> {
                String fileName = p.getFileName().toString();
                if (fileName.startsWith(".")) return false;
                if (!filterInclude.stream().allMatch(fileName::contains)) return false;
                return filterExclude.stream().noneMatch(fileName::contains);
            }).map(Path::toString).toArray(String[]::new);
        }

        @Override
        public boolean isValid() {
            if (!super.isValid()) return false;
            List<String> choice = Arrays.asList(getChoiceList());
            for (String s : getSelectedItems()) {
                if (!choice.contains(s)) return false;
            }
            return true;
        }

        @Override
        public FileListParameter duplicate() {
            FileListParameter dup = new FileListParameter(name);
            dup.setContentFrom(this);
            transferStateArguments(this, dup);
            return dup;
        }
    }

    public class ColumnListParameter extends AbstractChoiceParameterMultiple<String, ColumnListParameter> {
        Set<String> excludeCols;
        Supplier<String[]> excludeColFun;

        public ColumnListParameter(String name, boolean multiple) {
            super(name, s -> s, false, multiple);
        }

        public ColumnListParameter setExcludeColFun(Supplier<String[]> excludeColFun) {
            this.excludeColFun = excludeColFun;
            return this;
        }

        public ColumnListParameter setExcludeColumns(String... excludeCols) {
            this.excludeCols = new HashSet<>(Arrays.asList(excludeCols));
            return this;
        }

        @Override
        public String[] getChoiceList() {
            if (excludeColFun != null) excludeCols = new HashSet<>(Arrays.asList(excludeColFun.get()));
            return Arrays.stream(selectedFiles.getSelectedItems()).map(f -> FileIO.readFisrtFromFile(f, s -> s)).filter(Objects::nonNull)
                    .flatMap(header -> Arrays.stream(header.split(getParseRegex())))
                    .filter(c -> excludeCols == null || !excludeCols.contains(c))
                    .distinct().sorted().toArray(String[]::new);
        }

        @Override
        public boolean isValid() {
            if (!super.isValid()) return false;
            List<String> choice = Arrays.asList(getChoiceList());
            for (String s : getSelectedItems()) {
                if (!choice.contains(s)) return false;
            }
            return true;
        }

        @Override
        public ColumnListParameter duplicate() {
            ColumnListParameter dup = new ColumnListParameter(name, isMultipleSelection());
            dup.setContentFrom(this);
            transferStateArguments(this, dup);
            return dup;
        }
    }

    public JPanel getMainPanel() {
        return mainPanel;
    }

    public void close() {

    }

    public PlotPanel addWorkingDir(String workingDir) {
        if (workingDir == null) return this;
        if (workingDirs().noneMatch(wd -> wd.equals(workingDir))) {
            FileChooser fc = defWD.duplicate();
            fc.setSelectedFilePath(workingDir);
            this.workingDirs.insert(fc);
        }
        return this;
    }

    public PlotPanel addModelFile(String workingDir, String modelName) {
        addWorkingDir(workingDir);
        if (modelName == null) return this;
        if (filter(true).noneMatch(f -> f.equals(".csv"))) {
            TextParameter filter = defTxt.duplicate();
            filter.setValue(".csv");
            fileFilterInclude.insert(filter);
        }
        if (xColumn.getSelectedItem() == null) xColumn.setSelectedItem("Epoch");
        List<String> allFiles = Arrays.asList(selectedFiles.getChoiceList());
        String evalPath = Paths.get(workingDir).resolve(modelName + ".eval.csv").toString();
        String logPath = Paths.get(workingDir).resolve(modelName + ".csv").toString();
        logger.debug("eval path: {} log path: {} all files: {}", evalPath, logPath, allFiles);
        if (allFiles.contains(evalPath)) selectedFiles.addSelectedItems(evalPath);
        else if (allFiles.contains(logPath)) selectedFiles.addSelectedItems(logPath);
        this.config.getTree().updateUI();
        if (root.isValid()) updateChart();
        return this;
    }


    {
// GUI initializer generated by IntelliJ IDEA GUI Designer
// >>> IMPORTANT!! <<<
// DO NOT EDIT OR ADD ANY CODE HERE!
        $$$setupUI$$$();
    }

    /**
     * Method generated by IntelliJ IDEA GUI Designer
     * >>> IMPORTANT!! <<<
     * DO NOT edit this method OR call it in your code!
     *
     * @noinspection ALL
     */
    private void $$$setupUI$$$() {
        mainPanel = new JPanel();
        mainPanel.setLayout(new GridLayoutManager(1, 1, new Insets(0, 0, 0, 0), -1, -1));
        final JSplitPane splitPane1 = new JSplitPane();
        splitPane1.setDividerLocation(200);
        mainPanel.add(splitPane1, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null, new Dimension(200, 200), null, 0, false));
        final JPanel panel1 = new JPanel();
        panel1.setLayout(new GridLayoutManager(2, 1, new Insets(0, 0, 0, 0), -1, -1));
        splitPane1.setLeftComponent(panel1);
        panel1.setBorder(BorderFactory.createTitledBorder(null, "Controls", TitledBorder.DEFAULT_JUSTIFICATION, TitledBorder.DEFAULT_POSITION, null, null));
        updateButton = new JButton();
        updateButton.setText("Update Plot");
        panel1.add(updateButton, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        parameterJSP = new JScrollPane();
        panel1.add(parameterJSP, new GridConstraints(1, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_WANT_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_WANT_GROW, null, null, null, 0, false));
        plotJSP = new JScrollPane();
        splitPane1.setRightComponent(plotJSP);
    }

    /**
     * @noinspection ALL
     */
    public JComponent $$$getRootComponent$$$() {
        return mainPanel;
    }

}
