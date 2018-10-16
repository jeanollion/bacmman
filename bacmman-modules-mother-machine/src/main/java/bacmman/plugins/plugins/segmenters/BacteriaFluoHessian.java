package bacmman.plugins.plugins.segmenters;

import bacmman.configuration.parameters.*;
import bacmman.data_structure.RegionPopulation;
import bacmman.data_structure.SegmentedObject;
import bacmman.data_structure.Voxel;
import bacmman.image.Histogram;
import bacmman.image.HistogramFactory;
import bacmman.image.Image;
import bacmman.image.ImageMask;
import bacmman.plugins.*;
import bacmman.plugins.SimpleThresholder;
import bacmman.plugins.plugins.thresholders.BackgroundFit;
import bacmman.plugins.plugins.thresholders.IJAutoThresholder;
import bacmman.processing.split_merge.SplitAndMergeHessian;
import bacmman.utils.Utils;
import ij.process.AutoThresholder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.DoubleStream;
import java.util.stream.Stream;

public class BacteriaFluoHessian extends BacteriaHessian<BacteriaFluoHessian> implements Hint {
    private final static Logger logger = LoggerFactory.getLogger(BacteriaFluoHessian.class);
    private double globalBackgroundSigma=1;
    PluginParameter<SimpleThresholder> foreThresholderFrame = new PluginParameter<>("Method", bacmman.plugins.SimpleThresholder.class, new IJAutoThresholder().setMethod(AutoThresholder.Method.Otsu), false).setEmphasized(true).setHint("Threshold for foreground region selection. Computed on each frame.");
    PluginParameter<ThresholderHisto> foreThresholder = new PluginParameter<>("Method", ThresholderHisto.class, new BackgroundFit(20), false).setEmphasized(true).setHint("Threshold for foreground region selection. Computed on the whole parent-track/root track.");
    EnumChoiceParameter<BacteriaFluo.THRESHOLD_COMPUTATION> foreThresholdMethod=  new EnumChoiceParameter<>("Foreground Threshold", BacteriaFluo.THRESHOLD_COMPUTATION.values(), BacteriaFluo.THRESHOLD_COMPUTATION.ROOT_TRACK, false);
    ConditionalParameter foreThldCond = new ConditionalParameter(foreThresholdMethod).setActionParameters(BacteriaFluo.THRESHOLD_COMPUTATION.CURRENT_FRAME.toString(), foreThresholderFrame).setActionParameters(BacteriaFluo.THRESHOLD_COMPUTATION.ROOT_TRACK.toString(), foreThresholder).setActionParameters(BacteriaFluo.THRESHOLD_COMPUTATION.PARENT_TRACK.toString(), foreThresholder).setHint("Threshold for foreground region selection after watershed partitioning on edge map. All regions with median value over this value are considered foreground. <br />If <em>CURRENT_FRAME</em> is selected, threshold will be computed at each frame. If <em>PARENT_BRANCH</em> is selected, threshold will be computed on the whole parent track. If <em>ROOT_TRACK</em> is selected, threshold will be computed on the whole root track, on raw images (no prefilters).<br />Configuration Hint: value is displayed on right click menu: <em>display thresholds</em> command. Tune the value using intermediate image <em>Region Values after partitioning</em>, only foreground partitions should be over this value");

    public BacteriaFluoHessian() {
        super();
        this.hessianScale.setValue(2);
        this.mergeThreshold.setValue(0.25);
        this.splitThreshold.setValue(0.15);
        this.localThresholdFactor.setValue(0.5);
        this.splitMethod.setSelectedEnum(SPLIT_METHOD.HESSIAN);
    }

