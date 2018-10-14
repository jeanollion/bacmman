package bacmman.plugins.plugins.segmenters;

import bacmman.configuration.parameters.BoundedNumberParameter;
import bacmman.configuration.parameters.NumberParameter;
import bacmman.configuration.parameters.PreFilterSequence;
import bacmman.core.Core;
import bacmman.data_structure.Region;
import bacmman.data_structure.RegionPopulation;
import bacmman.data_structure.SegmentedObject;
import bacmman.data_structure.Voxel;
import bacmman.image.*;
import bacmman.plugins.ManualSegmenter;
import bacmman.plugins.ObjectSplitter;
import bacmman.plugins.SegmenterSplitAndMerge;
import bacmman.plugins.TestableProcessingPlugin;
import bacmman.plugins.plugins.pre_filters.ImageFeature;
import bacmman.plugins.plugins.pre_filters.Sigma;
import bacmman.plugins.plugins.thresholders.IJAutoThresholder;
import bacmman.plugins.plugins.trackers.ObjectIdxTracker;
import bacmman.processing.EDT;
import bacmman.processing.Filters;
import bacmman.processing.RegionFactory;
import bacmman.processing.clustering.RegionCluster;
import bacmman.processing.neighborhood.DisplacementNeighborhood;
import bacmman.processing.split_merge.SplitAndMergeHessian;
import ij.process.AutoThresholder;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public abstract class SegmenterSplitAndMergeHessian implements SegmenterSplitAndMerge, ObjectSplitter, TestableProcessingPlugin {
    NumberParameter vcThldForVoidMC = new BoundedNumberParameter("Variation coefficient threshold", 3, 0.085, 0, null).setHint("Parameter to look for void microchannels, at track pre-filter step: <br />Lower this value if no cells are found in some microchannel containing cells. On the contrary increase the value if false positive objects are segmented in void microchannels<br />Computation details: To assess if whole microchannel track is void: sigma / mu of raw images is computed on whole track, in the central line of each microchannel (1/3 of the width). If sigma / mu is inferior to the value of this parameter, the whole track is considered to be void. <br />If the track is not void, a global otsu threshold is computed on the prefiltered signal. A channel is considered as void if its value of sigma / mu of raw signal is inferior to this threshold and is its mean value of prefiltered signal is superior to the global threshold");
    NumberParameter hessianScale = new BoundedNumberParameter("Hessian scale", 1, 4, 1, 6).setHint("In pixels. Used in step 2). Lower value -> finner split, more sentitive to noise. Influences the value of split threshold parameter. <br />Configuration Hint: tune this value using the intermediate image <em>Hessian</em>");
    NumberParameter splitThreshold = new BoundedNumberParameter("Split Threshold", 4, 0.3, 0, null).setEmphasized(true).setHint("At step 2) regions are merge if sum(hessian)|interface / sum(raw intensity)|interface < (this parameter). <br />Lower value splits more.  <br />Configuration Hint: Tune the value using intermediate image <em>Interface Values before merge by Hessian</em>, interface with a value over this threshold will not be merged");
    PreFilterSequence edgeMap = new PreFilterSequence("Edge Map").add(new ImageFeature().setFeature(ImageFeature.Feature.GAUSS).setScale(1.5), new Sigma(2).setMedianRadius(0));

    SplitAndMergeHessian splitAndMerge;
    SegmentedObject currentParent;

    // parameter from track parametrizable
    double globalBackgroundLevel=0;
    boolean isVoid;

    protected SplitAndMergeHessian initializeSplitAndMerge(SegmentedObject parent, int structureIdx, ImageMask foregroundMask) {
        SplitAndMergeHessian res= new SplitAndMergeHessian(parent.getPreFilteredImage(structureIdx), splitThreshold.getValue().doubleValue(), hessianScale.getValue().doubleValue(), globalBackgroundLevel);
        return res;
    }


    // segmenter split and merge interface
    protected ImageByte tempSplitMask;
    private SplitAndMergeHessian.Interface getInterface(Region o1, Region o2) {
        o1.draw(tempSplitMask, o1.getLabel());
        o2.draw(tempSplitMask, o2.getLabel());
        SplitAndMergeHessian.Interface inter = RegionCluster.getInteface(o1, o2, tempSplitMask, splitAndMerge.getFactory());
        inter.updateInterface();
        o1.draw(tempSplitMask, 0);
        o2.draw(tempSplitMask, 0);
        return inter;
    }
    @Override
    public double split(SegmentedObject parent, int structureIdx, Region o, List<Region> result) {
        result.clear();
        RegionPopulation pop =  splitObject(parent, structureIdx, o); // after this step pop is in same landmark as o's landmark
        if (pop.getRegions().size()<=1) return Double.POSITIVE_INFINITY;
        else {
            if (tempSplitMask==null) tempSplitMask = new ImageByte("split mask", parent.getMask());
            result.addAll(pop.getRegions());
            if (pop.getRegions().size()>2) return 0; //   objects could not be merged during step process means no contact (effect of local threshold)
            SplitAndMergeHessian.Interface inter = getInterface(result.get(0), result.get(1));
            //logger.debug("split @ {}-{}, inter size: {} value: {}/{}", parent, o.getLabel(), inter.getVoxels().size(), inter.value, splitAndMerge.splitThresholdValue);
            if (inter.getVoxels().size()<=1) return 0;
            double cost = getCost(inter.value, splitAndMerge.getSplitThresholdValue(), true);
            return cost;
        }

    }

    @Override public double computeMergeCost(SegmentedObject parent, int structureIdx, List<Region> objects) {
        if (objects.isEmpty() || objects.size()==1) return 0;
        Image input = parent.getPreFilteredImage(structureIdx);
        RegionPopulation mergePop = new RegionPopulation(objects, objects.get(0).isAbsoluteLandMark() ? input : new BlankMask(input).resetOffset());
        mergePop.relabel(false); // ensure distinct labels , if not cluster cannot be found
        if (splitAndMerge==null || !parent.equals(currentParent)) {
            currentParent = parent;
            splitAndMerge = initializeSplitAndMerge(parent, structureIdx, parent.getMask());
        }
        RegionCluster c = new RegionCluster(mergePop, true, splitAndMerge.getFactory());
        List<Set<Region>> clusters = c.getClusters();
        if (clusters.size()>1) { // merge impossible : presence of disconnected objects
            if (stores!=null) logger.debug("merge impossible: {} disconnected clusters detected", clusters.size());
            return Double.POSITIVE_INFINITY;
        }
        double maxCost = Double.NEGATIVE_INFINITY;
        Set<SplitAndMergeHessian.Interface> allInterfaces = c.getInterfaces(clusters.get(0));
        for (SplitAndMergeHessian.Interface i : allInterfaces) {
            i.updateInterface();
            if (i.value>maxCost) maxCost = i.value;
        }

        if (Double.isInfinite(maxCost)) return Double.POSITIVE_INFINITY;
        return getCost(maxCost, splitAndMerge.getSplitThresholdValue(), false);

    }
    public static double getCost(double value, double threshold, boolean valueShouldBeBelowThresholdForAPositiveCost)  {
        if (valueShouldBeBelowThresholdForAPositiveCost) {
            if (value>=threshold) return 0;
            else return (threshold-value);
        } else {
            if (value<=threshold) return 0;
            else return (value-threshold);
        }
    }

    // manual correction implementations

    // object splitter interface
    boolean splitVerbose;
    @Override public void setSplitVerboseMode(boolean verbose) {
        this.splitVerbose=verbose;
    }



    // track parameterization
    /**
     * Detected void microchannels
     * All microchannels are void if variation coefficient (sigma/mu) of raw sigal is inferior to the corresponding parameter value
     * If not, a global threshold is computed on all prefiltered images, if mean prefiltered value < global thld OR variation coefficient < parameter -> the microchannel is considered as void
     * @param structureIdx
     * @param parentTrack
     * @return void microchannels
     */
    protected Set<SegmentedObject> getVoidMicrochannels(int structureIdx, List<SegmentedObject> parentTrack) {
        double globalVoidThldSigmaMu = vcThldForVoidMC.getValue().doubleValue();
        // get sigma in the middle line of each MC
        double[] globalSum = new double[3];
        Function<SegmentedObject, float[]> compute = p->{
            Image imR = p.getRawImage(structureIdx);
            Image im = p.getPreFilteredImage(structureIdx);
            if (im==null) throw new RuntimeException("no prefiltered image");
            int xMargin = im.sizeX()/3;
            MutableBoundingBox bb= new MutableBoundingBox(im).resetOffset().extend(new SimpleBoundingBox(xMargin, -xMargin, im.sizeX(), -im.sizeY()/6, 0, 0)); // only central line to avoid border effects + remove open end -> sometimes optical aberration
            double[] sum = new double[2];
            double[] sumR = new double[3];
            BoundingBox.loop(bb, (x, y, z)-> {
                if (p.getMask().insideMask(x, y, z)) {
                    double v = im.getPixel(x, y, z);
                    sum[0]+=v;
                    sum[1]++;
                    v = imR.getPixel(x, y, z);
                    sumR[0]+=v;
                    sumR[1]+=v*v;
                    sumR[2]++;
                }
            });
            synchronized(globalSum) {
                globalSum[0]+=sumR[0];
                globalSum[1]+=sumR[1];
                globalSum[2]+=sumR[2];
            }
            double meanR = sumR[0]/sumR[2];
            double meanR2 = sumR[1]/sumR[2];
            return new float[]{(float)(sum[0]/sum[1]), (float)meanR, (float)Math.sqrt(meanR2 - meanR * meanR)};
        };
        List<float[]> meanF_meanR_sigmaR = parentTrack.stream().parallel().map(p->compute.apply(p)).collect(Collectors.toList());
        // 1) criterion for void microchannel track
        double globalMean = globalSum[0]/globalSum[2];
        double globalMin = globalBackgroundLevel;
        double globalSigma = Math.sqrt(globalSum[1]/globalSum[2] - globalMean * globalMean);
        if (globalSigma/(globalMean-globalMin)<globalVoidThldSigmaMu) {
            logger.debug("parent: {} sigma/mean: {}/{}-{}={} all channels considered void: {}", parentTrack.get(0), globalSigma, globalMean, globalMin, globalSigma/(globalMean-globalMin));
            return new HashSet<>(parentTrack);
        }
        // 2) criterion for void microchannels : low intensity value
        // intensity criterion based on global threshold (otsu for phase, backgroundFit(5) for fluo
        double globalThld = getGlobalThreshold(parentTrack, structureIdx);

        Set<SegmentedObject> outputVoidMicrochannels = IntStream.range(0, parentTrack.size())
                .filter(idx -> meanF_meanR_sigmaR.get(idx)[0]<globalThld)  // test on mean value is because when mc is very full, sigma can be low enough
                .filter(idx -> meanF_meanR_sigmaR.get(idx)[2]/(meanF_meanR_sigmaR.get(idx)[1]-globalMin)<globalVoidThldSigmaMu) // test on sigma/mu value because when mc is nearly void, mean can be low engough
                .mapToObj(idx -> parentTrack.get(idx))
                .collect(Collectors.toSet());
        logger.debug("parent: {} global Sigma/Mean-Min {}/{}-{}={} global thld: {} void mc {}/{}", parentTrack.get(0), globalSigma,globalMean, globalMin, globalSigma/(globalMean-globalMin), globalThld,outputVoidMicrochannels.size(), parentTrack.size() );
        //logger.debug("10 frames lower std/mu: {}", IntStream.range(0, parentTrack.size()).mapToObj(idx -> new Pair<>(parentTrack.get(idx).getFrame(), meanF_meanR_sigmaR.get(idx)[2]/(meanF_meanR_sigmaR.get(idx)[1]-globalMin))).sorted((p1, p2)->Double.compare(p1.value, p2.value)).limit(10).collect(Collectors.toList()));
        //logger.debug("10 frames upper std/mu: {}", IntStream.range(0, parentTrack.size()).mapToObj(idx -> new Pair<>(parentTrack.get(idx).getFrame(), meanF_meanR_sigmaR.get(idx)[2]/(meanF_meanR_sigmaR.get(idx)[1]-globalMin))).sorted((p1, p2)->-Double.compare(p1.value, p2.value)).limit(10).collect(Collectors.toList()));

        //logger.debug("s/mu : {}", Utils.toStringList(meanF_meanR_sigmaR.subList(10, 15), f->"mu="+f[0]+" muR="+f[1]+"sR="+f[2]+ " sR/muR="+f[2]/f[1]));
        return outputVoidMicrochannels;
    }
    /**
     *
     * @param parent
     * @param structureIdx
     * @return global threshold for void microchannel test
     */
    protected abstract double getGlobalThreshold(List<SegmentedObject> parent, int structureIdx);


    static double  getGlobalOtsuThreshold(Stream<SegmentedObject> parent, int structureIdx) {
        Map<Image, ImageMask> imageMapMask = parent.collect(Collectors.toMap(p->p.getPreFilteredImage(structureIdx), p->p.getMask() ));
        Histogram histo = HistogramFactory.getHistogram(()->Image.stream(imageMapMask, true).parallel(), HistogramFactory.BIN_SIZE_METHOD.AUTO_WITH_LIMITS);
        return IJAutoThresholder.runThresholder(AutoThresholder.Method.Otsu, histo);

    }


    // testable processing plugin
    Map<SegmentedObject, TestableProcessingPlugin.TestDataStore> stores;
    @Override public void setTestDataStore(Map<SegmentedObject, TestableProcessingPlugin.TestDataStore> stores) {
        this.stores=  stores;
    }
}
