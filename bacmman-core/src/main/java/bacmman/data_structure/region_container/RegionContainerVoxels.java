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
import bacmman.data_structure.Voxel;

import java.util.Map;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import bacmman.utils.JSONUtils;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 *
 * @author Jean Ollion
 */
public class RegionContainerVoxels extends RegionContainer {

    int[] x, y, z;
    int zMin=0;
    public RegionContainerVoxels(SegmentedObject structureObject) {
        super(structureObject);
        createCoordsArrays(structureObject.getRegion());
    }

    
    private void createCoordsArrays(Region object) {
        if (!object.is2D()) {
            Collection<Voxel> voxels = object.getVoxels();
            x = new int[voxels.size()];
            y = new int[voxels.size()];
            z = new int[voxels.size()];
            int idx = 0;
            for (Voxel v : voxels) {
                x[idx] = v.x;
                y[idx] = v.y;
                z[idx++] = v.z;
            }
        } else {
            Collection<Voxel> voxels = object.getVoxels();
            x = new int[voxels.size()];
            y = new int[voxels.size()];
            z = null;
            zMin = voxels.stream().mapToInt(v->v.z).min().orElse(0);
            int idx = 0;
            for (Voxel v : voxels) {
                x[idx] = v.x;
                y[idx++] = v.y;
            }
        }
    }

    private Set<Voxel> getVoxels() {
        if (x == null || y == null) {
            return new HashSet(0);
        }
        HashSet<Voxel> voxels = new HashSet<>(x.length);
        if (z != null) {
            for (int i = 0; i < x.length; ++i) voxels.add(new Voxel(x[i], y[i], z[i]));
        } else {
            for (int i = 0; i < x.length; ++i) voxels.add(new Voxel(x[i], y[i], zMin));
        }
        return voxels;
    }
    @Override
    public Region getRegion() {
        return new Region(getVoxels(), segmentedObject.getIdx() + 1, bounds, is2D, segmentedObject.getScaleXY(), segmentedObject.getScaleZ());
    }
    @Override
    public void update() {
        super.update();
        createCoordsArrays(segmentedObject.getRegion());
    }
    @Override 
    public JSONObject toJSON() {
        JSONObject res = super.toJSON();
        if (x!=null) res.put("x", JSONUtils.toJSONArray(x));
        if (y!=null) res.put("y", JSONUtils.toJSONArray(y));
        if (z!=null) res.put("z", JSONUtils.toJSONArray(z));
        else res.put("zMin", zMin);
        return res;
    }
    @Override 
    public void initFromJSON(Map json) {
        super.initFromJSON(json);
        if (!json.containsKey("x") || !json.containsKey("y")) throw new IllegalArgumentException("JSON object do no contain x & y values");
        JSONArray xJ = (JSONArray)json.get("x");
        JSONArray yJ = (JSONArray)json.get("y");
        JSONArray zJ = json.containsKey("z") ? (JSONArray)json.get("z") : null;
        if (xJ.size()!=yJ.size() || (zJ!=null && zJ.size()!=xJ.size())) throw new IllegalArgumentException("JSON object arrays of different sizes");
        x = JSONUtils.fromIntArray(xJ);
        y = JSONUtils.fromIntArray(yJ);
        if (zJ!=null) z = JSONUtils.fromIntArray(zJ);
        if (json.containsKey("zMin")) zMin = ((Long)json.getOrDefault("zMin", 0)).intValue();
    }
    protected RegionContainerVoxels() {}
}
