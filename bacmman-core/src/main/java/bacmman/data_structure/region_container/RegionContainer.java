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
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

/**
 *
 * @author Jean Ollion
 */

public abstract class RegionContainer {
    public static final int MAX_VOX_3D = 20;
    public static final int MAX_VOX_2D = 30;
    protected transient SegmentedObject structureObject;
    SimpleBoundingBox bounds;
    boolean is2D;
    public RegionContainer(SegmentedObject structureObject) {
        this.is2D = structureObject.getRegion().is2D();
        this.structureObject=structureObject;
        this.bounds=new SimpleBoundingBox(structureObject.getBounds());
    }
    public void setStructureObject(SegmentedObject structureObject) {
        this.structureObject=structureObject;
    }
    protected float getScaleXY() {return structureObject.getScaleXY();}
    protected float getScaleZ() {return structureObject.getScaleZ();}
    public boolean is2D() {return is2D;}
    public abstract Region getRegion();

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
    protected RegionContainer() {}
    public static RegionContainer createFromJSON(SegmentedObject o, Map json) {
        RegionContainer res;
        if (json.containsKey("x")) res = new RegionContainerVoxels(); // coord list
        else if (json.containsKey("roi")||json.containsKey("roiZ")) res = new RegionContainerIjRoi();
        else res = new RegionContainerBlankMask(); // only bounds
        res.setStructureObject(o);
        res.initFromJSON(json);
        return res;
    }
}
