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
package bacmman.plugins.plugins.segmenters;

import bacmman.configuration.parameters.*;
import bacmman.core.Core;
import bacmman.image.*;
import bacmman.plugins.*;
import bacmman.plugins.plugins.measurements.objectFeatures.object_feature.SNR;
import bacmman.processing.*;
import bacmman.processing.neighborhood.ConicalNeighborhood;
import bacmman.processing.neighborhood.CylindricalNeighborhood;
import bacmman.processing.neighborhood.Neighborhood;
import bacmman.processing.watershed.MultiScaleWatershedTransform;
import bacmman.processing.watershed.WatershedTransform;
import bacmman.plugins.plugins.manual_segmentation.WatershedObjectSplitter;
import bacmman.plugins.plugins.thresholders.BackgroundThresholder;
import bacmman.utils.Utils;
import bacmman.utils.geom.Point;
import bacmman.data_structure.Region;
import bacmman.data_structure.RegionPopulation;
import bacmman.data_structure.SegmentedObject;

import bacmman.image.MutableBoundingBox;
import bacmman.image.Image;
import bacmman.image.ImageByte;
import bacmman.image.ImageFloat;
import bacmman.image.ImageMask;
import bacmman.image.ImageMask2D;
import bacmman.image.SimpleOffset;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static bacmman.processing.watershed.WatershedTransform.watershed;

import java.util.function.ToDoubleFunction;
import java.util.stream.Collectors;
import java.util.stream.DoubleStream;
import java.util.stream.IntStream;

/**
 *
 * @author Jean Ollion
 */
public class SpotSegmenter implements Segmenter, TrackConfigurable<SpotSegmenter>, ManualSegmenter, ObjectSplitter, TestableProcessingPlugin, Hint, HintSimple {
    public static boolean debug = false;
    ArrayNumberParameter scale = new ArrayNumberParameter("Scale", 0, new BoundedNumberParameter("Scale", 1, 2, 1, 10)).setSorted(true).setHint("Scale (in pixels) for Laplacian transform. <br />Configuration hint: determines the <em>Laplacian</em> image displayed in test mode");
    ArrayNumberParameter gaussScale = new ArrayNumberParameter("Smooth Scale", 0, new BoundedNumberParameter("Scale", 1, 1, 0, 10)).setSorted(true).setHint("Scale (in pixels) for gaussian smooth (for a value lower than 0.5 no gaussian smooth)<br />Configuration hint: determines the <em>Gaussian</em> image displayed in test mode");
    NumberParameter minSpotSize = new BoundedNumberParameter("Min. Spot Size", 0, 5, 1, null).setHint("Spots under this size (in voxel number) will be removed");
    NumberParameter laplacianThld = new NumberParameter<>("Seed Laplacian Threshold", 2, 2.15).setEmphasized(true).setHint("Laplacian threshold for selection of watershed seeds.<br />Higher values tend to increase false negative detections and decrease false positive detection.<br /> Configuration hint: refer to the <em>Laplacian</em> image displayed in test mode"); // was 2.25
    NumberParameter propagationThld = new NumberParameter<>("Propagation Threshold", 2, 1.63).setEmphasized(true).setHint("Lower threshold for watershed propagation: watershed propagation stops at this value. <br />Lower value will yield larger spots.<br />Configuration hint: refer to <em>Laplacian</em> image displayed in test mode (or <em>Gaussian</em> if selected as watershed map)");
    NumberParameter gaussianThld = new NumberParameter<>("Seed Threshold", 2, 1.2).setEmphasized(true).setHint("Gaussian threshold for selection of watershed seeds.<br /> Higher values tend to increase false negative detections and decrease false positive detections.<br />Configuration hint: refer to <em>Gaussian</em> image displayed in test mode"); // was 1.6
    enum NORMALIZATION_MODE {NO_NORM, PER_CELL_CENTER_SCALE, PER_CELL_CENTER, PER_FRAME_CENTER}
    EnumChoiceParameter<NORMALIZATION_MODE> normMode = new EnumChoiceParameter<>("Intensity normalization", NORMALIZATION_MODE.values(), NORMALIZATION_MODE.PER_CELL_CENTER).setLegacyInitializationValue(NORMALIZATION_MODE.PER_CELL_CENTER_SCALE).setHint("Normalization of the input intensity, will influence the Threshold values<br /> Let I be the intensity of the signal, MEAN the mean of the background of I and SD the standard deviation of the background of I. Backgroun threshold within the cell is determined by applying BackgroundThresholder to I within the cell. PER_CELL_CENTER_SCALE: (default) I -> (I - MEAN) / SD . PER_CELL_CENTER: I -> I - MEAN");

