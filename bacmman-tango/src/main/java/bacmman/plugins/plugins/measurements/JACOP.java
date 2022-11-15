package bacmman.plugins.plugins.measurements;

import bacmman.configuration.parameters.ObjectClassParameter;
import bacmman.configuration.parameters.Parameter;
import bacmman.configuration.parameters.PluginParameter;
import bacmman.configuration.parameters.TextParameter;
import bacmman.data_structure.Measurements;
import bacmman.data_structure.SegmentedObject;
import bacmman.image.Image;
import bacmman.image.TypeConverter;
import bacmman.image.wrappers.IJImageWrapper;
import bacmman.measurement.MeasurementKey;
import bacmman.measurement.MeasurementKeyObject;
import bacmman.plugins.Hint;
import bacmman.plugins.Measurement;
import bacmman.plugins.Thresholder;
import bacmman.plugins.plugins.thresholders.ConstantValue;
import bacmman.plugins.plugins.thresholders.Percentile;
import bacmman.processing.jacop.ImageColocalizer;

import java.util.ArrayList;
import java.util.List;

public class JACOP implements Measurement, Hint {
    ObjectClassParameter mask = new ObjectClassParameter("Segmentation Mask", -1, false, false).setHint("Segmented Object class within which colocalization will be computed");
    ObjectClassParameter signal1 = new ObjectClassParameter("Signal 1", -1, false, false).setHint("First signal to colocalize");
    ObjectClassParameter signal2 = new ObjectClassParameter("Signal 2", -1, false, false).setHint("Second signal to colocalize");
    PluginParameter<Thresholder> thld1 = new PluginParameter<>("Threshold for Signal 1", Thresholder.class,true).setHint("Optional. Threshold computed on signal 1. Values above this threshold will be considered in computations");
    PluginParameter<Thresholder> thld2 = new PluginParameter<>("Threshold for Signal 2", Thresholder.class,true).setHint("Optional. Threshold computed on signal 2. Values above this threshold will be considered in computations");
    TextParameter key = new TextParameter("Suffix", "", false).setHint("Characters added at the end of the measurement names");


    @Override
    public int getCallObjectClassIdx() {
        return mask.getSelectedClassIdx();
    }

    @Override
    public boolean callOnlyOnTrackHeads() {
        return false;
    }

    @Override
    public List<MeasurementKey> getMeasurementKeys() {
        ArrayList<MeasurementKey> res = new ArrayList<>();
        String suffix = key.getValue();
        res.add(new MeasurementKeyObject("Pearson"+suffix, mask.getSelectedClassIdx()));
        res.add(new MeasurementKeyObject("Overlap"+suffix, mask.getSelectedClassIdx()));
        res.add(new MeasurementKeyObject("K1"+suffix, mask.getSelectedClassIdx()));
        res.add(new MeasurementKeyObject("K2"+suffix, mask.getSelectedClassIdx()));
        res.add(new MeasurementKeyObject("Manders1"+suffix, mask.getSelectedClassIdx()));
        res.add(new MeasurementKeyObject("Manders2"+suffix, mask.getSelectedClassIdx()));
        res.add(new MeasurementKeyObject("ICA"+suffix, mask.getSelectedClassIdx()));
        return res;
    }

    @Override
    public void performMeasurement(SegmentedObject object) {
        Image mask = TypeConverter.toCommonImageType(object.getMask());
        Image signal1 = object.getRawImage(this.signal1.getSelectedClassIdx());
        Image signal2 = object.getRawImage(this.signal2.getSelectedClassIdx());
        int thld1 = this.thld1.isOnePluginSet() ? (int)this.thld1.instantiatePlugin().runThresholder(signal1, object) : Integer.MIN_VALUE;
        int thld2 = this.thld2.isOnePluginSet() ? (int)this.thld2.instantiatePlugin().runThresholder(signal2, object) : Integer.MIN_VALUE;
        ImageColocalizer colocalizer = new ImageColocalizer(IJImageWrapper.getImagePlus(signal1), IJImageWrapper.getImagePlus(signal2), IJImageWrapper.getImagePlus(mask));
        double pearson = colocalizer.pearson(thld1, thld2);
        double[] overlapK1K2 = colocalizer.overlap(thld1, thld2);
        double[] M1M2 = colocalizer.MM(thld1, thld2);
        double ICA = colocalizer.ICA();
        Measurements m = object.getMeasurements();
        List<MeasurementKey> keys = getMeasurementKeys();
        m.setValue(keys.get(0).getKey(), pearson);
        m.setValue(keys.get(1).getKey(), overlapK1K2[0]);
        m.setValue(keys.get(2).getKey(), overlapK1K2[1]);
        m.setValue(keys.get(3).getKey(), overlapK1K2[2]);
        m.setValue(keys.get(4).getKey(), M1M2[0]);
        m.setValue(keys.get(5).getKey(), M1M2[1]);
        m.setValue(keys.get(6).getKey(), ICA);
    }

    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{mask, signal1, signal2, thld1, thld2};
    }

    @Override
    public String getHintText() {
        return "JACOP (Just Another Colocalisation Plugin): Computes a set of commonly used co-localization indicators" +
                "<br>Based on the implementation of Fabrice P. Cordelières: https://github.com/fabricecordelieres/IJ-Plugin_JACoP" +
                "<br> This implementation computes all coefficients within the mask of the selected object class (parameter: Segmentation Mask)" +
                "<br>If you use this module please cite: S. Bolte & F. P. Cordelières, A guided tour into subcellular colocalization analysis in light microscopy, Journal of Microscopy, Volume 224, Issue 3: 213-232" +
                "<br>Note that all coefficient values except ICA depend on thresholds defined in parameters <em>Threshold for Signal 1</em> and <em>Threshold for signal 2</em>" +
                "<br>Manders coefficient: Manders1 = fraction of Signal 1 overlapping Signal 2 / Manders2 = fraction of Signal 2 overlapping Signal 1.";
    }
}
