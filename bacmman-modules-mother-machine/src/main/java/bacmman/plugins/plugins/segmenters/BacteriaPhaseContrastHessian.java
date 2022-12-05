package bacmman.plugins.plugins.segmenters;

import bacmman.configuration.parameters.*;
import bacmman.data_structure.RegionPopulation;
import bacmman.data_structure.SegmentedObject;
import bacmman.data_structure.Voxel;
import bacmman.image.Histogram;
import bacmman.image.HistogramFactory;
import bacmman.image.Image;
import bacmman.image.ImageMask;
import bacmman.plugins.DevPlugin;
import bacmman.plugins.Hint;
import bacmman.plugins.ProcessingPipeline;
import bacmman.plugins.TestableProcessingPlugin;
import bacmman.processing.ImageFeatures;
import bacmman.processing.split_merge.SplitAndMergeHessian;

import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.DoubleStream;
import java.util.stream.Stream;

public class BacteriaPhaseContrastHessian extends BacteriaHessian<BacteriaPhaseContrastHessian> implements Hint, DevPlugin {

    BooleanParameter upperCellCorrectionR = new BooleanParameter("Upper Cell Correction", false).setHint("If true: when the upper cell is touching the top of the microchannel, a different local threshold factor is applied to the upper half of the cell");
    NumberParameter upperCellLocalThresholdFactorR = new BoundedNumberParameter("Upper cell local threshold factor", 2, 2, 0, null).setHint("Local Threshold factor applied to the upper part of the cell");
    NumberParameter maxYCoordinateR = new BoundedNumberParameter("Max yMin coordinate of upper cell", 0, 5, 0, null);
    ConditionalParameter<Boolean> condR = new ConditionalParameter<>(upperCellCorrectionR).setActionParameters(true, upperCellLocalThresholdFactorR, maxYCoordinateR);
    protected NumberParameter localThresholdFactorRaw = new BoundedNumberParameter("Local Threshold Factor (on raw image)", 2, 2, 0, null).setEmphasized(true).setHint(localThresholdFactor.getHintText());
    EnumChoiceParameter<CONTOUR_ADJUSTMENT_METHOD> contourAdjustmentMethodRaw = new EnumChoiceParameter<>("Contour Adjustment (on raw image)", CONTOUR_ADJUSTMENT_METHOD.values(), null).setHint("Method for contour adjustment, performed on raw input image");
    ConditionalParameter<CONTOUR_ADJUSTMENT_METHOD> contourAdjustmentCondRaw = new ConditionalParameter<>(contourAdjustmentMethodRaw).setActionParameters(CONTOUR_ADJUSTMENT_METHOD.LOCAL_THLD_W_EDGE, localThresholdFactorRaw, condR);

    public BacteriaPhaseContrastHessian() {
        super();
        //this.mergeThreshold.setHint(mergeThreshold.getHintText().replace("mean(intensity) @ interface(A-B)", "mean(intensity) in A and B"));
        //this.splitThreshold.setHint(splitThreshold.getHintText().replace("mean(intensity) @ interface(A-B)", "mean(intensity) in A and B"));
        this.localThresholdFactor.setValue(0.75);
        this.contourAdjustmentMethodRaw.setSelectedEnum(CONTOUR_ADJUSTMENT_METHOD.LOCAL_THLD_W_EDGE);
    }