    enum QUALITY_FORMULA {GL, G, L}
    EnumChoiceParameter<QUALITY_FORMULA> qualityFormula = new EnumChoiceParameter<>("Quality Formula", QUALITY_FORMULA.values(), QUALITY_FORMULA.G).setLegacyInitializationValue(QUALITY_FORMULA.GL).setHint("Formula for quality feature. <br />GL : sqrt(Gaussian x Laplacian). G: Gaussian. L: Laplacian. <br /> Gaussian (resp. Laplacian) correspond to the value of the Gaussian (resp Laplacian) transform at the center of the spot");
    enum WATERSHED_MAP {LAPLACIAN, GAUSSIAN}
    EnumChoiceParameter<WATERSHED_MAP> watershedMap = new EnumChoiceParameter<>("Watershed Map", WATERSHED_MAP.values(), WATERSHED_MAP.LAPLACIAN).setHint("Feature Map to detect seeds and run watershed transform on.");

    boolean planeByPlane = false;
    Parameter[] parameters = new Parameter[]{scale, gaussScale, minSpotSize, laplacianThld, propagationThld, gaussianThld, normMode, qualityFormula, watershedMap};
    ProcessingVariables pv = new ProcessingVariables();
    protected static String toolTipAlgo = "<br /><br /><em>Algorithmic Details</em>:<ul>"
            + "<li>Spots are detected using a seeded watershed algorithm applied on the Laplacian transform.</li> "
            + "<li>Seeds are set on the regional maxima of the Laplacian transform, within the mask of the segmentation parent. Selected seeds have a Laplacian value larger than <em>Seed Laplacian Threshold</em> and a Gaussian value superior to <em>Seed Threshold</em></li>"
            + "<li>If several scales are provided, the Laplacian scale-space will be computed (3D for 2D input, and 4D for 3D input) and the seeds will be 3D/4D local extrema in the scale space in order to determine at the same time their scale and spatial localization</li>"
            + "<li>Watershed propagation is done within the segmentation parent mask until Laplacian values reach the threshold defined in the <em>Propagation Threshold</em> parameter</li>"
            + "<li>A quality parameter defined as √(Laplacian x Gaussian) at the center of the spot is computed (used in <em>NestedSpotTracker</em>)</li></ul>" +
            "<br />In order to increase robustness to variations in the background fluorescence in bacteria, the input image is first normalized by subtracting the mean value and dividing by the standard-deviation value of the background signal within the cell. Laplacian & Gaussian transforms are then computed on the normalized image.";
    protected static String toolTipDispImage = "<br /><br />Images displayed in test mode:" +
            "<ul><li><em>Gaussian</em>: Gaussian transform applied to the normalized input image.<br />This image can be used to tune the <em>Seed Threshold</em> parameter, which should be lower than the intensity of the center of the spots and larger than the background intracellular fluorescence on the <em>Gaussian</em> transformed image.</li>" +
            "<li><em>Laplacian</em>: Laplacian transform applied to the normalized input image.<br />This image can be used to tune the <em>Seed Laplacian Threshold</em> parameter, which should be lower than the intensity of the center of the spots and larger than the background intracellular fluorescence on the <em>Laplacian</em> transformed image.<br />This image can also be used to tune the <em>Propagation Threshold</em> parameter, which value should be lower than the intensity inside the spots and larger than the background intracellular fluorescence on the <em>Laplacian</em> transformed image</li>";
    protected static String toolTipDispImageAdvanced = "<li><em>Seeds</em>: Selected seeds for the seeded-watershed transform</li></ul>";
    protected static String toolTipSimple ="<b>Fluorescence Spot Detection</b>.<br />" +
            "Segments spot-like objects in fluorescence images using a criterion on the Gaussian and the Laplacian transforms. <br />Also check the new version of this module called SpotDetector, based on radial symmetry transform<br />If spot detection is not satisfying try changing the <em>Seed Threshold</em> and/or <em>Seed Laplacian Threshold</em>. If spots are too big or too small, try changing the <em>Propagation Threshold</em> parameter. ";

    // tool tip interface
    @Override
    public String getHintText() {
        return toolTipSimple+toolTipAlgo+toolTipDispImage+toolTipDispImageAdvanced;
    }

    @Override
    public String getSimpleHintText() {
        return toolTipSimple + toolTipDispImage+ "</ul>";
    }

    public SpotSegmenter() {}
    
    public SpotSegmenter(double thresholdSeeds, double thresholdPropagation, double thresholdIntensity) {
        this.gaussianThld.setValue(thresholdIntensity);
        this.laplacianThld.setValue(thresholdSeeds);
        this.propagationThld.setValue(thresholdPropagation);
    }

    boolean parallel; // TODO : implement multithread

    public SpotSegmenter setThresholdSeeds(double threshold) {
        this.laplacianThld.setValue(threshold);
        return this;
    }
    
