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

import bacmman.core.Core;
import bacmman.data_structure.SegmentedObject;
import bacmman.plugins.HintSimple;
import bacmman.plugins.TestableOperation;
import bacmman.plugins.plugins.thresholders.BackgroundThresholder;
import bacmman.configuration.parameters.BoundedNumberParameter;
import bacmman.configuration.parameters.NumberParameter;
import bacmman.configuration.parameters.Parameter;
import bacmman.configuration.parameters.PluginParameter;
import bacmman.data_structure.input_image.InputImages;
import bacmman.image.Histogram;
import bacmman.image.HistogramFactory;
import bacmman.image.MutableBoundingBox;
import bacmman.image.Image;
import bacmman.image.ImageInteger;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import bacmman.plugins.plugins.segmenters.MicrochannelFluo2D;
import bacmman.plugins.MicrochannelSegmenter.Result;
import bacmman.plugins.ThresholderHisto;
import bacmman.plugins.Hint;
import static bacmman.plugins.plugins.segmenters.MicrochannelFluo2D.FILL_TOOL_TIP;
import static bacmman.plugins.plugins.segmenters.MicrochannelFluo2D.SIZE_TOOL_TIP;
import static bacmman.plugins.plugins.segmenters.MicrochannelFluo2D.THLD_TOOL_TIP;
import static bacmman.plugins.plugins.segmenters.MicrochannelFluo2D.TOOL_TIP;

import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 *
 * @author Jean Ollion
 */
public class CropMicrochannelsFluo2D extends CropMicroChannels implements Hint, HintSimple, TestableOperation {
    protected NumberParameter channelLength = new BoundedNumberParameter("Channel Length", 0, 410, 0, null).setEmphasized(true).setHint("Length of microchannels, in pixels. This length will determine the y-size of the cropped image");
    NumberParameter minObjectSize = new BoundedNumberParameter("Object Size Filter", 0, 200, 1, null).setEmphasized(true).setHint(SIZE_TOOL_TIP);
    NumberParameter fillingProportion = new BoundedNumberParameter("Filling proportion of Microchannel", 2, 0.4, 0.05, 1).setEmphasized(true).setHint(FILL_TOOL_TIP);
    PluginParameter<ThresholderHisto> thresholder = new PluginParameter<>("Threshold", ThresholderHisto.class, new BackgroundThresholder(3, 6, 3), false).setEmphasized(true).setHint(THLD_TOOL_TIP);
    
    Parameter[] parameters = new Parameter[]{channelLength, cropMarginY, minObjectSize, thresholder, fillingProportion, boundGroup};
    double threshold = Double.NaN;
    public CropMicrochannelsFluo2D(int channelHeight, int cropMargin, int minObjectSize, double fillingProportion, int FrameNumber) {
        this.channelLength.setValue(channelHeight);
        this.cropMarginY.setValue(cropMargin);
        this.minObjectSize.setValue(minObjectSize);
        this.fillingProportion.setValue(fillingProportion);
        this.referencePoint.setSelectedIndex(0);
        this.frameNumber.setValue(0);
        //frameNumber.setValue(FrameNumber);
    }
    private static String simpleHint = "<b>Automatically crops the image around the microchannels in fluorescence images</b><br />" +
            "The microchannels should be aligned along the Y-axis, with their closed-end up (for instance use <em>AutorotationXY</em> and <em>AutoFlip</em> modules)" +
            "<br />The <em>Filling proportion of Microchannel</em> & <em>Channel Length</em> parameters should be tuned for each new setup. If an error telling that no bounds are found is thrown during pre-processing, their value should be increased<br />";
    private static String testModeDisp = "<br />Displayed image and graphs in test mode:<ul><li><em>Thresholded Bacteria:</em> Result of bacteria thresholding (using the threshold method defined in the <em>Threshold</em> parameter), after filtering of small objects (see the <em>Object Size Filter</em> parameter).</li>"
            + "<li><em>Microchannel Fill proportion:</em>Plot representing the proportion of filled length of detected microchannels. See module description and help for the <em>Filling proportion of Microchannel</em> parameter</li></ul>";

    @Override
    public String getHintText() {
        return  simpleHint + "<br />The microchannels are detected as follows:<br />"+TOOL_TIP + testModeDisp;

    }
    @Override
    public String getSimpleHintText() {
        return simpleHint + testModeDisp;
    }
    
