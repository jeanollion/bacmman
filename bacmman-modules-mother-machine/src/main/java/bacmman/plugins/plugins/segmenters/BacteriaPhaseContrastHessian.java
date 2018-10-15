package bacmman.plugins.plugins.segmenters;

import bacmman.data_structure.SegmentedObject;
import bacmman.data_structure.Voxel;
import bacmman.image.Histogram;
import bacmman.image.HistogramFactory;
import bacmman.image.Image;
import bacmman.image.ImageMask;
import bacmman.plugins.Hint;
import bacmman.processing.split_merge.SplitAndMergeHessian;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class BacteriaPhaseContrastHessian extends BacteriaHessian<BacteriaPhaseContrastHessian> implements Hint {

    final private String hint = "<b>Bacteria segmentation within microchannels</b><br />"
            + "This algorithm is designed to work on inverted (foreground is bright) and normalized phase-contrast images, filtered with the Track-pre-filter: \"SubtractBackgroundMicrochannels\"<br />"
            + "<ol><li>Partition of the whole microchannel using seeded watershed algorithm on maximal hessian eignvalue transform</li>"
            +"<li>Merging of partition using a criterion on hessian value at interface see hint of parameter <em>Merge Threshold</em></li>"
            +"<li>Local Threshold of regions to fit contour on <em>Edge Map<em>, see hint of <em>Local Threshold factor</em> for details</li>"
            +"<li>Region of intensity inferior to <em>Threshold</em> are erased</li>"
            +"<li>Foreground is split by applying a watershed transform on the maximal hessian Eigen value, regions are then merged, using a criterion described in <em>Split Threshold</em> parameter</li>"
            +"</ol>";

    @Override public String getHintText() {return hint;}

    @Override
    void setInterfaceValue(Image input, SplitAndMergeHessian sam) {
        sam.setInterfaceValue(i-> {
            Collection<Voxel> voxels = i.getVoxels();
            if (voxels.isEmpty()) return Double.NaN;
            else {
                Image hessian = sam.getHessian();
                double val  =  voxels.stream().mapToDouble(v->hessian.getPixel(v.x, v.y, v.z)).average().getAsDouble();
                // normalize using mean value (compare with max of mean or max of median
                double mean = Stream.concat(i.getE1().getVoxels().stream(), i.getE2().getVoxels().stream()).mapToDouble(v->(double)input.getPixel(v.x, v.y, v.z)).average().getAsDouble();
                val/=mean;
                return val;
            }
        });
    }
    // track parametrization
    @Override
    public TrackConfigurer<BacteriaHessian> run(int structureIdx, List<SegmentedObject> parentTrack) {
        Set<SegmentedObject> voidMC = getVoidMicrochannels(structureIdx, parentTrack);
        double[] thlds = getTrackThresholds(parentTrack, structureIdx, voidMC);
        return (p, s) -> {
            if (voidMC.contains(p)) s.isVoid=true;
            s.globalBackgroundLevel = 0; // was 0. use in SplitAndMergeHessian -> should be minimal value
            s.filterThld = thlds[1]; // was mean over otsu
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
