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

import bacmman.configuration.parameters.BooleanParameter;
import bacmman.configuration.parameters.BoundedNumberParameter;
import bacmman.configuration.parameters.ChannelImageParameter;
import bacmman.configuration.parameters.ChoiceParameter;
import bacmman.configuration.parameters.GroupParameter;
import bacmman.configuration.parameters.NumberParameter;
import bacmman.configuration.parameters.Parameter;
import bacmman.configuration.parameters.PluginParameter;
import bacmman.configuration.parameters.SimpleListParameter;
import bacmman.data_structure.input_image.InputImages;
import bacmman.data_structure.input_image.SimpleInputImages;
import bacmman.image.*;
import bacmman.image.TypeConverter;
import bacmman.plugins.TestableOperation;
import ij.ImagePlus;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;
import bacmman.image.wrappers.IJImageWrapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.IntStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import bacmman.plugins.Cropper;
import bacmman.processing.ImageTransformation;
import bacmman.plugins.ConfigurableTransformation;
import bacmman.plugins.MultichannelTransformation;
import bacmman.plugins.Hint;
import bacmman.utils.HashMapGetCreate;
import bacmman.utils.SynchronizedPoolWithSourceObject;
import bacmman.utils.ThreadRunner;

/**
 *
 * @author Jean Ollion
 */
public class ImageStabilizerXY implements ConfigurableTransformation, MultichannelTransformation, TestableOperation, Hint {
    public final static Logger logger = LoggerFactory.getLogger(ImageStabilizerXY.class);
    ChoiceParameter transformationType = new ChoiceParameter("Transformation", new String[]{"Translation"}, "Translation", false); //, "Affine"
    ChoiceParameter pyramidLevel = new ChoiceParameter("Pyramid Level", new String[]{"0", "1", "2", "3", "4"}, "1", false);
    BoundedNumberParameter alpha = new BoundedNumberParameter("Template Update Coefficient", 2, 1, 0, 1);
    BoundedNumberParameter maxIter = new BoundedNumberParameter("Maximum Iterations", 0, 1000, 1, null);
    BoundedNumberParameter segmentLength = new BoundedNumberParameter("Segment length", 0, 20, 2, null);
    NumberParameter tol = new BoundedNumberParameter("Error Tolerance", 15, 5e-8, 0, null);
    BooleanParameter allowInterpolation = new BooleanParameter("Allow non-integer translation (interpolation)", false);
    PluginParameter<Cropper> cropper = new PluginParameter<>("Cropper", Cropper.class, true);
    SimpleListParameter<GroupParameter> additionalTranslation = new SimpleListParameter<GroupParameter>("Additional Translation", new GroupParameter("Channel Translation", new ChannelImageParameter("Channel"), new NumberParameter("dX", 3, 0), new NumberParameter("dY", 3, 0), new NumberParameter("dZ", 3, 0)));
    Parameter[] parameters = new Parameter[]{maxIter, tol, pyramidLevel, segmentLength, cropper, additionalTranslation, allowInterpolation}; //alpha
    ArrayList<ArrayList<Double>> translationTXY = new ArrayList<ArrayList<Double>>();
    public static boolean debug=false;
    public ImageStabilizerXY(){}
    
    public ImageStabilizerXY(int pyramidLevel, int maxIterations, double tolerance, int segmentLength) {
        this.pyramidLevel.setSelectedIndex(pyramidLevel);
        this.tol.setValue(tolerance);
        this.maxIter.setValue(maxIterations);
        this.segmentLength.setValue(segmentLength);
    }
    @Override
    public String getHintText() {
        return "XY-registration in time axis by image correlation. <br />Based on Lucas-Kanade algorithm, implementation: <a href='http://www.cs.cmu.edu/~kangli/code/Image_Stabilizer.html'>http://www.cs.cmu.edu/~kangli/code/Image_Stabilizer.html</a>";
    }
    @Override
    public boolean highMemory() {return false;}
    public ImageStabilizerXY setCropper(Cropper cropper) {
        this.cropper.setPlugin(cropper);
        return this;
    }
    