    public CropMicrochannelsFluo2D() {
        //this.margin.setValue(30);
    }
    public CropMicrochannelsFluo2D setThresholder(ThresholderHisto instance) {
        this.thresholder.setPlugin(instance);
        return this;
    }
    public CropMicrochannelsFluo2D setChannelDim(int channelHeight, double fillingProportion) {
        this.channelLength.setValue(channelHeight);
        this.fillingProportion.setValue(fillingProportion);
        return this;
    }
    public CropMicrochannelsFluo2D setParameters(int minObjectSize) {
        this.minObjectSize.setValue(minObjectSize);
        return this;
    }
    @Override
    public void computeConfigurationData(int channelIdx, InputImages inputImages) throws IOException {
        // compute one threshold for all images
        List<Image> allImages = Arrays.asList(InputImages.getImageForChannel(inputImages, channelIdx, false));
        ThresholderHisto thlder = thresholder.instantiatePlugin();
        Histogram histo = HistogramFactory.getHistogram(()->Image.stream(allImages).filter(v->v!=0).parallel(), HistogramFactory.BIN_SIZE_METHOD.BACKGROUND); // v!=0: in case rotation was performed : null rows/colums can interfere with threshold computation
        threshold = thlder.runThresholderHisto(histo);
        super.computeConfigurationData(channelIdx, inputImages);
    }
            
    @Override
    public MutableBoundingBox getBoundingBox(Image image) {
        double thld = Double.isNaN(threshold)? 
                thresholder.instantiatePlugin().runThresholderHisto(HistogramFactory.getHistogram(()->image.stream().filter(v->v!=0), HistogramFactory.BIN_SIZE_METHOD.BACKGROUND)) // v!=0: in case rotation was performed : null rows/colums can interfere with threshold computation
                : threshold;
        return getBoundingBox(image, null , thld);
    }
    
