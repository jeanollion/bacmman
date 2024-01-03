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
package bacmman.ui.gui.image_interaction;

import bacmman.data_structure.Region;
import bacmman.data_structure.SegmentedObject;
import bacmman.data_structure.Voxel;
import bacmman.image.BoundingBox;
import bacmman.image.Image;
import bacmman.image.ImageByte;
import bacmman.image.ImageInteger;
import bacmman.image.Offset;
import bacmman.image.SimpleImageProperties;
import bacmman.image.SimpleOffset;
import bacmman.processing.ImageOperations;
import bacmman.processing.RegionFactory;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import bacmman.processing.FillHoles2D;
import bacmman.processing.Filters;
import bacmman.utils.Pair;
import bacmman.utils.Utils;
import java.util.Set;
import java.util.stream.Stream;

/**
 *
 * @author Jean Ollion
 */
public class ManualObjectStrecher {
    public static final Logger logger = LoggerFactory.getLogger(ManualObjectStrecher.class);
    public static void strechObjects(List<ObjectDisplay> parents, int structureIdx, int[] xPoints, int[] yPoints, double thresholdQuantile, boolean brightObject) {
        logger.debug("will strech {} objects, of structure: {}, x: {}, y: {}", parents.size(), structureIdx, xPoints, yPoints);
        List<Pair<SegmentedObject, Region>> objectsToUpdate = new ArrayList<>(parents.size());
        for (ObjectDisplay p : parents) {
            Stream<SegmentedObject> children = p.object.getChildren(structureIdx);
            if (children==null) continue;
            // get uppermost children :
            SegmentedObject child = children.min(Comparator.comparingInt(s -> s.getBounds().yMin())).orElse(null);
            if (child ==null) continue;
            Region childObject = child.getRegion().duplicate();
            Offset offset = new SimpleOffset(p.object.getBounds()).reverseOffset().translate(p.offset);
            childObject.translate(offset); // translate object in ROI referencial
            Set<Voxel> contour = childObject.getContour();
            if (contour.isEmpty()) continue;
            Voxel left = contour.iterator().next(), right = left;
            for (Voxel v : contour) { // get upper leftmost & upper rightmost voxels
                if (v.x<left.x) left=v;
                else if (v.x==left.x && v.y<left.y) left = v;
                if (v.x>right.x) right=v;
                else if (v.x==right.x && v.y<right.y) right = v;
            }
            ImageByte strechMap = new ImageByte("strech map", new SimpleImageProperties(p.offset, 1, 1));
            logger.debug("strechMap Bounds: {}", strechMap.getBoundingBox());
            Voxel leftUp=null, rightUp = null;
            for (int i = 0; i<xPoints.length; ++i) { // draw upper part && look
                if (strechMap.containsWithOffset(xPoints[i], yPoints[i], 0)) {
                    if (leftUp==null) {
                        leftUp = new Voxel(xPoints[i], yPoints[i], 0);
                        leftUp.value = (float)leftUp.getDistanceSquare(left);
                    } else {
                        float d = (float)left.getDistanceSquare(xPoints[i], yPoints[i], 0);
                        if (d<leftUp.value) {
                            leftUp = new Voxel(xPoints[i], yPoints[i], 0, d);
                        }
                    }
                    if (rightUp==null) {
                        rightUp = new Voxel(xPoints[i], yPoints[i], 0);
                        rightUp.value = (float)rightUp.getDistanceSquare(right);
                    } else {
                        float d = (float)right.getDistanceSquare(xPoints[i], yPoints[i], 0);
                        if (d<rightUp.value) {
                            rightUp = new Voxel(xPoints[i], yPoints[i], 0, d);
                        }
                    }
                }
            }
            // draw contour of new object
            drawLine(leftUp.x, leftUp.y, rightUp.x, rightUp.y, 0, 1, strechMap);
            drawLine(leftUp.x, leftUp.y, left.x, left.y, 0, 1, strechMap);
            drawLine(right.x, right.y, rightUp.x, rightUp.y, 0, 1, strechMap);
            childObject.draw(strechMap, 1, null);
            //ImageWindowManagerFactory.showImage(strechMap.duplicate("contours"));
            FillHoles2D.fillHoles(strechMap, 2);
            //childObject.draw(strechMap, 0, strechMap.getBoundingBox().reverseOffset());
            //ImageWindowManagerFactory.showImage(strechMap.duplicate("filledObject"));
            
            // Adjust filled object according to contours of existing object
            double meanIntensityContour=0;
            Image intensityMap = p.object.getRawImage(structureIdx);
            intensityMap.translate(offset);
            for (Voxel v : contour) meanIntensityContour += intensityMap.getPixelWithOffset(v.x, v.y, v.z);
            meanIntensityContour/=contour.size();
            
            ImageInteger outsideChildrenMask = p.object.getChildRegionPopulation(structureIdx).getLabelMap();
            ImageOperations.not(outsideChildrenMask, outsideChildrenMask);
            double meanIntensityOutsideObject = ImageOperations.getMeanAndSigma(intensityMap, outsideChildrenMask, null, true)[0];
            double thld = meanIntensityContour * thresholdQuantile + meanIntensityOutsideObject *(1-thresholdQuantile);
            logger.debug("mean int thld: {}, contour: {}, meanOutside : {}", thld,  meanIntensityContour, meanIntensityOutsideObject);
            
            // TODO adjust object according to intensities -> 2 scenarios bright object vs dark object, and compare using compaction of resulting objects
            ImageByte thlded = ImageOperations.threshold(intensityMap, thld, brightObject, false);
            //ImageWindowManagerFactory.showImage(thlded.duplicate("thld"));
            ImageOperations.and(thlded, strechMap, thlded);
            //ImageWindowManagerFactory.showImage(thlded.duplicate("and with thld"));
            //check that after thesholding, object reaches line -> if not , do not apply thresholding
            int yMin = RegionFactory.getObjectsImage(strechMap, false)[0].getBounds().yMin()+p.offset.yMin();
            logger.debug("y Min: {}, line y to reach: {}", yMin, Math.max(leftUp.y, rightUp.y)+1);
            if (yMin<=Math.max(leftUp.y, rightUp.y)+1) {
                strechMap = thlded;
                // Regularisation of object
                Filters.close(strechMap, strechMap, Filters.getNeighborhood(2, 1, strechMap), true);
                Filters.open(strechMap, strechMap, Filters.getNeighborhood(2, 1, strechMap), true);
                //ImageWindowManagerFactory.showImage(strechMap.duplicate("after close"));
            }
            offset.reverseOffset();
            strechMap.translate(offset);
            Region[] allO = RegionFactory.getObjectsImage(strechMap, false);
            if (allO.length>0) {
                Region newObject = allO[0].translate(strechMap.getBoundingBox());
                objectsToUpdate.add(new Pair(child, newObject));
                logger.debug("resulting object bounds: {} (old: {})", newObject, child.getRegion().getBounds(), newObject);
            }
            intensityMap.translate(offset);
            childObject.translate(offset);
        }
        logger.debug("objects to update: {}", Utils.toStringList(objectsToUpdate, p->p.key.getRegion().getVoxels().size()+">"+p.value.getVoxels().size()));

    }
    public static void drawLine(int x,int y,int x2, int y2, int z, int value, Image image) {
        int w = x2 - x ;
        int h = y2 - y ;
        int dx1 = 0, dy1 = 0, dx2 = 0, dy2 = 0 ;
        if (w<0) dx1 = -1 ; else if (w>0) dx1 = 1 ;
        if (h<0) dy1 = -1 ; else if (h>0) dy1 = 1 ;
        if (w<0) dx2 = -1 ; else if (w>0) dx2 = 1 ;
        int longest = Math.abs(w) ;
        int shortest = Math.abs(h) ;
        if (!(longest>shortest)) {
            longest = Math.abs(h) ;
            shortest = Math.abs(w) ;
            if (h<0) dy2 = -1 ; else if (h>0) dy2 = 1 ;
            dx2 = 0 ;            
        }
        int numerator = longest >> 1 ;
        for (int i=0;i<=longest;i++) {
            image.setPixelWithOffset(x, y, z, value);
            numerator += shortest ;
            if (!(numerator<longest)) {
                numerator -= longest ;
                x += dx1 ;
                y += dy1 ;
            } else {
                x += dx2 ;
                y += dy2 ;
            }
        }
    }
}
