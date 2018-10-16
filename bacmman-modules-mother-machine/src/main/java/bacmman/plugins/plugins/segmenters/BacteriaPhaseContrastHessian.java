package bacmman.plugins.plugins.segmenters;

import bacmman.configuration.parameters.*;
import bacmman.data_structure.Region;
import bacmman.data_structure.RegionPopulation;
import bacmman.data_structure.SegmentedObject;
import bacmman.data_structure.Voxel;
import bacmman.image.Histogram;
import bacmman.image.HistogramFactory;
import bacmman.image.Image;
import bacmman.image.ImageMask;
import bacmman.plugins.Hint;
import bacmman.processing.split_merge.SplitAndMergeHessian;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class BacteriaPhaseContrastHessian extends BacteriaHessian<BacteriaPhaseContrastHessian> implements Hint {

    BooleanParameter upperCellCorrection = new BooleanParameter("Upper Cell Correction", false).setHint("If true: when the upper cell is touching the top of the microchannel, a different local threshold factor is applied to the upper half of the cell");
    NumberParameter upperCellLocalThresholdFactor = new BoundedNumberParameter("Upper cell local threshold factor", 2, 2, 0, null).setHint("Local Threshold factor applied to the upper part of the cell");
    NumberParameter maxYCoordinate = new BoundedNumberParameter("Max yMin coordinate of upper cell", 0, 5, 0, null);
    ConditionalParameter cond = new ConditionalParameter(upperCellCorrection).setActionParameters("true", upperCellLocalThresholdFactor, maxYCoordinate);

    BooleanParameter upperCellCorrectionR = new BooleanParameter("Upper Cell Correction", false).setHint("If true: when the upper cell is touching the top of the microchannel, a different local threshold factor is applied to the upper half of the cell");
    NumberParameter upperCellLocalThresholdFactorR = new BoundedNumberParameter("Upper cell local threshold factor", 2, 2, 0, null).setHint("Local Threshold factor applied to the upper part of the cell");
    NumberParameter maxYCoordinateR = new BoundedNumberParameter("Max yMin coordinate of upper cell", 0, 5, 0, null);
    ConditionalParameter condR = new ConditionalParameter(upperCellCorrectionR).setActionParameters("true", upperCellLocalThresholdFactorR, maxYCoordinateR);


    protected NumberParameter localThresholdFactorRaw = new BoundedNumberParameter("Local Threshold Factor (on raw image)", 2, 2, 0, null).setEmphasized(true).setHint(localThresholdFactor.getHintText());
    EnumChoiceParameter<CONTOUR_ADJUSTMENT_METHOD> contourAdjustmentMethodRaw = new EnumChoiceParameter<>("Contour Adjustment (on raw image)", CONTOUR_ADJUSTMENT_METHOD.values(), null, true).setHint("Method for contour adjustment, performed on raw input image");
    ConditionalParameter contourAdjustmentCondRaw = new ConditionalParameter(contourAdjustmentMethodRaw).setActionParameters(CONTOUR_ADJUSTMENT_METHOD.LOCAL_THLD_W_EDGE.toString(), localThresholdFactorRaw, condR);


    public BacteriaPhaseContrastHessian() {
        super();
        //this.mergeThreshold.setHint(mergeThreshold.getHintText().replace("mean(intensity) @ interface(A-B)", "mean(intensity) in A and B"));
        //this.splitThreshold.setHint(splitThreshold.getHintText().replace("mean(intensity) @ interface(A-B)", "mean(intensity) in A and B"));
        contourAdjustmentCond.setActionParameters(CONTOUR_ADJUSTMENT_METHOD.LOCAL_THLD_W_EDGE.toString(), localThresholdFactor, cond);
        this.localThresholdFactor.setValue(0.75);
        this.contourAdjustmentMethodRaw.setSelectedEnum(CONTOUR_ADJUSTMENT_METHOD.LOCAL_THLD_W_EDGE);
    }

    final private String hint = "<b>Bacteria segmentation within microchannels</b><br />"
            + "This algorithm is designed to work on inverted (foreground is bright) and normalized phase-contrast images, filtered with the Track-pre-filter: \"SubtractBackgroundMicrochannels\"<br />"
            +operationSequenceHint;

    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{vcThldForVoidMC, hessianScale, mergeThreshold, edgeMap, foreThresholder, splitThreshold, contourAdjustmentCond, contourAdjustmentCondRaw, splitMethod};
    }

    @Override public String getHintText() {return hint;}

    @Override boolean rawHasDarkBackground() {return false;}

    @Override
    void setInterfaceValue(Image input, SplitAndMergeHessian sam) {
        /*sam.setInterfaceValue(i-> {
            Collection<Voxel> voxels = i.getVoxels();
            if (voxels.isEmpty()) return Double.NaN;
            else {
                Image hessian = sam.getHessian();
                double val  =  voxels.stream().mapToDouble(v->hessian.getPixel(v.x, v.y, v.z)).average().getAsDouble();
                double mean = Stream.concat(i.getE1().getVoxels().stream(), i.getE2().getVoxels().stream()).mapToDouble(v->(double)input.getPixel(v.x, v.y, v.z)).average().getAsDouble();
                val/=mean;
                return val;
            }
        });*/
    }

    @Override
    protected RegionPopulation localThreshold(RegionPopulation pop, Image edgeMap, Image intensity, Image rawIntensity, ImageMask mask) {
        if (pop.getRegions().isEmpty()) return pop;
        switch(contourAdjustmentMethod.getSelectedEnum()) {
            case LOCAL_THLD_W_EDGE:
                pop = localThresholdWEdge(pop, edgeMap, intensity, mask, true, false);
                break;
            default:
        }
        switch(contourAdjustmentMethodRaw.getSelectedEnum()) {
            case LOCAL_THLD_W_EDGE:
                pop = localThresholdWEdge(pop, edgeMap, rawIntensity, mask, false, true);
                break;
            default:
        }
        return pop;
    }

    private RegionPopulation localThresholdWEdge(RegionPopulation pop, Image edgeMap, Image intensity, ImageMask mask, boolean darkBck, boolean raw) {
        // different local threshold for middle part of upper cell when touches borders
        boolean differentialLF = false;
        if (raw? upperCellCorrectionR.getSelected() : upperCellCorrection.getSelected()) {
            Region upperCell = pop.getRegions().stream().min(Comparator.comparingInt(r -> r.getBounds().yMin())).get();
            if (upperCell.getBounds().yMin() <= (raw ? maxYCoordinateR.getValue().intValue() : maxYCoordinate.getValue().intValue())) {
                differentialLF = true;
                double yLim = upperCell.getGeomCenter(false).get(1) + upperCell.getBounds().sizeY() / 3.0;
                pop.localThresholdEdges(intensity, edgeMap, raw ? localThresholdFactorRaw.getValue().doubleValue() : localThresholdFactor.getValue().doubleValue(), darkBck, false, 0, mask, v -> v.y < yLim); // local threshold for lower cells & half lower part of cell
                if (stores != null) { //|| (callFromSplit && splitVerbose)
                    logger.debug("y lim: {}", yLim);
                }
                pop.localThresholdEdges(intensity, edgeMap, raw ? upperCellLocalThresholdFactorR.getValue().doubleValue() : upperCellLocalThresholdFactor.getValue().doubleValue(), darkBck, false, 0, mask, v -> v.y > yLim); // local threshold for half upper part of 1st cell
            }
        }
        if (!differentialLF) pop.localThresholdEdges(intensity, edgeMap, raw ? localThresholdFactorRaw.getValue().doubleValue() : localThresholdFactor.getValue().doubleValue(), darkBck, false, 0, mask, null);
        pop.smoothRegions(2, true, mask);
        return pop;
    }

    // track parametrization
    @Override
    public TrackConfigurer<BacteriaHessian> run(int structureIdx, List<SegmentedObject> parentTrack) {
        Set<SegmentedObject> voidMC = getVoidMicrochannels(structureIdx, parentTrack);
        double[] thlds = getTrackThresholds(parentTrack, structureIdx, voidMC);
        logger.debug("Threshold: {}", thlds[1]);
        return (p, s) -> {
            if (voidMC.contains(p)) s.isVoid=true;
            s.globalBackgroundLevel = 0; // was 0. used in SplitAndMergeHessian -> should be minimal value
            s.filterThld = thlds[1];
        };
    }

    protected double[] getTrackThresholds(List<SegmentedObject> parentTrack, int structureIdx, Set<SegmentedObject> voidMC) {
        if (voidMC.size()==parentTrack.size()) return new double[]{Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY};
        // 1) get global otsu thld for images with foreground
        //double globalThld = getGlobalOtsuThreshold(parentTrack.stream().filter(p->!voidMC.contains(p)), structureIdx);
        Map<Image, ImageMask> imageMapMask = parentTrack.stream().collect(Collectors.toMap(p->p.getPreFilteredImage(structureIdx), p->p.getMask() ));
        Histogram histo = HistogramFactory.getHistogram(()->Image.stream(imageMapMask, true).parallel(), HistogramFactory.BIN_SIZE_METHOD.AUTO_WITH_LIMITS);
        double globalThld = this.foreThresholder.instanciatePlugin().runThresholderHisto(histo);
        //estimate a minimal threshold : middle point between mean value under global threshold and global threshold
        double mean = histo.getValueFromIdx(histo.getMeanIdx(0, (int)histo.getIdxFromValue(globalThld)));
        double minThreshold = (mean+globalThld)/2.0;
        double meanUp = histo.getValueFromIdx(histo.getMeanIdx((int)histo.getIdxFromValue(globalThld), histo.data.length));
        double maxThreshold = (meanUp+globalThld)/2.0;
        logger.debug("bacteria phase segmentation: {} global threshold on images with forground: global thld: {}, thresholds: [{};{}]", parentTrack.get(0), globalThld, minThreshold, maxThreshold);
        return new double[]{minThreshold, globalThld, maxThreshold};
    }
    @Override
    protected double getGlobalThreshold(List<SegmentedObject> parent, int structureIdx) {
        return getGlobalOtsuThreshold(parent.stream(), structureIdx);
    }
}
