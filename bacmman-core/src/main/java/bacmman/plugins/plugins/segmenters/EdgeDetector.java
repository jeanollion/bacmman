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
import bacmman.data_structure.Region;
import bacmman.data_structure.RegionPopulation;
import bacmman.data_structure.SegmentedObject;
import bacmman.plugins.*;
import bacmman.plugins.SimpleThresholder;
import bacmman.processing.Filters;
import bacmman.processing.ImageFeatures;
import bacmman.processing.watershed.WatershedTransform;
import bacmman.measurement.BasicMeasurements;
import bacmman.plugins.plugins.thresholders.IJAutoThresholder;
import bacmman.configuration.parameters.ConditionalParameter;
import ij.process.AutoThresholder;
import bacmman.image.Image;
import bacmman.image.ImageFloat;
import bacmman.image.ImageInteger;
import bacmman.processing.ImageLabeller;
import bacmman.image.ImageMask;
import bacmman.image.ImageProperties;
import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import bacmman.plugins.plugins.pre_filters.ImageFeature;

import java.util.List;
import java.util.function.Consumer;
/**
 *
 * @author Jean Ollion
 */
public class EdgeDetector implements DevPlugin, Segmenter, Hint, TestableProcessingPlugin {
    Map<SegmentedObject, TestDataStore> stores;
    @Override
    public void setTestDataStore(Map<SegmentedObject, TestDataStore> stores) {
        this.stores = stores;
    }

    public static enum THLD_METHOD {
        INTENSITY_MAP("Intensity Map"), VALUE_MAP("Value Map"), NO_THRESHOLDING("No thresholding");
        String name;
        THLD_METHOD(String name) {
            this.name = name;
        }
        public String getName() {return name;}
        public static THLD_METHOD getValue(String name) {
            try {
                return Arrays.stream(THLD_METHOD.values()).filter(e->e.getName().equals(name)).findAny().get();
            } catch (Exception e) {
                throw new IllegalArgumentException("Method not found");
            }
        }
    }
    protected PreFilterSequence watershedMap = new PreFilterSequence("Watershed Map").setEmphasized(true).add(new ImageFeature().setFeature(ImageFeature.Feature.StructureMax).setScale(1.5).setSmoothScale(2)).setHint("Watershed map, separation between regions are at area of maximal intensity of this map");
    public PluginParameter<SimpleThresholder> threshold = new PluginParameter<>("Threshold", bacmman.plugins.SimpleThresholder.class, new IJAutoThresholder().setMethod(AutoThresholder.Method.Otsu), false).setEmphasized(true).setHint("Threshold method used to remove background regions");
    EnumChoiceParameter<THLD_METHOD> thresholdMethod = new EnumChoiceParameter<>("Remove background method", THLD_METHOD.values(), THLD_METHOD.VALUE_MAP, THLD_METHOD::getName).setEmphasized(true).setHint("Intensity Map: compute threshold on raw intensity map and removes regions whose median value is under the threshold<br />Value Map: same as Intensity map but threshold is computed on an image where all pixels values are replaced by the median value of each region<br />"); //<pre>Secondary Map: This method is designed to robustly threshold foreground objects and regions located between foreground objects. Does only work in case foreground objects are of comparable intensities<br />1) Ostus's method is applied on on the image where pixels values are replaced by median value of eache region. <br />2) Ostus's method is applied on on the image where pixels values are replaced by median value of secondary map of each region. Typically using Hessian Max this allows to select regions in between two foreground objects or directly connected to foreground <br />3) A map with regions that are under threshold in 1) and over threshold in 2) ie regions that are not foreground but are either in between two objects or connected to one objects. The histogram of this map is computed and threshold is set in the middle of the largest histogram zone without objects</pre>
    ConditionalParameter<THLD_METHOD> thresholdCond = new ConditionalParameter<>(thresholdMethod).setActionParameters(THLD_METHOD.INTENSITY_MAP, threshold).setActionParameters(THLD_METHOD.VALUE_MAP, threshold);
    NumberParameter seedRadius = new BoundedNumberParameter("Seed Radius", 1, 1.5, 1, null).setEmphasized(true);
    NumberParameter minSizePropagation = new BoundedNumberParameter("Min Size Propagation", 0, 0, 0, null);
    BooleanParameter darkBackground = new BooleanParameter("Dark Background", true);

    Consumer<Image> addTestImage;

