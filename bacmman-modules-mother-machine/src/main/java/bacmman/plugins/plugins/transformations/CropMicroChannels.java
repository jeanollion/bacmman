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
package bacmman.plugins.plugins.transformations;

import bacmman.configuration.parameters.BoundedNumberParameter;
import bacmman.configuration.parameters.ChoiceParameter;
import bacmman.configuration.parameters.GroupParameter;
import bacmman.configuration.parameters.NumberParameter;
import bacmman.data_structure.input_image.InputImages;
import bacmman.image.*;
import bacmman.plugins.ConfigurableTransformation;

import java.io.IOException;
import java.util.*;

import bacmman.plugins.MultichannelTransformation;
import bacmman.plugins.TestableOperation;
import bacmman.utils.ThreadRunner;
import bacmman.utils.Utils;

import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author Jean Ollion
 */
public abstract class CropMicroChannels implements ConfigurableTransformation, MultichannelTransformation, TestableOperation {
    public static boolean debug = false;
    private final static Logger logger = LoggerFactory.getLogger(CropMicroChannels.class);
    protected NumberParameter xStart = new BoundedNumberParameter("X start", 0, 0, 0, null);
    protected NumberParameter xStop = new BoundedNumberParameter("X stop (0 for image width)", 0, 0, 0, null);
    protected NumberParameter yStart = new BoundedNumberParameter("Y start", 0, 0, 0, null);
    protected NumberParameter yStop = new BoundedNumberParameter("Y stop (0 for image heigth)", 0, 0, 0, null);
    protected GroupParameter boundGroup = new GroupParameter("Bound constraint", xStart, xStop, yStart, yStop).setHint("Parameters to crop the image according to constant bounds. <br />If needed, a constant crop should be set here rather than in a separate module at a previous step, because of possible XY-drift.");
    protected BoundedNumberParameter cropMarginY = new BoundedNumberParameter("Crop Margin", 0, 45, 0, null).setHint("The y-coordinate of the microchannels closed-end used to crop the image is defined as: <em>Y start</em> - <em>Crop margin</em> (for definition of <em>Y start</em> see help of the module)<br />A positive value will results in larger microchannels.");
    protected NumberParameter processingWindow = new BoundedNumberParameter("Processing Window", 0, 100, 10, null).setHint("Number of frames processed at a time. Reduce in case of out-of-memory error");
    