    public SpotSegmenter setThresholdPropagation(double threshold) {
        //this.thresholdLow.setPlugin(new ConstantValue(threshold));
        this.propagationThld.setValue(threshold);
        return this;
    }
    
    public SpotSegmenter setGaussianThld(double threshold) {
        this.gaussianThld.setValue(threshold);
        return this;
    }
    
    public SpotSegmenter setScale(double... scale) {
        this.scale.setValue(scale);
        return this;
    }
    public double[] getScale() {
        if (noLaplacian()) return new double[0];
        double[] res = scale.getArrayDouble();
        List<Double> res2 = Utils.toList(res);
        Utils.removeDuplicates(res2, true);
        if (res2.size()<res.length) return Utils.toDoubleArray(res2, false);
        else return res;
    }
    public double[] getGaussianScale() {
        if (noGaussian()) return new double[0];
        double[] res = gaussScale.getArrayDouble();
        List<Double> res2 = Utils.toList(res);
        Utils.removeDuplicates(res2, true);
        if (res2.size()<res.length) return Utils.toDoubleArray(res2, false);
        else return res;
    }
    /**
     * See {@link #run(Image, SegmentedObject, double[], double[], int, double, double, double, NORMALIZATION_MODE)}
     * @param input
     * @param objectClassIdx
     * @param parent
     * @return 
     */
    @Override
    public RegionPopulation runSegmenter(Image input, int objectClassIdx, SegmentedObject parent) {
        return run(input, parent, getScale(), getGaussianScale(), minSpotSize.getValue().intValue(), laplacianThld.getValue().doubleValue(), propagationThld.getValue().doubleValue(), gaussianThld.getValue().doubleValue(), normMode.getSelectedEnum());
    }
    // testable
    Map<SegmentedObject, TestDataStore> stores;
    @Override public void setTestDataStore(Map<SegmentedObject, TestDataStore> stores) {
        this.stores=  stores;
    }

    private static class ProcessingVariables {
        Image input;
        ImageFloat[] lap;
        ImageFloat[] gauss;
        boolean lapScaled, gaussScaled;
        double[] ms;
        double[] gaussianScale;
        public void initPV(Image input, ImageMask mask, double[] gaussianScale, NORMALIZATION_MODE normMode) { //, SimpleThresholder thresholder
            this.input=input;
            this.gaussianScale=gaussianScale;
            if (ms == null && (NORMALIZATION_MODE.PER_CELL_CENTER.equals(normMode) || NORMALIZATION_MODE.PER_CELL_CENTER_SCALE.equals(normMode) )) {
                //BackgroundFit.debug=debug;
                ms = new double[2];
                //double thld = thresholder.runSimpleThresholder(input, mask);
                //ms = ImageOperations.getMeanAndSigma(input, mask, d -> d<thld);
                //double thld = BackgroundFit.backgroundFit(HistogramFactory.getHistogram(()->input.stream(mask, true), HistogramFactory.BIN_SIZE_METHOD.AUTO_WITH_LIMITS), 5, ms);
                double thld = BackgroundThresholder.runThresholder(input, mask, 6, 6, 2, Double.MAX_VALUE, ms); // more robust than background fit because too few values to make histogram
                if (debug) logger.debug("scaling thld: {} mean & sigma: {}", thld, ms); //if (debug) 
            }
            switch (normMode) {
                case NO_NORM: {
                    ms = new double[]{0, 1};
                    break;
                }
                case PER_CELL_CENTER: {
                    ms[1] = 1;
                    break;
                }
            }
        }

        protected ImageFloat[] getGaussianMap() {
            if (gauss==null) throw new RuntimeException("Gaussian map not initialized");
            if (!gaussScaled) {
                for (int i = 0; i<gauss.length; ++i) {
                    if (!gauss[i].sameDimensions(input)) gauss[i] = gauss[i].cropWithOffset(input.getBoundingBox()); // map was computed on parent that differs from segmentation parent
                    double mul = gaussianScale[i]<0.5 ? 1 : gaussianScale[i];
                    ImageOperations.affineOperation2WithOffset(gauss[i], gauss[i], mul/ms[1], -ms[0]);
                }
                gaussScaled=true;
            }
            return gauss;
        }
        
