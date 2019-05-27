package bacmman.plugins.plugins.post_filters;

import bacmman.configuration.parameters.BoundedNumberParameter;
import bacmman.configuration.parameters.Parameter;
import bacmman.data_structure.Region;
import bacmman.data_structure.RegionPopulation;
import bacmman.data_structure.SegmentedObject;
import bacmman.plugins.PostFilter;
import bacmman.utils.HashMapGetCreate;
import bacmman.utils.geom.Point;

import java.util.List;
import java.util.function.BiPredicate;
import java.util.function.Function;

public class RemoveCloseObjects implements PostFilter {
    BoundedNumberParameter minDist = new BoundedNumberParameter("Minimal distance", 3, 1, 0, null).setHint("When two objects are closer than this distance (in pixels), only one of the two objects is kept: <br >If both object have a quality attribute, then the object with highest quality is kept, else the intensity at the center is compared");

    @Override
    public RegionPopulation runPostFilter(SegmentedObject parent, int childStructureIdx, RegionPopulation childPopulation) {
        List<Region> regions = childPopulation.getRegions();
        Function<Region, Point> getCenter = r -> r.getCenter()!=null ? r.getCenter() : r.getGeomCenter(false);
        double thld= Math.pow(minDist.getValue().doubleValue(), 2);
        BiPredicate<Region, Region> areClose = (r1, r2) -> getCenter.apply(r1).distSq(getCenter.apply(r2))<thld;
        HashMapGetCreate<Region, Double> regionValue = new HashMapGetCreate<>(r -> {
            Point center = getCenter.apply(r);
            return (double)parent.getRawImage(childStructureIdx).getPixel(center.get(0), center.get(1), center.getWithDimCheck(2));
        });
        BiPredicate<Region, Region> keepFirst = (r1, r2) -> {
            if (Double.isNaN(r1.getQuality()) || Double.isNaN(r2.getQuality())) {
                return regionValue.getAndCreateIfNecessary(r1)>=regionValue.getAndCreateIfNecessary(r2);
            } else return r1.getQuality()>=r2.getQuality();
        };

        for (int i = 0; i<regions.size()-1;++i) {
            for (int j = i+1; j<regions.size(); ++j) {
                if (areClose.test(regions.get(i), regions.get(j))) {
                    if (keepFirst.test(regions.get(i), regions.get(j))) {
                        regions.remove(j);
                        --j;
                    } else {
                        regions.remove(i);
                        --i;
                        --j;
                    }
                }
            }
        }
        childPopulation.relabel(false);
        return childPopulation;
    }

    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{minDist};
    }
}
