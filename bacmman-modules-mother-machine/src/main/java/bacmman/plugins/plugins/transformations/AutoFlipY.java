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

import bacmman.configuration.parameters.*;
import bacmman.core.Core;
import bacmman.image.*;
import bacmman.plugins.*;
import bacmman.plugins.plugins.thresholders.BackgroundThresholder;
import bacmman.configuration.parameters.ConditionalParameter;
import bacmman.data_structure.input_image.InputImages;
import bacmman.data_structure.Region;
import bacmman.data_structure.RegionPopulation;

import static bacmman.image.BoundingBox.intersect2D;

import bacmman.processing.ImageFeatures;
import bacmman.processing.ImageLabeller;
import bacmman.processing.ImageOperations;

import java.io.IOException;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static bacmman.plugins.plugins.transformations.AutoFlipY.AutoFlipMethod.OPTICAL_FLOW;

import bacmman.processing.ImageTransformation;
import bacmman.utils.*;

/**
 *
 * @author Jean Ollion
 */
public class AutoFlipY implements ConfigurableTransformation, MultichannelTransformation, Hint, TestableOperation {
    
    public enum AutoFlipMethod {
        OPTICAL_FLOW("Optical Flow", "Flips the image so that bacteria moves towards the lower part of the image.<br />Based on a simple estimation of bacteria motion along Y-axis"),
        FLUO("Bacteria Fluo", "Bacteria poles touching the closed-end of microchannels are often aligned in parallel to the main channel. This method estimates the alignment of bacteria at the top and the bottom of the image. If bacteria are more aligned at the bottom, the image is flipped."),
        FLUO_HALF_IMAGE("Bacteria Fluo: Upper Half of Image", "Flips the image if the fluorescence signal is higher  in the bottom half of the image."),
        PHASE("Phase Contrast Optical Aberration", "The optical aberration (i.e. the bright line corresponding to the sides of the main channel in phase contrast images) is detected. Then, the image is divided into two parts corresponding to the regions above and below the bright line, each part is projected onto the x-axis and the variance of the intensity of the projected pixels is computed for each part. The part with the maximal variance corresponds to the region of the image where the microchannels are. The image is therefore eventually flipped so that the part of the image containing the microchannels is above the bright line. <br />In case the y-coordinate of the bright line  is smaller than the value of the <em>Microchannel Length</em> parameter (i.e. the bright line is very close to the upper side of the image), the image is always flipped");

        final String name;
        final String toolTip;
        AutoFlipMethod(String name, String toolTip) {
            this.name=name;
            this.toolTip=toolTip;
        }
        public static AutoFlipMethod getMethod(String name) {
            for (AutoFlipMethod m : AutoFlipMethod.values()) if (m.name.equals(name)) return m;
            return null;
        }
    }
    String toolTip = "Flips the image along the Y-axis if the closed-end of the microchannels is below the open-end. Microchannels must first be aligned along the Y-axis (use for instance <em>AutorotationXY</em> module)";
    ChoiceParameter method = new ChoiceParameter("Method", Utils.transform(AutoFlipMethod.values(), new String[AutoFlipMethod.values().length], f->f.name), OPTICAL_FLOW.name, false).setEmphasized(true);
    PluginParameter<SimpleThresholder> fluoThld = new PluginParameter<>("Threshold for bacteria Segmentation", SimpleThresholder.class, new BackgroundThresholder(3, 6, 3), false);
    NumberParameter minObjectSize = new BoundedNumberParameter("Minimal Object Size", 1, 100, 10, null).setHint("Object under this size (in pixels) will be removed");
    NumberParameter microchannelLength = new BoundedNumberParameter("Microchannel Length", 0, 400, 100, null).setEmphasized(true).setHint("Minimal Length of Microchannels");
    NumberParameter binFactor = new BoundedNumberParameter("Bin factor", 0, 2, 1, null).setEmphasized(false).setHint("Spatial binning factor");
    NumberParameter thresholdFactor_dt = new BoundedNumberParameter("dI/dt threshold factor", 3, 0.5, 0.01, null).setEmphasized(false).setHint("Threshold factor to filter dI/dt image");
    NumberParameter thresholdFactor_dy = new BoundedNumberParameter("dI/dy threshold factor", 3, 0.5, 0.01, null).setEmphasized(false).setHint("Threshold factor to filter dI/dy image");
    NumberParameter stabSegment = new BoundedNumberParameter("Frame segment", 0, 20, 5, null).setEmphasized(false).setHint("Frame segment for XY stabilization (see ImageStabilizerXY plugin)");
    NumberParameter frameLimit = new BoundedNumberParameter("Frame Limit", 0, 200, 5, null).setEmphasized(false).setHint("Maximum number of frames to consider");