        protected ImageFloat[] getLaplacianMap() {
            if (lap==null) throw new RuntimeException("Laplacian map not initialized");
            if (!lapScaled) {
                for (int i = 0; i<lap.length; ++i) {
                    if (!lap[i].sameDimensions(input)) lap[i] = lap[i].cropWithOffset(input.getBoundingBox()); // map was computed on parent that differs from segmentation parent
                    ImageOperations.affineOperation2WithOffset(lap[i], lap[i], 1/ms[1], 0);
                } // no additive coefficient
                lapScaled=true;
            }
            return lap;
        }
    }
    /**
     * Spots are detected using a seeded watershed algorithm in the laplacian transform
     * Input image is scaled by removing the mean value and dividing by the standard-deviation value of the background within the segmentation parent
     * Seeds are set on regional maxima of the laplacian transform, within the mask of {@param parent}, with laplacian value superior to {@param thresholdSeeds} and gaussian value superior to {@param intensityThreshold}
     * If several scales are provided, the laplacian scale space will be computed (3D for 2D input, and 4D for 3D input) and the seeds will be 3D/4D local extrema in the scale space in order to determine at the same time their scale and spatial localization
     * Watershed propagation is done within the mask of {@param parent} until laplacian values reach {@param thresholdPropagation}
     * A quality parameter in computed as √(laplacian x gaussian) at the center of the spot
     * @param input pre-diltered image from wich spots will be detected
     * @param parent segmentation parent
     * @param scale scale for laplacian filtering, corresponds to size of the objects to be detected, if several, objects will be detected in the scale space
     * @param minSpotSize under this size spots will be erased
     * @param laplacianThld minimal laplacian value to segment a spot
     * @param thresholdPropagation laplacian value at the border of spots
     * @param gaussianThreshold minimal gaussian value to semgent a spot
     * @return segmented spots
     */
    public RegionPopulation run(Image input, SegmentedObject parent, double[] scale, double[] gaussScale, int minSpotSize, double laplacianThld, double thresholdPropagation, double gaussianThreshold, NORMALIZATION_MODE normMode) {
        Arrays.sort(scale);
        Arrays.sort(gaussScale);
        ImageMask parentMask = parent.getMask().sizeZ()!=input.sizeZ() ? new ImageMask2D(parent.getMask()) : parent.getMask();
        if (this.parentMapMeanAndSigma !=null) {
            pv.ms = parentMapMeanAndSigma.get(parent.getParent());
            //logger.debug("set mean sd @ frame: {} = {}", parent.getFrame(), this.pv.ms);
        }
        this.pv.initPV(input, parentMask, gaussScale, normMode);
        if (pv.getGaussianMap()==null || pv.getLaplacianMap()==null) throw new RuntimeException("Mutation Segmenter not parametrized");//setMaps(computeMaps(input, input));

        QUALITY_FORMULA qFormula;
        Image[] secondarySPZ, primarySPZ; // in case there are several z
        boolean invertedMaps = false;
        double primaryThld, secondaryThld;
        if (this.watershedMap.getSelectedEnum().equals(WATERSHED_MAP.GAUSSIAN)) {
            secondarySPZ = noLaplacian()? new Image[0] : Image.mergeImagesInZ(Arrays.asList(pv.getLaplacianMap())).toArray(new ImageFloat[0]);
            primarySPZ = Image.mergeImagesInZ(Arrays.asList(pv.getGaussianMap())).toArray(new ImageFloat[0]);
            if (qualityFormula.getSelectedEnum().equals(QUALITY_FORMULA.G)) qFormula = QUALITY_FORMULA.L;
            else if (qualityFormula.getSelectedEnum().equals(QUALITY_FORMULA.L)) qFormula = QUALITY_FORMULA.G;
            else qFormula = QUALITY_FORMULA.GL;
            scale = gaussScale;
            primaryThld = gaussianThreshold;
            secondaryThld = laplacianThld;
            invertedMaps = true;
        } else {
            secondarySPZ = noGaussian() ? new Image[0] : Image.mergeImagesInZ(Arrays.asList(pv.getGaussianMap())).toArray(new ImageFloat[0]);
            primarySPZ = Image.mergeImagesInZ(Arrays.asList(pv.getLaplacianMap())).toArray(new ImageFloat[0]);
            qFormula = qualityFormula.getSelectedEnum();
            secondaryThld = gaussianThreshold;
            primaryThld = laplacianThld;
        }

        double[] radii = new double[scale.length];
        for (int z = 0; z<radii.length; ++z) radii[z] = Math.max(1, scale[z]); //-0.5
        Neighborhood n = radii.length>1 ? ConicalNeighborhood.generateScaleSpaceNeighborhood(radii, false) : new CylindricalNeighborhood(radii[0], 1, false);
        //Neighborhood n = new CylindricalNeighborhood(1.5, lap.length, false);
        //Neighborhood n = new CylindricalNeighborhood(1.5, 1, false);
        
        // 4D local max
        ImageByte[] seedsSPZ = new ImageByte[primarySPZ.length];
        Filters.LocalMax[] lmZ = new Filters.LocalMax[primarySPZ.length];
        for (int z = 0; z<primarySPZ.length; ++z) {
            lmZ[z] = new Filters.LocalMax(new ImageMask2D(parent.getMask(), parent.getMask().sizeZ()!=input.sizeZ()?0:z));
            lmZ[z].setUp(primarySPZ[z], n);
            seedsSPZ[z] = new ImageByte("", primarySPZ[z]);
        }

        for (int zz = 0; zz<primarySPZ.length; ++zz) {
            final int z = zz;
            BoundingBox.loop(primarySPZ[z].getBoundingBox().resetOffset(), (x, y, sp)->{
                double currentValue = primarySPZ[z].getPixel(x, y, sp);
                if (parentMask.insideMask(x, y, z) && currentValue>=primaryThld && (secondarySPZ.length==0 || getGaussianValueMaxZ(secondarySPZ[z], x, y)>=secondaryThld)) { // check pixel is over thresholds
                    if ( (z==0 || (z>0 && seedsSPZ[z-1].getPixel(x, y, sp)==0)) && lmZ[z].hasNoValueOver(currentValue, x, y, sp)) { // check if 1) was not already checked at previous plane [make it not parallelizable] && if is local max on this z plane
                        boolean lm = true;
                        if (z>0) lm = lmZ[z-1].hasNoValueOver(currentValue, x, y, sp); // check if local max on previous z plane
                        if (lm && z<primarySPZ.length-1) lm = lmZ[z+1].hasNoValueOver(currentValue, x, y, sp); // check if local max on next z plane
                        //logger.debug("candidate seed: x:{}, y:{}, z:{},value: {} local max ? {}, no value sup below:{} , no value sup over:{}", x, y, z, currentValue, lm, z>0?lmZ[z-1].hasNoValueOver(currentValue, x, y, sp):true, z<lapSPZ.length-1?lmZ[z+1].hasNoValueOver(currentValue, x, y, sp):true);
                        if (lm) seedsSPZ[z].setPixel(x, y, sp, 1);
                    }
                }
            });
        }
        
        ImageByte[] seedMaps = arrangeSpAndZPlanes(seedsSPZ, planeByPlane).toArray(new ImageByte[0]);
        Image[] wsMap = ((List<Image>)arrangeSpAndZPlanes(primarySPZ, planeByPlane)).toArray(new Image[0]);
        Image[] wsMapGauss = secondarySPZ.length==0 ? new Image[0] : ((List<Image>)arrangeSpAndZPlanes(secondarySPZ, planeByPlane)).toArray(new Image[0]);
        RegionPopulation[] pops =  MultiScaleWatershedTransform.watershed(wsMap, parentMask, seedMaps, true, new MultiScaleWatershedTransform.ThresholdPropagationOnWatershedMap(thresholdPropagation), null);
        //ObjectPopulation pop =  watershed(lap, parent.getMask(), seedPop.getObjects(), true, new ThresholdPropagationOnWatershedMap(thresholdPropagation), new SizeFusionCriterion(minSpotSize), false);
        SubPixelLocalizator.debug=debug;
        for (int i = 0; i<pops.length; ++i) { // TODO in 3D : check if better with Gaussian
            int z = i/scale.length;
            setCenterAndQuality(wsMap[i], wsMapGauss, pops[i], z, qFormula);
            for (Region o : pops[i].getRegions()) {
                if (planeByPlane && primarySPZ.length>1) { // keep track of z coordinate
                    o.setCenter(o.getCenter().duplicate(3)); // adding z dimention
                    o.translate(new SimpleOffset(0, 0, z));
                }  
            }
        }
        RegionPopulation pop = MultiScaleWatershedTransform.combine(pops, input);
        if (stores!=null) {
            logger.debug("Parent: {}: Quality: {}", parent, Utils.toStringList(pop.getRegions(), o->""+o.getQuality()));
            logger.debug("Parent: {}: Center: {}", parent ,Utils.toStringList(pop.getRegions(), o->""+o.getCenter()));
        }
        pop.filter(new RegionPopulation.RemoveFlatObjects(false));
        pop.filter(new RegionPopulation.Size().setMin(minSpotSize));
        if (stores!=null) {
            String name1 = invertedMaps ? "Gaussian" : "Laplacian";
            String name2 = invertedMaps ? "Laplacian" : "Gaussian";
            if (planeByPlane) {
                if (scale.length>1) {
                    for (int z = 0; z<seedsSPZ.length; ++z) {
                        if (stores.get(parent).isExpertMode())stores.get(parent).addIntermediateImage("Seeds: Scale-space z="+z, seedsSPZ[z]);
                        stores.get(parent).addIntermediateImage(name1+": Scale-space z="+z, primarySPZ[z]);
                    }
                } else {
                    if (stores.get(parent).isExpertMode())stores.get(parent).addIntermediateImage("Seeds", Image.mergeZPlanes(seedsSPZ));
                    stores.get(parent).addIntermediateImage(name1, Image.mergeZPlanes(primarySPZ));
                }
                if (gaussScale.length>1 && secondarySPZ.length>0) {
                    for (int z = 0; z<secondarySPZ.length; ++z) {
                        stores.get(parent).addIntermediateImage(name2+": Scale-space z="+z, secondarySPZ[z]);
                    }
                } else {
                    stores.get(parent).addIntermediateImage(name2, Image.mergeZPlanes(secondarySPZ));
                }
            } else {
                if (seedMaps[0].sizeZ()>1) {
                    for (int sp = 0; sp<wsMap.length; ++sp) {
                        if (stores.get(parent).isExpertMode())stores.get(parent).addIntermediateImage("Seeds Scale-space="+sp, seedMaps[sp]);
                        if (wsMap.length>0) stores.get(parent).addIntermediateImage(name1+" Scale-space="+sp, wsMap[sp]);
                    }
                    for (int sp = 0; sp<wsMapGauss.length; ++sp) {
                        stores.get(parent).addIntermediateImage(name2+" Scale-space="+sp, wsMapGauss[sp]);
                    }
                } else {
                    if (stores.get(parent).isExpertMode()) stores.get(parent).addIntermediateImage("Seeds", Image.mergeZPlanes(seedMaps));
                    if (wsMap.length>0) stores.get(parent).addIntermediateImage(name1, Image.mergeZPlanes(wsMap));
                    if (wsMapGauss.length>0) stores.get(parent).addIntermediateImage(name2, Image.mergeZPlanes(wsMapGauss));
                }
            }
        }
        return pop;
    }