    ChoiceParameter referencePoint = new ChoiceParameter("Reference point", new String[]{"Top", "Bottom"}, "Top", false);
    Map<Integer, ? extends BoundingBox> cropBounds;
    BoundingBox bounds;
    boolean ref2D;
    public CropMicroChannels setReferencePoint(boolean top) {
        this.referencePoint.setSelectedIndex(top ? 0 : 1);
        return this;
    }
    @Override
    public void computeConfigurationData(int channelIdx, InputImages inputImages) throws IOException  {
        cropBounds=null;
        bounds = null;
        if (channelIdx<0) throw new IllegalArgumentException("Channel no configured");
        Image<? extends Image> image = inputImages.getImage(channelIdx, inputImages.getDefaultTimePoint());
        // check configuration validity
        if (xStop.getValue().intValue()==0 || xStop.getValue().intValue()>=image.sizeX()) xStop.setValue(image.sizeX()-1);
        if (xStart.getValue().intValue()>=xStop.getValue().intValue()) {
            logger.warn("CropMicroChannels2D: illegal configuration: xStart>=xStop, set to default values");
            xStart.setValue(0);
            xStop.setValue(image.sizeX()-1);
        }
        if (yStop.getValue().intValue()==0 || yStop.getValue().intValue()>=image.sizeY()) yStop.setValue(image.sizeY()-1);
        if (yStart.getValue().intValue()>=yStop.getValue().intValue()) {
            logger.warn("CropMicroChannels2D: illegal configuration: yStart>=yStop, set to default values");
            yStart.setValue(0);
            yStop.setValue(image.sizeY()-1);
        }
        ref2D = image.sizeZ()==1;
        image = null;
        int framesN = 0;
        List<Integer> frames; 
        switch(framesN) {
            case 0: // all frames
                frames = IntStream.range(0, inputImages.getFrameNumber()).mapToObj(i->(Integer)i).collect(Collectors.toList());
                break;
            case 1:
                frames = new ArrayList<Integer>(){{add(inputImages.getDefaultTimePoint());}};
                break;
            default :
                frames =  InputImages.chooseNImagesWithSignal(inputImages, channelIdx, framesN);
        }
        /*List<Image> images = ThreadRunner.parallelExecutionBySegmentsFunction(f-> {
            try {
                return inputImages.getImage(channelIdx, f);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }, frames, 100);*/
        IOException[] ex = new IOException[1];
        Function<Integer, MutableBoundingBox> getBds = f -> {
            Image<? extends Image> im = InputImages.getImage(inputImages,channelIdx, f, ex);
            if (im.sizeZ()>1) {
                int plane = inputImages.getBestFocusPlane(f);
                if (plane<0) throw new RuntimeException("CropMicrochannel can only be run on 2D images AND no autofocus algorithm was set");
                im = im.splitZPlanes().get(plane);
            }
            return getBoundingBox(im);
        };
        TEST_MODE test = testMode;
        logger.debug("test mode: {}", test);
        if (framesN!=1 && test.testSimple()) { // only test for one frame
            int idx = frames.stream().min( Comparator.comparing(f -> Math.abs(f-inputImages.getDefaultTimePoint()))).get();
            logger.debug("testing for 1 frame, closest frame to default timePoint: {}", idx);
            getBds.apply(frames.indexOf(idx));
        }
        if (framesN!=1) this.setTestMode(TEST_MODE.NO_TEST);
        logger.debug("computing bounding box on {} frames", frames.size());
        List<MutableBoundingBox> bds = ThreadRunner.parallelExecutionBySegmentsFunction(getBds, frames, processingWindow.getIntValue(), true);
        if (ex[0]!=null) throw ex[0];
        Map<Integer, MutableBoundingBox> bounds = Utils.toMapWithNullValues(frames.stream(), i->i, bds::get, true); // not using Collectors.toMap because result of getBounds can be null

        List<Integer> nullBounds = bounds.entrySet().stream().filter(e->e.getValue()==null).map(Map.Entry::getKey).collect(Collectors.toList());
        if (!nullBounds.isEmpty()) logger.debug("bounds could not be computed for frames: {}", nullBounds);
        bounds.values().removeIf(Objects::isNull);
        if (bounds.isEmpty()) throw new RuntimeException("Bounds could not be computed");
        logger.debug("filling gaps... (memory usage: {})", Utils.getMemoryUsage());
        if (framesN==0 && bounds.size()<frames.size()) { // fill null bounds
            Set<Integer> missingFrames = new HashSet<>(frames);
            missingFrames.removeAll(bounds.keySet());
            missingFrames.forEach((f) -> {
                int infF = bounds.keySet().stream().filter(fr->fr<f).mapToInt(fr->fr).max().orElse(-1);
                int supF = bounds.keySet().stream().filter(fr->fr>f).mapToInt(fr->fr).min().orElse(-1);
                if (infF>=0 && supF>=0) { // mean bounding box between the two
                    MutableBoundingBox b1 = bounds.get(infF);
                    MutableBoundingBox b2 = bounds.get(supF);
                    MutableBoundingBox res = new MutableBoundingBox((b1.xMin()+b2.xMin())/2, (b1.xMax()+b2.xMax())/2, (b1.yMin()+b2.yMin())/2, (b1.yMax()+b2.yMax())/2, (b1.zMin()+b2.zMin())/2, (b1.zMax()+b2.zMax())/2);
                    bounds.put(f, res);
                } else if (infF>=0)  bounds.put(f, bounds.get(infF).duplicate());
                else bounds.put(f, bounds.get(supF).duplicate());
            });
        }
        logger.debug("uniformize bounding boxes... (memory usage: {})", Utils.getMemoryUsage());
        uniformizeBoundingBoxes(bounds, inputImages, channelIdx);
        if (framesN==0)  cropBounds = bounds;
        else this.bounds = bounds.values().stream().findAny().get();
        /*if (framesN !=0) { // one bounding box for all images: merge all bounds by expanding
            Iterator<MutableBoundingBox> it = bounds.values().iterator();
            MutableBoundingBox bds = it.next();
            while (it.hasNext()) bds.union(it.next());
            this.bounds = bds;
        }*/

        if (framesN!=1) this.setTestMode(test); // restore initial value
    }
    protected abstract void uniformizeBoundingBoxes(Map<Integer, MutableBoundingBox> allBounds, InputImages inputImages, int channelIdx)  throws IOException;
    
