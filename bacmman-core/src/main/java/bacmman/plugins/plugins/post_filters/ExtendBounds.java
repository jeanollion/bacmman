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
package bacmman.plugins.plugins.post_filters;

import bacmman.configuration.parameters.BoundedNumberParameter;
import bacmman.configuration.parameters.ChoiceParameter;
import bacmman.configuration.parameters.Parameter;
import bacmman.data_structure.RegionPopulation;
import bacmman.data_structure.SegmentedObject;
import bacmman.plugins.PostFilter;
import bacmman.plugins.Hint;
import bacmman.image.BlankMask;
import bacmman.image.BoundingBox;
import bacmman.image.MutableBoundingBox;
import bacmman.image.SimpleBoundingBox;
import bacmman.image.SimpleImageProperties;

import java.util.Arrays;

/**
 *
 * @author Jean Ollion
 */
public class ExtendBounds implements PostFilter, Hint {

    @Override
    public String getHintText() {
        return "Transforms a segmented object into a rectangular object corresponding to the bounding box of the object, extended by a user-defined size in each direction";
    }
    public enum OUT_OF_BOUNDS_CONDITION {
        TRIM("Trim"),
        KEEP_SIZE("Keep Global Size"),
        KEEP_CENTER("Keep Center");
        public final String name;
        private OUT_OF_BOUNDS_CONDITION(String name) {this.name = name;}
        public static OUT_OF_BOUNDS_CONDITION get(String name) {
            return Arrays.stream(OUT_OF_BOUNDS_CONDITION.values()).filter(s->s.name.equals(name)).findAny().orElseThrow(()->new RuntimeException("Out of bound condition not found"));
        }
        public static String[] names() {
            return Arrays.stream(OUT_OF_BOUNDS_CONDITION.values()).map(s->s.name).toArray(l->new String[l]);
        }
    }
    BoundedNumberParameter x1 = new BoundedNumberParameter("X Left", 0, 8, 0, null).setEmphasized(true).setHint("Number of pixel to add to bounds on left side in X direction");
    ChoiceParameter outOfBoundX = new ChoiceParameter("X out-of-bound", OUT_OF_BOUNDS_CONDITION.names(), OUT_OF_BOUNDS_CONDITION.KEEP_SIZE.name, false).setEmphasized(true).setHint("In case the extended bounding box is out of the bound of the parent image:<br /><ul><li><em>Trim: </em> The bound is simply set to the bound of the parent image</li><li><em>Keep Global Size: </em> The bounds are translated in order to fit in the parent image and keep a constant size</li><li><em>Keep Center: </em> Bounds are equally trimmed on both sides so that the original bound is still in the middle of the new bounds</li></ul>");
    BoundedNumberParameter x2 = new BoundedNumberParameter("X Right", 0, 8, 0, null).setEmphasized(true).setHint("Number of pixel to add to bounds on right side in X direction");
    BoundedNumberParameter y1 = new BoundedNumberParameter("Y Up", 0, 8, 0, null).setEmphasized(true).setHint("Number of pixel to add to bounds on upper side in Y direction");
    ChoiceParameter outOfBoundY = new ChoiceParameter("Y out-of-bound", OUT_OF_BOUNDS_CONDITION.names(), OUT_OF_BOUNDS_CONDITION.TRIM.name, false).setEmphasized(true).setHint("In case the extended bounding box is out of the bound of the parent image:<br /><ul><li><em>Trim: </em> The bound is simply set to the bound of the parent image</li><li><em>Keep Global Size: </em> The bounds are translated in order to fit in the parent image and keep a constant size</li><li><em>Keep Center: </em> Bounds are equally trimmed on both sides so that the original bound is still in the middle of the new bounds</li></ul>");
    BoundedNumberParameter y2 = new BoundedNumberParameter("Y Down", 0, 0, 0, null).setEmphasized(true).setHint("Number of pixel to add to bounds on lower side in Y direction");
    BoundedNumberParameter z1 = new BoundedNumberParameter("Z Up", 0, 0, 0, null).setEmphasized(true).setHint("Number of pixel to add to bounds on upper side in Z direction");
    ChoiceParameter outOfBoundZ = new ChoiceParameter("Z out-of-bound", OUT_OF_BOUNDS_CONDITION.names(), OUT_OF_BOUNDS_CONDITION.TRIM.name, false).setEmphasized(true).setHint("In case the extended bounding box is out of the bound of the parent image:<br /><ul><li><em>Trim: </em> The bound is simply set to the bound of the parent image</li><li><em>Keep Global Size: </em> The bounds are translated in order to fit in the parent image and keep a constant size</li><li><em>Keep Center: </em> Bounds are equally trimmed on both sides so that the original bound is still in the middle of the new bounds</li></ul>");
    BoundedNumberParameter z2 = new BoundedNumberParameter("Z Down", 0, 0, 0, null).setEmphasized(true).setHint("Number of pixel to add to bounds on lower side in Z direction");
    
    
    @Override
    public RegionPopulation runPostFilter(SegmentedObject parent, int childStructureIdx, RegionPopulation childPopulation) {
        int x1 = this.x1.getValue().intValue();
        int y1 = this.y1.getValue().intValue();
        int z1 = this.z1.getValue().intValue();
        int x2 = this.x2.getValue().intValue();
        int y2 = this.y2.getValue().intValue();
        int z2 = this.z2.getValue().intValue();
        OUT_OF_BOUNDS_CONDITION oobX = OUT_OF_BOUNDS_CONDITION.get(outOfBoundX.getSelectedItem());
        OUT_OF_BOUNDS_CONDITION oobY = OUT_OF_BOUNDS_CONDITION.get(outOfBoundY.getSelectedItem());
        OUT_OF_BOUNDS_CONDITION oobZ = OUT_OF_BOUNDS_CONDITION.get(outOfBoundZ.getSelectedItem());
        BoundingBox parentBds = new SimpleBoundingBox(parent.getBounds()).resetOffset();
        childPopulation.ensureEditableRegions();
        childPopulation.getRegions().forEach(r->{
            MutableBoundingBox bds = new MutableBoundingBox(r.getBounds().xMin()-x1, r.getBounds().xMax()+x2, r.getBounds().yMin()-y1, r.getBounds().yMax()+y2, r.getBounds().zMin()-z1, r.getBounds().zMax()+z2);
            if (x1>0) {
                if (bds.xMin()<0) {
                    switch(oobX) {
                        case TRIM:
                            bds.setxMin(0);
                            break;
                        case KEEP_SIZE:
                            bds.translate(-bds.xMin(), 0, 0);
                            break;
                        case KEEP_CENTER:
                            bds.setxMax(bds.xMax()+bds.xMin());
                            bds.setxMin(0);
                            break;
                    }
                }
            }
            if (x2>0) {
                if (bds.xMax()>parentBds.xMax()) {
                    switch(oobX) {
                        case TRIM:
                            bds.setxMax(parentBds.xMax());
                            break;
                        case KEEP_SIZE:
                            bds.translate(parentBds.xMax()-bds.xMax(), 0, 0);
                            break;
                        case KEEP_CENTER:
                            bds.setxMax(bds.xMax()+bds.xMin());
                            bds.setxMin(bds.xMin()-(parentBds.xMax()-bds.xMax()));
                            break;
                    }
                }
            }
            if (bds.xMin()<0 || bds.xMax()>parentBds.xMax()) throw new RuntimeException("Extended bounds could not fit into image on X axis");
            if (y1>0) {
                if (bds.yMin()<0) {
                    switch(oobY) {
                        case TRIM:
                            bds.setyMin(0);
                            break;
                        case KEEP_SIZE:
                            bds.translate(0,-bds.yMin(), 0);
                            break;
                        case KEEP_CENTER:
                            bds.setyMax(bds.yMax()+bds.yMin());
                            bds.setyMin(0);
                            break;
                    }
                }
            }
            if (y2>0) {
                if (bds.yMax()>parentBds.yMax()) {
                    switch(oobY) {
                        case TRIM:
                            bds.setyMax(parentBds.yMax());
                            break;
                        case KEEP_SIZE:
                            bds.translate(0, parentBds.yMax()-bds.yMax(), 0);
                            break;
                        case KEEP_CENTER:
                            bds.setyMax(bds.yMax()+bds.yMin());
                            bds.setyMin(bds.yMin()-(parentBds.yMax()-bds.yMax()));
                            break;
                    }
                }
            }
            if (bds.yMin()<0 || bds.yMax()>parentBds.yMax()) throw new RuntimeException("Extended bounds could not fit into image on Y axis");
            if (z1>0) {
                if (bds.zMin()<0) {
                    switch(oobZ) {
                        case TRIM:
                            bds.setzMin(0);
                            break;
                        case KEEP_SIZE:
                            bds.translate(0, 0, -bds.zMin());
                            break;
                        case KEEP_CENTER:
                            bds.setzMax(bds.zMax()+bds.zMin());
                            bds.setzMin(0);
                            break;
                    }
                }
            }
            if (z2>0){
                if (bds.zMax()>parentBds.zMax()) {
                    switch(oobZ) {
                        case TRIM:
                            bds.setzMax(parentBds.zMax());
                            break;
                        case KEEP_SIZE:
                            bds.translate(0, 0, parentBds.zMax()-bds.zMax());
                            break;
                        case KEEP_CENTER:
                            bds.setzMax(bds.zMax()+bds.zMin());
                            bds.setzMin(bds.zMin()-(parentBds.zMax()-bds.zMax()));
                            break;
                    }
                }
            }
            if (bds.zMin()<0 || bds.zMax()>parentBds.zMax()) throw new RuntimeException("Extended bounds could not fit into image on Z axis");
            r.setMask(new BlankMask(new SimpleImageProperties(bds, r.getScaleXY(), r.getScaleZ())));
        });
        return childPopulation;
    }

    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{x1, x2, outOfBoundX, y1, y2, outOfBoundY, z1, z2, outOfBoundZ};
    }
    
}