    final private String hint = "<b>Bacteria segmentation within microchannels</b><br />"  + this.operationSequenceHint;
    @Override public String getHintText() {return hint;}

    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{vcThldForVoidMC, hessianScale, mergeThreshold, edgeMap, foreThldCond, splitThreshold, contourAdjustmentCond};
    }

    @Override
    public RegionPopulation runSegmenter(Image input, int objectClassIdx, SegmentedObject parent) {
        if (isVoid) return null;
        if (Double.isNaN(filterThld)) filterThld = foreThresholderFrame.instanciatePlugin().runSimpleThresholder(input, parent.getMask());
        return super.runSegmenter(input, objectClassIdx, parent);
    }

    @Override
    void setInterfaceValue(Image input, SplitAndMergeHessian sam) {
        // default value is used
    }


    // apply to segmenter from whole track information (will be set prior to call any other methods)

    @Override
    public TrackConfigurable.TrackConfigurer<BacteriaFluoHessian> run(int structureIdx, List<SegmentedObject> parentTrack) {
        if (parentTrack.get(0).getRawImage(structureIdx)==parentTrack.get(0).getPreFilteredImage(structureIdx)) { // no prefilter -> perform on root
            logger.debug("no prefilters detected: global mean & sigma on root track");
            double[] ms = getRootBckMeanAndSigma(parentTrack, structureIdx, null);
            this.globalBackgroundLevel = ms[0];
            this.globalBackgroundSigma = ms[1];
        } else { // prefilters -> perform on parent track
            logger.debug("prefilters detected: global mean & sigma on parent track");
            Map<Image, ImageMask> imageMapMask = parentTrack.stream().collect(Collectors.toMap(p->p.getPreFilteredImage(structureIdx), p->p.getMask() ));
            // Background fit on parent track doesn't necessarily
            /*
            Histogram histo = HistogramFactory.getHistogram(()->Image.stream(imageMapMask, true).parallel(), HistogramFactory.BIN_SIZE_METHOD.BACKGROUND);
            double[] ms = new double[2];
            BackgroundFit.backgroundFit(histo, 5, ms);
            this.globalBackgroundLevel = ms[0];
            this.globalBackgroundSigma = ms[1];
            */
            DoubleStream pixStream = Image.stream(imageMapMask, true);
            this.globalBackgroundLevel = pixStream.min().orElse(0);
            this.globalBackgroundSigma = 1; // TODO check this case
        }
        logger.debug("global background: {} global sigma: {}", globalBackgroundLevel, globalBackgroundSigma);
        Set<SegmentedObject> voidMC = getVoidMicrochannels(structureIdx, parentTrack);
        double thld = getTrackThresholds(parentTrack, structureIdx, voidMC);
        return (p, s) -> {
            if (voidMC.contains(p)) s.isVoid=true;
            s.filterThld = thld;
            s.globalBackgroundLevel = globalBackgroundLevel;
            s.globalBackgroundSigma = globalBackgroundSigma;
        };
    }

    protected double getTrackThresholds(List<SegmentedObject> parentTrack, int structureIdx, Set<SegmentedObject> voidMC) {
        if (voidMC.size()==parentTrack.size()) return Double.POSITIVE_INFINITY;
        Histogram[] histoRoot=new Histogram[1], histoParent=new Histogram[1];
        Supplier<Histogram> getHistoParent = () -> {
            if (histoParent[0]==null) {
                Map<Image, ImageMask> imageMapMask = parentTrack.stream().filter(p->!voidMC.contains(p)).collect(Collectors.toMap(p->p.getPreFilteredImage(structureIdx), p->p.getMask() ));
                histoParent[0] = HistogramFactory.getHistogram(()->Image.stream(imageMapMask, true).parallel(), HistogramFactory.BIN_SIZE_METHOD.AUTO_WITH_LIMITS);
            }
            return histoParent[0];
        };
        boolean needToComputeGlobalMax = this.foreThresholdMethod.getSelectedIndex()>0;
        if (!needToComputeGlobalMax) return Double.NaN;
        double foreThld;
        ThresholderHisto thlder = foreThresholder.instanciatePlugin();
        switch (foreThresholdMethod.getSelectedEnum()) {
            case ROOT_TRACK:
                if (thlder instanceof BackgroundFit) {
                    double[] ms = getRootBckMeanAndSigma(parentTrack, structureIdx, histoRoot);
                    foreThld = ms[0] + ((BackgroundFit)thlder).getSigmaFactor() * ms[1];
                } else foreThld = getRootThreshold(parentTrack, structureIdx, histoRoot);
            break;
            case PARENT_TRACK:
                foreThld = thlder.runThresholderHisto(getHistoParent.get());
            break;
            default:
                foreThld = Double.NaN;
        }
        logger.debug("parent: {} global threshold on images with foreground: [{}]", parentTrack.get(0), foreThld);
        return foreThld;
    }

    @Override
    protected double getGlobalThreshold(List<SegmentedObject> parent, int structureIdx) {
        return globalBackgroundLevel + 5 * globalBackgroundSigma; // TODO when prefilters are present -> sigma not set
    }
    private double getRootThreshold(List<SegmentedObject> parents, int structureIdx, Histogram[] histoStore) {
        // particular case si BackgroundFit -> call
        String key =  foreThresholder.toJSONEntry().toJSONString()+"_"+structureIdx;
        if (parents.get(0).getRoot().getAttributes().containsKey(key)) {
            return parents.get(0).getRoot().getAttribute(key, Double.NaN);
        } else {
            synchronized(parents.get(0).getRoot()) {
                if (parents.get(0).getRoot().getAttributes().containsKey(key)) {
                    return parents.get(0).getRoot().getAttribute(key, Double.NaN);
                } else {
                    List<Image> im = parents.stream().map(p->p.getRoot()).map(p->p.getRawImage(structureIdx)).collect(Collectors.toList());
                    ThresholderHisto thlder = foreThresholder.instanciatePlugin();
                    Histogram histo;
                    if (histoStore!=null && histoStore[0]!=null ) histo = histoStore[0];
                    else {
                        histo = HistogramFactory.getHistogram(()->Image.stream(im).parallel(), HistogramFactory.BIN_SIZE_METHOD.AUTO_WITH_LIMITS) ;
                        if (histoStore!=null) histoStore[0] = histo;
                    }
                    double thld = thlder.runThresholderHisto(histo);
                    parents.get(0).getRoot().setAttribute(key, thld);
                    logger.debug("computing thld: {} on root: {} -> {}", key, parents.get(0).getRoot(), thld);
                    return thld;
                }
            }
        }
    }

    private static double[] getRootBckMeanAndSigma(List<SegmentedObject> parents, int structureIdx, Histogram[] histoStore) {
        String meanK = "backgroundMean_"+structureIdx;
        String stdK = "backgroundStd_"+structureIdx;
        if (parents.get(0).getRoot().getAttributes().containsKey(meanK)) {
            logger.debug("found on root {} mean {}, sigma: {}", parents.get(0), parents.get(0).getRoot().getAttribute(meanK, 0d),parents.get(0).getRoot().getAttribute(stdK, 1d));
            return new double[]{parents.get(0).getRoot().getAttribute(meanK, 0d), parents.get(0).getRoot().getAttribute(stdK, 1d)};
        } else {
            synchronized(parents.get(0).getRoot()) {
                if (parents.get(0).getRoot().getAttributes().containsKey(meanK)) {
                    return new double[]{parents.get(0).getRoot().getAttribute(meanK, 0d), parents.get(0).getRoot().getAttribute(stdK, 1d)};
                } else {
                    Histogram histo;
                    if (histoStore!=null && histoStore[0]!=null) histo = histoStore[0];
                    else {
                        List<Image> im = parents.stream().map(p->p.getRoot()).map(p->p.getRawImage(structureIdx)).collect(Collectors.toList());
                        histo = HistogramFactory.getHistogram(()->Image.stream(im).parallel(), HistogramFactory.BIN_SIZE_METHOD.BACKGROUND);
                        if (histoStore!=null) histoStore[0] = histo;
                    }
                    double[] ms = new double[2];
                    BackgroundFit.backgroundFit(histo, 10, ms);
                    parents.get(0).getRoot().setAttribute(meanK, ms[0]);
                    parents.get(0).getRoot().setAttribute(stdK, ms[1]);
                    logger.debug("compute root {} mean {}, sigma: {}", parents.get(0), ms[0], ms[1]);
                    return ms;
                }
            }
        }
    }
}
