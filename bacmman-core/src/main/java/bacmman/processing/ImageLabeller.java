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
package bacmman.processing;

import bacmman.data_structure.CoordCollection;
import bacmman.data_structure.Region;
import bacmman.data_structure.RegionPopulation;
import bacmman.data_structure.Voxel;
import bacmman.image.BlankMask;
import bacmman.image.Image;
import bacmman.image.ImageInt;
import bacmman.image.ImageMask;
import bacmman.processing.neighborhood.Neighborhood;
import bacmman.processing.watershed.WatershedTransform;
import bacmman.processing.watershed.WatershedTransform.WatershedConfiguration;

import java.util.*;

/**
 *
 * @author Jean Ollion
 */
public class ImageLabeller {
    public final ImageInt imLabels;
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
        spots = new HashMap<>();
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
            if (mask.sizeZ()>1) il.neigh= ImageLabeller.neigh3DHalf;
            else il.neigh= ImageLabeller.neigh2D8Half;
            il.labelSpots();
            return il.getObjects();
        }
    }
    
    public static Region[] labelImageLowConnectivity(ImageMask mask) {
        if (mask instanceof BlankMask) return new Region[]{new Region((BlankMask)mask, 1, mask.sizeZ()==1)};
        else {
            ImageLabeller il = new ImageLabeller(mask);
            if (mask.sizeZ()>1) il.neigh= ImageLabeller.neigh3DLowHalf;
            else il.neigh= ImageLabeller.neigh2D4Half;
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
            WatershedTransform instance;
            @Override
            public void setUp(WatershedTransform instance) {this.instance=instance;}

            @Override
            public boolean continuePropagation(long currentVox, long nextVox) {
                return instance.getHeap().getPixel(mask, nextVox) == instance.getHeap().getPixel(mask, currentVox);
            }
        };
        WatershedTransform.FusionCriterion fus = new WatershedTransform.FusionCriterion() {
            WatershedTransform instance;
            @Override
            public void setUp(WatershedTransform instance) {this.instance = instance;}

            @Override
            public boolean checkFusionCriteria(WatershedTransform.Spot s1, WatershedTransform.Spot s2, long currentVoxel) {
                long c1 = s1.voxels.stream().findAny().getAsLong();
                long c2 = s2.voxels.stream().findAny().getAsLong();
                double l1 = instance.getHeap().getPixel(mask, c1);
                return l1==instance.getHeap().getPixel(mask, c2) && l1==instance.getHeap().getPixel(mask, currentVoxel);
            }
        };
        WatershedConfiguration config = new WatershedConfiguration().lowConectivity(lowConnectivity).fusionCriterion(fus).propagationCriterion(prop);
        RegionPopulation pop = WatershedTransform.watershed(mask, null, WatershedTransform.createSeeds(seeds, mask.sizeZ()==1, mask.getScaleXY(), mask.getScaleZ()), config);
        return pop;
    }
    protected Region[] getObjects() {
        Region[] res = new Region[spots.size()];
        int label = 0;
        for (Spot s : spots.values()) res[label++]= s.toRegion(label);
        return res;
    }
    
    private void labelSpots() {
        int currentLabel = 1;
        CoordCollection cc = CoordCollection.create(mask.sizeX(), mask.sizeY(), mask.sizeZ());
        for (int z = 0; z < mask.sizeZ(); ++z) {
            for (int y = 0; y < mask.sizeY(); ++y) {
                for (int x = 0; x < mask.sizeX(); ++x) {
                    long coord = cc.toCoord(x, y, z);
                    if (cc.insideMask(mask, coord)) {
                        Spot currentSpot = null;
                        for (int[] t : neigh) {
                            if (cc.insideBounds(coord, t[0], t[1], t[2])) {
                                long next = cc.translate(coord, t[0], t[1], t[2]);
                                int nextLabel = cc.getPixelInt(imLabels, next);
                                if (nextLabel != 0) {
                                    if (currentSpot == null) {
                                        currentSpot = spots.get(nextLabel);
                                        currentSpot.addVox(coord);
                                    } else if (nextLabel != currentSpot.label) {
                                        currentSpot = currentSpot.fusion(spots.get(nextLabel));
                                        currentSpot.addVox(coord);
                                    }
                                }
                            }
                        }
                        if (currentSpot == null) {
                            spots.put(currentLabel, new Spot(currentLabel++, coord));
                        }
                    }
                }
            }
        }
    }

    private void labelSpots(Neighborhood n) {
        int currentLabel = 1;
        CoordCollection cc = CoordCollection.create(mask.sizeX(), mask.sizeY(), mask.sizeZ());
        for (int z = 0; z < mask.sizeZ(); ++z) {
            for (int y = 0; y < mask.sizeY(); ++y) {
                for (int x = 0; x < mask.sizeX(); ++x) {
                    long coord = cc.toCoord(x, y, z);
                    n.setPixels(x, y, z, imLabels, mask);
                    if (cc.insideMask(mask, coord)) {
                        Spot currentSpot = null;
                        for (int i = 0; i<n.getValueCount(); ++i) {
                            int nextLabel = (int)n.getPixelValues()[i]; // double values might not be enough...
                            if (nextLabel != 0) {
                                if (currentSpot == null) {
                                    currentSpot = spots.get(nextLabel);
                                    currentSpot.addVox(coord);
                                } else if (nextLabel != currentSpot.label) {
                                    currentSpot = currentSpot.fusion(spots.get(nextLabel));
                                    currentSpot.addVox(coord);
                                }
                            }
                        }
                        if (currentSpot == null) {
                            spots.put(currentLabel, new Spot(currentLabel++, coord));
                        }
                    }
                }
            }
        }
    }

    public class Spot {

        CoordCollection voxels;
        public int label;

        public Spot(int label, long coord) {
            this.label = label;
            this.voxels = CoordCollection.create(mask.sizeX(), mask.sizeY(), mask.sizeZ());
            voxels.add(coord);
            voxels.setPixel(imLabels, coord, label);
        }

        public void addVox(long c) {
            voxels.add(c);
            voxels.setPixel(imLabels, c, label);
        }

        public void setLabel(int label) {
            this.label = label;
            voxels.stream().forEach(c -> voxels.setPixel(imLabels, c, label));
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

        public Region toRegion(int label) {
            return new Region(voxels.getMask(mask.getScaleXY(), mask.getScaleZ()), label, mask.sizeZ()==1);
        }
    }
}
