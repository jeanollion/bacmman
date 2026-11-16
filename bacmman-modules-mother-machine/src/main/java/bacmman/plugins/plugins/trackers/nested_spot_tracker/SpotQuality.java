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
package bacmman.plugins.plugins.trackers.nested_spot_tracker;

import bacmman.configuration.parameters.*;
import bacmman.data_structure.Region;
import bacmman.data_structure.SegmentedObject;
import bacmman.image.Image;
import bacmman.measurement.BasicMeasurements;
import bacmman.image.ImageMask;
import bacmman.image.ImageMask2D;
import bacmman.plugins.plugins.segmenters.BackgroundEstimation;
import bacmman.plugins.plugins.segmenters.SpotSegmenter;
import bacmman.plugins.plugins.segmenters.SpotSegmenter.NORMALIZATION_MODE;
import bacmman.plugins.plugins.segmenters.SpotSegmenter.QUALITY_FORMULA;
import bacmman.utils.HashMapGetCreate;
import bacmman.utils.Utils;
import bacmman.utils.geom.Point;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Spot quality computed by {@link bacmman.plugins.plugins.trackers.NestedSpotTracker}, with the same formula as {@link SpotSegmenter}:
 * value of the gaussian and / or laplacian transforms at the center of the spot (or median over the spot), after normalization of the intensity within the bacterium
 */
public class SpotQuality {
    final static Logger logger = LoggerFactory.getLogger(SpotQuality.class);
    public enum COMPUTE {NEVER, ALWAYS, IF_MISSING}
    public enum VALUE {CENTER, MEDIAN}

