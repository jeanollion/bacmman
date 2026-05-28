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

import bacmman.core.Core;
import bacmman.data_structure.CoordCollection;
import bacmman.data_structure.Region;
import bacmman.data_structure.RegionPopulation;
import bacmman.image.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static bacmman.plugins.Plugin.logger;
import bacmman.processing.clustering.ClusterCollection;
import bacmman.processing.clustering.SimpleInterfaceVoxelSet;
import bacmman.processing.clustering.RegionCluster;

/**
 *
 * @author Jean Ollion
 */
public class FillHoles2D {
    public static boolean debug=false;

    public static void fillHoles(RegionPopulation pop) { // object-wise 2D fill holes
        for (Region o : pop.getRegions()) {
            if (o.getMask() instanceof ImageInteger) { // ensure mask value is 1
                ImageInteger mask = (ImageInteger)o.getMask();
                ImageMask.loop(mask, (x, y, z)->mask.setPixel(x, y, z, 1));
            }
            o.ensureMaskIsImageInteger(); // mask need to be dense to be modified
            fillHoles(o.getMaskAsImageInteger(), 2);
            o.setMask(o.getMask()); // set mask to reset contours,voxels, bounds etc...
        }
        pop.relabel(true);
    }

    public static void fillHoles(ImageInteger image, int midValue) {
        int sizeZ = image.sizeZ();
        CoordStack stack = new CoordStack();
        for (int z = 0; z<sizeZ; ++z) fillHolesPlane(image, midValue, z, stack);
    }

    // background (0) pixels connected to a border are temporarily set to midValue then back to 0;
    // remaining 0 pixels are holes -> set to foreground (1). foreground is normalized to 1.
    private static void fillHolesPlane(ImageInteger image, int midValue, int z, CoordStack stack) {
        int sizeX = image.sizeX();
        int sizeY = image.sizeY();
        for (int y = 0; y<sizeY; ++y) {
            if (image.getPixelInt(0, y, z)==0) floodFill(image, 0, y, z, midValue, sizeX, sizeY, stack);
            if (image.getPixelInt(sizeX-1, y, z)==0) floodFill(image, sizeX-1, y, z, midValue, sizeX, sizeY, stack);
        }
        for (int x = 0; x<sizeX; ++x) {
            if (image.getPixelInt(x, 0, z)==0) floodFill(image, x, 0, z, midValue, sizeX, sizeY, stack);
            if (image.getPixelInt(x, sizeY-1, z)==0) floodFill(image, x, sizeY-1, z, midValue, sizeX, sizeY, stack);
        }
        int n = sizeX * sizeY;
        for (int xy = 0; xy<n; ++xy) {
            if (image.getPixelInt(xy, z)==midValue) image.setPixel(xy, z, 0);
            else image.setPixel(xy, z, 1);
        }
    }

    // 4-connected scan-line flood fill of background (0) with fillColor, on plane z.
    // Adapted from ij.process.FloodFiller.
    private static void floodFill(ImageInteger image, int xStart, int yStart, int z, int fillColor, int sizeX, int sizeY, CoordStack stack) {
        if (image.getPixelInt(xStart, yStart, z)==fillColor) return; // target color is background (0)
        stack.clear();
        stack.push(xStart, yStart);
        while (!stack.isEmpty()) {
            int x = stack.popX();
            int y = stack.popY();
            if (image.getPixelInt(x, y, z)!=0) continue;
            int x1 = x, x2 = x;
            while (x1>=0 && image.getPixelInt(x1, y, z)==0) x1--; // find start of scan-line
            x1++;
            while (x2<sizeX && image.getPixelInt(x2, y, z)==0) x2++; // find end of scan-line
            x2--;
            for (int i = x1; i<=x2; ++i) image.setPixel(i, y, z, fillColor); // fill scan-line
            if (y>0) { // find scan-lines above this one
                boolean inScanLine = false;
                for (int i = x1; i<=x2; ++i) {
                    boolean match = image.getPixelInt(i, y-1, z)==0;
                    if (!inScanLine && match) { stack.push(i, y-1); inScanLine = true; }
                    else if (inScanLine && !match) inScanLine = false;
                }
            }
            if (y<sizeY-1) { // find scan-lines below this one
                boolean inScanLine = false;
                for (int i = x1; i<=x2; ++i) {
                    boolean match = image.getPixelInt(i, y+1, z)==0;
                    if (!inScanLine && match) { stack.push(i, y+1); inScanLine = true; }
                    else if (inScanLine && !match) inScanLine = false;
                }
            }
        }
    }

