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

import bacmman.data_structure.Region;
import bacmman.data_structure.RegionPopulation;
import bacmman.data_structure.Voxel;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

import bacmman.processing.neighborhood.CylindricalNeighborhood;
import bacmman.processing.neighborhood.Neighborhood;
import bacmman.processing.watershed.WatershedTransform;
import bacmman.processing.watershed.WatershedTransform.WatershedConfiguration;
import java.util.Set;

/**
 *
 * @author Jean Ollion
 */
public class ImageLabeller {
    public final ImageInt imLabels;
    public final int[][] labels;
    public final int sizeX;
    public final HashMap<Integer, Spot> spots;
    public final ImageMask mask;
    public static final int[][] neigh3DHalf = new int[][]{
            {1, 1, -1}, {0, 1, -1}, {-1, 1, -1}, {1, 0, -1}, {0, 0, -1}, {-1, 0, -1}, {1, -1, -1}, {0, 1, -1}, {-1, -1, -1},
            {1, -1, 0}, {0, -1, 0}, {-1, -1, 0}, {-1, 0, 0}
        };
    public static final int[][] neigh3DLowHalf = new int[][]{ {0, 0, -1}, {0, -1, 0},  {-1, 0, 0} };
    public static final int[][] neigh2D8Half = new int[][]{ {1, -1, 0}, {0, -1, 0}, {-1, -1, 0}, {-1, 0, 0} };
    public static final int[][] neigh2D4Half = new int[][]{ {0, -1, 0}, {-1, 0, 0} };
    int[][] neigh;
    
    public ImageLabeller(ImageMask mask) {
        this.mask=mask;
        imLabels = new ImageInt("labels", mask);
        labels = imLabels.getPixelArray();
        sizeX = mask.sizeX();
        spots = new HashMap<Integer, Spot>();
    }
    public static Region[] labelImage(ImageMask mask, Neighborhood n) {
        if (mask instanceof BlankMask) return new Region[]{new Region((BlankMask)mask, 1, mask.sizeZ()==1)};
        else {
            ImageLabeller il = new ImageLabeller(mask);
            il.labelSpots(n);
            return il.getObjects();
        }
    }

    public static Region[] labelImage(ImageMask mask) {
        if (mask instanceof BlankMask) return new Region[]{new Region((BlankMask)mask, 1, mask.sizeZ()==1)};
        else {
            ImageLabeller il = new ImageLabeller(mask);
            if (mask.sizeZ()>1) il.neigh=ImageLabeller.neigh3DHalf;
            else il.neigh=ImageLabeller.neigh2D8Half;
            il.labelSpots();
            return il.getObjects();
        }
    }
    
    public static Region[] labelImageLowConnectivity(ImageMask mask) {
        if (mask instanceof BlankMask) return new Region[]{new Region((BlankMask)mask, 1, mask.sizeZ()==1)};
        else {
            ImageLabeller il = new ImageLabeller(mask);
            if (mask.sizeZ()>1) il.neigh=ImageLabeller.neigh3DLowHalf;
            else il.neigh=ImageLabeller.neigh2D4Half;
            il.labelSpots();
            return il.getObjects();
        }
    }
    
    public static List<Region> labelImageList(ImageMask mask) {
        return new ArrayList<>(Arrays.asList(labelImage(mask)));
    }
    