    protected void uniformizeX(Map<Integer, MutableBoundingBox> allBounds) {
        int sizeX = allBounds.values().stream().mapToInt(SimpleBoundingBox::sizeX).min().getAsInt();
        allBounds.values().stream().filter(bb->bb.sizeX()!=sizeX).forEach(bb-> {
            int diff = bb.sizeX() - sizeX;
            int addLeft=diff/2;
            int remRight = diff - addLeft;
            bb.setxMin(bb.xMin()+addLeft);
            bb.setxMax(bb.xMax()-remRight);
        });
    }
    
    /**
     * 
     * @param image
     * @param y
     * @return array containing xMin, xMax such that {@param image} has non null values @ y={@param y} in range [xMin, xMax] and that is range is maxmimal
     */
    protected static int[] getXMinAndMax(Image image, int y) {
        int start = 0;
        while (start<image.sizeX() && image.getPixel(start, y, 0)==0) ++start;
        int end = image.sizeX()-1;
        while (end>=0 && image.getPixel(end, y, 0)==0) --end;
        return new int[]{start, end};
    }
    /**
     *  
     
     * @param image
     * @param x
     * @return array containing yMin and yMax such that the whole {@param x} line strictly before yMin and aftery yMax of {@param image} have null values
     */
    protected static int[] getYMinAndMax(Image image, int x) {
        int start = 0;
        while (start<image.sizeY() && image.getPixel(x, start, 0)==0) ++start;
        int end = image.sizeY()-1;
        while (end>=0 && image.getPixel(x, end, 0)==0) --end;
        return new int[]{start, end};
    }
    protected static int getYmin(Image image, int xL, int xR) {
        int startL = 0;
        while (startL<image.sizeY() && image.getPixel(xL, startL, 0)==0) ++startL;
        int startR = 0;
        while (startR<image.sizeY() && image.getPixel(xR, startR, 0)==0) ++startR;
        return Math.max(startR, startL);
    }
    
    @Override
    public boolean isConfigured(int totalChannelNumner, int totalTimePointNumber) {
        return bounds!=null || cropBounds!=null && this.cropBounds.size()==totalTimePointNumber;
    }
    
    @Override
    public OUTPUT_SELECTION_MODE getOutputChannelSelectionMode() {
        return OUTPUT_SELECTION_MODE.ALL;
    }
    protected abstract MutableBoundingBox getBoundingBox(Image image);
    @Override
    public Image applyTransformation(int channelIdx, int timePoint, Image image) {
        BoundingBox bds = bounds!=null ? bounds : cropBounds.get(timePoint);
        bds = new MutableBoundingBox(bds).setzMin(0).setzMax(image.sizeZ()-1);
        return image.crop(bds);
    }
    TestableOperation.TEST_MODE testMode= TestableOperation.TEST_MODE.NO_TEST;
    @Override
    public void setTestMode(TestableOperation.TEST_MODE testMode) {this.testMode=testMode;}
}