    private static double getGaussianValueMaxZ(Image gaussian, int x, int y) {
        if (gaussian.sizeZ()==1) return gaussian.getPixel(x, y, 0);
        return IntStream.range(0, gaussian.sizeZ()).mapToDouble(z -> gaussian.getPixel(x, y, z)).max().getAsDouble();
    }

    private static void setCenterAndQuality(Image lap, Image[] gauss, RegionPopulation pop, int z, QUALITY_FORMULA quality_formula) {
        SubPixelLocalizator.setSubPixelCenter(lap, pop.getRegions(), true); // lap -> better in case of close objects
        for (Region o : pop.getRegions()) { // quality criterion : sqrt (smooth * lap)
            if (o.getQuality()==0 || o.getCenter()==null) o.setCenter( o.getMassCenter(lap, false)); // localizator didnt work -> use center of mass
            o.getCenter().ensureWithinBounds(lap.getBoundingBox().resetOffset());
            double zz = o.getCenter().numDimensions()>2?o.getCenter().get(2):z;
            //logger.debug("size : {} set quality: center: {} : z : {}, bounds: {}, is2D: {}", o.getSize(), o.getCenter(), z, wsMap[i].getBoundingBox().translateToOrigin(), o.is2D());
            double zzz = (zz>lap.sizeZ()-1) ? lap.sizeZ()-1 : zz;
            double L = lap.getPixel(o.getCenter().get(0), o.getCenter().get(1), zzz);
            ToDoubleFunction<Image> getGaussValue = g -> g.getPixel(o.getCenter().get(0), o.getCenter().get(1), zzz);
            double G = gauss.length==1 ? getGaussValue.applyAsDouble(gauss[0]) : Arrays.stream(gauss).mapToDouble(getGaussValue).max().orElse(Double.NaN);
            switch (quality_formula) {
                case GL:
                default:
                    o.setQuality(Math.sqrt(G * L));
                    break;
                case G:
                    o.setQuality(G);
                    break;
                case L:
                    o.setQuality(L);
                    break;
            }

        }
    }

