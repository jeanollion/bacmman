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
package bacmman.image;

import bacmman.processing.neighborhood.EllipsoidalNeighborhood;


public interface ImageMask<I extends ImageMask<I>> extends ImageProperties<I> {

    public boolean insideMask(int x, int y, int z);
    public boolean insideMask(int xy, int z);
    public boolean insideMaskWithOffset(int x, int y, int z);
    public int count();
    public static void loop(ImageMask mask, LoopFunction function) {
        BoundingBox.loop(new SimpleBoundingBox(mask).resetOffset(), (x, y, z)-> {if (mask.insideMask(x, y, z)) function.loop(x, y, z);});
    }
    public static void loop(ImageMask mask, LoopFunction function, LoopPredicate predicate) {
        BoundingBox.loop(new SimpleBoundingBox(mask).resetOffset(), (x, y, z)-> {if (mask.insideMask(x, y, z) && predicate.test(x, y, z)) function.loop(x, y, z);});
    }
    static void loopWithOffset(ImageMask mask, LoopFunction function) {
        BoundingBox.loop(mask, (x, y, z)-> {if (mask.insideMaskWithOffset(x, y, z)) function.loop(x, y, z);});
    }
    static void loopWithOffset(ImageMask mask, LoopFunction function, Offset off) {
        if (off == null || Offset.offsetNull(off)) loopWithOffset(mask, function);
        else BoundingBox.loop(mask, (x, y, z)-> {if (mask.insideMaskWithOffset(x, y, z)) function.loop(x+off.xMin(), y+off.yMin(), z+off.zMin());});
    }
    public static void loopWithOffset(ImageMask mask, LoopFunction function, LoopPredicate predicate) {
        BoundingBox.loop(mask, (x, y, z)-> {if (mask.insideMaskWithOffset(x, y, z) && predicate.test(x, y, z)) function.loop(x, y, z);});
    }

    public ImageMask duplicateMask();
    public static LoopPredicate insideMask(final ImageMask mask, boolean withOffset) {
        if (withOffset) return (x,y, z)->mask.insideMaskWithOffset(x, y, z); 
        else return (x,y, z)->mask.insideMask(x, y, z);
    }

    public static LoopPredicate borderOfMask(final ImageMask mask, boolean withOffset) {
        EllipsoidalNeighborhood n = mask.sizeZ()>1 ? new EllipsoidalNeighborhood(1.5, 1.5, true) : new EllipsoidalNeighborhood(1.5, true);
        if (withOffset) {
            // remove offset to n so that n.hasNullValue works with offset
            for (int i = 0; i<n.getSize(); ++i) {
                n.dx[i] -= mask.xMin();
                n.dy[i] -= mask.yMin();
                n.dz[i] -= mask.zMin();
            }
            return (x,y, z)-> {
                if (!mask.insideMaskWithOffset(x, y, z)) return false;
                return n.hasNullValue(x, y, z, mask, true);
            };
        } else {
            return (x,y, z)-> {
                if (!mask.insideMask(x, y, z)) return false;
                return n.hasNullValue(x, y, z, mask, true);
            };
        }
    }

    static ImageMask crop(ImageMask mask, BoundingBox box) {
        // box coordinates are relative to mask .
        return cropWithOffset(mask, box.duplicate().translate(mask));
    }

    static ImageMask cropWithOffset(ImageMask mask, BoundingBox box) {
        // box & mask coordinates are absolute
        ImageProperties props = new SimpleImageProperties(box, mask.getScaleXY(), mask.getScaleZ());
        PredicateMask.InsideMaskFunction insideMask = (x, y, z) -> { // x, y, z relative to box
            if (!box.contains(x, y, z)) return false;
            return mask.containsWithOffset(x + box.xMin(), y + box.yMin(), z + box.zMin());
        };
        PredicateMask.InsideMaskXYFunction insideMaskXY = (xy, z) -> { // xy, z relative to box
            int y = xy / box.sizeX();
            int x = xy % box.sizeX();
            if (!box.contains(x, y, z)) return false;
            return mask.containsWithOffset(x + box.xMin(), y + box.yMin(), z + box.zMin());
        };
        boolean is2D = box.sizeZ()==1 && mask.sizeZ()==1;
        return new PredicateMask(props, insideMask, insideMaskXY, is2D);
    }

}
