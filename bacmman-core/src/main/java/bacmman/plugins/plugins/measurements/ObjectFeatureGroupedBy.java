package bacmman.plugins.plugins.measurements;

import bacmman.configuration.parameters.*;
import bacmman.data_structure.RegionPopulation;
import bacmman.data_structure.SegmentedObject;
import bacmman.image.Image;
import bacmman.image.ImageMask;
import bacmman.measurement.MeasurementKey;
import bacmman.measurement.MeasurementKeyObject;
import bacmman.plugins.Hint;
import bacmman.plugins.Measurement;
import bacmman.plugins.ObjectFeature;
import bacmman.plugins.object_feature.IntensityMeasurement;
import bacmman.plugins.object_feature.IntensityMeasurementCore;
import bacmman.plugins.object_feature.ObjectFeatureWithCore;
import bacmman.utils.ArrayUtil;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import java.util.stream.DoubleStream;
import java.util.stream.IntStream;

public class ObjectFeatureGroupedBy implements Measurement, Hint {
    ObjectClassParameter groupClass = new ObjectClassParameter("Group By Class");

    @Override
    public String getHintText() {
        return "For each object of class <em>Group Class</em>: Gather all included objects of class <em>object class</em>, perform measurement and return a reduction of these values. <br>WEIGHTED_MEAN corresponds to the average value weighted by the size/volume of the objects";
    }

    public enum REDUCTION_OP {WEIGHTED_MEAN, MEAN, STD, MEDIAN, MIN, MAX, SUM}
    MultipleEnumChoiceParameter<REDUCTION_OP> reductionOP = new MultipleEnumChoiceParameter<>("Reduction operation", REDUCTION_OP.values(), Enum::name, REDUCTION_OP.WEIGHTED_MEAN);

    ObjectClassParameter objectClass = new ObjectClassParameter("Object class", -1, false, false).setEmphasized(true).setHint("Segmented object class of to compute feature(s) on (defines the region-of-interest of the measurement)");
    PluginParameter<ObjectFeature> def = new PluginParameter<>("Feature", ObjectFeature.class, false)
            .setAdditionalParameters(new TextParameter("Name", "", false)).setNewInstanceConfiguration(oc->{
                if (oc instanceof IntensityMeasurement) ((IntensityMeasurement)oc).setIntensityStructure(objectClass.getSelectedClassIdx());
            });
    SimpleListParameter<PluginParameter<ObjectFeature>> features = new SimpleListParameter<>("Features", def).setMinChildCount(1).setChildrenNumber(1).setEmphasized(true);
    PreFilterSequence preFilters = new PreFilterSequence("Pre-Filters").setHint("All intensity measurements features will be computed on the image filtered by the operation defined in this parameter.");

    Parameter[] parameters = new Parameter[]{groupClass, objectClass, preFilters, features, reductionOP};

    public ObjectFeatureGroupedBy() {
        features.addNewInstanceConfiguration(pp -> {
            pp.addListener( s -> {
                TextParameter tp = ((TextParameter)s.getAdditionalParameters().get(0));
                if (s.isOnePluginSet()) tp.setValue(s.instantiatePlugin().getDefaultName());
                else tp.setValue("");
            });
            ((TextParameter)pp.getAdditionalParameters().get(0)).addValidationFunction((t)-> t.getValue().length()>0);
        });
    }

    @Override
    public int getCallObjectClassIdx() {
        return groupClass.getSelectedClassIdx();
    }

    @Override
    public boolean callOnlyOnTrackHeads() {
        return false;
    }

    @Override
    public List<MeasurementKey> getMeasurementKeys() {
        List<REDUCTION_OP> reduction = reductionOP.getSelectedItems();
        ArrayList<MeasurementKey> res=  new ArrayList<>(features.getChildCount() * reduction.size());
        for (PluginParameter<ObjectFeature> ofp : features.getActivatedChildren()) {
            for (REDUCTION_OP r : reduction) {
                res.add(new MeasurementKeyObject(((TextParameter) ofp.getAdditionalParameters().get(0)).getValue()+ "_" + r.name(), groupClass.getSelectedIndex()));
            }
        }
        return res;
    }

    @Override
    public void performMeasurement(SegmentedObject object) {
        int childOCIdx = objectClass.getSelectedIndex();
        List<SegmentedObject> children = object.getChildren(childOCIdx, false).collect(Collectors.toList());
        RegionPopulation pop = new RegionPopulation(children.stream().map(SegmentedObject::getRegion).collect(Collectors.toList()), object.getMaskProperties());
        Map<Image, IntensityMeasurementCore> cores = new ConcurrentHashMap<>();
        BiFunction<Image, ImageMask, Image> pf = (im, mask) -> preFilters.filter(im,mask);
        List<REDUCTION_OP> reduction = reductionOP.getSelectedItems();
        for (PluginParameter<ObjectFeature> ofp : features.getActivatedChildren()) {
            ObjectFeature f = ofp.instantiatePlugin();
            if (f!=null) {
                f.setUp(object, childOCIdx, pop);
                if (f instanceof ObjectFeatureWithCore) ((ObjectFeatureWithCore)f).setUpOrAddCore(cores, pf);
                double[] values = children.stream().mapToDouble(o->  f.performMeasurement(o.getRegion()) ).toArray();
                String name = ((TextParameter)ofp.getAdditionalParameters().get(0)).getValue();
                double[] meanSigma = reduction.contains(REDUCTION_OP.STD) ? ArrayUtil.meanSigma(values, 0, values.length, null) : null;
                for (REDUCTION_OP op : reduction) {
                    double value;
                    switch (op) {
                        case MAX:
                            value = values[ArrayUtil.max(values)];
                            break;
                        case MIN:
                            value = values[ArrayUtil.min(values)];
                            break;
                        case WEIGHTED_MEAN:
                        default:
                            double[] size = children.stream().mapToDouble(o->  o.getRegion().size() ).toArray();
                            double sumSize = DoubleStream.of(size).sum();
                            value = IntStream.range(0, values.length).mapToDouble(i -> values[i] * size[i] / sumSize).sum();
                            break;
                        case MEAN:
                            value = meanSigma==null? ArrayUtil.mean(values, 0, values.length) : meanSigma[0];
                            break;
                        case STD:
                            value = meanSigma[1];
                            break;
                        case MEDIAN:
                            value = ArrayUtil.median(values);
                            break;
                        case SUM:
                            value = DoubleStream.of(values).sum();
                            break;
                    }
                    object.getMeasurements().setValue(name + "_"+op.name(), value);
                }

            }
        }
    }

    @Override
    public Parameter[] getParameters() {
        return parameters;
    }
}