    private static <T extends Image<T>> List<T> arrangeSpAndZPlanes(T[] spZ, boolean ZbyZ) {
        if (ZbyZ) {
            List<T> res = new ArrayList<>(spZ.length * spZ[0].sizeZ());
            for (int z = 0; z<spZ.length; ++z) {
                for (int sp = 0; sp<spZ[z].sizeZ(); ++sp) {
                    res.add(spZ[z].getZPlane(sp));
                }
            }
            return res;
        } else return Image.mergeImagesInZ(Arrays.asList(spZ));
    }
    
    public void printSubLoc(String name, Image locMap, Image smooth, Image lap, RegionPopulation pop, MutableBoundingBox globBound) {
        MutableBoundingBox b = locMap.getBoundingBox().translate(globBound.reverseOffset());
        List<Region> objects = pop.getRegions();
        
        for(Region o : objects) o.setCenter(o.getMassCenter(locMap, false));
        pop.translate(b, false);
        logger.debug("mass center: centers: {}", Utils.toStringList(objects, o -> o.getCenter()+" value: "+o.getQuality()));
        pop.translate(b.duplicate().reverseOffset(), false);
        
        for(Region o : objects) o.setCenter(o.getGeomCenter(false));
        pop.translate(b, false);
        logger.debug("geom center {}: centers: {}", name, Utils.toStringList(objects, o -> o.getCenter()+" value: "+o.getQuality()));
        pop.translate(b.duplicate().reverseOffset(), false);
        
        
        SubPixelLocalizator.setSubPixelCenter(locMap, objects, true);
        pop.translate(b, false);
        logger.debug("locMap: {}, centers: {}", name, Utils.toStringList(objects, o ->  o.getCenter() +" value: "+o.getQuality()));
        pop.translate(b.duplicate().reverseOffset(), false);
        logger.debug("smooth values: {}", Utils.toStringList(objects, o->""+smooth.getPixel(o.getCenter().get(0), o.getCenter().get(1), o.getCenter().getWithDimCheck(2))) );
        logger.debug("lap values: {}", Utils.toStringList(objects, o->""+lap.getPixel(o.getCenter().get(0), o.getCenter().get(1), o.getCenter().getWithDimCheck(2))) );

    }
    @Override
    public Parameter[] getParameters() {
        return parameters;
    }