    // variables
    Image wsMap;
    ImageInteger seedMap;
    Image watershedPriorityMap;
    boolean parallel; //TODO
    String toolTip = "Segment region at maximal values of the watershed map; <br />"
            + "1) Partition of the whole image using classical watershed seeded on all regional minima of the watershed map. <br />"
            + "2) Suppression of background regions depending on the selected method; <br />";
    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{watershedMap, seedRadius, minSizePropagation, thresholdCond, darkBackground};
    }

    @Override
    public String getHintText() {return toolTip;}

    public PreFilterSequence getWSMapSequence() {
        return this.watershedMap;
    }
    
    public Image getWsMap(Image input, ImageMask mask) {
        if (wsMap==null) wsMap = watershedMap.filter(input, mask);
        return wsMap;
    }

    public ImageInteger getSeedMap(Image input,  ImageMask mask) {
        if (seedMap==null) seedMap = Filters.localExtrema(getWsMap(input, mask), null, false, mask, Filters.getNeighborhood(seedRadius.getValue().doubleValue(), getWsMap(input, mask)), parallel);
        return seedMap;
    }
    public EdgeDetector setSeedRadius(double radius) {
        this.seedRadius.setValue(radius);
        return this;
    }
    public EdgeDetector setMinSizePropagation(int minSize) {
        this.minSizePropagation.setValue(minSize);
        return this;
    }
    public EdgeDetector setIsDarkBackground(boolean dark) {
        this.darkBackground.setSelected(dark);
        return this;
    }
    public EdgeDetector setWsMap(Image wsMap) {
        this.wsMap = wsMap;
        return this;
    }
    public EdgeDetector setWsPriorityMap(Image map) {
        this.watershedPriorityMap = map;
        return this;
    }
    public EdgeDetector setSeedMap(ImageInteger seedMap) {
        this.seedMap = seedMap;
        return this;
    }
    
    public Image getWsPriorityMap(Image input, SegmentedObject parent) {
        if (this.watershedPriorityMap==null) watershedPriorityMap = ImageFeatures.gaussianSmooth(input, 2, false); // TODO parameter?
        return watershedPriorityMap;
    }
    public EdgeDetector setPreFilters(List<PreFilter> prefilters) {
        this.watershedMap.removeAllElements();
        this.watershedMap.add(prefilters);
        return this;
    }
    public EdgeDetector setPreFilters(PreFilter... prefilters) {
        this.watershedMap.removeAllElements();
        this.watershedMap.add(prefilters);
        return this;
    }
    public EdgeDetector setThresholder(bacmman.plugins.SimpleThresholder thlder) {
        this.threshold.setPlugin(thlder);
        return this;
    }
    public EdgeDetector setThrehsoldingMethod(THLD_METHOD method) {
        this.thresholdMethod.setSelectedEnum(method);
        return this;
    }
    @Override
    public RegionPopulation runSegmenter(Image input, int objectClassIdx, SegmentedObject parent) {
        if (stores!=null) addTestImage = i -> stores.get(parent).addIntermediateImage(i.getName(), i);
        return run(input, parent.getMask());
    }
    public RegionPopulation partitionImage(Image input, ImageMask mask) {
        int minSizePropagation = this.minSizePropagation.getValue().intValue();
        WatershedTransform.WatershedConfiguration config = new WatershedTransform.WatershedConfiguration().lowConectivity(false);
        if (minSizePropagation>0) config.fusionCriterion(new WatershedTransform.SizeFusionCriterion(minSizePropagation));
        //config.propagation(WatershedTransform.PropagationType.DIRECT);
        Image wsMap = getWsMap(input, mask);
        RegionPopulation res =  WatershedTransform.watershed(wsMap, mask, Arrays.asList(ImageLabeller.labelImage(getSeedMap(input, mask))), config);
        if (addTestImage!=null) {
            addTestImage.accept(res.getLabelMap().duplicate("EdgeDetector: Segmented Regions"));
            addTestImage.accept(seedMap.setName("EdgeDetector: Seeds"));
            addTestImage.accept(wsMap.setName("EdgeDetector: Watershed Map"));
        }
        return res;
    }
    public RegionPopulation run(Image input, ImageMask mask) {
        RegionPopulation allRegions = partitionImage(input, mask);
        filterRegions(allRegions, input, mask);
        return allRegions;
    }
    
    public void filterRegions(RegionPopulation pop, Image input, ImageMask mask) {
        switch (thresholdMethod.getSelectedEnum()) {
            case INTENSITY_MAP:
            default:{
                    double thld = threshold.instantiatePlugin().runSimpleThresholder(input, mask);
                    if (addTestImage!=null) addTestImage.accept(generateRegionValueMap(pop, input).setName("Intensity value Map"));
                    pop.filter(new RegionPopulation.QuantileIntensity(thld, darkBackground.getSelected(), input));
                    return;
            } case VALUE_MAP: {
                    Map<Region, Double> values = pop.getRegions().stream().collect(Collectors.toMap(o->o, valueFunction(input)));
                    Image valueMap = generateRegionValueMap(input, values);
                    double thld = threshold.instantiatePlugin().runSimpleThresholder(valueMap , mask);
                    if (addTestImage!=null) addTestImage.accept(valueMap.setName("EdgeDetector: Intensity value Map"));
                    if (darkBackground.getSelected()) values.entrySet().removeIf(e->e.getValue()>=thld);
                    else values.entrySet().removeIf(e->e.getValue()<=thld);
                    pop.getRegions().removeAll(values.keySet());
                    pop.relabel(true);
                    return;
            } case NO_THRESHOLDING: { 
                return;
            }
        }
    } 

    public static Image generateRegionValueMap(RegionPopulation pop, Image image) {
        Map<Region, Double> objectValues = pop.getRegions().stream().collect(Collectors.toMap(o->o, valueFunction(image)));
        return generateRegionValueMap(image, objectValues);
    }

    public static Image generateRegionValueMap(ImageProperties image, Map<Region, Double> objectValues) {
        Image valueMap = new ImageFloat("Value per region", image);
        for (Map.Entry<Region, Double> e : objectValues.entrySet()) {
            e.getKey().loop((x, y, z)->valueMap.setPixel(x, y, z, e.getValue()));
        }
        return valueMap;
    }

    protected static Function<Region, Double> valueFunction(Image image) { // default: median value
        return r-> BasicMeasurements.getQuantileValue(r, image, 0.5)[0];
    }

}