    public static List<Region> labelImageListLowConnectivity(ImageMask mask) {
        return new ArrayList<>(Arrays.asList(labelImageLowConnectivity(mask)));
    }
    /**
     * 
     * @param seeds seeds contained by final objects 
     * @param mask label mask
     * @return  Label objects starting from {@param seeds} that have same value on {@param mask} as the seed's value. If two object that have same seed value meet, they will be merged
     */
    public static RegionPopulation labelImage(List<Voxel> seeds, Image mask, boolean lowConnectivity) {
        WatershedTransform.PropagationCriterion prop = new WatershedTransform.PropagationCriterion() {
            @Override
            public void setUp(WatershedTransform instance) {}

            @Override
            public boolean continuePropagation(Voxel currentVox, Voxel nextVox) {
                return mask.getPixel(nextVox.x, nextVox.y, nextVox.z) == mask.getPixel(currentVox.x, currentVox.y, currentVox.z);
            }
        };
        WatershedTransform.FusionCriterion fus = new WatershedTransform.FusionCriterion() {

            @Override
            public void setUp(WatershedTransform instance) {}

            @Override
            public boolean checkFusionCriteria(WatershedTransform.Spot s1, WatershedTransform.Spot s2, Voxel currentVoxel) {
                Voxel v1 = s1.voxels.iterator().next();
                Voxel v2 = s2.voxels.iterator().next();
                return mask.getPixel(v1.x, v1.y, v1.z)==mask.getPixel(v2.x, v2.y, v2.z) && mask.getPixel(v1.x, v1.y, v1.z)==mask.getPixel(currentVoxel.x, currentVoxel.y, currentVoxel.z);
            }
        };
        WatershedConfiguration config = new WatershedConfiguration().lowConectivity(lowConnectivity).fusionCriterion(fus).propagationCriterion(prop);
        RegionPopulation pop = WatershedTransform.watershed(mask, null, WatershedTransform.createSeeds(seeds, mask.sizeZ()==1, mask.getScaleXY(), mask.getScaleZ()), config);
        return pop;
    }
    protected Region[] getObjects() {
        Region[] res = new Region[spots.size()];
        int label = 0;
        for (Spot s : spots.values()) res[label++]= new Region(s.voxels, label, mask.sizeZ()==1, mask.getScaleXY(), mask.getScaleZ());
        return res;
    }
    
    private void labelSpots() {
        int currentLabel = 1;
        Spot currentSpot;
        Voxel v;
        int nextLabel;
        int xy;
        for (int z = 0; z < mask.sizeZ(); ++z) {
            for (int y = 0; y < mask.sizeY(); ++y) {
                for (int x = 0; x < sizeX; ++x) {
                    xy = x + y * sizeX;
                    if (mask.insideMask(xy, z)) {
                        currentSpot = null;
                        v = new Voxel(x, y, z);
                        for (int[] t : neigh) {
                            if (mask.contains(x + t[0], y + t[1], z + t[2])) {
                                nextLabel = labels[z + t[2]][xy + t[0] + t[1] * sizeX];
                                if (nextLabel != 0) {
                                    if (currentSpot == null) {
                                        currentSpot = spots.get(nextLabel);
                                        currentSpot.addVox(v);
                                    } else if (nextLabel != currentSpot.label) {
                                        currentSpot = currentSpot.fusion(spots.get(nextLabel));
                                        currentSpot.addVox(v);
                                    }
                                }
                            }
                        }
                        if (currentSpot == null) {
                            spots.put(currentLabel, new Spot(currentLabel++, v));
                        }
                    }
                }
            }
        }
    }

    public void labelSpots(Neighborhood n) {
        int currentLabel = 1;
        Spot currentSpot;
        for (int z = 0; z<mask.sizeZ(); ++z) {
            for (int y = 0; y<mask.sizeY(); ++y) {
                for (int x=0; x<sizeX; ++x) {
                    if (mask.insideMask(x, y, z)) {
                        currentSpot = null;
                        Voxel v = new Voxel(x, y, z);
                        n.setPixels(x, y, z, imLabels, mask);
                        for (int i = 0; i<n.getValueCount(); ++i) {
                            int nextLabel = (int)n.getPixelValues()[i]; // double values might not be enough...
                            if (nextLabel != 0) {
                                if (currentSpot== null) {
                                    currentSpot= spots.get(nextLabel);
                                    currentSpot.addVox(v);
                                } else if (nextLabel != currentSpot.label) {
                                    currentSpot = currentSpot.fusion(spots.get(nextLabel));
                                    currentSpot.addVox(v);
                                }
                            }

                        }
                        if (currentSpot == null) {
                            spots.put(currentLabel, new Spot(currentLabel++, v));
                        }
                    }
                }
            }
        }
    }
    
    public class Spot {

        Set<Voxel> voxels;
        public int label;

        public Spot(int label, Voxel v) {
            this.label = label;
            this.voxels = new HashSet<>();
            voxels.add(v);
            labels[v.z][v.x+v.y*sizeX] = label;
        }

        public void addVox(Voxel v) {
            voxels.add(v);
            labels[v.z][v.x+v.y*sizeX] = label;
        }

        public void setLabel(int label) {
            this.label = label;
            for (Voxel v : voxels) {
                labels[v.z][v.x+v.y*sizeX] = label;
            }
        }

        public Spot fusion(Spot other) {
            if (other.label < label) {
                return other.fusion(this);
            }
            spots.remove(other.label);
            voxels.addAll(other.voxels);
            other.setLabel(label);
            return this;
        }

        public int getSize() {
            return voxels.size();
        }
    }
}