    public MutableBoundingBox getBoundingBox(Image image, ImageInteger thresholdedImage, double threshold) {
        if (debug) testMode = TEST_MODE.TEST_EXPERT;
        Consumer<Image> dispImage = testMode.testSimple() ? i-> Core.showImage(i) : null;
        BiConsumer<String, Consumer<List<SegmentedObject>>> miscDisp = testMode.testSimple() ? (s, c)->c.accept(Collections.EMPTY_LIST) : null;
        Result r = MicrochannelFluo2D.segmentMicroChannels(image, thresholdedImage, 0, 20, this.channelLength.getValue().intValue(), this.fillingProportion.getValue().doubleValue(), threshold, this.minObjectSize.getValue().intValue(), MicrochannelFluo2D.METHOD.PEAK, dispImage, miscDisp);
        if (r == null) return null;
        
        int xStart = this.xStart.getValue().intValue();
        int xStop = this.xStop.getValue().intValue();
        int yStart = this.yStart.getValue().intValue();
        int yStop = this.yStop.getValue().intValue();
        int yMin = Math.max(yStart, r.yMin);
        if (yStop==0) yStop = image.sizeY()-1;
        if (xStop==0) xStop = image.sizeX()-1;
        int cropMargin = this.cropMarginY.getValue().intValue();
        yStart = Math.max(yMin-cropMargin, yStart);
        yStop = Math.min(yStop, yMin+channelLength.getValue().intValue()-1);
        
        //xStart = Math.max(xStart, r.getXMin()-cropMargin);
        //xStop = Math.min(xStop, r.getXMax() + cropMargin);
        MutableBoundingBox bounds = new MutableBoundingBox(xStart, xStop, yStart, yStop, 0, image.sizeZ()-1);
        
        // in case a rotation was performed null rows / columns were added: look for min x & max x @ y min & y max
        // 1) limit x min and max @ middle y
        int[] xMMMid= getXMinAndMax(image, (int)bounds.yMean());
        int[] xMMMid2= getXMinAndMax(image, 1+(int)bounds.yMean()); // when fluo bck is close to 0 : more robust using 2 lines
        xMMMid[0] = (int)((xMMMid[0]+xMMMid2[0]+1)/2d);
        xMMMid[1] = (int)((xMMMid[1]+xMMMid2[1])/2d);
        if (bounds.xMin()<xMMMid[0]) bounds.setxMin(xMMMid[0]);
        if (bounds.xMax()>xMMMid[1]) bounds.setxMax(xMMMid[1]);
        
        // 2) limit Y to non-null values
        int[] yMMLeft= getYMinAndMax(image, bounds.xMin());
        int[] yMMLeft2= getYMinAndMax(image, bounds.xMin()+1);
        yMMLeft[0] = (int)((yMMLeft[0]+yMMLeft2[0]+1)/2d);
        yMMLeft[1] = (int)((yMMLeft[1]+yMMLeft2[1])/2d);
        int[] yMMRight= getYMinAndMax(image, bounds.xMax());
        int[] yMMRight2= getYMinAndMax(image, bounds.xMax()-1);
        yMMRight[0] = (int)((yMMRight[0]+yMMRight2[0]+1)/2d);
        yMMRight[1] = (int)((yMMRight[1]+yMMRight2[1])/2d);
        
        int yMinLim = Math.min(yMMLeft[0], yMMRight[0]);
        int yMaxLim = Math.max(yMMLeft[1], yMMRight[1]);
        if (bounds.yMin()<yMinLim) bounds.setyMin(yMinLim);
        if (bounds.yMax()>yMaxLim) bounds.setyMax(yMaxLim);
        
        //3) limit X to non-null values
        int[] xMMUp= getXMinAndMax(image, bounds.yMin());
        int[] xMMUp2= getXMinAndMax(image, bounds.yMin()+1); // fluo values can be close to 0 : better detection using 2 lines
        xMMUp[0] = (int)((xMMUp[0]+xMMUp2[0]+1)/2d);
        xMMUp[1] = (int)((xMMUp[1]+xMMUp2[1])/2d);
        int[] xMMDown= getXMinAndMax(image, bounds.yMax());
        int[] xMMDown2= getXMinAndMax(image, bounds.yMax()-1);  // fluo values can be close to 0 : better detection using 2 lines
        xMMDown[0] = (int)((xMMDown[0]+xMMDown2[0]+1)/2d);
        xMMDown[1] = (int)((xMMDown[1]+xMMDown2[1])/2d);
        
        int xMinLim = Math.max(xMMUp[0], xMMDown[0]);
        int xMaxLim = Math.min(xMMUp[1], xMMDown[1]);
        if (bounds.xMin()<xMinLim) bounds.setxMin(xMinLim);
        if (bounds.xMax()>xMaxLim) bounds.setxMax(xMaxLim);
        
        //4) limit yStart to upper mother even if it will include rotation background in the image
        if (bounds.yMin()>yMin) bounds.setyMin(Math.max(0, yMin- 20));
        
        return bounds;
        
    }
    @Override
    protected void uniformizeBoundingBoxes(Map<Integer, MutableBoundingBox> allBounds, InputImages inputImages, int channelIdx)  throws IOException {
        // reference point = top -> all y start are conserved
        int imageSizeY = inputImages.getImage(channelIdx, inputImages.getDefaultTimePoint()).sizeY();
        int maxSizeY = allBounds.values().stream().mapToInt(b->b.sizeY()).max().getAsInt();
        // TODO : parameter to allow out of bound in order to preserve size ? 
        int sY = allBounds.values().stream().filter(b->b.sizeY()!=maxSizeY).mapToInt(b-> { // get maximal sizeY so that all channels fit within range
            int yMin = b.yMin();
            int yMax = yMin + maxSizeY-1;
            if (yMax>=imageSizeY) {
                yMax = imageSizeY-1;
                yMin = Math.max(0, yMax - maxSizeY+1);
            }
            return yMax - yMin +1;
        }).min().orElse(-1);
        if (sY>0) allBounds.values().stream().filter(b->b.sizeY()!=sY).forEach(b-> b.setyMax(b.yMin() + sY -1)); // keep yMin and set yMax
        //int sizeY = (int)Math.round(ArrayUtil.quantile(allBounds.values().stream().mapToDouble(b->b.sizeY()).sorted(), allBounds.size(), 0.5));
        uniformizeX(allBounds);
    }

    @Override public Parameter[] getParameters() {
        return parameters;
    }
    @Override
    public boolean highMemory() {return false;}
}