    EnumChoiceParameter<COMPUTE> compute = new EnumChoiceParameter<>("Compute Spot Quality", COMPUTE.values(), COMPUTE.IF_MISSING).setLegacyInitializationValue(COMPUTE.NEVER)
            .setHint("Whether the tracker computes the quality of the spots itself, instead of using the quality given by the segmenter. The quality is compared to <em>Spot Quality Threshold</em> to separate reliable (high-quality) spots from faint (low-quality) spots.<ul>" +
                    "<li>"+COMPUTE.NEVER+": the quality given by the segmenter is used. Spots without quality are considered as high-quality spots</li>" +
                    "<li>"+COMPUTE.ALWAYS+": the quality is computed by the tracker for all spots (the quality given by the segmenter is replaced), e.g. to use another quality formula than the segmenter, or a segmenter whose quality is not suited</li>" +
                    "<li>"+COMPUTE.IF_MISSING+": the quality is computed by the tracker only for spots that have no quality (segmenters that do not compute a quality)</li></ul>" +
                    "Only used when segmentation and tracking are run together.<br />Technical: the quality is computed as in <em>SpotSegmenter</em>: value at the center of the spot (or median over the spot, see <em>Spot Value</em>) of the gaussian and / or laplacian transforms of the pre-filtered image of the parent of the bacteria (e.g. microchannel), after normalization of the intensity within the bacterium that contains the spot");
    EnumChoiceParameter<QUALITY_FORMULA> formula = new EnumChoiceParameter<>("Quality Formula", QUALITY_FORMULA.values(), QUALITY_FORMULA.G)
            .setHint("How the quality is computed from the intensity at the center of the spot. G: the spot is reliable if it is bright. L: the spot is reliable if it is bright compared to its close surroundings (contrast). GL: combination of both.<br />Technical: "+SpotSegmenter.QUALITY_FORMULA_HINT);
    BoundedNumberParameter gaussianScale = new BoundedNumberParameter("Smooth Scale", 1, 1, 0, 10)
            .setHint("Reduces the noise of the intensity used for the quality: larger values average the intensity over a larger area. Set it close to the radius of the spots or lower.<br />Scale (in pixels) of the gaussian transform. Below 0.5: no smoothing");
    BoundedNumberParameter laplacianScale = new BoundedNumberParameter("Laplacian Scale", 1, 2, 1, 10)
            .setHint("Size of the spots for which the contrast is measured: set it close to the radius of the spots.<br />Scale (in pixels) of the laplacian transform (laplacian of gaussian)");
    BooleanParameter laplacianPlaneByPlane = new BooleanParameter("Laplacian Plane By Plane", true)
            .setHint("For 3D images: computes the laplacian in each z-plane independently. Relevant when there are few z-planes or when the resolution in z is low");
    FloatParameter scaleZRatio = new FloatParameter("Scale Z ratio", 0)
            .setHint("For 3D images: takes into account the anisotropy for the gaussian and laplacian transforms: scaleZ = scaleXY x ratio.<br />Set 0 to use the image calibration, otherwise set a value lower than 1 when the Z resolution is lower than the XY resolution");
    EnumChoiceParameter<VALUE> value = new EnumChoiceParameter<>("Spot Value", VALUE.values(), VALUE.CENTER)
            .setHint("Where the intensity of the spot is read.<ul><li>"+VALUE.CENTER+": at the center of the spot (as in <em>SpotSegmenter</em>): sensitive to the brightest part of the spot</li><li>"+VALUE.MEDIAN+": median over all the voxels of the spot: less sensitive to noise and to the position of the center, but depends on the contour of the spot (large spots include more of their dim border)</li></ul>Technical: the gaussian and laplacian values are read (center: linear interpolation at the center of the spot), normalized, then combined by the <em>Quality Formula</em>; with "+VALUE.MEDIAN+" the formula is applied to the medians of the gaussian and laplacian values");
    ConditionalParameter<QUALITY_FORMULA> formulaCond = new ConditionalParameter<>(formula)
            .setActionParameters(QUALITY_FORMULA.G, gaussianScale, scaleZRatio)
            .setActionParameters(QUALITY_FORMULA.L, laplacianScale, laplacianPlaneByPlane, scaleZRatio)
            .setActionParameters(QUALITY_FORMULA.GL, gaussianScale, laplacianScale, laplacianPlaneByPlane, scaleZRatio);
    EnumChoiceParameter<NORMALIZATION_MODE> normMode = new EnumChoiceParameter<>("Intensity normalization", new NORMALIZATION_MODE[]{NORMALIZATION_MODE.NO_NORM, NORMALIZATION_MODE.PER_CELL_CENTER, NORMALIZATION_MODE.PER_CELL_CENTER_SCALE}, NORMALIZATION_MODE.PER_CELL_CENTER)
            .setHint("Makes the quality independent of the background fluorescence of each bacterium, so that the same threshold can be used for all bacteria. The <em>Spot Quality Threshold</em> depends on this choice.<br />Let I be the intensity, MEAN and SD the mean and standard deviation of the background of I within the bacterium that contains the spot. NO_NORM: I. PER_CELL_CENTER: I -> I - MEAN. PER_CELL_CENTER_SCALE: I -> (I - MEAN) / SD.<br />MEAN and SD are estimated within the bacterium (see <em>Background Estimation</em>). The normalization is applied to the gaussian and laplacian transforms (as in <em>SpotSegmenter</em>)");
    BackgroundEstimation backgroundEstimation = new BackgroundEstimation();
    ConditionalParameter<NORMALIZATION_MODE> normCond = new ConditionalParameter<>(normMode).setActionParameters(NORMALIZATION_MODE.PER_CELL_CENTER, backgroundEstimation.getParameter()).setActionParameters(NORMALIZATION_MODE.PER_CELL_CENTER_SCALE, backgroundEstimation.getParameter());
    ConditionalParameter<COMPUTE> computeCond = new ConditionalParameter<>(compute)
            .setActionParameters(COMPUTE.ALWAYS, formulaCond, value, normCond)
            .setActionParameters(COMPUTE.IF_MISSING, formulaCond, value, normCond)
            .setLegacyInitializationValue(COMPUTE.NEVER);

    public ConditionalParameter<COMPUTE> getParameter() {
        return computeCond;
    }

    public SpotQuality setCompute(COMPUTE compute) {
        this.compute.setSelectedEnum(compute);
        return this;
    }

    public COMPUTE getCompute() {
        return compute.getSelectedEnum();
    }