    protected boolean verboseManualSeg;
    public void setManualSegmentationVerboseMode(boolean verbose) {
        this.verboseManualSeg=verbose;
    }
    
    @Override
    public RegionPopulation manualSegment(Image input, SegmentedObject parent, ImageMask segmentationMask, int objectClassIdx, List<Point> seedsXYZ) {
        this.pv.initPV(input, segmentationMask, getGaussianScale(), this.normMode.getSelectedEnum()) ;
        if (pv.gauss ==null || pv.lap==null) setMaps(computeMaps(input, input));
        else logger.debug("manual seg: maps already set!");
        List<Region> seedObjects = RegionFactory.createSeedObjectsFromSeeds(seedsXYZ, input.sizeZ()==1, input.getScaleXY(), input.getScaleZ());
        Image lap;
        Image[] smooth;
        QUALITY_FORMULA qFormula;
        if (watershedMap.getSelectedEnum().equals(WATERSHED_MAP.GAUSSIAN)) {
            smooth = pv.getLaplacianMap(); // todo max in scale space for each seed?
            lap = pv.getGaussianMap()[0]; // todo max in scale space for each seed?
            if (qualityFormula.getSelectedEnum().equals(QUALITY_FORMULA.G)) qFormula = QUALITY_FORMULA.L;
            else if (qualityFormula.getSelectedEnum().equals(QUALITY_FORMULA.L)) qFormula = QUALITY_FORMULA.G;
            else qFormula = QUALITY_FORMULA.GL;
        } else {
            lap = pv.getLaplacianMap()[0]; // todo max in scale space for each seed?
            smooth = pv.getGaussianMap(); // todo max in scale space for each seed?
            qFormula = qualityFormula.getSelectedEnum();
        }
        WatershedTransform.WatershedConfiguration config = new WatershedTransform.WatershedConfiguration().decreasingPropagation(true).propagationCriterion(new WatershedTransform.ThresholdPropagationOnWatershedMap(this.propagationThld.getValue().doubleValue())).fusionCriterion(new WatershedTransform.SizeFusionCriterion(minSpotSize.getValue().intValue())).lowConectivity(false);
        RegionPopulation pop =  WatershedTransform.watershed(lap, segmentationMask, seedObjects, config);
        setCenterAndQuality(lap, smooth, pop, 0, qFormula);
        if (verboseManualSeg) {
            Image seedMap = new ImageByte("seeds from: "+input.getName(), input);
            for (Point seed : seedsXYZ) seedMap.setPixel(seed.getIntPosition(0), seed.getIntPosition(1), seed.getIntPosition(2), 1);
            Core.showImage(seedMap);
            Core.showImage(lap.setName("Laplacian (watershedMap). Scale: "+scale.getArrayDouble()[0]));
            double[] gaussScale = getGaussianScale();
            for (int i = 0; i<smooth.length; ++i) Core.showImage(smooth[i].setName("Gaussian Scale: "+gaussScale[0]));
            Core.showImage(pop.getLabelMap().setName("segmented from: "+input.getName()));
        }
        return pop;
    }


    @Override
    public RegionPopulation splitObject(Image input, SegmentedObject parent, int structureIdx, Region object) {
        ImageFloat wsMap = this.pv!=null && this.pv.lap!=null ? pv.lap[0] : ImageFeatures.getLaplacian(input, DoubleStream.of(scale.getArrayDouble()).min().orElse(1.5), true, false);
        wsMap = object.isAbsoluteLandMark() ? wsMap.cropWithOffset(object.getBounds()) : wsMap.crop(object.getBounds());
        RegionPopulation res =  WatershedObjectSplitter.splitInTwoSeedSelect(wsMap, object.getMask(), true, false, manualSplitVerbose, parallel);
        res.translate(object.getBounds(), object.isAbsoluteLandMark());
        return res;
    }

    boolean manualSplitVerbose;
    @Override
    public void setSplitVerboseMode(boolean verbose) {
        manualSplitVerbose=verbose;
    }