    final private String hint = "<b>Bacteria segmentation within microchannels</b><br />"
            + "This algorithm is designed to work on inverted (foreground is bright) and normalized phase-contrast images, filtered with the Track-pre-filter: \"SubtractBackgroundMicrochannels\"<br />"
            +operationSequenceHint;

    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{vcThldForVoidMC, hessianScale, mergeThreshold, edgeMap, foreThresholder, splitThreshold, contourAdjustmentCondHess, contourAdjustmentCond, contourAdjustmentCondRaw, splitMethod};
    }

    @Override public String getHintText() {return hint;}

    @Override boolean rawHasDarkBackground() {return false;}

    @Override
    void setInterfaceValue(Image input, SplitAndMergeHessian sam) {
        sam.setInterfaceValue(i-> {
            Collection<Voxel> voxels = i.getVoxels();
            if (voxels.isEmpty()) return Double.NaN;
            else {
                Image hessian = sam.getHessian();
                double val  =  voxels.stream().mapToDouble(v->hessian.getPixel(v.x, v.y, v.z)).average().getAsDouble();
                double mean = DoubleStream.concat(i.getE1().streamValues(input), i.getE2().streamValues(input)).average().getAsDouble();
                val/=mean;
                return val;
            }
        });
    }


    protected void localThreshold(RegionPopulation pop, SegmentedObject parent, int objectClassIdx) {
        if (pop.getRegions().isEmpty()) return;
        Image smooth = ImageFeatures.gaussianSmooth(parent.getPreFilteredImage(objectClassIdx), 2, false);
        switch(contourAdjustmentMethodHess.getSelectedEnum()) {
            case LOCAL_THLD_W_EDGE:
                localThresholdWEdge(pop, splitAndMerge.getWatershedMap(), smooth, parent.getMask(), true, this.upperCellCorrectionHess.getSelected(), maxYCoordinateHess.getValue().intValue(), this.localThresholdFactorHess.getValue().doubleValue(), upperCellLocalThresholdFactorHess.getValue().doubleValue());
                break;
            default:
        }
        if (contourAdjustmentMethod.getSelectedIndex()<0 && contourAdjustmentMethodRaw.getSelectedIndex()<0) return;

        Image edges = this.edgeMap.filter(parent.getPreFilteredImage(objectClassIdx), parent.getMask());
        switch(contourAdjustmentMethod.getSelectedEnum()) {
            case LOCAL_THLD_W_EDGE:
                localThresholdWEdge(pop, edges, smooth, parent.getMask(), true, this.upperCellCorrection.getSelected(), maxYCoordinate.getValue().intValue(), this.localThresholdFactor.getValue().doubleValue(), upperCellLocalThresholdFactor.getValue().doubleValue());
                break;
            default:
        }
        switch(contourAdjustmentMethodRaw.getSelectedEnum()) {
            case LOCAL_THLD_W_EDGE:
                localThresholdWEdge(pop, edges, parent.getRawImage(objectClassIdx), parent.getMask(), false, this.upperCellCorrectionR.getSelected(), maxYCoordinateR.getValue().intValue(), this.localThresholdFactorRaw.getValue().doubleValue(), upperCellLocalThresholdFactorR.getValue().doubleValue());
                break;
            default:
        }

        if (stores != null) {
            Consumer<Image> imageDisp = TestableProcessingPlugin.getAddTestImageConsumer(stores, parent);
            imageDisp.accept(EdgeDetector.generateRegionValueMap(pop, parent.getPreFilteredImage(objectClassIdx)).setName("Region Values after local threshold: prefiltered images"));
            imageDisp.accept(edges.setName("Edge map for local threshold"));
        }
    }


    // track parametrization
    @Override
    public TrackConfigurer<BacteriaPhaseContrastHessian> run(int structureIdx, List<SegmentedObject> parentTrack) {
        Set<SegmentedObject> voidMC = getVoidMicrochannels(structureIdx, parentTrack);
        double[] thlds = getTrackThresholds(parentTrack, structureIdx, voidMC);
        logger.debug("Threshold: {}", thlds[1]);
        return (p, s) -> {
            if (voidMC.contains(p)) s.isVoid=true;
            s.globalBackgroundLevel = 0; // was 0. used in SplitAndMergeHessian -> should be minimal value
            s.filterThld = thlds[1];
        };
    }

    @Override
    public ProcessingPipeline.PARENT_TRACK_MODE parentTrackMode() {
        return ProcessingPipeline.PARENT_TRACK_MODE.WHOLE_PARENT_TRACK_ONLY;
    }

    protected double[] getTrackThresholds(List<SegmentedObject> parentTrack, int structureIdx, Set<SegmentedObject> voidMC) {
        if (voidMC.size()==parentTrack.size()) return new double[]{Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY};
        // 1) get global otsu thld for images with foreground
        //double globalThld = getGlobalOtsuThreshold(parentTrack.stream().filter(p->!voidMC.contains(p)), structureIdx);
        Map<Image, ImageMask> imageMapMask = parentTrack.stream().collect(Collectors.toMap(p->p.getPreFilteredImage(structureIdx), p->p.getMask() ));
        Histogram histo = HistogramFactory.getHistogram(()->Image.stream(imageMapMask, true).parallel(), HistogramFactory.BIN_SIZE_METHOD.AUTO_WITH_LIMITS);
        double globalThld = this.foreThresholder.instantiatePlugin().runThresholderHisto(histo);
        //estimate a minimal threshold : middle point between mean value under global threshold and global threshold
        double mean = histo.getValueFromIdx(histo.getMeanIdx(0, (int)histo.getIdxFromValue(globalThld)));
        double minThreshold = (mean+globalThld)/2.0;
        double meanUp = histo.getValueFromIdx(histo.getMeanIdx((int)histo.getIdxFromValue(globalThld), histo.getData().length));
        double maxThreshold = (meanUp+globalThld)/2.0;
        logger.debug("bacteria phase segmentation: {} global threshold on images with forground: global thld: {}, thresholds: [{};{}]", parentTrack.get(0), globalThld, minThreshold, maxThreshold);
        return new double[]{minThreshold, globalThld, maxThreshold};
    }
    @Override
    protected double getGlobalThreshold(List<SegmentedObject> parent, int structureIdx) {
        return getGlobalOtsuThreshold(parent.stream(), structureIdx);
    }
}
