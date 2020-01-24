package bacmman.data_structure.region_container;

import bacmman.data_structure.Region;
import bacmman.data_structure.SegmentedObject;
import bacmman.data_structure.Spot;
import bacmman.utils.JSONUtils;
import bacmman.utils.geom.Point;
import org.json.simple.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class RegionContainerSpot extends RegionContainer {
    public final static Logger logger = LoggerFactory.getLogger(RegionContainerSpot.class);
    public RegionContainerSpot() {
        super();
    }

    public RegionContainerSpot(SegmentedObject object) {
        super(object);
    }

    @Override
    public Region getRegion() {
        return new Spot(new Point(JSONUtils.fromFloatArray((List) segmentedObject.getAttribute("Center"))),
                ((Number) segmentedObject.getAttribute("Radius", Double.valueOf(1d))).doubleValue(),
                ((Number) segmentedObject.getAttribute("Intensity", Double.valueOf(0d))).doubleValue(),
                segmentedObject.getIdx() + 1, is2D, segmentedObject.getScaleXY(), segmentedObject.getScaleZ());
    }

    @Override
    public JSONObject toJSON() {
        JSONObject res = super.toJSON();
        res.put("Type", ANALYTICAL_TYPES.SPHERE.name()); // center, radius and intensity are stored in the attribute parameter
        return res;
    }
}
