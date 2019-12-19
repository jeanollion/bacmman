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
package bacmman.plugins.plugins.track_pre_filters;

import bacmman.configuration.parameters.BooleanParameter;
import bacmman.configuration.parameters.BoundedNumberParameter;
import bacmman.configuration.parameters.NumberParameter;
import bacmman.configuration.parameters.Parameter;
import bacmman.data_structure.Region;
import bacmman.data_structure.SegmentedObject;
import bacmman.data_structure.Voxel;
import bacmman.image.BlankMask;
import bacmman.image.BoundingBox;
import bacmman.image.Image;
import bacmman.image.ImageFloat;
import bacmman.image.ImageMask;
import bacmman.image.SimpleBoundingBox;
import bacmman.image.SimpleOffset;
import bacmman.image.TypeConverter;
import bacmman.plugins.Hint;
import bacmman.plugins.HintSimple;
import bacmman.plugins.ProcessingPipeline;
import bacmman.processing.Filters;
import bacmman.processing.Filters.Mean;
import bacmman.processing.ImageTransformation;
import bacmman.plugins.TrackPreFilter;
import bacmman.plugins.plugins.pre_filters.IJSubtractBackground;
import bacmman.plugins.plugins.pre_filters.IJSubtractBackground.FILTER_DIRECTION;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

/**
 *
 * @author Jean Ollion
 */
public class SubtractBackgroundMicrochannels implements TrackPreFilter, Hint, HintSimple {
    public static boolean debug = false;
    BooleanParameter isDarkBck = new BooleanParameter("Image Background", "Dark", "Light", false);
    BooleanParameter smooth = new BooleanParameter("Perform Smoothing", false);
    NumberParameter radius = new BoundedNumberParameter("Radius", 0, 1000, 1, null).setHint("Radius of the paraboloïd will be this value * sum Y size of microchannels.<br />Lower value -> less homogeneity/faster");
    Parameter[] parameters = new Parameter[]{radius, isDarkBck, smooth};

    @Override
    public ProcessingPipeline.PARENT_TRACK_MODE parentTrackMode() {
        return ProcessingPipeline.PARENT_TRACK_MODE.SINGLE_INTERVAL;
    }

    @Override
    public String getHintText() {
        return "Subtracts background on a whole microchannel track at once. "
                + "<br />Builds an image with all microchannel images pasted head to tail. "
                + "<br />To avoid any border effects, each microchannel image is mirrored in the x-axis, as well as  the whole image"
                + "<br />Allows homogeneous background subtraction on the whole track";
    }
    @Override
    public String getSimpleHintText() {
        return "Subtract-background algorithm adapted to microchannel track";
    }
    @Override
    public void filter(int structureIdx, TreeMap<SegmentedObject, Image> preFilteredImages, boolean canModifyImages) {
        //smooth.setSelected(true);
        // construct one single image 
        long t0 = System.currentTimeMillis();
        TrackMaskYWithMirroring tm = new TrackMaskYWithMirroring(new ArrayList<>(preFilteredImages.keySet()), true, false);
        ImageFloat allImagesY = (ImageFloat)tm.generateEmptyImage("sub mc", new ImageFloat("", 0, 0, 0));
        int idx = 0;
        for (SegmentedObject o : tm.parents) {
            Image im = preFilteredImages.get(o);
            if (!(im instanceof ImageFloat) || !canModifyImages) {
                im = TypeConverter.toFloat(im, null);
                preFilteredImages.replace(o, im);
            }
            //fillOutsideMask(o.getRegion(), im);
            tm.pasteMirror(im, allImagesY, idx++);
        }
        //if (debug) ImageWindowManagerFactory.showImage(allImagesY);
        int sizeY = allImagesY.sizeY();
        //double mirrorProportion = radius.getValue().doubleValue()<preFilteredImages.size()*0.75 ? 0.5 : 1;
        double mirrorProportion = 1;
        int offsetY = (int)(allImagesY.sizeY()*mirrorProportion);
        ImageFloat[] allImagesYStore = new ImageFloat[]{allImagesY};
        allImagesY = mirrorY(allImagesYStore[0], offsetY); // mirrorY image on both Y ends
        allImagesYStore[0] = allImagesY;
        // apply filter
        double radius = sizeY*(this.radius.getValue().doubleValue());
        //logger.debug("necessary memory: {}MB", allImagesY.getSizeXY()*32/8000000);
        //ThreadRunner.executeUntilFreeMemory(()-> {IJSubtractBackground.filter(allImagesYStore[0], radius, true, !isDarkBck.getSelected(), smooth.getSelected(), false, false);}, 10);
        long t1 = System.currentTimeMillis();
        IJSubtractBackground.filterCustomSlidingParaboloid(allImagesYStore[0], radius, !isDarkBck.getSelected(), smooth.getSelected(), false, true, FILTER_DIRECTION.X_DIRECTION, FILTER_DIRECTION.Y_DIRECTION, FILTER_DIRECTION.X_DIRECTION, FILTER_DIRECTION.Y_DIRECTION);
        long t2 = System.currentTimeMillis();
        
        allImagesY = allImagesY.crop(allImagesY.getBoundingBox().setyMin(offsetY).setyMax(offsetY+sizeY-1)); // crop
        // recover data
        idx = 0;
        for (SegmentedObject o : tm.parents) {
            Image.pasteImage(allImagesY, preFilteredImages.get(o), null, tm.getObjectOffset(idx++, 1));
            //fillOutsideMask(o.getRegion(), preFilteredImages.get(o));
        }
        long t3 = System.currentTimeMillis();
        logger.debug("subtrack backgroun microchannel done in {}ms, filtering: {}ms", t3-t0, t2-t1);
    }
    
    
    