    /**
     * Computes the quality of the spots of each parent of {@param parentTrack}, according to the parameters
     * @param parentTrack track of the parents of the spots and the bacteria (e.g. microchannels)
     * @param spotClassIdx object class of the spots
     * @param bacteria returns the bacterium that contains the spot (can return null)
     * @return number of spots whose quality was computed
     */
    public int computeQuality(List<SegmentedObject> parentTrack, int spotClassIdx, Function<SegmentedObject, SegmentedObject> bacteria) {
        COMPUTE c = compute.getSelectedEnum();
        if (COMPUTE.NEVER.equals(c)) return 0;
        boolean all = COMPUTE.ALWAYS.equals(c);
        return Utils.parallel(parentTrack.stream(), true).mapToInt(p -> {
            List<SegmentedObject> spots = p.getChildren(spotClassIdx).filter(s -> all || Double.isNaN(s.getRegion().getQuality())).collect(Collectors.toList());
            if (spots.isEmpty()) return 0;
            computeQuality(p, spotClassIdx, spots, bacteria);
            return spots.size();
        }).sum();
    }

    private void computeQuality(SegmentedObject parent, int spotClassIdx, List<SegmentedObject> spots, Function<SegmentedObject, SegmentedObject> bacteria) {
        QUALITY_FORMULA f = formula.getSelectedEnum();
        boolean useG = !QUALITY_FORMULA.L.equals(f), useL = !QUALITY_FORMULA.G.equals(f);
        double scaleZRatio = this.scaleZRatio.getDoubleValue();
        Image input = parent.getPreFilteredImage(spotClassIdx); // raw image if no pre-filtered image
        double gScale = gaussianScale.getDoubleValue();
        double lScale = laplacianScale.getDoubleValue();
        Image gauss = useG ? SpotSegmenter.computeGaussian(input, gScale, SpotSegmenter.getScaleZ(gScale, scaleZRatio, input)) : null;
        Image lap = useL ? SpotSegmenter.computeLaplacian(input, lScale, SpotSegmenter.getScaleZ(lScale, scaleZRatio, input), laplacianPlaneByPlane.getSelected()) : null;
        if (gauss!=null) gauss.resetOffset().translate(input); // same landmark as input
        if (lap!=null) lap.resetOffset().translate(input);
        boolean median = VALUE.MEDIAN.equals(value.getSelectedEnum());
        NORMALIZATION_MODE norm = normMode.getSelectedEnum();
        // background mean & sd within each bacterium (or within the parent if the spot is not in a bacterium)
        HashMapGetCreate<SegmentedObject, double[]> meanSigma = HashMapGetCreate.getRedirectedMap(b -> {
            if (NORMALIZATION_MODE.NO_NORM.equals(norm)) return new double[]{0, 1};
            Image crop = b.equals(parent) ? input : input.cropWithOffset(b.getBounds());
            ImageMask mask = b.getMask().sizeZ() != crop.sizeZ() ? new ImageMask2D(b.getMask()) : b.getMask();
            double[] ms = backgroundEstimation.getMeanAndSigma(crop, mask);
            if (NORMALIZATION_MODE.PER_CELL_CENTER.equals(norm)) ms[1] = 1;
            return ms;
        }, HashMapGetCreate.Syncronization.NO_SYNC); // one map per parent: not shared between threads
        for (SegmentedObject s : spots) {
            Region r = s.getRegion();
            SegmentedObject b = bacteria.apply(s);
            if (b == null) b = parent;
            double[] ms = meanSigma.getAndCreateIfNecessary(b);
            double g, l;
            if (median) {
                g = useG ? BasicMeasurements.getQuantileValue(r, gauss, 0.5)[0] : Double.NaN;
                l = useL ? BasicMeasurements.getQuantileValue(r, lap, 0.5)[0] : Double.NaN;
            } else {
                Point c = r.getCenterOrGeomCenter().duplicate();
                if (r.isAbsoluteLandMark()) c.translateRev(input); // relative to input image
                c.ensureWithinBounds(input.getBoundingBox().resetOffset());
                double z = c.numDimensions() > 2 ? c.get(2) : 0;
                g = useG ? gauss.getPixel(c.get(0), c.get(1), z) : Double.NaN;
                l = useL ? lap.getPixel(c.get(0), c.get(1), z) : Double.NaN;
            }
            double G = useG ? (g - ms[0]) * SpotSegmenter.gaussianNormalizationFactor(gScale, ms) : Double.NaN;
            double L = useL ? l / ms[1] : Double.NaN;
            double Q = SpotSegmenter.quality(G, L, f);
            r.setQuality(Q);
            s.setAttribute("Quality", Q);
        }
    }
}
