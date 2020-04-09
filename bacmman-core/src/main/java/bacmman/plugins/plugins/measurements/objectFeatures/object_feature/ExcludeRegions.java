package bacmman.plugins.plugins.measurements.objectFeatures.object_feature;

import bacmman.configuration.parameters.BoundedNumberParameter;
import bacmman.configuration.parameters.ObjectClassParameter;
import bacmman.configuration.parameters.Parameter;
import bacmman.configuration.parameters.PluginParameter;
import bacmman.data_structure.Region;
import bacmman.data_structure.RegionPopulation;
import bacmman.data_structure.SegmentedObject;
import bacmman.image.*;
import bacmman.plugins.ObjectFeature;
import bacmman.plugins.object_feature.IntensityMeasurementCore;
import bacmman.plugins.object_feature.ObjectFeatureWithCore;
import bacmman.processing.Filters;
import bacmman.utils.HashMapGetCreate;
import bacmman.utils.Pair;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

public class ExcludeRegions implements ObjectFeature, ObjectFeatureWithCore {
    PluginParameter<ObjectFeature> feature = new PluginParameter<>("Feature", ObjectFeature.class, false).setEmphasized(true).setHint("Feature that will be computed on a region that excludes regions defined by exclude object class parameter");
    ObjectClassParameter excludeOc = new ObjectClassParameter("Exclude Object class", -1, false, false).setEmphasized(true).setHint("Class of objects to exclude");
    protected BoundedNumberParameter dilateExcluded = new BoundedNumberParameter("Dilatation radius for excluded object", 1, 1, 0, null).setHint("Dilate objects to be excluded");

    ObjectFeature currentFeature;
    HashMap<Region, Region> modifiedObjectMap;
    @Override
    public Parameter[] getParameters() {
        return new Parameter[] {excludeOc, dilateExcluded, feature};
    }

    @Override
    public ObjectFeature setUp(SegmentedObject parent, int childStructureIdx, RegionPopulation childPopulation) {
        currentFeature = feature.instantiatePlugin();
        RegionPopulation excludePopulation = parent.getChildRegionPopulation(excludeOc.getSelectedClassIdx());
        modifiedObjectMap = HashMapGetCreate.getRedirectedMap(r->r, HashMapGetCreate.Syncronization.SYNC_ON_KEY);
        if (!excludePopulation.getRegions().isEmpty()) {
            double dilRad = this.dilateExcluded.getValue().doubleValue();
            // compute excluded population
            List<Region> objects = childPopulation.getRegions();
            Map<Region, List<Region>> objectMapExcluded = HashMapGetCreate.getRedirectedMap(objects.size(), new HashMapGetCreate.ListFactory(), HashMapGetCreate.Syncronization.NO_SYNC);
            for (Region o : excludePopulation.getRegions()) {
                Region p = o.getContainer(objects, null, null); // parents are in absolute offset
                if (p != null) {
                    Region oDil = o;
                    if (dilRad > 0) {
                        ImageInteger oMask = o.getMaskAsImageInteger();
                        oMask = Filters.binaryMax(oMask, null, Filters.getNeighborhood(dilRad, dilRad, oMask), false, true, false);
                        oDil = new Region(oMask, 1, o.is2D()).setIsAbsoluteLandmark(o.isAbsoluteLandMark());
                    }
                    objectMapExcluded.get(p).add(oDil);
                }
            }

            // remove foreground objects from background mask & erode it

            SimpleOffset drawOffset = childPopulation.isAbsoluteLandmark() ? null : new SimpleOffset(childPopulation.getImageProperties()).reverseOffset();
            for (Region region : objects) {
                ImageMask ref = region.getMask();
                List<Region> toExclude = objectMapExcluded.get(region);
                if (toExclude != null) {
                    ImageByte mask = TypeConverter.toByteMask(ref, null, 1).setName("SNR mask");
                    for (Region o : toExclude) o.draw(mask, 0, drawOffset);
                    Region modifiedBackgroundRegion = new Region(mask, 1, region.is2D()).setIsAbsoluteLandmark(childPopulation.isAbsoluteLandmark());
                    modifiedObjectMap.put(region, modifiedBackgroundRegion);
                    //ImageWindowManagerFactory.showImage( mask);
                }
            }
            // create child pop and init feature:
            RegionPopulation pop = new RegionPopulation(modifiedObjectMap.values(), childPopulation.getImageProperties());
            currentFeature.setUp(parent, childStructureIdx, pop);
        } else {
            currentFeature.setUp(parent, childStructureIdx, childPopulation);
        }
        return this;
    }

    @Override
    public double performMeasurement(Region region) {
        return currentFeature.performMeasurement(modifiedObjectMap.get(region));
    }

    @Override
    public String getDefaultName() {
        return "";
    }

    @Override
    public void setUpOrAddCore(Map<Image, IntensityMeasurementCore> availableCores, BiFunction<Image, ImageMask, Image> preFilters) {
        if (currentFeature instanceof ObjectFeatureWithCore) ((ObjectFeatureWithCore)currentFeature).setUpOrAddCore(availableCores, preFilters);
    }

    @Override
    public int getIntensityStructure() {
        if (currentFeature instanceof ObjectFeatureWithCore) return ((ObjectFeatureWithCore)currentFeature).getIntensityStructure();
        else return -1;
    }
}