    // track parametrizable
    /**
     * Compute Maps on parent image 
     * {@link #runSegmenter(Image, int, SegmentedObject) } is supposed to be called from bacteria, thus to avoid border effect gaussian smooth and laplacian transform should be computed on microchannel images
     * @param structureIdx
     * @param parentTrack
     * @return 
     */
    @Override
    public TrackConfigurer<SpotSegmenter> run(int structureIdx, List<SegmentedObject> parentTrack) {
        Map<SegmentedObject, Image[]> parentMapImages = parentTrack.stream().parallel().collect(Collectors.toMap(p->p, p->computeMaps(p.getRawImage(structureIdx), p.getPreFilteredImage(structureIdx))));
        int segParent = parentTrack.iterator().next().getExperimentStructure().getSegmentationParentObjectClassIdx(structureIdx);
        logger.debug("track config: norm mode: {}", normMode.getSelectedEnum());
        Map<SegmentedObject, double[]> parentMapMeanAndSigma = NORMALIZATION_MODE.PER_FRAME_CENTER.equals(normMode.getSelectedEnum()) ? parentTrack.stream().parallel().collect(Collectors.toMap(p->p, p -> {
            SNR snr = new SNR();
            snr.setUsePreFilteredImage(true);
            RegionPopulation bactPop = p.getChildRegionPopulation(segParent);
            if (bactPop.getRegions().isEmpty()) return new double[0];
            snr.setUp(p, structureIdx, bactPop);
            if (p.getFrame()<10) logger.info("PER FRAME NORM: F= {} mean sd = {}", p.getFrame(), snr.getBackgroundMeanSD(bactPop.getRegions().get(0)));
            //return snr.getBackgroundMeanSD(bactPop.getRegions().get(0));
            return new double[]{snr.getBackgroundMeanSD(bactPop.getRegions().get(0))[0], 1};
        })) : null;

        return (p, s) -> {
            s.parentMapMeanAndSigma = parentMapMeanAndSigma;
            s.setMaps(parentMapImages.get(p));
        };
    }

    @Override
    public ProcessingPipeline.PARENT_TRACK_MODE parentTrackMode() {
        return ProcessingPipeline.PARENT_TRACK_MODE.MULTIPLE_INTERVALS;
    }
    private boolean noGaussian() {return this.watershedMap.getSelectedEnum().equals(WATERSHED_MAP.LAPLACIAN) && this.qualityFormula.getSelectedEnum().equals(QUALITY_FORMULA.L);}
    private boolean noLaplacian() {return this.watershedMap.getSelectedEnum().equals(WATERSHED_MAP.GAUSSIAN) && this.qualityFormula.getSelectedEnum().equals(QUALITY_FORMULA.G);}

    Map<SegmentedObject, double[]> parentMapMeanAndSigma;
    protected Image[] computeMaps(Image rawSource, Image filteredSource) {
        double[] scale = getScale();
        double[] gaussScale = this.getGaussianScale();
        Image[] maps = new Image[scale.length+gaussScale.length];

        for (int i = 0; i<gaussScale.length; ++i) {
            final int ii = i;
            Function<Image, Image> gaussF = f->gaussScale[ii]<0.5 ? TypeConverter.toFloat(f, null) : ImageFeatures.gaussianSmooth(f, gaussScale[ii], false).setName("gaussian: "+gaussScale[ii]);
            maps[i] = planeByPlane ? ImageOperations.applyPlaneByPlane(filteredSource, gaussF) : gaussF.apply(filteredSource);
            if (maps[i] == null) throw new RuntimeException("Null gaussian: scale: " + gaussScale[ii]);
        }
        for (int i = 0; i<scale.length; ++i) {
            final int ii = i;
            Function<Image, Image> lapF = f->ImageFeatures.getLaplacian(f, scale[ii], true, false).setName("laplacian: "+scale[ii]);
            maps[i+gaussScale.length] = ImageOperations.applyPlaneByPlane(filteredSource, lapF); //  : lapF.apply(filteredSource); if too few images laplacian is not relevent in 3D. TODO: put a condition on slice number, and check laplacian values
        }
        return maps;
    }
    
    protected void setMaps(Image[] maps) {
        if (maps==null) return;
        double[] gaussScale = getGaussianScale();
        double[] scale = getScale();
        if (maps.length!=scale.length+gaussScale.length) throw new IllegalArgumentException("Maps should be of length "+scale.length+gaussScale.length+" and contain Gaussian & Laplacian of Gaussian for each scale");
        this.pv.gauss = Arrays.stream(maps).limit(gaussScale.length).toArray(ImageFloat[]::new);
        this.pv.lap = Arrays.stream(maps).skip(gaussScale.length).toArray(ImageFloat[]::new);
    }
    
}