    BooleanParameter stabilize = new BooleanParameter("Stabilize", true);
    ConditionalParameter<Boolean> stabCond = new ConditionalParameter<>(stabilize).setActionParameters(Boolean.TRUE, stabSegment);
    ConditionalParameter<String> cond = new ConditionalParameter<>(method)
            .setActionParameters(OPTICAL_FLOW.name, frameLimit, binFactor, thresholdFactor_dt, thresholdFactor_dy, stabCond)
            .setActionParameters(AutoFlipMethod.FLUO.name, fluoThld, minObjectSize)
            .setActionParameters(AutoFlipMethod.PHASE.name, microchannelLength);
    Boolean flip = null;
    public AutoFlipY() {
        cond.addListener(p->{ 
            AutoFlipMethod m = AutoFlipMethod.getMethod(method.getSelectedItem());
            if (m!=null) cond.setHint(m.toolTip);
            else cond.setHint("Choose autoFlip algorithm");
        });
    }
    @Override
    public String getHintText() {
        return toolTip;
    }

    @Override
    public boolean highMemory() {return false;}

    public AutoFlipY setMethod(AutoFlipMethod method) {
        this.method.setValue(method.name);
        return this;
    }
    List<Image> upperObjectsTest, lowerObjectsTest;
    Buffers buffers;
    @Override
    public void computeConfigurationData(int channelIdx, InputImages inputImages) throws IOException {
        flip=null;
        AutoFlipMethod m = AutoFlipMethod.getMethod(method.getSelectedItem());
        IOException[] ioe = new IOException[1];
        switch(m) {
            case FLUO: {
                if (testMode.testExpert()) {
                    upperObjectsTest=new ArrayList<>();
                    lowerObjectsTest=new ArrayList<>();
                }
                // rough segmentation and get side where cells are better aligned
                List<Integer> frames = InputImages.chooseNImagesWithSignal(inputImages, channelIdx, 200);

                Function<Integer, Boolean> ex = f->{
                    Image<? extends Image> image = InputImages.getImage(inputImages,channelIdx, f, ioe);
                    if (image==null) return false;
                    if (image.sizeZ()>1) {
                        int plane = inputImages.getBestFocusPlane(f);
                        if (plane<0) throw new RuntimeException("AutoFlip can only be run on 2D images AND no autofocus algorithm was set");
                        image = image.splitZPlanes().get(plane);
                    }
                    return isFlipFluo(image);
                };
                buffers = new Buffers(inputImages.getImage(channelIdx, frames.get(0)).getZPlane(0));
                List<Boolean> flips = ThreadRunner.parallelExecutionBySegmentsFunction(ex, frames, Core.PRE_PROCESSING_WINDOW, true);
                buffers.imageIntPool.flush();
                buffers = null;
                if (ioe[0]!=null) throw ioe[0];
                long countFlip = flips.stream().filter(b->b!=null && b).count();
                long countNoFlip = flips.stream().filter(b->b!=null && !b).count();
                if (testMode.testExpert()) {
                    if (!upperObjectsTest.isEmpty()) Core.showImage(Image.mergeZPlanes(upperObjectsTest).setName("Upper Objects"));
                    if (!lowerObjectsTest.isEmpty()) Core.showImage(Image.mergeZPlanes(lowerObjectsTest).setName("Lower Objects"));
                    upperObjectsTest.clear();
                    lowerObjectsTest.clear();
                }
                flip = countFlip>countNoFlip;
                logger.info("AutoFlipY: {} (flip:{} vs:{})", flip, countFlip, countNoFlip);
                break;
            }
            case FLUO_HALF_IMAGE: {
                // compares signal in upper half & lower half -> signal should be in upper half
                List<Integer> frames = InputImages.chooseNImagesWithSignal(inputImages, channelIdx, 20);
                Function<Integer, Boolean> ex = f->{
                    Image<? extends Image> image = InputImages.getImage(inputImages,channelIdx, f, ioe);
                    if (image==null) return false;
                    if (image.sizeZ()>1) {
                        int plane = inputImages.getBestFocusPlane(f);
                        if (plane<0) throw new RuntimeException("AutoFlip can only be run on 2D images AND no autofocus algorithm was set");
                        image = image.splitZPlanes().get(plane);
                    }
                    return isFlipFluoUpperHalf(image);
                };
                List<Boolean> flips = ThreadRunner.parallelExecutionBySegmentsFunction(ex, frames, Core.PRE_PROCESSING_WINDOW, true);
                if (ioe[0]!=null) throw ioe[0];
                long countFlip = flips.stream().filter(b->b!=null && b).count();
                long countNoFlip = flips.stream().filter(b->b!=null && !b).count();

                flip = countFlip>countNoFlip;
                logger.info("AutoFlipY: {} (flip:{} vs:{})", flip, countFlip, countNoFlip);
                break;
            } case PHASE: {
                int length = microchannelLength.getValue().intValue();
                Image image = inputImages.getImage(channelIdx, inputImages.getDefaultTimePoint());
                float[] yProj = ImageOperations.meanProjection(image, ImageOperations.Axis.Y, null, v->v>0); // if rotation before -> top & bottom of image can contain zeros -> mean proj then return NaN
                int startY = getFirstNonNanIdx(yProj, true);
                int stopY = getFirstNonNanIdx(yProj, false);
                if (startY>=stopY)  throw new RuntimeException("AutoFlip error: no values>0");
                int peakIdx = ArrayUtil.max(yProj, startY, stopY+1);           
                double median = ArrayUtil.median(Arrays.copyOfRange(yProj, startY, stopY+1-startY));
                double peakHeight = yProj[peakIdx] - median;
                float thld = (float)(peakHeight * 0.25 + median  ); //
                int startOfPeakIdx = ArrayUtil.getFirstOccurence(yProj, peakIdx, startY, v->v<thld)-length/6; // is there enough space above the aberration ?
                if (startOfPeakIdx-startY<length*0.75) {
                    flip = true;
                    return;
                }
                int endOfPeakIdx = ArrayUtil.getFirstOccurence(yProj, peakIdx, stopY+1, v->v<thld)+length/6; // is there enough space under the aberration ?
                if (stopY+1 - endOfPeakIdx<=length*0.75) {
                    flip = false;
                    return;
                }
                //logger.debug("would flip: {} values: [{};{}], peak: [{}-{}-{}] height: {} [{}-{}]", flip, start, end, startOfPeakIdx, peakIdx, endOfPeakIdx,yProj[peakIdx]-median, yProj[peakIdx], median );

                // compare upper and lower side X-variances withing frame of microchannel length
                float[] xProjUpper = ImageOperations.meanProjection(image, ImageOperations.Axis.X, new SimpleBoundingBox(0, image.sizeX()-1, Math.max(startY, startOfPeakIdx-length), startOfPeakIdx, 0, image.sizeZ()-1), v->v>0); 
                float[] xProjLower = ImageOperations.meanProjection(image, ImageOperations.Axis.X, new SimpleBoundingBox(0, image.sizeX()-1, endOfPeakIdx, Math.min(stopY, endOfPeakIdx+length), 0, image.sizeZ()-1), v->v>0);
                double varUpper = ArrayUtil.meanSigma(xProjUpper, getFirstNonNanIdx(xProjUpper, true), getFirstNonNanIdx(xProjUpper, false)+1, null)[1];
                double varLower = ArrayUtil.meanSigma(xProjLower, getFirstNonNanIdx(xProjLower, true), getFirstNonNanIdx(xProjLower, false)+1, null)[1];
                flip = varLower>varUpper;
                logger.info("AutoFlipY: {} (var upper: {}, var lower: {} aberration: [{};{};{}]", flip, varLower, varUpper,startOfPeakIdx, peakIdx, endOfPeakIdx );
                break;
            } case OPTICAL_FLOW: {
                if (inputImages.getFrameNumber()<3) throw new RuntimeException("AutoFlipY > Optical flow method requires at least 3 frames");
                int frameLimit = Math.min(this.frameLimit.getIntValue(), inputImages.getFrameNumber());
                if (frameLimit <=1 ) frameLimit = inputImages.getFrameNumber();
                // compute dI/dy & dI/dt
                int binFactor = this.binFactor.getValue().intValue();

                Image[] images = new Image[frameLimit];
                for (int t = 0; t<images.length; ++t) {
                    images[t] = inputImages.getImage(channelIdx, t);
                    if (images[t].sizeZ()>1) images[t] = images[t].getZPlane(inputImages.getBestFocusPlane(t));
                    if (binFactor>1) images[t] = ImageOperations.spatialBinning(images[t], binFactor, 1, false);
                }
                if (stabilize.getSelected()) {
                    images = ImageStabilizerXY.stabilize(images, stabSegment.getValue().intValue(), binFactor==1 ? 1 : 0, true, true, inputImages.getTmpDirectory());
                }

                Image dt_ = ImageFeatures.getDerivative(Image.mergeZPlanes(images), 1, 1, 0, 0, 1, false);
                // remove first and last images
                Image[] images2 = Arrays.stream(images).skip(1).limit(images.length-2).toArray(Image[]::new);
                Image image3D  = Image.mergeZPlanes(images2);
                Image dt = Image.mergeZPlanes((List<Image>) IntStream.range(1, dt_.sizeZ()-1).mapToObj(i->dt_.getZPlane(i)).collect(Collectors.toList()));
                Image dy = ImageFeatures.getDerivative(image3D, 2, 1, 0, 1, 0, false);

                // filter immobile values
                double sigma_dt = ImageOperations.getMeanAndSigma(dt, null, null)[1];
                double thld_dt = sigma_dt*thresholdFactor_dt.getValue().doubleValue();
                BoundingBox.loop(dt, (x, y, z)->dt.setPixel(x, y, z, 0), (x, y, z)->Math.abs(dt.getPixel(x, y, z))<thld_dt, true);
                double sigma_dy = ImageOperations.getMeanAndSigma(dy, null, null)[1];
                double thld_dy = sigma_dy*thresholdFactor_dy.getValue().doubleValue();
                BoundingBox.loop(dt, (x, y, z)->dy.setPixel(x, y, z, 0), (x, y, z)->Math.abs(dy.getPixel(x, y, z))<thld_dy, true);

                Image Vy = ImageOperations.divide(dt, dy, null,-1);

                double meanVelocityY = ImageOperations.getMeanAndSigma(Vy, null, v->!Double.isNaN(v) && Double.isFinite(v), true)[0];
                flip = meanVelocityY<0;
                if (this.testMode.testSimple()) {
                    logger.debug("Mean velocity : {}, thld_dt: {}, thld_dt {}", meanVelocityY, thld_dt, thld_dy);
                    Core.showImage(image3D.setName("input"));
                    Core.showImage(dy.setName("dI/dy"));
                    Core.showImage(dt.setName("dI/dt"));
                    BoundingBox.loop(Vy, (x, y, z)->Vy.setPixel(x, y, z, 0), (x, y, z)->Double.isNaN(Vy.getPixel(x, y, z)) || Double.isInfinite(Vy.getPixel(x, y, z)), true);
                    Core.showImage(Vy.setName("Vy"));
                }
            }
        }
    }
    private static int getFirstNonNanIdx(float[] array, boolean fromStart) {
        if (fromStart) {
            int start = 0;
            while (start<array.length && Float.isNaN(array[start])) ++start;
            return start;
        } else {
            int end = array.length-1;
            while (end>0 && Float.isNaN(array[end])) --end;
            return end;
        }
    }
    private Boolean isFlipPhaseOpticalAberration(Image image) {
        /* 
        1) search for optical aberration
        2) get x variance for each line above and under aberration -> microchannels are where variance is maximal
        */
        ImageMask upper = new BlankMask( image.sizeX(), image.sizeY()/2, image.sizeZ(), image.xMin(), image.yMin(), image.zMin(), image.getScaleXY(), image.getScaleZ());
        ImageMask lower = new BlankMask( image.sizeX(), image.sizeY()/2, image.sizeZ(), image.xMin(), image.yMin()+image.sizeY()/2, image.zMin(), image.getScaleXY(), image.getScaleZ());
        double upperMean = ImageOperations.getMeanAndSigmaWithOffset(image, upper, null, true)[0];
        double lowerMean = ImageOperations.getMeanAndSigmaWithOffset(image, lower, null, true)[0];
        if (testMode.testSimple()) logger.debug("AutoFlipY: upper half mean {} lower: {}", upperMean, lowerMean);
        if (upperMean>lowerMean) return false;
        else if (lowerMean>upperMean) return true;
        else return null;
    }
    private Boolean isFlipFluoUpperHalf(Image image) {
        ImageMask upper = new BlankMask( image.sizeX(), image.sizeY()/2, image.sizeZ(), image.xMin(), image.yMin(), image.zMin(), image.getScaleXY(), image.getScaleZ());
        ImageMask lower = new BlankMask( image.sizeX(), image.sizeY()/2, image.sizeZ(), image.xMin(), image.yMin()+image.sizeY()/2, image.zMin(), image.getScaleXY(), image.getScaleZ());
        double upperMean = ImageOperations.getMeanAndSigmaWithOffset(image, upper, null, true)[0];
        double lowerMean = ImageOperations.getMeanAndSigmaWithOffset(image, lower, null, true)[0];
        if (testMode.testSimple()) logger.debug("AutoFlipY: upper half mean {} lower: {}", upperMean, lowerMean);
        if (upperMean>lowerMean) return false;
        else if (lowerMean>upperMean) return true;
        else return null;
    }
    private Boolean isFlipFluo(Image image) {
        int minSize = minObjectSize.getValue().intValue();
        SimpleThresholder thlder = fluoThld.instantiatePlugin();
        double thld = thlder.runSimpleThresholder(image, null);
        if (testMode.testSimple()) logger.debug("threshold: {}", thld);
        ImageMask mask = new PredicateMask(image, thld, true, true);
        ImageInt buffer = buffers!=null?buffers.imageIntPool.pull() : null;
        List<Region> objects = ImageLabeller.labelImageList(mask, buffer);
        if (buffers!=null) buffers.imageIntPool.push(buffer);
        objects.removeIf(o->o.size()<minSize);
        // filter by median sizeY
        Map<Region, Integer> sizeY = objects.stream().collect(Collectors.toMap(o->o, o->o.getBounds().sizeY()));
        double medianSizeY = ArrayUtil.medianInt(sizeY.values());
        objects.removeIf(o->sizeY.get(o)<medianSizeY/2);
        if (testMode.testSimple()) logger.debug("objects: {}, minSize: {}, minSizeY: {} (median sizeY: {})", objects.size(), minSize, medianSizeY/2, medianSizeY);
        if (objects.isEmpty() || objects.size()<=2) return null;
        Map<Region, MutableBoundingBox> xBounds = objects.stream().collect(Collectors.toMap(o->o, o->new MutableBoundingBox(o.getBounds().xMin(), o.getBounds().xMax(), 0, 1, 0, 1)));
        Iterator<Region> it = objects.iterator();
        List<Region> yMinOs = new ArrayList<>();
        List<Region> yMaxOs = new ArrayList<>();
        while(it.hasNext()) {
            Region o = it.next();
            List<Region> inter = new ArrayList<>(objects);
            inter.removeIf(oo->!intersect2D(xBounds.get(oo), xBounds.get(o)));
            yMinOs.add(Collections.min(inter, Comparator.comparingInt(o3 -> o3.getBounds().yMin())));
            yMaxOs.add(Collections.max(inter, Comparator.comparingInt(o2 -> o2.getBounds().yMax())));
            objects.removeAll(inter);
            it = objects.iterator();
        }

        // filter outliers with distance to median value
        double yMinMed = ArrayUtil.medianInt(Utils.transform(yMinOs, o->o.getBounds().yMin()));
        yMinOs.removeIf(o->Math.abs(o.getBounds().yMin()-yMinMed)>o.getBounds().sizeY()/4.);
        double yMaxMed = ArrayUtil.medianInt(Utils.transform(yMaxOs, o->o.getBounds().yMax()));
        yMaxOs.removeIf(o->Math.abs(o.getBounds().yMax()-yMaxMed)>o.getBounds().sizeY()/4.);
        if (yMinOs.size()<=2 || yMaxOs.size()<=2) return null;
        if (testMode.testExpert()) {
            this.upperObjectsTest.add(new RegionPopulation(yMinOs, image).getLabelMap().setName("Upper Objects"));
            this.lowerObjectsTest.add(new RegionPopulation(yMaxOs, image).getLabelMap().setName("Lower Objects"));
        }
        List<Pair<Integer, Integer>> yMins = Utils.transform(yMinOs, o->new Pair<>(o.getBounds().yMin(), o.getBounds().sizeY()));
        double sigmaMin = getSigma(yMins);
        List<Pair<Integer, Integer>> yMaxs = Utils.transform(yMaxOs, o->new Pair<>(o.getBounds().yMax(), o.getBounds().sizeY()));
        double sigmaMax = getSigma(yMaxs);
        if (testMode.testSimple()) {
            logger.debug("yMins sigma: {}: {}", sigmaMin, Utils.toStringList(yMins));
            logger.debug("yMaxs sigma {}: {}", sigmaMax, Utils.toStringList(yMaxs));
            logger.debug("flip: {}", sigmaMin>sigmaMax);
        }
        return sigmaMin>sigmaMax;
    }
    