    private static ImageFloat mirrorY(ImageFloat input, int size) { 
        ImageFloat res = new ImageFloat("", input.sizeX(), input.sizeY()+2*size, input.sizeZ());
        Image.pasteImage(input, res, new SimpleOffset(0, size, 0));
        Image imageFlip = ImageTransformation.flip(input, ImageTransformation.Axis.Y);
        Image.pasteImage(imageFlip, res, null, input.getBoundingBox().resetOffset().setyMin(input.sizeY()-size));
        Image.pasteImage(imageFlip, res, new SimpleOffset(0, size+input.sizeY(), 0), input.getBoundingBox().resetOffset().setyMax(size-1));
        return res;
    }
    private static void fillOutsideMask(Region o, Image input) {
        if (!(o.getMask() instanceof BlankMask)) {
            ImageMask mask = o.getMask();
            Set<Voxel> contour = o.getContour();
            Mean mean = new Filters.Mean(mask);
            mean.setUp(input, Filters.getNeighborhood(2.5, mask));
            BoundingBox.loop(mask, (x, y, z)-> {
                if (mask.insideMaskWithOffset(x, y, z)) return;
                Voxel closest = contour.stream().min((v1, v2)->Double.compare(v1.getDistanceSquare(x, y, z), v2.getDistanceSquare(x, y, z))).get();
                //input.setPixelWithOffset(x, y, z, input.getPixelWithOffset(closest.x, closest.y, closest.z));
                input.setPixelWithOffset(x, y, z, mean.applyFilter(closest.x-input.xMin(), closest.y-input.yMin(), closest.z-input.zMin()));
            });
            
        }
    }
    

