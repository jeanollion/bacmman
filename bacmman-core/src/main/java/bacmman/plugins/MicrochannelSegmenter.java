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
package bacmman.plugins;

import bacmman.data_structure.Region;
import bacmman.data_structure.RegionPopulation;
import bacmman.data_structure.SegmentedObject;
import bacmman.image.BlankMask;
import bacmman.image.Image;
import bacmman.image.ImageProperties;
import bacmman.image.MutableBoundingBox;
import bacmman.utils.ArrayUtil;

import java.util.ArrayList;
import java.util.List;

/**
 *
 * @author Jean Ollion
 */
public interface MicrochannelSegmenter extends Segmenter {
    /**
     * 
     * @param input image to segment microchannels from
     * @return Result object defining bounds of each segmented microchannel within {@param input} image
     */
    public Result segment(Image input, int structureIdx, SegmentedObject parent);
    /**
     * Result Class for Microchannel Segmenters that defines the bounds of every microchannels, ordered from left to rigth. 
     */
    public static class Result {
        public final int[] xMax;
        public final int[] xMin;
        public final int[] yMinShift;
        public int yMin, yMax;

        public Result(List<int[]> sortedMinMaxYShiftList, int yMin, int yMax) {
            this.yMin = yMin;
            this.yMax=yMax;
            this.xMax= new int[sortedMinMaxYShiftList.size()];
            this.xMin=new int[sortedMinMaxYShiftList.size()];
            this.yMinShift= new int[sortedMinMaxYShiftList.size()];
            int idx = 0;
            for (int[] minMax : sortedMinMaxYShiftList) {
                xMin[idx] = minMax[0];
                xMax[idx] = minMax[1];
                yMinShift[idx++] = minMax[2];
            }
        }
        public int getXMin() {
            return xMin[0];
        }
        public int getXMax() {
            return xMax[xMax.length-1];
        }
        public int getXWidth(int idx) {
            return xMax[idx]-xMin[idx];
        }
        public double getXMean(int idx) {
            return (xMax[idx]+xMin[idx]) / 2d ;
        }
        public int getYMin() {
            return yMin+ ArrayUtil.min(yMinShift);
        }
        public int getYMax() {
            return yMax;
        }
        public int size() {
            return xMin.length;
        }
        public MutableBoundingBox getBounds(int idx, boolean includeYMinShift) {
            return new MutableBoundingBox(xMin[idx], xMax[idx], yMin+(includeYMinShift?yMinShift[idx]:0), yMax, 0, 0);
        }
        public Region getRegion(int idx, double scaleXY, double scaleZ, boolean includeYMinShift, int zMax) {
            MutableBoundingBox bds = getBounds(idx, includeYMinShift);
            if (zMax>1) bds.unionZ(zMax);
            return new Region(new BlankMask( bds, scaleXY, scaleZ), idx+1, bds.sizeZ()==1);
        }
        public RegionPopulation getObjectPopulation(ImageProperties im, boolean includeYMinShift) {
            List<Region> l = new ArrayList<>(xMin.length);
            for (int i = 0; i<xMin.length; ++i) l.add(getRegion(i, im.getScaleXY(), im.getScaleZ(), includeYMinShift, im.sizeZ()));
            return new RegionPopulation(l, im);
        }
    }
}
