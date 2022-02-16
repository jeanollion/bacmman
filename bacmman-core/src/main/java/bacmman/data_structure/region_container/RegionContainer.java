/* 
 * Copyright (C) 2018 Jean Ollion
 *
 * This File is part of BACMMAN
 *
 * BACMMAN is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * BACMMAN is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with BACMMAN.  If not, see <http://www.gnu.org/licenses/>.
 */
package bacmman.data_structure.region_container;

import bacmman.data_structure.Region;
import bacmman.data_structure.SegmentedObject;
import bacmman.image.MutableBoundingBox;
import bacmman.image.SimpleBoundingBox;
import java.util.Map;
import java.util.function.Supplier;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

/**
 *
 * @author Jean Ollion
 */

public abstract class RegionContainer {
    public static final int MAX_VOX_3D = 20;
    public static final int MAX_VOX_2D = 30;
    protected transient SegmentedObject segmentedObject;
    SimpleBoundingBox bounds;
    boolean is2D;
    public RegionContainer(SegmentedObject segmentedObject) {
        this.is2D = segmentedObject.getRegion().is2D();
        this.segmentedObject = segmentedObject;
        this.bounds=new SimpleBoundingBox(segmentedObject.getBounds());
    }
    protected RegionContainer() {}
    protected RegionContainer(SimpleBoundingBox bounds, boolean is2D) {
        this.bounds=bounds;
        this.is2D=is2D;
    }
    public SimpleBoundingBox getBounds() {
        return bounds;
    }
    public void setSegmentedObject(SegmentedObject segmentedObject) {
        this.segmentedObject = segmentedObject;
    }
    protected float getScaleXY() {return segmentedObject.getScaleXY();}
    protected float getScaleZ() {return segmentedObject.getScaleZ();}
    public boolean is2D() {return is2D;}
    public abstract Region getRegion();
    public void update() {
        bounds = new SimpleBoundingBox(segmentedObject.getRegion().getBounds());
        is2D = segmentedObject.is2D();
    }
    public void initFromJSON(Map<String, Object> json) {
        JSONArray bds =  (JSONArray)json.get("bounds");
        this.bounds=new MutableBoundingBox();
        this.bounds.initFromJSONEntry(bds);
        if (json.containsKey("is2D")) is2D = (Boolean)json.get("is2D");
        else is2D = true; // for retrocompatibility. do not call to structure object's method at it may not be fully initiated and may not have access to dataset
    }
    public JSONObject toJSON() {
        JSONObject res = new JSONObject();
        res.put("bounds", bounds.toJSONEntry());
        res.put("is2D", is2D);
        return res;
    }

    public static RegionContainer createFromJSON(SegmentedObject o, Map json) {
        RegionContainer res;
        if (json.containsKey("x")) res = new RegionContainerVoxels(); // coord list
        else if (json.containsKey("roi")||json.containsKey("roiZ")) res = new RegionContainerIjRoi();
        else if (json.containsKey("Type")) res = ANALYTICAL_TYPES.valueOf((String)json.get("Type")).getCreator();
        else res = new RegionContainerBlankMask(); // retro-compatibility
        res.setSegmentedObject(o);
        res.initFromJSON(json);
        return res;
    }
    public enum ANALYTICAL_TYPES {SPHERE(RegionContainerSpot::new), ELLIPSE2D(RegionContainerEllipse2D::new), RECTANGLE((RegionContainerBlankMask::new));
        private final Supplier<RegionContainer> containerCreator;
        ANALYTICAL_TYPES(Supplier<RegionContainer> containerCreator) {
            this.containerCreator=containerCreator;
        }
        public RegionContainer getCreator() {
            return containerCreator.get();
        }
    }
}