    @Override
    public Parameter[] getParameters() {
        return parameters;
    }

    
    private static class TrackMaskYWithMirroring {
        int maxParentSizeX, maxParentSizeZ;
        SimpleBoundingBox[] trackOffset;
        List<SegmentedObject> parents;
        int mirrorY=1;
        int mirrorX = 1;
        private TrackMaskYWithMirroring(List<SegmentedObject> parentTrack, boolean mirrorY, boolean mirrorX) {
            boolean middleXZ = true;
            trackOffset = new SimpleBoundingBox[parentTrack.size()];
            if (mirrorY) this.mirrorY=3;
            if (mirrorX) this.mirrorX=3;
            this.parents=parentTrack;
            int maxX=0, maxZ=0;
            for (int i = 0; i<parentTrack.size(); ++i) { // compute global Y and Z max to center parent masks
                if (maxX<parentTrack.get(i).getRegion().getBounds().sizeX()) maxX=parentTrack.get(i).getRegion().getBounds().sizeX();
                if (maxZ<parentTrack.get(i).getRegion().getBounds().sizeZ()) maxZ=parentTrack.get(i).getRegion().getBounds().sizeZ();
            }
            maxParentSizeX=maxX;
            maxParentSizeZ=maxZ;
            int offX = mirrorX ? maxParentSizeX : 0;
            logger.trace("track mask image object: max parent X-size: {} z-size: {}", maxParentSizeX, maxParentSizeZ);
            int currentOffsetY=0;
            for (int i = 0; i<parentTrack.size(); ++i) {
                
                trackOffset[i] = new SimpleBoundingBox(parentTrack.get(i).getBounds()).resetOffset(); 
                if (middleXZ) trackOffset[i].translate(offX+(int)((maxParentSizeX-1)/2.0-(trackOffset[i].sizeX()-1)/2.0), currentOffsetY , (int)((maxParentSizeZ-1)/2.0-(trackOffset[i].sizeZ()-1)/2.0)); // Y & Z middle of parent track
                else trackOffset[i].translate(offX, currentOffsetY, 0); // X & Z up of parent track
                currentOffsetY+=trackOffset[i].sizeY()*this.mirrorY;
                logger.trace("current index: {}, current bounds: {} current offsetX: {}", i, trackOffset[i], currentOffsetY);
            }
        }
        public Image generateEmptyImage(String name, Image type) {
            return Image.createEmptyImage(name, type, new BlankMask( this.maxParentSizeX*mirrorX, trackOffset[trackOffset.length-1].yMin()+trackOffset[trackOffset.length-1].sizeY()*mirrorY, Math.max(type.sizeZ(), this.maxParentSizeZ)).setCalibration(parents.get(0).getScaleXY(), parents.get(0).getScaleZ()));
        }   
        public SimpleBoundingBox getObjectOffset(int idx, int positionY) {
            if (mirrorY==1) return trackOffset[idx];
            switch (positionY) {
                case 0:
                    return trackOffset[idx];
                case 1:
                    return trackOffset[idx].duplicate().translate(0, trackOffset[idx].sizeY(), 0);
                    
                case 2:
                    return trackOffset[idx].duplicate().translate(0, trackOffset[idx].sizeY()*2, 0);
                    
                default:
                    return null;
            }
        }
        public BoundingBox getObjectOffset(int idx, int positionY, int positionX) {
            SimpleBoundingBox bds = getObjectOffset(idx, positionY);
            if (mirrorX==1) return bds;
            switch (positionX) {
                case 0:
                    return bds.duplicate().translate(-bds.sizeX(), 0, 0);
                case 1:
                    return bds;
                case 2: 
                    return bds.duplicate().translate(bds.sizeX(), 0, 0);
                default: 
                    return null;
            }
        }
        
        public void pasteMirror(Image source, Image dest, int idx) { // will modify image
            BoundingBox bds1_0 = getObjectOffset(idx, 1);
            Image.pasteImage(source, dest, bds1_0); // center
            if (mirrorX==3) {
                ImageTransformation.flip(source, ImageTransformation.Axis.X);
                BoundingBox bds = getObjectOffset(idx, 1, 0);
                Image.pasteImage(source, dest, bds);
                correctSides(dest, bds, true, false); 
                bds = getObjectOffset(idx, 1, 2);
                Image.pasteImage(source, dest, bds);
                correctSides(dest, bds, false, true); 
                ImageTransformation.flip(source, ImageTransformation.Axis.X);
            } else correctSides(dest, bds1_0, true, true);
            if (mirrorY==1) return;
            ImageTransformation.flip(source, ImageTransformation.Axis.Y);
            BoundingBox bds0_0 = getObjectOffset(idx, 0);
            Image.pasteImage(source, dest, bds0_0);
            if (mirrorX==1) correctSides(dest, bds0_0, true, true);
            BoundingBox bds2_0 = getObjectOffset(idx, 2);
            Image.pasteImage(source, dest, bds2_0);
            if (mirrorX==1)  correctSides(dest, bds2_0, true , true);
            if (mirrorX==3) {
                ImageTransformation.flip(source, ImageTransformation.Axis.X);
                for (int posX = 0; posX<=2; posX+=2) {
                    for (int posY = 0; posY<=2; posY+=2) {
                        BoundingBox bds = getObjectOffset(idx, posY, posX);
                        Image.pasteImage(source, dest, bds);
                        correctSides(dest, bds, posX==0, posX==2); 
                    }
                }
            }
            
        }
        private static void correctSides(Image dest, BoundingBox bds, boolean left, boolean right) { // when some mask don't have the same width, zeros can remain on the sides -> fill them with the nearest value
            if ((left && bds.xMin()>dest.xMin()) || (right && bds.xMax()<dest.xMax())) {
                for (int y = bds.yMin(); y<=bds.yMax(); ++y) {
                    if (left) for (int x=dest.xMin(); x<bds.xMin(); ++x) dest.setPixel(x, y, bds.zMin(), dest.getPixel(bds.xMin(), y, bds.zMin()));
                    if (right) for (int x=bds.xMax()+1; x<=dest.xMax(); ++x) dest.setPixel(x, y, bds.zMin(), dest.getPixel(bds.xMax(), y, bds.zMin()));
                }
            }
        }
    }
}
