package bacmman.data_structure.region_container;

import bacmman.data_structure.Region;
import bacmman.data_structure.SegmentedObject;
import bacmman.data_structure.Spot;
import bacmman.utils.geom.Point;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import java.util.ArrayList;
import java.util.Base64;
import java.util.Map;

public class RegionContainerSpot extends RegionContainer {
    double radius;
    Point center;

    public RegionContainerSpot(SegmentedObject object) {
        super(object);
        if (!(object.getRegion() instanceof Spot)) throw new IllegalArgumentException("Region should be a spot");
        this.radius = ((Spot)object.getRegion()).getRadius();
        this.center = object.getRegion().getCenter();
    }

    @Override
    public Region getRegion() {
        return new Spot(center, radius, structureObject.getIdx() + 1, is2D, structureObject.getScaleXY(), structureObject.getScaleZ());
    }

    @Override
    public void initFromJSON(Map json) {
        super.initFromJSON(json);
        center = new Point();
        center.initFromJSONEntry(json.get("center"));
        radius = (double)json.get("radius");
    }
    @Override
    public JSONObject toJSON() {
        JSONObject res = super.toJSON();
        res.put("center", center.toJSONEntry());
        res.put("radius", radius);
        return res;
    }
}