    public ImageStabilizerXY setAdditionalTranslation(int channelIdx, double... deltas) {
        if (getAdditionalTranslationParameter(channelIdx, false)!=null) throw new IllegalArgumentException("Translation already set for channel: "+channelIdx);
        GroupParameter g = additionalTranslation.createChildInstance();
        additionalTranslation.insert(g);
        ((ChannelImageParameter)g.getChildAt(0)).setSelectedClassIdx(channelIdx);
        for (int i = 0; i<Math.min(3, deltas.length); ++i) ((NumberParameter)g.getChildAt(i+ 1)).setValue(deltas[i]);
        return this;
    }
    
    @Override
    public void computeConfigurationData(final int channelIdx, final InputImages inputImages) throws IOException {
        if (debug) testMode = TEST_MODE.TEST_EXPERT;
        long tStart = System.currentTimeMillis();
        final int tRef = inputImages.getDefaultTimePoint();
        //final int tRef=0;
        final int maxIterations = this.maxIter.getValue().intValue();
        final double tolerance = this.tol.getValue().doubleValue();
        
        //new IJImageDisplayer().showImage(imageRef.setName("ref image"));
        //if (true) return;
        final Double[][] translationTXYArray = new Double[inputImages.getFrameNumber()][];
        Cropper crop = cropper.instantiatePlugin();
        MutableBoundingBox cropBB= crop==null? null : crop.getCropBoundginBox(channelIdx, inputImages);
        logger.debug("crop bounding box: {}", cropBB);
        InputImages.getImageForChannel(inputImages, channelIdx, false); // ensure all transformations are performed on images (multithread)
        ccdSegments(channelIdx, inputImages, segmentLength.getValue().intValue(), tRef, translationTXYArray, maxIterations, tolerance, cropBB);
        translationTXY = new ArrayList<>(translationTXYArray.length);
        for (Double[] d : translationTXYArray) translationTXY.add(new ArrayList<>(Arrays.asList(d)));
        if (cropBB!=null) { // ensure BB never gets out of the image + store BB parameters
            double minDX = Collections.min(translationTXY, (l1, l2) -> Double.compare(l1.get(0), l2.get(0))).get(0);
            double maxDX = Collections.max(translationTXY, (l1, l2) -> Double.compare(l1.get(0), l2.get(0))).get(0);
            double minDY = Collections.min(translationTXY, (l1, l2) -> Double.compare(l1.get(1), l2.get(1))).get(1);
            double maxDY = Collections.max(translationTXY, (l1, l2) -> Double.compare(l1.get(1), l2.get(1))).get(1);
            logger.debug("ImageStabXY : dx:[{};{}], dy:[{};{}]", minDX, maxDX, minDY, maxDY);
            double[] addTransMin = this.getAdditionalTranslation(channelIdx);
            double[] addTransMax = this.getAdditionalTranslation(channelIdx);
            for (int c = 1; c<inputImages.getChannelNumber(); ++c) {
                double[] addTrans = this.getAdditionalTranslation(c);     
                for (int i = 0; i<3; ++i) {
                    if (addTransMin[i]>addTrans[i]) addTransMin[i] = addTrans[i]; 
                    if (addTransMax[i]<addTrans[i]) addTransMax[i] = addTrans[i];
                }
            }
            logger.debug("transMax: {}, transMin: {}", addTransMax, addTransMin);
            MutableBoundingBox bds = null;
            try {
                bds = inputImages.getImage(0, tRef).getBoundingBox().resetOffset();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            // bb translated (int)(tXY-addTrans)
            int xLeft = (int)Math.round(minDX-addTransMax[0]);
            xLeft = xLeft<0 ? -Math.min(0, cropBB.xMin()+xLeft) : cropBB.xMin();
            int xRight= (int)Math.round(maxDX-addTransMin[0]);
            xRight = bds.xMax() - (xRight>0 ? Math.max(0, cropBB.xMax()+xRight-bds.xMax()) : 0);
            cropBB.contractX(xLeft, xRight);
            
            int yLeft = (int)Math.round(minDY-addTransMax[1]);
            yLeft = yLeft<0 ? -Math.min(0, cropBB.yMin()+yLeft) : cropBB.yMin();
            int yRight= (int)Math.round(maxDY-addTransMin[1]);
            yRight = bds.yMax() - (yRight>0 ? Math.max(0, cropBB.yMax()+yRight-bds.yMax()) : 0);
            cropBB.contractY(yLeft, yRight);
            
            logger.debug("ImageStabXY : contract x:[{};{}], y:[{};{}]", xLeft, xRight, yLeft, yRight);
            
            translationTXY.add(new ArrayList<Double>(){{add((double)cropBB.xMin()); add((double)cropBB.xMax()); add((double)cropBB.yMin()); add((double)cropBB.yMax()); add((double)cropBB.zMin()); add((double)cropBB.zMax());}});
        }   
        long tEnd = System.currentTimeMillis();
        logger.debug("ImageStabilizerXY: total estimation time: {}, reference timePoint: {}", tEnd-tStart, tRef);
    }

    public static Image[] stabilize(Image[] images, int segmentLength, int pyramidLevel, boolean allowInterpolation, boolean crop, String tmpDir) throws IOException{
        InputImages ii = new SimpleInputImages(tmpDir, images);
        ImageStabilizerXY stab = new ImageStabilizerXY();
        stab.segmentLength.setValue(segmentLength);
        stab.pyramidLevel.setSelectedIndex(pyramidLevel);
        stab.allowInterpolation.setSelected(allowInterpolation);
        stab.computeConfigurationData(0, ii);
        Image[] res = IntStream.range(0, images.length).parallel().mapToObj(t -> stab.applyTransformation(0, t, images[t])).toArray(Image[]::new);
        if (allowInterpolation) {
            Image maxType = Arrays.stream(res).max(PrimitiveType.typeComparator()).get();
            if (maxType.floatingPoint()) for (int t=0;t<res.length; ++t) res[t] = TypeConverter.toFloatingPoint(res[t], false, false);
        }
        if (crop) {
            double dXmin = -stab.translationTXY.stream().mapToDouble(d->d.get(0)).min().getAsDouble();
            double dXmax = stab.translationTXY.stream().mapToDouble(d->d.get(0)).max().getAsDouble();
            double dYmin = -stab.translationTXY.stream().mapToDouble(d->d.get(1)).min().getAsDouble();
            double dYmax = stab.translationTXY.stream().mapToDouble(d->d.get(1)).max().getAsDouble();
            if (dXmin!=0 || dXmax!=0 || dYmin!=0 || dYmax!=0) {
                MutableBoundingBox cropBB = new MutableBoundingBox(res[0]);
                cropBB.setxMin(cropBB.xMin() + (int) Math.ceil(dXmax));
                cropBB.setxMax(cropBB.xMax() - (int) Math.ceil(dXmin));
                cropBB.setyMin(cropBB.yMin() + (int) Math.ceil(dYmax));
                cropBB.setyMax(cropBB.yMax() - (int) Math.ceil(dYmin));
                for (int t = 0; t < res.length; ++t) res[t] = res[t].crop(cropBB);
                logger.debug("crop: x: [{}; {}] y: [{}; {}]", dXmin, dXmax, dYmin, dYmax);
            }
        }
        return res;
    }
    private void ccdSegments(final int channelIdx, final InputImages inputImages, int segmentLength, int tRef, final Double[][] translationTXYArray, final int maxIterations, final double tolerance, MutableBoundingBox cropBB) throws IOException {
        if (segmentLength<2) segmentLength = 2;
        int nSegments = (int)(0.5 +(double)(inputImages.getFrameNumber()-1) / (double)segmentLength) ;
        if (nSegments<1) nSegments=1;
        int[][] segments = new int[nSegments][3]; // tStart, tEnd, tRef
        if (testMode.testExpert()) logger.debug("n segment: {}, {}", segments.length);
        final Map<Integer, Integer> mapImageToRef = new HashMap<>(inputImages.getFrameNumber());
        for (int i = 0; i<nSegments; ++i) {
            segments[i][0] = i==0 ? 0 : segments[i-1][1]+1;
            segments[i][1] = i==segments.length-1 ? inputImages.getFrameNumber()-1 : segments[i][0]+segmentLength-1;
            segments[i][2] = i==0 ? Math.min(Math.max(0, tRef), segments[i][1]) : segments[i-1][1]; 
            for (int j = segments[i][0]; j<=segments[i][1]; ++j) mapImageToRef.put(j, segments[i][2]);
            if (testMode.testExpert()) logger.debug("segment: {}, {}", i, segments[i]);
        }
        if (testMode.testExpert())logger.debug("im to ref map: {}", mapImageToRef);
        MutableBoundingBox refBB = cropBB==null ? inputImages.getImage(channelIdx, tRef).getBoundingBox().resetOffset() : cropBB;
        // process each segment
        final Function<Integer, FloatProcessor> processorMap = i-> {
            try {
                return getFloatProcessor(cropBB==null ? inputImages.getImage(channelIdx, i) : inputImages.getImage(channelIdx, i).crop(cropBB), false);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        };
        BiFunction<Integer, Bucket, Bucket> r = (imageRefIdx, bucket) -> {
            if (bucket.imageRefIdx!=imageRefIdx) { // only compute gradient if reference image is different
                ImageStabilizerCore.gradient(bucket.pyramid[1][0], processorMap.apply(imageRefIdx));
                bucket.imageRefIdx=imageRefIdx;
            }
            return bucket;
        };
        Function<Integer, Bucket> f = (imageRefIdx) -> {
            Bucket res = new Bucket(refBB, pyramidLevel.getSelectedIndex());
            return r.apply(imageRefIdx, res);
        };
        SynchronizedPoolWithSourceObject<Bucket, Integer> pyramids = new SynchronizedPoolWithSourceObject<>(f, r, true);
        List<Entry<Integer, Integer>> l = new ArrayList<>(mapImageToRef.entrySet());
        Collections.shuffle(l); // shuffle so that pyramids with given gradient have more chance to be used several times
        ThreadRunner.execute(l, false, (Entry<Integer, Integer> p, int idx) -> {
            double[] outParams = new double[2];
            if (p.getKey()==tRef) translationTXYArray[p.getKey()] = new Double[]{0d, 0d};
            else {
                Bucket b = pyramids.poll(p.getValue());
                translationTXYArray[p.getKey()] = performCorrection(processorMap, p.getKey(), b.pyramid, outParams);
                pyramids.push(b, p.getValue());
            }
            if (testMode.testExpert()) logger.debug("t: {}, tRef: {}, dX: {}, dY: {}, rmse: {}, iterations: {}", p.getKey(), p.getValue(), translationTXYArray[p.getKey()][0], translationTXYArray[p.getKey()][1], outParams[0], outParams[1]);
        });
        // translate shifts
        for (int i = 1; i<segments.length; ++i) {
            Double[] ref = translationTXYArray[segments[i][2]];
            for (int t = segments[i][0]; t<=segments[i][1]; ++t) {
                translationTXYArray[t][0]+=ref[0];
                translationTXYArray[t][1]+=ref[1];
            }
            if (testMode.testExpert()) logger.debug("ref: {}, tp: {}, trans: {}", i,segments[i][2], ref);
        }
    }
    
    private void ccdSegmentTemplateUpdate(final int channelIdx, final InputImages inputImages, final int tStart, final int tEnd, final int tRef, final Double[][] translationTXYArray, final int maxIterations, final double tolerance)  throws IOException {
        
        final Image imageRef = inputImages.getImage(channelIdx, tRef);
        FloatProcessor ipFloatRef = getFloatProcessor(imageRef, true);
        ImageProcessor[][] pyramids = ImageStabilizerCore.initWorkspace(imageRef.sizeX(), imageRef.sizeY(), pyramidLevel.getSelectedIndex());
        FloatProcessor trans=null;
        double a = alpha.getValue().doubleValue();
        translationTXYArray[tRef] = new Double[]{0d, 0d};
        if (a<1) trans  = new FloatProcessor(imageRef.sizeX(), imageRef.sizeY());
        for (int t = tRef-1; t>=0; --t) translationTXYArray[t] = performCorrectionWithTemplateUpdate(channelIdx, inputImages, t, ipFloatRef, pyramids, trans, maxIterations, tolerance, a, translationTXYArray[t+1]);
        if (a<1 && tRef>0) ipFloatRef = getFloatProcessor(imageRef, true); // reset template
        for (int t = tRef+1; t<inputImages.getFrameNumber(); ++t) translationTXYArray[t] = performCorrectionWithTemplateUpdate(channelIdx, inputImages, t, ipFloatRef, pyramids, trans, maxIterations, tolerance, a, translationTXYArray[t-1]);
        
    }
    
    public static Image testTranslate(Image imageRef, Image imageToTranslate, int maxIterations, double maxTolerance, int pyramidLevel) {
        FloatProcessor ipFloat1 = getFloatProcessor(imageRef, true);
        FloatProcessor ipFloat2 = getFloatProcessor(imageToTranslate, true);
        
        ImageProcessor[][] pyramids = ImageStabilizerCore.initWorkspace(imageRef.sizeX(), imageRef.sizeY(), pyramidLevel);
        double[] outParam = new double[2];
        double[][] wp = ImageStabilizerCore.estimateTranslation(ipFloat2, ipFloat1, pyramids[0], pyramids[1], true, maxIterations, maxTolerance, null, outParam);
        logger.debug("dX: {}, dY: {}, rmse: {}, iterations: {}", wp[0][0], wp[1][0], outParam[0], outParam[1]);
        return ImageTransformation.translate(imageToTranslate, -wp[0][0], -wp[1][0], 0, ImageTransformation.InterpolationScheme.BSPLINE5);
    }
    
    private Double[] performCorrection(Function<Integer, FloatProcessor> processorMap, int t, ImageProcessor[][] pyramids, double[] outParameters) {
        long t0 = System.currentTimeMillis();
        FloatProcessor currentTime = processorMap.apply(t);
        long tStart = System.currentTimeMillis();
        double[][] wp = ImageStabilizerCore.estimateTranslation(currentTime, null, pyramids[0], pyramids[1], false, maxIter.getValue().intValue(), tol.getValue().doubleValue(), null, outParameters);
        long tEnd = System.currentTimeMillis();
        Double[] res =  new Double[]{wp[0][0], wp[1][0]};
        if (testMode.testExpert()) logger.debug("ImageStabilizerXY: timepoint: {} dX: {} dY: {}, open & preProcess time: {}, estimate translation time: {}", t, res[0], res[1], tStart-t0, tEnd-tStart);
        return res;
    }
    
    private Double[] performCorrectionWithTemplateUpdate(int channelIdx, InputImages inputImages, int t, FloatProcessor ipFloatRef, ImageProcessor[][] pyramids,  FloatProcessor trans, int maxIterations, double tolerance, double alpha, Double[] estimateShift) throws IOException {
        long t0 = System.currentTimeMillis();
        FloatProcessor currentTime = getFloatProcessor(inputImages.getImage(channelIdx, t), false);
        long tStart = System.currentTimeMillis();
        double[] outParam = new double[2];
        double[][] wp = ImageStabilizerCore.estimateTranslation(currentTime, ipFloatRef, pyramids[0], pyramids[1], true, maxIterations, tolerance, estimateShift, outParam);
        long tEnd = System.currentTimeMillis();
        Double[] res =  new Double[]{wp[0][0], wp[1][0]};
        logger.debug("ImageStabilizerXY: timepoint: {} dX: {} dY: {}, open & preProcess time: {}, estimate translation time: {}", t, res[0], res[1], tStart-t0, tEnd-tStart);
        //update template 
        if (alpha<1) {
            ImageStabilizerCore.warpTranslation(trans, currentTime, wp);
            ImageStabilizerCore.combine(ipFloatRef, trans, alpha);
        }
        return res;
    }
    
    private static FloatProcessor getFloatProcessor(Image image, boolean duplicate) {
        if (image.sizeZ()>1) image = image.getZPlane((int)(image.sizeZ()/2.0+0.5)); //select middle slice only
        if (!(image instanceof ImageFloat)) image = TypeConverter.toFloat(image, null);
        else if (duplicate) image = image.duplicate("");
        ImagePlus impRef = IJImageWrapper.getImagePlus(image);
        return (FloatProcessor)impRef.getProcessor();
    }
    private MutableBoundingBox getBB() {
        ArrayList<Double> bds = translationTXY.get(translationTXY.size()-1);
        return new MutableBoundingBox(bds.get(0).intValue(), bds.get(1).intValue(), bds.get(2).intValue(), bds.get(3).intValue(), bds.get(4).intValue(), bds.get(5).intValue());
    }
    
    @Override
    public Image applyTransformation(int channelIdx, int timePoint, Image image) {
        MutableBoundingBox cropBB =  cropper.isOnePluginSet() ? getBB() : null;
        ArrayList<Double> trans = translationTXY.get(timePoint);
        boolean allowNonInteger = allowInterpolation.getSelected();
        //logger.debug("stabilization time: {}, channel: {}, X:{}, Y:{}", timePoint, channelIdx, trans.get(0), trans.get(1));
        double[] additionalTranslationValue = getAdditionalTranslation(channelIdx);
        double dX = -trans.get(0)+additionalTranslationValue[0];
        double dY = -trans.get(1)+additionalTranslationValue[1];
        double dZ = additionalTranslationValue[2];
        if (cropBB!=null) {
            cropBB.translate((int)-Math.round(dX), (int)-Math.round(dY), (int)-Math.round(dZ));
            dX-=(int)Math.round(dX);
            dY-=(int)Math.round(dY);
            dZ-=(int)Math.round(dZ);
        }
        if (!allowNonInteger) {
            dX = Math.round(dX);
            dY = Math.round(dY);
            dZ = Math.round(dZ);
        }
        //logger.debug("tp: {} ch: {}, bds: {}, dX:{} dY: {}, dZ: {}", timePoint, channelIdx, cropBB!=null?cropBB : "null", dX, dY, dZ);
        if (dX!=0 || dY!=0 || dZ!=0) {
            if (allowNonInteger && !(image instanceof ImageFloat)) image = TypeConverter.toFloat(image, null);
            image = ImageTransformation.translate(image, dX, dY, dZ, allowNonInteger ? ImageTransformation.InterpolationScheme.BSPLINE5 : ImageTransformation.InterpolationScheme.NEAREST);
        }
        if (cropBB!=null) image = image.crop(cropBB);
        //if (timePoint<=0) logger.debug("add trans for channel: {} = {}", channelIdx, additionalTranslationValue);
        return image;
    }
    private GroupParameter getAdditionalTranslationParameter(int channelIdx, boolean onlyActivated) {
        List<GroupParameter> list = onlyActivated ? additionalTranslation.getActivatedChildren() : additionalTranslation.getChildren();
        for (GroupParameter g : list) {
            if (((ChannelImageParameter)g.getChildAt(0)).getSelectedIndex()==channelIdx) return g;
        }
        return null;
    }
    private double[] getAdditionalTranslation(int channelIdx) {
        double[] res = new double[3];
        GroupParameter g = getAdditionalTranslationParameter(channelIdx, true);
        if (g!=null) {
            for (int i = 0; i<3; ++i) res[i] = ((NumberParameter)g.getChildAt(i+1)).getValue().doubleValue();
        }
        return res;
    }
    
    @Override
    public boolean isConfigured(int totalChannelNumner, int totalTimePointNumber) {
        return translationTXY!=null && translationTXY.size()==(totalTimePointNumber+(cropper.isOnePluginSet()?1:0));
    } 

    public boolean isTimeDependent() {
        return true;
    }

    public Parameter[] getParameters() {
        return parameters;
    }

    public boolean does3D() {
        return true;
    }

    public OUTPUT_SELECTION_MODE getOutputChannelSelectionMode() {
        return OUTPUT_SELECTION_MODE.ALL;
    }
    private static class Bucket {
        ImageProcessor[][] pyramid;
        int imageRefIdx;
        public Bucket(MutableBoundingBox refBB, int pyramidLevel) {
            pyramid = ImageStabilizerCore.initWorkspace(refBB.sizeX(), refBB.sizeY(), pyramidLevel);
            this.imageRefIdx=-1;
        }

        @Override
        public int hashCode() {
            int hash = 5;
            hash = 29 * hash + this.imageRefIdx;
            return hash;
        }

        @Override
        public boolean equals(Object obj) {
            if (obj == null) {
                return false;
            }
            if (getClass() != obj.getClass()) {
                return false;
            }
            final Bucket other = (Bucket) obj;
            if (this.imageRefIdx != other.imageRefIdx) {
                return false;
            }
            return true;
        }
        
    }
    TEST_MODE testMode=TEST_MODE.NO_TEST;
    @Override
    public void setTestMode(TEST_MODE testMode) {this.testMode=testMode;}

}