    private static double getSigma(List<Pair<Integer, Integer>> l) {
        double mean = 0;
        for (Pair<Integer, Integer> p : l) mean +=p.key;
        mean/=(double)l.size();
        double mean2 = 0;
        /*double count = 0;
        for (Pair<Integer, Integer> p : l) { // ponderation with y-size of the object...
            mean2 += Math.pow(p.key-mean, 2) * p.value;
            count+=p.value;
        }*/
        for (Pair<Integer, Integer> p : l) {
            mean2 += Math.pow(p.key-mean, 2);
        }
        return mean2/l.size();
    }
    
    @Override
    public boolean isConfigured(int totalChannelNumber, int totalTimePointNumber) {
        return flip!=null;
    }
    
    @Override
    public Image applyTransformation(int channelIdx, int timePoint, Image image) {
        if (flip) {
            ///logger.debug("AutoFlipY: flipping (flip config: {} ({}))", flip, flip.getClass().getSimpleName());
            return ImageTransformation.flip(image, ImageTransformation.Axis.Y);
        } //else logger.debug("AutoFlipY: no flip");
        return image;
    }

    @Override
    public OUTPUT_SELECTION_MODE getOutputChannelSelectionMode() {
        return OUTPUT_SELECTION_MODE.ALL;
    }

    TEST_MODE testMode=TEST_MODE.NO_TEST;
    @Override
    public void setTestMode(TEST_MODE testMode) {this.testMode=testMode;}

    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{cond};
    }

    static class Buffers {
        public final SynchronizedPool<ImageInt> imageIntPool;
        public Buffers(ImageProperties props) {
            imageIntPool = new SynchronizedPool<>(() -> new ImageInt("labels", props), (ImageInt im) -> ImageOperations.fill(im, 0, null));
        }
    }
}