    // growable stack of (x,y) coordinates, reused across planes and seeds
    private static final class CoordStack {
        private int[] xs = new int[1024];
        private int[] ys = new int[1024];
        private int size = 0;
        void clear() { size = 0; }
        boolean isEmpty() { return size==0; }
        void push(int x, int y) {
            if (size==xs.length) {
                xs = java.util.Arrays.copyOf(xs, size*2);
                ys = java.util.Arrays.copyOf(ys, size*2);
            }
            xs[size] = x;
            ys[size] = y;
            ++size;
        }
        int popX() { return xs[size-1]; }
        int popY() { int y = ys[size-1]; --size; return y; }
    }

    public static boolean fillHolesClosing(ImageInteger image, double closeRadius, double backgroundProportion, double minSizeFusion, boolean parallele) {
        ImageInteger close = Filters.binaryCloseExtend(image, Filters.getNeighborhood(closeRadius, closeRadius, image), parallele);
        FillHoles2D.fillHoles(close, 2); // binary close generate an image with only 1's
        ImageOperations.xor(close, image, close);
        RegionPopulation foregroundPop = new RegionPopulation(image, false);
        RegionPopulation closePop = new RegionPopulation(close, false);
        if (debug) Core.showImage(closePop.getLabelMap().duplicate("close XOR"));
        closePop.filter(new InterfaceSizeFilter(foregroundPop, minSizeFusion, backgroundProportion));
        if (!closePop.getRegions().isEmpty()) {
            if (debug) {
                closePop.relabel(true);
                Core.showImage(closePop.getLabelMap().duplicate("close XOR after filter"));
                Core.showImage(foregroundPop.getLabelMap().duplicate("seg map before close"));
            }
            for (Region o : closePop.getRegions()) o.draw(image, 1);
            return true;
        } else return false;
    }

    private static class InterfaceSizeFilter implements RegionPopulation.Filter {
        RegionCluster clust;
        final RegionPopulation foregroundObjects;
        final double fusionSize, backgroundFactor;
        public InterfaceSizeFilter(RegionPopulation foregroundObjects, double fusionSize, double backgroundFactor) {
            this.foregroundObjects=foregroundObjects;
            this.fusionSize=fusionSize;
            this.backgroundFactor=backgroundFactor;
        }
        @Override
        public void init(RegionPopulation population) {
            if (!population.getImageProperties().sameDimensions(foregroundObjects.getImageProperties())) throw new IllegalArgumentException("Foreground objects population should have same bounds as current population");
            ClusterCollection.InterfaceFactory<Region, SimpleInterfaceVoxelSet> f = (Region e1, Region e2) -> new SimpleInterfaceVoxelSet(e1, e2);
            List<Region> allObjects = new ArrayList<>(population.getRegions().size()+foregroundObjects.getRegions().size());
            allObjects.addAll(population.getRegions());
            allObjects.addAll(foregroundObjects.getRegions());
            RegionPopulation mixedPop = new RegionPopulation(allObjects, population.getImageProperties());
            mixedPop.relabel();
            clust = new RegionCluster(mixedPop, new BlankMask(foregroundObjects.getImageProperties()), false, f); // high connectivity -> more selective
            //if (debug) new IJImageDisplayer().showImage(RegionCluster.drawInterfaces(clust));
        }
        private Map<Integer, Integer> getInterfaceSize(Region o) {
            Set<SimpleInterfaceVoxelSet> inter = clust.getInterfaces(o);
            Map<Integer, Integer> res = new HashMap<>(inter.size());
            for (SimpleInterfaceVoxelSet i : inter) {
                Region other = i.getOther(o);
                res.put(other.getLabel(), res.getOrDefault(other.getLabel(), 0) + i.getVoxels(other).size());
            }
            return res;
            
        }
        @Override
        public boolean keepObject(Region object) {
            Map<Integer, Integer> interfaces = getInterfaceSize(object);
            double bck = (interfaces.containsKey(0)) ? interfaces.remove(0) : 0;
            double fore = 0; for (Integer i : interfaces.values()) fore+=i;
            if (debug) logger.debug("fillHolesClosing object: {}, bck prop: {}, #inter: {}, bck:{}", object.getLabel(), bck/(bck+fore), interfaces.entrySet(), bck);
            if (bck>(bck+fore)*backgroundFactor) return false;
            if (interfaces.size()==1) return true;
            else { // link separated objects only if all but one are small enough
                Set<Region> interactants = clust.getInteractants(object);
                interactants.removeIf(o -> o.getLabel()==0 || o.size()<=fusionSize); 
                return interactants.size()<=1;
            }
        }
        
    }
}
