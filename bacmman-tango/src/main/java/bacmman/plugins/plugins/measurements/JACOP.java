package bacmman.plugins.plugins.measurements;

import bacmman.configuration.parameters.*;
import bacmman.data_structure.Measurements;
import bacmman.data_structure.SegmentedObject;
import bacmman.data_structure.Selection;
import bacmman.image.Image;
import bacmman.image.TypeConverter;
import bacmman.image.wrappers.IJImageWrapper;
import bacmman.measurement.MeasurementKey;
import bacmman.measurement.MeasurementKeyObject;
import bacmman.plugins.Hint;
import bacmman.plugins.Measurement;
import bacmman.plugins.Thresholder;
import bacmman.processing.jacop.ImageColocalizer;
import bacmman.utils.Pair;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class JACOP implements Measurement, Hint {
    ObjectClassParameter mask = new ObjectClassParameter("Segmentation Mask", 0, false, false).setHint("Segmented Object class within which colocalization will be computed");
    ObjectClassParameter signal1 = new ObjectClassParameter("Signal 1", -1, false, false).setHint("First signal to colocalize");
    ObjectClassParameter signal2 = new ObjectClassParameter("Signal 2", -1, false, false).setHint("Second signal to colocalize");
    PluginParameter<Thresholder> thld1 = new PluginParameter<>("Threshold for Signal 1", Thresholder.class,true).setHint("Threshold computed on signal 1. Values above this threshold will be considered in computations.<br> If no threshold is selected, areas inside segmented objects (if existing) of signal 1 will be considered in computations");
    PluginParameter<Thresholder> thld2 = new PluginParameter<>("Threshold for Signal 2", Thresholder.class,true).setHint("Threshold computed on signal 2. Values above this threshold will be considered in computations<br> If no threshold is selected, areas inside segmented objects (if existing) of signal 2 will be considered in computations");
    TextParameter prefix = new TextParameter("Prefix", "", false).setHint("Prefix added to the measurement names");
    static final String[] metrics = {"PersonWhole", "Person", "ICA", "Manders1", "Manders2", "Overlap", "K1", "K2"};
    MultipleChoiceParameter metricChoice = new MultipleChoiceParameter("Metrics", metrics, 0, 1, 2, 3, 4);
    enum Z_SLICES {ALL_SLICES, PER_SLICE_CONSTANT_STEP, PER_SLICE_RELATIVE_STEP}
    EnumChoiceParameter<Z_SLICES> zPlanes = new EnumChoiceParameter<>("Z Slice Mode", Z_SLICES.values(), Z_SLICES.ALL_SLICES).setHint("In case of 3D images choose how Z slices are handled: " +
            "<ul><li>ALL_SLICES: Colocalization is performed in 3D on all planes</li>" +
            "<li>PER_SLICE_CONSTANT_STEP: Colocalization is performed in 2D on slices around the middle slice with a constant step between slices</li>" +
            "<li>PER_SLICE_RELATIVE_STEP: Colocalization is performed in 2D on slices around the middle slice with a step between slices relative to the total number of slices</li></ul>.<br>In 2D modes, thresholds are computed on the 3D image");

    BoundedNumberParameter sliceNumber = new BoundedNumberParameter("nSlices", 0, 2, 0, null).setHint("Number of slices under and over the middle slice. Total number of slice is 2 * nSlices + 1.");
    BoundedNumberParameter step = new BoundedNumberParameter("Step", 0, 1, 1, null).setHint("Step between slices (in z unit). 2 means: one slice every two slices");
    ConditionalParameter<Z_SLICES> zPlanesCond = new ConditionalParameter<>(zPlanes)
            .setActionParameters(Z_SLICES.PER_SLICE_CONSTANT_STEP, sliceNumber, step)
            .setActionParameters(Z_SLICES.PER_SLICE_RELATIVE_STEP, sliceNumber);
    @Override
    public int getCallObjectClassIdx() {
        return mask.getSelectedClassIdx();
    }

    @Override
    public boolean callOnlyOnTrackHeads() {
        return false;
    }

    protected List<Pair<Integer, String>> getZPlanes(int middleZ, int sizeZ) {
        switch (zPlanes.getSelectedEnum()) {
            case ALL_SLICES:
            default: {
                return null;
            } case PER_SLICE_CONSTANT_STEP: {
                List<Pair<Integer, String>> res= new ArrayList<>();
                res.add(new Pair<>(middleZ, "Mid"));
                int step = this.step.getIntValue();
                for (int i = 1; i<=sliceNumber.getIntValue(); ++i) {
                    res.add(new Pair<>(middleZ + i * step, "Up"+i));
                    res.add(new Pair<>(middleZ - i * step, "Down"+i));
                }
                return res;
            } case PER_SLICE_RELATIVE_STEP: {
                List<Pair<Integer, String>> res= new ArrayList<>();
                res.add(new Pair<>(middleZ, "Mid"));
                int step = Math.max(1, sizeZ / (sliceNumber.getIntValue() * 2));
                for (int i = 1; i<=sliceNumber.getIntValue(); ++i) {
                    res.add(new Pair<>(Math.min(sizeZ - 1, middleZ + i * step), "Up"+i));
                    res.add(new Pair<>(Math.max(0, middleZ - i * step), "Down"+i));
                }
                return res;
            }
        }
    }

    @Override
    public List<MeasurementKey> getMeasurementKeys() {
        List<Pair<Integer, String>> planes=getZPlanes(0, 1);
        if (planes==null) return getMeasurementKeys(null);
        else {
            ArrayList<MeasurementKey> res = new ArrayList<>();
            for (Pair<Integer, String> z : planes) res.addAll(getMeasurementKeys(z.value));
            res.add(new MeasurementKeyObject(prefix.getValue()+"MidPlane", mask.getSelectedClassIdx()));
            return res;
        }
    }

    protected List<MeasurementKey> getMeasurementKeys(String z) {
        ArrayList<MeasurementKey> res = new ArrayList<>();
        String prefix = this.prefix.getValue();
        String suffix = z==null ? "" : "_z"+z;
        for (String m : metricChoice.getSelectedItemsNames()) {
            res.add(new MeasurementKeyObject(prefix+m+suffix, mask.getSelectedClassIdx()));
        }
        return res;
    }

    @Override
    public void performMeasurement(SegmentedObject object) {
        Image mask = TypeConverter.castToIJ1ImageType(object.getMask());
        Image signal1 = object.getRawImage(this.signal1.getSelectedClassIdx());
        Image signal2 = object.getRawImage(this.signal2.getSelectedClassIdx());
        int thld1 = this.thld1.isOnePluginSet() ? (int)this.thld1.instantiatePlugin().runThresholder(signal1, object) : Integer.MIN_VALUE;
        int thld2 = this.thld2.isOnePluginSet() ? (int)this.thld2.instantiatePlugin().runThresholder(signal2, object) : Integer.MIN_VALUE;
        Image mask1 = !this.thld1.isOnePluginSet() ? object.getChildRegionPopulation(this.signal1.getSelectedClassIdx(), false).getLabelMap():null;
        Image mask2 = !this.thld2.isOnePluginSet() ? object.getChildRegionPopulation(this.signal2.getSelectedClassIdx(), false).getLabelMap():null;
        Measurements m = object.getMeasurements();
        List<Pair<Integer, String>> planes=getZPlanes((mask.sizeZ()-1)/2, mask.sizeZ());
        if (planes==null) performMeasurement(m, signal1, signal2, mask, mask1, mask2, thld1, thld2, null);
        else {
            logger.debug("container: {} sizeZ: {}, planes: {}", Selection.indicesString(object), mask.sizeZ(), planes);
            for (Pair<Integer, String> z : planes) {
                if (z.key>=0 && z.key<mask.sizeZ()) performMeasurement(m, signal1.getZPlane(z.key), signal2.getZPlane(z.key), mask.getZPlane(z.key), mask1==null ? null : mask1.getZPlane(z.key), mask2==null ? null : mask2.getZPlane(z.key), thld1, thld2, z.value);
            }
            object.getMeasurements().setValue(prefix.getValue()+"MidPlane", (mask.sizeZ()-1)/2 + object.getBounds().zMin());
        }
    }

    protected void performMeasurement(Measurements m, Image signal1, Image signal2, Image mask, Image mask1, Image mask2, int thld1, int thld2, String z) {
        ImageColocalizer colocalizer = new ImageColocalizer(IJImageWrapper.getImagePlus(signal1), IJImageWrapper.getImagePlus(signal2), IJImageWrapper.getImagePlus(mask));
        if (mask1!=null) colocalizer.setMaskA(IJImageWrapper.getImagePlus(mask1));
        else colocalizer.setThresholdA(thld1);
        if (mask2!=null) colocalizer.setMaskB(IJImageWrapper.getImagePlus(mask2));
        else colocalizer.setThresholdB(thld2);
        Map<String, Double> metricValues = new HashMap<>();
        if (metricChoice.isSelected("Person")) metricValues.put("Person", colocalizer.pearson());
        if (metricChoice.isSelected("Manders1") || metricChoice.isSelected("Manders2")) {
            double[] M1M2 = colocalizer.MM();
            metricValues.put("Manders1", M1M2[0]);
            metricValues.put("Manders2", M1M2[1]);
        }
        if (metricChoice.isSelected("Overlap") || metricChoice.isSelected("K1") || metricChoice.isSelected("K2")) {
            double[] overlapK1K2 = colocalizer.overlap();
            metricValues.put("Overlap", overlapK1K2[0]);
            metricValues.put("K1", overlapK1K2[1]);
            metricValues.put("K2", overlapK1K2[2]);
        }
        if (metricChoice.isSelected("ICA")) metricValues.put("ICA", colocalizer.ICA());
        if (metricChoice.isSelected("PersonWhole")) { // person for whole volume:
            colocalizer.setThresholdA(Integer.MIN_VALUE);
            colocalizer.setThresholdB(Integer.MIN_VALUE);
            metricValues.put("PersonWhole", colocalizer.pearson());
        }
        List<MeasurementKey> keys = getMeasurementKeys(z);
        String[] metrics = metricChoice.getSelectedItemsNames();
        for (int i = 0; i<metrics.length; ++i) {
            m.setValue(keys.get(i).getKey(), metricValues.get(metrics[i]));
        }
    }

    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{mask, signal1, signal2, thld1, thld2, zPlanesCond, prefix, metricChoice};
    }

    @Override
    public String getHintText() {
        return "JACOP (Just Another Colocalisation Plugin): Computes a set of commonly used co-localization indicators" +
                "<br>Based on the implementation of Fabrice P. Cordelières: https://github.com/fabricecordelieres/IJ-Plugin_JACoP" +
                "<br> This implementation computes all coefficients within the mask of the selected object class (parameter: Segmentation Mask)" +
                "<br>If you use this module please cite: S. Bolte & F. P. Cordelières, A guided tour into subcellular colocalization analysis in light microscopy, Journal of Microscopy, Volume 224, Issue 3: 213-232" +
                "<br>Note that all coefficient values except <em>ICA</em> and <em>PearsonWhole</em> depend on thresholds defined in parameters <em>Threshold for Signal 1</em> and <em>Threshold for signal 2</em>. When no threshold is selected, the area within the corresponding segmented object class is considered if existing otherwise no value is computed" +
                "<br><em>PearsonWhole</em> and <em>ICA</em> are computed the whole volume." +
                "<br><em>Manders coefficient</em>: Manders1 = fraction of Signal 1 overlapping Signal 2 / Manders2 = fraction of Signal 2 overlapping Signal 1.";
    }
}
