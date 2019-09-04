package bacmman.py_dataset;

import bacmman.core.Core;
import bacmman.core.Task;
import bacmman.data_structure.*;
import bacmman.data_structure.dao.MasterDAO;
import bacmman.image.*;
import bacmman.image.wrappers.ImgLib2ImageWrapper;
import bacmman.plugins.PluginFactory;
import bacmman.utils.HashMapGetCreate;
import bacmman.utils.geom.Point;
import ch.systemsx.cisd.hdf5.IHDF5Writer;
import ij.ImageJ;
import net.imagej.ops.transform.scaleView.DefaultScaleView;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.Img;
import net.imglib2.interpolation.InterpolatorFactory;
import net.imglib2.interpolation.randomaccess.LanczosInterpolatorFactory;
import net.imglib2.interpolation.randomaccess.NearestNeighborInterpolatorFactory;
import org.scijava.command.Command;
import org.scijava.command.CommandInfo;
import org.scijava.command.CommandModule;
import org.scijava.module.Module;
import org.scijava.module.ModuleItem;
import org.scijava.module.ModuleRunner;
import org.scijava.module.ModuleService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.Future;
import java.util.function.DoubleToIntFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public class ExtractDataset {
    public final static Logger logger = LoggerFactory.getLogger(ExtractDataset.class);

    public static void main(String[] args) {
        Core.getCore();
        PluginFactory.findPlugins("plugins.plugins");
        final String refSelName = "ds_noError";
        String[] selectionNames= new String[]{"ds_test"};
        //String[] selectionNames= new String[]{"ds_noError"};
        //String[] selectionNames= new String[]{"anomalyDS_normal", "anomalyDS_anomaly", "anomalyDS_packed"};
        String[] datasets = new String[]{"WT_180318_Fluo", "MF1_170511", "MutH_151220"};
        //String[] datasets = new String[]{"MutH_151220", "MutH_150324", "MutH_140115", "WT_150609", "WT_150616", "MutT_150402"};
        Path outputPath = Paths.get("/data/Images/MOP/data_segDis_resampled/");
        String fileName = "bacteriaSegTrackToPredict.h5";
        if (!Files.exists(outputPath)) {
            try {
                Files.createDirectories(outputPath);
            } catch (IOException e) {
                logger.error("Could not create dir: {}", e);
            }
        }
        // first erase existing files
        //checkDatasetsForDisplacementExtraction(datasets, selectionNames, 1);
        //if (true) return;
        try {
            Files.delete(outputPath.resolve(fileName));
        } catch (IOException e) {
            logger.error("Could not delete file: {}", outputPath.resolve(fileName));
        }
        int[] dimensions = new int[]{32, 256};
        for (String ds:datasets) {
            MasterDAO mDAO = new Task(ds).getDB();
            for (String selName : selectionNames) {
                Selection sel = selName.length()>0 ? mDAO.getSelectionDAO().getOrCreate(selName, false) : getAllElements(mDAO, 0);
                for (String position : sel.getAllPositions()) {
                    Map<SegmentedObject, RegionPopulation> resampledPop = getResampledPopMap(1, dimensions);
                    String outputName = (selName.length() > 0 ? selName + "/" : "") + ds + "/" + position + "/";
                    //extractEDM(outputPath.resolve(fileName), outputName + "edm", sel, position, resampledPop,dimensions);
                    //extractDisplacement(outputPath.resolve(fileName), outputName + "dy", sel, position, 1, resampledPop, dimensions);
                    extractRaw(outputPath.resolve(fileName), outputName + "raw", mDAO, sel, position, false, dimensions);
                }
            }
            mDAO.clearCache();
            // add histogram per dataset for raw images
            if (outputPath.resolve(fileName).toFile().exists()) {
                PyDatasetReader reader = new PyDatasetReader(outputPath.resolve(fileName).toFile());
                Map<String, Histogram> histos = new HashMap<>(selectionNames.length);
                for (String selName : selectionNames) {
                    PyDatasetReader.DatasetAccess dsA = reader.getDatasetAccess(selName, ds);
                    Supplier<Stream<Image>> imageStream = () -> dsA.extractImagesForPositions("/raw", null, false, false, dimensions);
                    double[] minAndMax = HistogramFactory.getMinAndMax(imageStream.get());
                    double binSize = HistogramFactory.getBinSize(minAndMax[0], minAndMax[1], 256);
                    Histogram h = HistogramFactory.getHistogram(imageStream.get(), binSize, 256, minAndMax[0]);
                    histos.put(dsA.dsName(), h);
                }
                reader.close();
                IHDF5Writer writer = HDF5IO.getWriter(outputPath.resolve(fileName).toFile(), true);
                histos.forEach((s, h) -> {
                    writer.int64().setArrayAttr(s, "raw_histogram", h.data);
                    writer.float64().setAttr(s, "raw_histogram_bin_size", h.binSize);
                    writer.float64().setArrayAttr(s, "raw_percentiles", h.getQuantiles(IntStream.range(0, 101).mapToDouble(i -> i / 100d).toArray()));
                });
                writer.close();
            }
        }
    }
    private static Map<SegmentedObject, RegionPopulation> getResampledPopMap(int objectClassIdx, int[] dimensions) {
        return new HashMapGetCreate.HashMapGetCreateRedirectedSyncKey<>(o -> {
            Image mask = o.getChildRegionPopulation(objectClassIdx).getLabelMap();
            ImageInteger maskR = TypeConverter.toShort(resampleImage(mask, true, dimensions), null);
            return new RegionPopulation(maskR, true);
        });
    }
    public static String getLabel(SegmentedObject e) {
        return Selection.indicesString(e.getTrackHead()) + "_f" + String.format("%05d", e.getFrame());
    }

    private static Selection getAllElements(MasterDAO mDAO, int objectClassIdx) {
        Selection sel = new Selection();
        for (String pos : mDAO.getExperiment().getPositionsAsString()) {
            sel.addElements(SegmentedObjectUtils.getAllObjectsAsStream(mDAO.getDao(pos), objectClassIdx).collect(Collectors.toList()));
        }
        return sel;
    }
    public enum SCALE_MODE {NO_SCALE, MAX_MIN_BYTE_SAFE_FLOAT, MAX_MIN_BYTE, MAX_MIN_SHORT}
    public static void extractFeature(Path outputPath, String dsName, Selection sel, String position, Function<SegmentedObject, Image> feature, SCALE_MODE scaleMode, boolean binary, Map<String, Object> metadata, boolean saveLabels, boolean saveDimensions, int... dimensions) {
        boolean test = false;
        if (test) new ImageJ();
        Supplier<Stream<SegmentedObject>> streamSupplier = position==null ? () -> sel.getAllElements().stream().parallel() : () -> sel.getElements(position).stream().parallel();

        List<Image> images = streamSupplier.get().map(e -> { //skip(1).
            Image im = feature.apply(e);
            Image out = resampleImage(im, binary, dimensions);
            out.setName(getLabel(e));
            return out;
        }).sorted(Comparator.comparing(Image::getName)).collect(Collectors.toList());

        if (metadata==null) metadata= new HashMap<>();
        Function<Image, Image> converter = null;
        switch (scaleMode) {
            case MAX_MIN_BYTE:
            case MAX_MIN_BYTE_SAFE_FLOAT:
                if (images.get(0).getBitDepth()>8) { // do not apply conversion in case images are already byte
                    DoubleSummaryStatistics stats = Image.stream(images).summaryStatistics();
                    double off;
                    double scale = 255d / (stats.getMax() - stats.getMin());
                    if (SCALE_MODE.MAX_MIN_BYTE_SAFE_FLOAT.equals(scaleMode) && stats.getMin()<0) { // regularize so that 0 maps to an integer
                        double z = - stats.getMin() * scale;
                        int zi = (int)(z + 0.5);
                        double d = z - zi;
                        off = stats.getMin() + d/scale;
                        //logger.debug("regularize offset: min {}, z: {}, zi: {}, new off: {}", stats.getMin(), z, zi, off);
                    } else off = stats.getMin();
                    metadata.put("scaling_center", - off * scale);
                    metadata.put("scaling_factor", scale);
                    metadata.put("original_bitDepth", images.get(0).getBitDepth());
                    int zero = (int) (0.5 -off * scale);
                    DoubleToIntFunction scaler = SCALE_MODE.MAX_MIN_BYTE_SAFE_FLOAT.equals(scaleMode) ?
                        d -> {
                            int res=(int) (0.5 + scale * (d - off));
                            if (res==zero) { // avoid small values being mapped to zero during compression
                                if (d>0) ++res;
                                else if (d<0) --res;
                            }
                            return res;
                        } : d -> (int) (0.5 + scale * (d - off));
                    converter = im -> TypeConverter.toByte(im, null, scaler);
                }
                break;
            case MAX_MIN_SHORT:
                if (images.get(0).getBitDepth()>16) { // do not apply conversion in case images are already byte or short
                    DoubleSummaryStatistics stats = Image.stream(images).summaryStatistics();
                    double off;
                    double scale = 65535d / (stats.getMax() - stats.getMin());
                    if (stats.getMin()<0) { // regularize so that 0 maps to an integer
                        double z = - stats.getMin() * scale;
                        int zi = (int)(z + 0.5);
                        double d = z - zi;
                        off = stats.getMin() - d/scale;
                    } else off = stats.getMin();
                    metadata.put("scaling_center", - off * scale);
                    metadata.put("scaling_factor", scale);
                    metadata.put("original_bitDepth", images.get(0).getBitDepth());
                    DoubleToIntFunction scaler = d -> (int) (0.5 + scale * (d - off));
                    converter = im -> TypeConverter.toShort(im, null, scaler);
                }
                break;
        }
        metadata.put("scale_xy", images.get(0).getScaleXY());
        metadata.put("scale_z", images.get(0).getScaleZ());
        if (converter!=null) images = images.stream().parallel().map(converter).collect(Collectors.toList());

        int[][] originalDimensions = saveDimensions ? streamSupplier.get().sorted(Comparator.comparing(e->getLabel(e))).map(o->{
            if (o.is2D()) return new int[]{o.getBounds().sizeX(), o.getBounds().sizeY()};
            else return new int[]{o.getBounds().sizeX(), o.getBounds().sizeY(), o.getBounds().sizeZ()};
        }).toArray(int[][]::new) : null;
        HDF5IO.savePyDataset(images, outputPath.toFile(), true, dsName, 4, saveLabels, originalDimensions, metadata );
    }

    public static void extractEDM(Path outputPath, String dsName, Selection sel, String position, Map<SegmentedObject, RegionPopulation> resampledPop, int... dimensions) {
        // resample after compute edm
        //Function<SegmentedObject, Image> edm = e -> e.getChildRegionPopulation(objectClassIdx).getEDM(true);
        // computes edm on resampled image
        Function<SegmentedObject, Image> edm2 = e -> {
            RegionPopulation pop = resampledPop.get(e);
            return pop.getEDM(true);
        };
        extractFeature(outputPath, dsName, sel, position, edm2, SCALE_MODE.MAX_MIN_BYTE, false, null, false, false, dimensions);

    }
    public static void checkDatasetsForDisplacementExtraction(String[] dbs, String[] sels, int objectClassIdx) {
        for (String db : dbs) {
            MasterDAO mDAO = new Task(db).getDB();
            mDAO.setConfigurationReadOnly(false);
            Selection noPrev = mDAO.getSelectionDAO().getOrCreate("no prev", true);
            Selection relabelError = mDAO.getSelectionDAO().getOrCreate("relabel error", true);
            for (String p : mDAO.getExperiment().getPositionsAsString()) {
                for (String selN : sels) {
                    Selection sel = mDAO.getSelectionDAO().getOrCreate(selN, false);
                    checkDatasetsForDisplacementExtraction(mDAO, sel, p, objectClassIdx);
                }
                mDAO.getDao(p).clearCache();
            }
            mDAO.clearCache();
        }
    }
    private static void checkDatasetsForDisplacementExtraction(MasterDAO mDAO, Selection sel, String position, int objectClassIdx) {
        if (!sel.getAllPositions().contains(position)) return;
        // count objects that miss previous objects to display a warning
        Selection noPrev = mDAO.getSelectionDAO().getOrCreate("no prev", false);
        noPrev.addElements(sel.getElements(position).stream().filter(p->p.getPrevious()!=null).flatMap(p -> p.getChildren(objectClassIdx)).filter(o->o.getPrevious()==null).collect(Collectors.toList()));
        Selection relabelError = mDAO.getSelectionDAO().getOrCreate("relabel error", false);
        Collection<SegmentedObject> modifiedObjectsStore = new HashSet<>();
        SegmentedObjectFactory factory = getFactory(objectClassIdx);
        relabelError.addElements(sel.getElements(position).stream().filter(p-> {
            int maxIdx = p.getChildren(objectClassIdx).mapToInt(o->o.getIdx()).max().orElse(-1);
            int objectCount = (int)p.getChildren(objectClassIdx).count();
            if (maxIdx+1!=objectCount) factory.relabelChildren(p, modifiedObjectsStore);
            return maxIdx+1!=objectCount;
        }).collect(Collectors.toList()));
        mDAO.getSelectionDAO().store(noPrev);
        mDAO.getSelectionDAO().store(relabelError);
        if (!modifiedObjectsStore.isEmpty()) mDAO.getDao(position).store(modifiedObjectsStore);
        logger.debug("ds: {}, position: {}, no prev {}, relabel error: {}", mDAO.getDBName(), position, noPrev.getElementStrings(position).size(), relabelError.getAllElementStrings().size());
    }
    public static void extractDisplacement(Path outputPath, String dsName, Selection sel, String position, int objectClassIdx, Map<SegmentedObject, RegionPopulation> ressampledMap, int... dimensions) {
        Function<SegmentedObject, Image> dis = e -> {
            RegionPopulation curPop = ressampledMap.get(e);
            Image displacement = new ImageFloat("", curPop.getImageProperties());
            if (e.getPrevious()!=null) { // if first frame previous image is self: no displacement
                RegionPopulation prevPop = ressampledMap.get(e.getPrevious());
                e.getChildren(objectClassIdx).filter(c->c.getPrevious()!=null).forEach(c -> {
                    Region r = curPop.getRegion(c.getIdx()+1); // compute displacement on resampled image so that the network learns a displacement corresponding to pixels
                    //Region rPrev = c.getPrevious().getRegion();
                    Region rPrev = prevPop.getRegion(c.getPrevious().getIdx()+1);
                    // compute displacement

                    Point prevC = rPrev.getGeomCenter(false).duplicate().translateRev(c.getPrevious().getParent().getBounds());
                    Point curC = r.getGeomCenter(false).duplicate().translateRev(c.getParent().getBounds());
                    double delta = curC.get(1) - prevC.get(1)==0 ? 0.01 : curC.get(1) - prevC.get(1); // y displacement
                    //logger.debug("displacement: {}", delta);
                    r.getVoxels().forEach(v -> displacement.setPixelWithOffset(v.x, v.y, v.z, delta));
                    if (r.getContour().size() < r.getVoxels().size())
                        r.getContour().forEach(v -> displacement.setPixelWithOffset(v.x, v.y, v.z, 0)); // erase contour to relax constraint

                });
            }
            return displacement;
        };
        extractFeature(outputPath, dsName, sel, position, dis, SCALE_MODE.NO_SCALE, true, null, false, false, dimensions);
    }

    public static void extractRaw(Path outputPath, String dsName, MasterDAO mDAO,  Selection sel, String position, boolean convertToByte, int... dimensions) {
        Function<SegmentedObject, Image> extractRaw = e -> {
            Image im = e.getRawImage(e.getStructureIdx());
            if (convertToByte) im = TypeConverter.toFloat(im, null); // so that only no conversion float->int is done during resampling
            return im;
        };
        extractFeature(outputPath, dsName, (Selection)sel, position, extractRaw, convertToByte ? SCALE_MODE.MAX_MIN_BYTE : SCALE_MODE.NO_SCALE, false, null, true,  true, dimensions);
    }

    /**
     *
     * @param im
     * @param dimensions dimension of the final image. If a dimension is negative, original will be cropped to that dimension if it is larger, or resampled if it is smaller
     * @return
     */
    public static Image resampleImage(Image im, boolean binary, int... dimensions) {
        if (dimensions==null || dimensions.length==0) return im;
        // negative dimension = crop

        if (Arrays.stream(dimensions).anyMatch(i->i<0)) { // needs cropping
            BoundingBox cropBB = new SimpleBoundingBox(0, dimensions[0]<0 && -dimensions[0]<im.sizeX() ? -dimensions[0]-1 : im.sizeX()-1, 0, dimensions.length>1 && dimensions[1]<0 && -dimensions[1]<im.sizeY() ? -dimensions[1]-1 : im.sizeY()-1, 0, dimensions.length>2 && dimensions[2]<0 && -dimensions[2]<im.sizeZ() ? -dimensions[2]-1 : im.sizeZ()-1);
            im = im.crop(cropBB);
            int[] dims = new int[dimensions.length];
            for (int i = 0; i<dimensions.length; ++i) dims[i] = Math.abs(dimensions[i]);
            dimensions=dims;
        }

        //Image res = Image.createEmptyImage("resampled", im, new SimpleImageProperties(dimensions[0]>0?dimensions[0]:im.sizeX(), dimensions.length>=2&&dimensions[1]>0?dimensions[1]:im.sizeY(), dimensions.length>=3&&dimensions[2]>0?dimensions[2]:im.sizeZ(), im.getScaleXY(), im.getScaleZ()));
        //Img out = ImgLib2ImageWrapper.getImage(res);
        //Resample resample = new Resample(Resample.Mode.LANCZOS);
        //resample.compute(in, out);
        //return res;
        double[] scaleFactors = new double[im.sizeZ()>1? 3:2];
        for (int i = 0; i<scaleFactors.length;++i) scaleFactors[i] = dimensions.length>i && dimensions[i]>0 ? (double)dimensions[i]/im.size(i) : 1;

        if (Arrays.stream(scaleFactors).anyMatch(i->i!=1)) { // needs resampling
            //logger.debug("resampling: scales: {}, dims: {}, binary: {}", scaleFactors, dimensions, binary);
            Img in = ImgLib2ImageWrapper.getImage(im);
            InterpolatorFactory inter = binary ? new NearestNeighborInterpolatorFactory() : new LanczosInterpolatorFactory(3, false);
            // Todo find why this is not running from GUI
            //final Future<CommandModule> future = Core.imagej2().command().run(DefaultScaleView.class, false, "scaleFactors", scaleFactors, "interpolator", inter, "in", in); // clipping will be performed during converion to byte
            //final Module module = Core.imagej2().module().waitFor(future);

            Map<String, Object> inputs = new HashMap<>();
            inputs.put("scaleFactors", scaleFactors);
            inputs.put("interpolator", inter);
            inputs.put("in", in);
            Module module = runModule(Core.imagej2().module(), DefaultScaleView.class, inputs);
            Image res = ImgLib2ImageWrapper.wrap((RandomAccessibleInterval) module.getOutput("out"));
            //logger.debug("scales: {}, dims: {}, output dims: {}", scaleFactors, dimensions, res.getBoundingBox());
            return res;
        } else return im;
    }


    public static <C extends Command> Module runModule(ModuleService moduleService, Class<C> commandClass, Map<String, Object> inputs) {
        CommandInfo command = Core.imagej2().command().getCommand(commandClass);
        if (command == null) command = new CommandInfo(commandClass);
        Module module = moduleService.createModule(command);

        Method method = null;
        try {
            method = moduleService.getClass().getDeclaredMethod("assignInputs", Module.class, Map.class);
            method.setAccessible(true);
            method.invoke(moduleService, module, inputs);
        } catch (NoSuchMethodException|IllegalAccessException|InvocationTargetException e) {
            logger.debug("error while assigning inputs", e);
        }
        final ModuleRunner runner = new ModuleRunner(moduleService.getContext(), module, null, null);
        runner.run();
        return module;
    }


    private static SegmentedObjectFactory getFactory(int objectClassIdx) {
        try {
            Constructor<SegmentedObjectFactory> constructor = SegmentedObjectFactory.class.getDeclaredConstructor(int.class);
            constructor.setAccessible(true);
            return constructor.newInstance(objectClassIdx);
        } catch (InstantiationException | IllegalAccessException | NoSuchMethodException | InvocationTargetException e) {
            throw new RuntimeException("Could not create track link editor", e);
        }
    }
}
