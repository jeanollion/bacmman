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
package bacmman.data_structure;

import bacmman.image.*;
import bacmman.measurement.BasicMeasurements;
import bacmman.measurement.GeometricalMeasurements;
import bacmman.processing.EDT;
import bacmman.processing.ImageOperations;
import bacmman.processing.RegionFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import bacmman.plugins.ObjectFeature;
import bacmman.plugins.plugins.trackers.ObjectIdxTracker.IndexingOrder;
import bacmman.processing.Filters;
import bacmman.processing.watershed.WatershedTransform;
import bacmman.processing.neighborhood.DisplacementNeighborhood;
import bacmman.processing.neighborhood.EllipsoidalNeighborhood;
import bacmman.processing.neighborhood.Neighborhood;
import bacmman.processing.watershed.WatershedTransform.WatershedConfiguration;
import bacmman.utils.ArrayUtil;
import bacmman.utils.HashMapGetCreate;
import bacmman.utils.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static bacmman.utils.Utils.objectsAllHaveSameProperty;

import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;
import java.util.stream.Collectors;

/**
 *
 * @author Jean Ollion
 */
public class RegionPopulation {
    public final static Logger logger = LoggerFactory.getLogger(RegionPopulation.class);
    private ImageInteger<? extends ImageInteger> labelImage;
    private List<Region> objects;
    private ImageProperties properties;
    //private boolean absoluteLandmark=false;
    private boolean lowConnectivity = false;

    /**
     * Creates an empty ObjectPopulation instance
     *
     * @param properties
     */
    public RegionPopulation(ImageProperties properties) {
        this.properties = new BlankMask(properties);
        this.objects = new ArrayList<>();
    }

    /**
     * @param image          image with values >0 within segmented objects
     * @param isLabeledImage if true, the image is considered as a labeled
     *                       image, one value per object, if false, the image will be labeled (and
     *                       thus modified) by Connected Components Labeling
     */
    public RegionPopulation(ImageInteger image, boolean isLabeledImage) {
        this.properties = image.getProperties();
        labelImage = image;
        if (!isLabeledImage) {
            objects = ImageLabeller.labelImageList(image);
            relabel(false); // in order to have consistent labels between image & object list
        }
    }

    public RegionPopulation(ImageMask mask) {
        properties = new SimpleImageProperties(mask);
        objects = ImageLabeller.labelImageList(mask);
    }

    public RegionPopulation setConnectivity(boolean low) {
        this.lowConnectivity = low;
        return this;
    }

    public RegionPopulation(Collection<? extends Region> objects, ImageProperties properties) {
        if (properties == null) throw new IllegalArgumentException("ImageProperties should no be null");
        if (objects != null) {
            if (objects instanceof List) this.objects = (List) objects;
            else this.objects = new ArrayList<>(objects);
        } else {
            this.objects = new ArrayList<>();
        }
        checkObjectValidity();
        this.properties = new BlankMask(properties);
    }

    private void checkObjectValidity() {
        if (!objectsAllHaveSameProperty(objects, o -> o.isAbsoluteLandMark()))
            throw new IllegalArgumentException("Regions should all have same landmark");
        if (!objectsAllHaveSameProperty(objects, o -> o.is2D))
            throw new IllegalArgumentException("Regions should all be either 2D or 3D");
        //if (twoObjectsHaveSameProperty(objects, o->o.getLabel())) relabel(false);
    }

    public RegionPopulation duplicate() {
        if (objects != null) {
            ArrayList<Region> ob = new ArrayList<>(objects.size());
            for (Region o : objects) ob.add(o.duplicate());
            return new RegionPopulation(ob, properties).setConnectivity(lowConnectivity);
        } else if (labelImage != null) {
            return new RegionPopulation(labelImage.duplicate(""), true).setConnectivity(lowConnectivity);
        }
        return new RegionPopulation(null, properties).setConnectivity(lowConnectivity);
    }

    public boolean isAbsoluteLandmark() {
        if (objects == null || objects.isEmpty()) return false; // default
        return objects.get(0).isAbsoluteLandMark();
    }

    public RegionPopulation ensureEditableRegions() {
        if (objects==null) return this;
        for (int i = 0; i<objects.size(); ++i) {
            if (objects.get(i) instanceof Spot) {
                Spot s = (Spot)objects.get(i);
                if (s.voxelsCreated()) objects.set(i, new Region(s.getVoxels(), s.label, s.is2D, s.scaleXY, s.scaleZ));
                else objects.set(i, new Region(s.getMask(), s.label, s.is2D));
            }
        }
        return this;
    }

    public RegionPopulation addObjects(boolean updateLabelImage, Region... objects) {
        return addObjects(Arrays.asList(objects), updateLabelImage);
    }

    /**
     * Add objects to the list.
     *
     * @param objects
     * @return
     */
    public RegionPopulation addObjects(List<Region> objects, boolean updateLabelImage) {
        this.objects.addAll(objects);
        checkObjectValidity();
        if (updateLabelImage) relabel(true);
        return this;
    }

    public ImageInteger<? extends ImageInteger<?>> getLabelMap() {
        if (labelImage == null) constructLabelImage();
        return labelImage;
    }

    public List<Region> getRegions() {
        if (objects == null) constructObjects();
        return objects;
    }
    public Region getRegion(int label) {
        if (objects == null) constructObjects();
        return objects.stream()
                .filter(r->r.getLabel()==label)
                .findAny().orElse(null);
    }
    private void draw(Region o, int label) {
        //if (this.absoluteLandmark) o.draw(labelImage, label, new BoundingBox(0, 0, 0)); // in order to remove the offset of the image
        //else o.draw(labelImage, label);
        o.draw(labelImage, label); // depends on object's landmark
    }

    private void constructLabelImage() {
        if (objects == null || objects.isEmpty()) {
            labelImage = ImageInteger.createEmptyLabelImage("labelImage", 0, getImageProperties());
        } else {
            labelImage = ImageInteger.createEmptyLabelImage("labelImage", objects.size(), getImageProperties());
            //logger.debug("creating image: properties: {} imagetype: {} number of objects: {}", properties, labelImage.getClass(), objects.size());
            for (Region o : objects) draw(o, o.getLabel());
        }
    }

    private void constructObjects() {
        if (labelImage == null) objects = new ArrayList<>();
        else {
            Region[] obs = RegionFactory.getObjectsImage(labelImage, false);
            objects = new ArrayList<>(Arrays.asList(obs));
        }
    }


    public void eraseObject(Region o, boolean eraseInList) {
        if (labelImage != null) {
            draw(o, 0);
        }
        if (eraseInList && objects != null) {
            objects.remove(o);
        }
    }

    public boolean hasImage() {
        return labelImage != null;
    }

    public RegionPopulation setProperties(ImageProperties properties, boolean onlyIfSameSize) {
        if (labelImage != null) {
            if (!onlyIfSameSize || labelImage.sameDimensions(properties)) {
                labelImage.resetOffset().translate(properties);
                this.properties = new SimpleImageProperties(properties);
            }
            labelImage.setCalibration(properties);

        } else {
            this.properties = new SimpleImageProperties(properties);  //set aussi la taille de l'image
        }

        return this;
    }

    public ImageProperties getImageProperties() {
        if (properties == null) {
            if (labelImage != null) {
                properties = new BlankMask(labelImage);
            } else if (objects != null && !objects.isEmpty()) { //unscaled, no offset for label image..
                MutableBoundingBox box = new MutableBoundingBox();
                for (Region o : objects) {
                    box.union(o.getBounds());
                }
                properties = box.getBlankMask();
            }
        }
        return properties;
    }

    public void relabel() {
        relabel(true);
    }

    public void relabel(boolean fillImage) {
        int idx = 1;
        for (Region o : getRegions()) o.label = idx++;
        redrawLabelMap(fillImage);
    }
    public void clearLabelMap() {
        if (labelImage==null) return;
        if (objects==null) this.getRegions();
        labelImage = null;
    }
    public void redrawLabelMap(boolean fillImage) {
        if (hasImage()) {
            int maxLabel = getRegions().isEmpty() ? 0 : Collections.max(getRegions(), Comparator.comparingInt(Region::getLabel)).getLabel();
            if (maxLabel > ImageInteger.getMaxValue(labelImage, false)) {
                labelImage = ImageInteger.createEmptyLabelImage(labelImage.getName(), maxLabel, properties);
            } else {
                if (fillImage) ImageOperations.fill(labelImage, 0, null);
            }
            for (Region o : getRegions()) draw(o, o.getLabel());
        }
    }

    public void translate(Offset offset, boolean absoluteLandmark) {
        for (Region o : getRegions()) {
            o.translate(offset);
            o.setIsAbsoluteLandmark(absoluteLandmark);
        }
        this.properties = new BlankMask(this.properties).translate(offset);
        if (labelImage != null) labelImage.translate(offset);
    }

    public void setVoxelIntensities(Image intensityMap) {
        for (Region o : getRegions()) {
            for (Voxel v : o.getVoxels()) v.value = intensityMap.getPixel(v.x, v.y, v.z);
        }
    }


    public boolean isInContactWithOtherObject(Region o) {
        DisplacementNeighborhood n = (DisplacementNeighborhood) Filters.getNeighborhood(1.5, 1, properties);
        getLabelMap();
        for (Voxel v : o.getContour()) {
            n.setPixels(v, labelImage, null);
            for (int i = 0; i < n.getValueCount(); ++i)
                if (n.getPixelValues()[i] > 0 && n.getPixelValues()[i] != o.getLabel())
                    return true; // TODO for high number of objects float can lead to ambiguities

        }
        return false;
    }

    public void fitToEdges(Image edgeMap, ImageMask mask) {
        // get seeds outside label image
        ImageInteger seedMap = Filters.localExtrema(edgeMap, null, false, null, Filters.getNeighborhood(1.5, 1.5, edgeMap));
        this.getLabelMap(); //creates the labelImage        
        // merge background seeds && foreground seeds : background = 1, foreground = label+1
        for (int z = 0; z < seedMap.sizeZ(); z++) {
            for (int xy = 0; xy < seedMap.sizeXY(); xy++) {
                if (seedMap.insideMask(xy, z)) {
                    if (mask.insideMask(xy, z)) {
                        seedMap.setPixel(xy, z, labelImage.getPixelInt(xy, z) + 1);
                    } else {
                        seedMap.setPixel(xy, z, 1);
                    }
                }
            }
        }
        ArrayList<Region> seeds = new ArrayList<>(Arrays.asList(RegionFactory.getObjectsImage(seedMap, false)));
        RegionPopulation pop = WatershedTransform.watershed(edgeMap, mask, seeds, new WatershedConfiguration().lowConectivity(lowConnectivity));
        this.objects = pop.getRegions();
        objects.remove(0); // remove background object
        relabel(true);
    }

    public RegionPopulation localThreshold(Image erodeMap, double iqrFactor, boolean darkBackground, boolean keepOnlyBiggestObject) {
        return localThreshold(erodeMap, iqrFactor, darkBackground, keepOnlyBiggestObject, 0, null);
    }

    /**
     * @param erodeMap
     * @param iqrFactor
     * @param darkBackground
     * @param keepOnlyBiggestObject when applying local threhsold one object could be split in several, if true only the biggest will be kept
     * @param dilateRegionRadius    radius for dilate region label-wise. 0 -> no dilatation
     * @param mask                  mask for region dilatation
     * @return
     */
    public RegionPopulation localThreshold(Image erodeMap, double iqrFactor, boolean darkBackground, boolean keepOnlyBiggestObject, double dilateRegionRadius, ImageMask mask) {
        Function<Region, Double> thldFct = o -> {
            List < Double > values = Utils.transform(o.getVoxels(), (Voxel v) -> (double) erodeMap.getPixel(v.x, v.y, v.z));
            double q1 = ArrayUtil.quantile(values, 0.25);
            double q2 = ArrayUtil.quantile(values, 0.5);
            double q3 = ArrayUtil.quantile(values, 0.75);
            double thld;
            if (darkBackground) {
                thld = q2 - iqrFactor * (q3 - q1);
                if (dilateRegionRadius > 0 || values.get(0) < thld) return thld; // if no dilatation: put the threshold only if some pixels are under thld
                else return null;
            } else {
                thld = q2 + iqrFactor * (q3 - q1);
                if (dilateRegionRadius > 0 || values.get(values.size() - 1) > thld) return thld;
                else return null;
            }
        };
        return localThreshold(erodeMap, thldFct, darkBackground, keepOnlyBiggestObject, dilateRegionRadius, mask);
    }
    /**
     *
     * @param erodeMap
     * @param thresholdFunction
     * @param darkBackground
     * @param keepOnlyBiggestObject when applying local threhsold one object could be split in several, if true only the biggest will be kept
     * @param dilateRegionRadius radius for dilate region label-wise. 0 -> no dilatation
     * @param mask mask for region dilatation
     * @return
     */
    public RegionPopulation localThreshold(Image erodeMap, Function<Region, Double> thresholdFunction, boolean darkBackground, boolean keepOnlyBiggestObject, double dilateRegionRadius, ImageMask mask) {
        //if (debug) ImageWindowManagerFactory.showImage(erodeMap);
        List<Region> addedObjects = new ArrayList<>();
        Map<Integer, Double> labelMapThld = Utils.toMapWithNullValues(getRegions().stream(), Region::getLabel, thresholdFunction, false);
        if (dilateRegionRadius>0) {
            labelImage =  (ImageInteger)Filters.applyFilter(getLabelMap(), null, new Filters.BinaryMaxLabelWise().setMask(mask), Filters.getNeighborhood(dilateRegionRadius, mask));
            constructObjects();
        }
        for (Region r : getRegions()) {
            if (!labelMapThld.containsKey(r.getLabel())) continue;
            double thld = labelMapThld.get(r.getLabel());
            boolean change = r.erodeContours(erodeMap, thld, darkBackground, keepOnlyBiggestObject, r.getContour(), null);
            if (change && !keepOnlyBiggestObject) {
                List<Region> subRegions = ImageLabeller.labelImageListLowConnectivity(r.mask);
                if (subRegions.size()>1) {
                    subRegions.remove(0);
                    r.ensureMaskIsImageInteger();
                    ImageInteger regionMask = r.getMaskAsImageInteger();
                    for (Region toErase: subRegions) {
                        toErase.draw(regionMask, 0);
                        toErase.translate(r.getBounds());
                    }
                    addedObjects.addAll(subRegions);
                }
            }
        }
        objects.addAll(addedObjects);
        relabel(true);
        constructObjects(); // updates bounds of objects
        return this;
    }
    public RegionPopulation localThresholdEdges(Image erodeMap, Image edgeMap, double sigmaFactor, boolean darkBackground, boolean keepOnlyBiggestObject, double dilateRegionRadius, ImageMask mask, Predicate<Voxel> removeContourVoxel) {
        if (dilateRegionRadius>0) {
            labelImage =  (ImageInteger)Filters.applyFilter(getLabelMap(), null, new Filters.BinaryMaxLabelWise().setMask(mask), Filters.getNeighborhood(dilateRegionRadius, mask));
            constructObjects();
        }
        List<Region> addedObjects = new ArrayList<>();
        Map<Integer, Double> labelMapThld = new HashMap<>(getRegions().size());
        for (Region r : getRegions()) {
            double[] values = erodeMap.stream(r.getMask(), r.isAbsoluteLandMark()).toArray();
            double[] valuesEdge = edgeMap.stream(r.getMask(), r.isAbsoluteLandMark()).toArray();
            double meanW = 0, mean = 0, sumEdge = 0;
            for (int i = 0; i < values.length; ++i) {
                sumEdge += valuesEdge[i];
                meanW += valuesEdge[i] * values[i];
                mean += values[i];
            }
            meanW /= sumEdge;
            mean /= values.length;
            double sigmaW = 0;
            for (int i = 0; i < values.length; ++i) sigmaW += Math.pow(values[i] - mean, 2) * valuesEdge[i];
            sigmaW = Math.sqrt(sigmaW / sumEdge);
            double thld;
            if (darkBackground) {
                thld = meanW - sigmaFactor * sigmaW;
                if (values[ArrayUtil.min(values)] < thld)
                    labelMapThld.put(r.getLabel(), thld); // if no dilatation: put the threshold only if some pixels are under thld
            } else {
                thld = meanW + sigmaFactor * sigmaW;
                if (values[ArrayUtil.max(values)] > thld) labelMapThld.put(r.getLabel(), thld);
            }
        }
        
        for (Region r : getRegions()) {
            if (!labelMapThld.containsKey(r.getLabel())) continue;
            double thld = labelMapThld.get(r.getLabel());
            Set<Voxel> contour = r.getContour();
            if (removeContourVoxel!=null) {
                contour.removeIf(removeContourVoxel);
                if (contour.isEmpty()) continue;
            }
            boolean change = r.erodeContours(erodeMap, thld, darkBackground, keepOnlyBiggestObject, contour, removeContourVoxel);
            //logger.debug("local thld edge: object: {}, thld: {}, changes: {}, absLandMark: {}", r.getLabel(), thld, change, r.isAbsoluteLandMark());
            if (change && !keepOnlyBiggestObject) {
                List<Region> subRegions = ImageLabeller.labelImageListLowConnectivity(r.mask);
                if (subRegions.size()>1) {
                    subRegions.remove(0);
                    r.ensureMaskIsImageInteger();
                    ImageInteger regionMask = r.getMaskAsImageInteger();
                    for (Region toErase: subRegions) {
                        toErase.draw(regionMask, 0);
                        toErase.translate(r.getBounds());
                    }
                    addedObjects.addAll(subRegions);
                }
            }
        }
        objects.addAll(addedObjects);
        relabel(true);
        constructObjects(); // updates bounds of objects
        return this;
    }
    
    public RegionPopulation erodToEdges(Image erodeMap, Image intensityMap, boolean keepOnlyBiggestObject, double dilateRegionRadius, ImageMask mask) {
        //if (debug) ImageWindowManagerFactory.showImage(erodeMap);
        List<Region> addedObjects = new ArrayList<>();
        if (dilateRegionRadius>0) {
            labelImage =  (ImageInteger)Filters.applyFilter(getLabelMap(), null, new Filters.BinaryMaxLabelWise().setMask(mask), Filters.getNeighborhood(dilateRegionRadius, mask));
            constructObjects();
        }
        for (Region r : getRegions()) {
            boolean change = r.erodeContoursEdge(erodeMap, intensityMap, keepOnlyBiggestObject);
            if (change && !keepOnlyBiggestObject) {
                List<Region> subRegions = ImageLabeller.labelImageListLowConnectivity(r.mask);
                if (subRegions.size()>1) {
                    subRegions.remove(0);
                    r.ensureMaskIsImageInteger();
                    ImageInteger regionMask = r.getMaskAsImageInteger();
                    for (Region toErase: subRegions) {
                        toErase.draw(regionMask, 0);
                        toErase.translate(r.getBounds());
                    }
                    addedObjects.addAll(subRegions);
                }
            }
        }
        objects.addAll(addedObjects);
        relabel(true);
        constructObjects(); // updates bounds of objects
        return this;
    }
    
    protected void localThreshold(Region o, Image intensity, double threshold) {
        Iterator<Voxel> it = o.getVoxels().iterator();
        while (it.hasNext()) {
            Voxel v = it.next();
            if (intensity.getPixel(v.x, v.y, v.z) < threshold) {
                it.remove();
                if (hasImage()) labelImage.setPixel(v.x, v.y, v.z, 0);
            }
        }
    }
    public Region getBackground(ImageMask mask) {
        if (mask!=null && !mask.sameDimensions(getLabelMap())) throw new RuntimeException("Mask should have same size as label map");
        int bckLabel = getRegions().isEmpty() ? 1 : Collections.max(getRegions(), Comparator.comparingInt(Region::getLabel)).getLabel()+1;
        ImageByte bckMask = new ImageByte("", this.getImageProperties()).resetOffset();
        if (mask!=null) ImageOperations.andNot(mask, getLabelMap(), bckMask);
        else ImageOperations.not(getLabelMap(), bckMask);
        return new Region(bckMask, bckLabel, bckMask.sizeZ()==1);
    }
    public void smoothRegions(double radius, boolean eraseVoxelsIfConnectedToBackground, ImageMask mask) {
        if (mask!=null && !mask.sameDimensions(getLabelMap())) throw new RuntimeException("Mask should have same size as label map");
        Neighborhood n = Filters.getNeighborhood(radius, getImageProperties());
        HashMapGetCreate<Integer, int[]> count = new HashMapGetCreate<>(9, i->new int[1]);
        
        Region bck = getBackground(mask);
        bck.getVoxels();
        getRegions().add(bck);
        this.redrawLabelMap(true);
        Map<Integer, Region> regionByLabel = getRegions().stream().collect(Collectors.toMap(r->r.getLabel(), r->r));
        Iterator<Region> rIt = getRegions().iterator();
        Set<Region> modified = new HashSet<>();
        while(rIt.hasNext()) {
            modified.clear();
            Region r = rIt.next();
            if (!eraseVoxelsIfConnectedToBackground && r==bck) continue;
            Iterator<Voxel> it = r.getVoxels().iterator();
            while(it.hasNext()) {
                Voxel v = it.next();
                n.setPixels(v, getLabelMap(), mask);
                for (int i = 0; i<n.getValueCount(); ++i) count.getAndCreateIfNecessary((int)n.getPixelValues()[i])[0]++;
                if (!eraseVoxelsIfConnectedToBackground) count.remove(bck.getLabel());
                
                if (!count.containsKey(r.getLabel())) {
                    logger.error("smooth interface: {} not present @Voxel: {}/ bck: {}, counts: {}", r.getLabel(), v, bck.getLabel(), Utils.toStringList(count.entrySet(), e->e.getKey()+"->"+e.getValue()[0]));
                    continue; // TODO solve
                }
                int maxLabel = Collections.max(count.entrySet(), Comparator.comparingInt(e -> e.getValue()[0])).getKey();
                if (maxLabel!=r.getLabel() &&  count.get(maxLabel)[0]> count.get(r.getLabel())[0]) {
                    it.remove();
                    modified.add(r);
                    //if (maxLabel>0) {
                        regionByLabel.get(maxLabel).getVoxels().add(v);
                        modified.add(regionByLabel.get(maxLabel));
                    //}
                    getLabelMap().setPixel(v.x, v.y, v.z, maxLabel);
                }
                count.clear();
            }
            if (r.getVoxels().isEmpty()) {
                rIt.remove();
                modified.remove(r);
            }
            for (Region mod : modified) mod.clearMask();
        }
        bck.draw(labelImage, 0);
        getRegions().remove(bck);
    }
    public RegionPopulation subset(SimpleFilter filter) {
        List<Region> keptObjects = new ArrayList<>();
        if (filter instanceof Filter) ((Filter)filter).init(this);
        for (Region o : getRegions()) {
            if (filter.keepObject(o)) keptObjects.add(o);
        }
        return new RegionPopulation(keptObjects, this.getImageProperties()).setConnectivity(lowConnectivity);
    }
    public RegionPopulation filter(SimpleFilter filter) {
        return filter(filter, null);
    }
    
    public RegionPopulation filterAndMergeWithConnected(SimpleFilter filter) {
        List<Region> removed = new ArrayList<>();
        filter(filter, removed);
        if (!removed.isEmpty()) mergeWithConnected(removed);
        return this;
    }
    
    public RegionPopulation filter(SimpleFilter filter, List<Region> removedObjects) {
        //int objectNumber = objects.size();
        if (removedObjects==null) removedObjects=new ArrayList<>();
        if (filter instanceof Filter) ((Filter)filter).init(this);
        for (Region o : getRegions()) {
            if (!filter.keepObject(o)) removedObjects.add(o);
        }
        if (removedObjects.isEmpty()) return this;
        this.objects.removeAll(removedObjects);
        if (hasImage()) for (Region o : removedObjects) draw(o, 0);
        relabel(false);
        //logger.debug("filter: {}, total object number: {}, remaning objects: {}", filter.getClass().getSimpleName(), objectNumber, objects.size());
        return this;
    }
    
    /**
     * 
     * @param otherPopulations populations that will be combined (destructive). Pixels will be added to overlapping objects, and non-overlapping objects will be added 
     * @return  the current instance for convinience
     */
    public RegionPopulation combine(List<RegionPopulation> otherPopulations) {
        for (RegionPopulation pop : otherPopulations) {
            pop.filter(new RemoveAndCombineOverlappingObjects(this));
            this.getRegions().addAll(pop.getRegions());
        }
        relabel(false);
        return this;
    }
    public void combine(RegionPopulation... otherPopulations) {
        if (otherPopulations.length>0) combine(Arrays.asList(otherPopulations));
    }
    
    public void keepOnlyLargestObject() {
        if (getRegions().isEmpty()) {
            return;
        }
        if (labelImage!=null) {
            for (Region o : getRegions()) {
                draw(o, 0);
            }
        }
        int maxIdx = 0;
        double maxSize = objects.get(0).size();
        for (int i = 1; i < objects.size(); ++i) {
            if (objects.get(i).getVoxels().size() > maxSize) {
                maxSize = objects.get(i).size();
                maxIdx = i;
            }
        }
        ArrayList<Region> objectsTemp = new ArrayList<Region>(1);
        Region o = objects.get(maxIdx);
        o.setLabel(1);
        objectsTemp.add(o);
        objects = objectsTemp;
        if (labelImage!=null) draw(o, o.getLabel());
    }
    
    public void mergeAll() {
        if (getRegions().isEmpty()) return;
        for (Region o : getRegions()) o.setLabel(1);
        this.relabel(false);
        objects.clear();
        constructObjects();
    }
    public void mergeAllConnected() {
        mergeAllConnected(Integer.MIN_VALUE);
    }
    /**
     * Merge all objects connected if their label is above {@param fromLabel}
     * Existing region are not modified, when merged, new regions are created
     * @param fromLabel 
     */
    private void mergeAllConnected(int fromLabel) {
        relabel(); // objects label start from 1 -> idx = label-1
        getRegions();
        List<Region> toRemove = new ArrayList<>();
        ImageInteger inputLabels = getLabelMap();
        int otherLabel;
        int[][] neigh = inputLabels.sizeZ()>1 ? (lowConnectivity ? ImageLabeller.neigh3DLowHalf : ImageLabeller.neigh3DHalf) : (lowConnectivity ? ImageLabeller.neigh2D4Half : ImageLabeller.neigh2D8Half);
        Voxel n;
        for (int z = 0; z<inputLabels.sizeZ(); z++) {
            for (int y = 0; y<inputLabels.sizeY(); y++) {
                for (int x = 0; x<inputLabels.sizeX(); x++) {
                    int label = inputLabels.getPixelInt(x, y, z);
                    if (label==0) continue;
                    if (label-1>=objects.size()) {
                        logger.error("label map, error @ label: {}", label);
                    }
                    Region currentRegion = objects.get(label-1);
                    for (int i = 0; i<neigh.length; ++i) {
                        n = new Voxel(x+neigh[i][0], y+neigh[i][1], z+neigh[i][2]);
                        if (inputLabels.contains(n.x, n.y, n.z)) { 
                            otherLabel = inputLabels.getPixelInt(n.x, n.y, n.z);   
                            if (otherLabel>0 && otherLabel!=label) {
                                if (label>=fromLabel || otherLabel>=fromLabel) {
                                    Region otherRegion = objects.get(otherLabel-1);
                                    if (otherLabel<label) { // switch
                                        Region temp = currentRegion;
                                        currentRegion = otherRegion;
                                        otherRegion = temp;
                                        label = currentRegion.getLabel();
                                    }
                                    Region newRegion = Region.merge(currentRegion, otherRegion);
                                    draw(otherRegion, label);
                                    toRemove.add(otherRegion);
                                    objects.set(label-1, newRegion);
                                    currentRegion = newRegion;
                                }
                            }
                        }
                    }
                }
            }
        }
        objects.removeAll(toRemove);
    }
    public void mergeWithConnected(Collection<Region> objectsToMerge) {
        // create a new list, with objects to merge at the end, and record the last label to merge
        ArrayList<Region> newObjects = new ArrayList<Region>();
        Set<Region> toMerge=  new HashSet<>(objectsToMerge);
        for (Region o : objects) if (!objectsToMerge.contains(o)) newObjects.add(o);
        int labelToMerge = newObjects.size()+1;
        newObjects.addAll(toMerge);
        this.objects=newObjects;
        mergeAllConnected(labelToMerge);
        // erase unmerged objects
        Iterator<Region> it = objects.iterator();
        while(it.hasNext()) {
            Region n = it.next();
            if (n.getLabel()>=labelToMerge) {
                eraseObject(n, false);
                it.remove();
            }
        }
    }
    public void mergeWithConnectedWithinSubset(Predicate<Region> subsetObject) {
        List<Region> toMerge=  getRegions().stream().filter(subsetObject).collect(Collectors.toList());
        objects.removeAll(toMerge);
        List<Region> oldObjects = objects;
        objects = toMerge;
        mergeAllConnected();
        oldObjects.addAll(objects);
        objects = oldObjects;
        relabel(false);
    }
    
    public void sortBySpatialOrder(final IndexingOrder order) {
        Comparator<Region> comp = new Comparator<Region>() {
            @Override
            public int compare(Region arg0, Region arg1) {
                return compareCenters(getCenterArray(arg0.getBounds()), getCenterArray(arg1.getBounds()), order);
            }
        };
        Collections.sort(objects, comp);
        relabel(false);
    }
    
    public Image getLocalThicknessMap() {
        Image ltmap = new ImageFloat("Local Thickness Map "+getImageProperties().getScaleXY()+ " z:"+getImageProperties().getScaleZ(), getImageProperties());
        for (Region r : getRegions()) {
            Image lt = bacmman.processing.localthickness.LocalThickness.localThickness(r.getMask(), getImageProperties().getScaleZ()/getImageProperties().getScaleXY(), true, false);
            ImageMask.loopWithOffset(r.getMask(), (x, y, z)->{
                ltmap.setPixelWithOffset(x, y, z, lt.getPixelWithOffset(x, y, z)); // with offset?
            });
        }
        //Image ltmap = LocalThickness.localThickness(this.getLabelMap(), 1, 1, true, 1);
        return ltmap;
    }
    public Image getEDM(boolean correctionForObjectsTouchingBorder) {
        Image edm = new ImageFloat("EDM"+getImageProperties().getScaleXY()+ " z:"+getImageProperties().getScaleZ(), getImageProperties());
        for (Region r : getRegions()) {
            EDT edt = new EDT();
            if (correctionForObjectsTouchingBorder) {
                boolean[] touchingBorders = BoundingBox.getTouchingBorders(this.properties, r.getBounds());
                edt.outOfBoundPolicy().setX(!touchingBorders[0], !touchingBorders[1]);
                edt.outOfBoundPolicy().setY(!touchingBorders[2], !touchingBorders[3]);
                edt.outOfBoundPolicy().setZ(!touchingBorders[4], !touchingBorders[5]);
            }
            Image edmR = edt.run(r.getMask(), true, 1, getImageProperties().getScaleZ()/getImageProperties().getScaleXY(), false);
            ImageMask.loopWithOffset(r.getMask(), (x, y, z)->{
                edm.setPixelWithOffset(x, y, z, edmR.getPixelWithOffset(x, y, z));
            });
        }
        return edm;
    }

    public RegionPopulation eraseTouchingContours(boolean lowConnectivity) {
        if (getRegions().isEmpty()) return this;
        redrawLabelMap(false);
        ImageInteger maskR = getLabelMap();
        Set<Voxel> toErase = new HashSet<>();
        // compute voxels that touch
        EllipsoidalNeighborhood neigh = new EllipsoidalNeighborhood(lowConnectivity?1:1.5, true);
        for (Region r : getRegions()) {
            Set<Voxel> contour = r.getContour();
            int lab = r.getLabel();
            ToIntFunction<Voxel> getVoxel = r.isAbsoluteLandMark() ? n->maskR.getPixelIntWithOffset(n.x, n.y, n.z) : n->maskR.getPixelInt(n.x, n.y, n.z);
            Set<Voxel> touching = contour.stream()
                    .filter(v -> neigh.stream(v, maskR, r.isAbsoluteLandMark()).anyMatch(n -> {
                        int l = getVoxel.applyAsInt(n);
                        return l>0 && l!=lab;
                    })).collect(Collectors.toSet());
            if (touching.size()<r.size()) { // make sure this does not erase the region
                toErase.addAll(touching);
                r.removeVoxels(touching);
            }
        }
        if (isAbsoluteLandmark()) toErase.forEach(v->maskR.setPixelWithOffset(v.x, v.y, v.z, 0));
        else toErase.forEach(v->maskR.setPixel(v.x, v.y, v.z, 0));
        return this;
    }
    
    private static double[] getCenterArray(BoundingBox b) {
        return new double[]{b.xMean(), b.yMean(), b.zMean()};
    }
    
    private static int compareCenters(double[] o1, double[] o2, IndexingOrder order) {
        if (o1[order.i1] != o2[order.i1]) {
            return Double.compare(o1[order.i1], o2[order.i1]);
        } else if (o1[order.i2] != o2[order.i2]) {
            return Double.compare(o1[order.i2], o2[order.i2]);
        } else {
            return Double.compare(o1[order.i3], o2[order.i3]);
        }
    }
    
    public static interface Filter extends SimpleFilter {
        public void init(RegionPopulation population);
    }
    public static interface SimpleFilter {
        public boolean keepObject(Region object);
    }

    public static class Feature implements Filter {
        boolean keepOverThreshold, strict;
        ObjectFeature feature;
        double threshold;
        public Feature(ObjectFeature feature, double threshold, boolean keepOverThreshold, boolean strict) {
            this.feature=feature;
            this.threshold=threshold;
            this.keepOverThreshold=keepOverThreshold;
            this.strict=strict;
        }
        @Override
        public void init(RegionPopulation population) { }

        @Override
        public boolean keepObject(Region object) {
            double testValue = feature.performMeasurement(object);
            //logger.debug("FeatureFilter: {}, object: {}, testValue: {}, threshold: {}", feature.getClass().getSimpleName(), object.getLabel(), testValue, threshold);
            if (Double.isNaN(testValue)) return true;
            if (keepOverThreshold) {
                if (strict) return testValue>threshold;
                else return testValue>=threshold;
            } else {
                if (strict) return testValue<threshold;
                else return testValue<=threshold;
            }
        }
    }
    
    public static class Thickness implements Filter {

        int tX = -1, tY = -1, tZ = -1;

        public Thickness setX(int minX) {
            this.tX = minX;
            return this;
        }

        public Thickness setY(int minY) {
            this.tY = minY;
            return this;
        }

        public Thickness setZ(int minZ) {
            this.tZ = minZ;
            return this;
        }
        
        @Override
        public boolean keepObject(Region object) {
            return (tX < 0 || object.getBounds().sizeX() > tX) && (tY < 0 || object.getBounds().sizeY() > tY) && (tZ < 0 || object.getBounds().sizeZ() > tZ);
        }

        public void init(RegionPopulation population) {}
    }
    
    public static class MeanThickness implements Filter {

        double tX = -1, tY = -1, tZ = -1;

        public MeanThickness setX(double minX) {
            this.tX = minX;
            return this;
        }

        public MeanThickness setY(double minY) {
            this.tY = minY;
            return this;
        }

        public MeanThickness setZ(double minZ) {
            this.tZ = minZ;
            return this;
        }
        
        @Override
        public boolean keepObject(Region object) {
            return (tX < 0 || (object.getBounds().sizeX() > tX && GeometricalMeasurements.meanThicknessX(object)>tX)) && (tY < 0 || (object.getBounds().sizeY() > tY && GeometricalMeasurements.meanThicknessY(object)>tY)) && (tZ < 0 || (object.getBounds().sizeZ() > tZ && GeometricalMeasurements.meanThicknessZ(object)>tZ));
        }

        public void init(RegionPopulation population) {}
    }
    public static class MedianThickness implements Filter {

        double tX = -1, tY = -1, tZ = -1;

        public MedianThickness setX(double minX) {
            this.tX = minX;
            return this;
        }

        public MedianThickness setY(double minY) {
            this.tY = minY;
            return this;
        }

        public MedianThickness setZ(double minZ) {
            this.tZ = minZ;
            return this;
        }
        
        @Override
        public boolean keepObject(Region object) {
            return (tX < 0 || (object.getBounds().sizeX() > tX && GeometricalMeasurements.medianThicknessX(object)>tX)) && (tY < 0 || (object.getBounds().sizeY() > tY && GeometricalMeasurements.medianThicknessY(object)>tY)) && (tZ < 0 || (object.getBounds().sizeZ() > tZ && GeometricalMeasurements.medianThicknessZ(object)>tZ));
        }

        public void init(RegionPopulation population) {}
    }

    public static class RemoveFlatObjects extends Thickness {

        public RemoveFlatObjects(Image image) {
            this(image.sizeZ() > 1);
        }

        public RemoveFlatObjects(boolean is3D) {
            super.setX(1).setY(1);
            if (is3D) {
                super.setZ(1);
            }
        }
    }
    public static class LocalThickness implements SimpleFilter {
        double thld;
        boolean keepOver = true;
        boolean strict = true;
        public LocalThickness(double threshold) {
            this.thld=threshold;
        }
        public LocalThickness keepOverThreshold(boolean keepOver) {
            this.keepOver=keepOver;
            return this;
        }
        @Override
        public boolean keepObject(Region object) {
            double lt = GeometricalMeasurements.localThickness(object);
            return lt>=thld == keepOver;
        }
    }
    public static class Size implements Filter {

        double min = -1, max = -1;

        public Size setMin(double min) {
            this.min = min;
            return this;
        }

        public Size setMax(double max) {
            this.max = max;
            return this;
        }
        @Override public void init(RegionPopulation population) {}
        @Override
        public boolean keepObject(Region object) {
            double size = object.size();
            return (min < 0 || size >= min) && (max < 0 || size < max);
        }
    }
    public enum Border {
        X(true, true, false, false, false), Xl(true, false, false, false, false), Xr(false, true, false, false, false), Y(false, false, true, true, false), YDown(false, false, false, true, false), YUp(false, false, true, false, false), Z(false, false, false, false, true), XY(true, true, true, true, false), XYup(true, true, true, false, false), XYZ(true, true, true, true, true), XlYup(true, false, true, false, false), XrYup(false, true, true, false, false);
        boolean xl, xr, yup, ydown, z;

        Border(boolean xl, boolean xr, boolean yup, boolean ydown, boolean z) {
            this.xl = xl;
            this.xr = xr;
            this.yup = yup;
            this.ydown = ydown;
            this.z = z;
        }
    };
    public static class ContactBorder implements Filter {

        int contactLimit;
        ImageProperties imageProperties;
        Border border;
        int tolerance;
        int tolEnd = 1;
        public ContactBorder(int contactLimit, ImageProperties imageProperties, Border border) {
            this.contactLimit = contactLimit;
            this.imageProperties = imageProperties;
            this.border = border;
        }
        public ContactBorder setLimit(int contactLimit) {
            this.contactLimit=contactLimit;
            return this;
        }
        public ContactBorder setTolerance(int tolerance) {
            if (tolerance<=0) tolerance=0;
            this.tolerance=tolerance;
            this.tolEnd=tolerance+1;
            return this;
        }
        public boolean contact(Voxel v) {
            if (border.xl && v.x <=tolerance) return true;
            if (border.xr && v.x >= imageProperties.sizeX() - tolEnd) return true;
            if (border.yup && v.y <=tolerance) return true;
            if (border.ydown && v.y >= imageProperties.sizeY() - tolEnd) return true;
            if (border.z && (v.z <=tolerance || v.z >= imageProperties.sizeZ() - tolEnd)) return true;
            return false;
        }
        public boolean contactWithOffset(Voxel v) {
            if (border.xl && v.x <= imageProperties.xMin() + tolerance) return true;
            if (border.xr && v.x > imageProperties.xMax() - tolEnd) return true;
            if (border.yup && v.y <= imageProperties.yMin() + tolerance) return true;
            if (border.ydown && v.y > imageProperties.yMax() - tolEnd) return true;
            if (border.z && (v.z <= imageProperties.zMin() + tolerance || v.z > imageProperties.zMax() - tolEnd)) return true;
            return false;
        }
        @Override public void init(RegionPopulation population) {}
        @Override
        public boolean keepObject(Region object) {
            if (contactLimit <= 0)  return true;
            if (object.isAbsoluteLandMark()) {
                if (!intersectWithOffset(object.getBounds())) return true;
                int count = 0;
                for (Voxel v : object.getContour()) {
                    if (contactWithOffset(v)) {
                        ++count;
                        if (count >= contactLimit) return false;
                    }
                }
                return true;
            } else {
                if (!intersect(object.getBounds())) return true;
                int count = 0;
                for (Voxel v : object.getContour()) {
                    if (contact(v)) {
                        ++count;
                        if (count >= contactLimit) return false;
                    }
                }
                return true;
            }
        }
        public int getContact(Region object) {
            if (object.isAbsoluteLandMark()) {
                // first check if there is intersection of bounding box with considered borders
                if (!intersectWithOffset(object.getBounds())) return 0;
                int count = 0;
                for (Voxel v : object.getContour()) {
                    if (contactWithOffset(v)) ++count;
                }
                return count;
            } else {
                // first check if there is intersection of bounding box with considered borders
                if (!intersect(object.getBounds())) return 0;
                int count = 0;
                for (Voxel v : object.getContour()) {
                    if (contact(v)) ++count;
                }
                return count;
            }
        }
        public boolean intersect(BoundingBox bounds) {
            if (border.xl && bounds.xMin() <=tolerance) return true;
            if (border.xr && bounds.xMax() >= imageProperties.sizeX() - tolEnd) return true;
            if (border.yup && bounds.yMin() <=tolerance) return true;
            if (border.ydown && bounds.yMax() >= imageProperties.sizeY() - tolEnd) return true;
            if (border.z && (bounds.zMin() <=tolerance || bounds.zMax() >= imageProperties.sizeZ() - tolEnd)) return true;
            return false;
        }
        public boolean intersectWithOffset(BoundingBox bounds) {
            if (border.xl && bounds.xMin() <= imageProperties.xMin() + tolerance) return true;
            if (border.xr && bounds.xMax() > imageProperties.xMax() - tolEnd) return true;
            if (border.yup && bounds.yMin() <= imageProperties.yMin() + tolerance) return true;
            if (border.ydown && bounds.yMax() > imageProperties.yMin() - tolEnd) return true;
            if (border.z && (bounds.zMin() <= imageProperties.zMin()+tolerance || bounds.zMax() > imageProperties.zMax() - tolEnd)) return true;
            return false;
        }
    }
    public static class ContactBorderMask extends ContactBorder {
        ImageMask mask;
        public ContactBorderMask(int contactLimit, ImageMask mask, Border border) {
            super(contactLimit, mask, border);
            this.mask = mask;
            this.tolerance=0;
            this.tolEnd=1;
        }
        @Override
        public ContactBorder setTolerance(int tolerance) {
            throw new UnsupportedOperationException("no tolerance for ContactBorderMask");
        }
        @Override
        public ContactBorderMask setLimit(int contactLimit) {
            this.contactLimit=contactLimit;
            return this;
        }
        @Override
        public boolean contact(Voxel v) {
            if (!mask.insideMask(v.x, v.y, v.z)) return false;
            if (border.xl && (!mask.contains(v.x-1, v.y, v.z) || !mask.insideMask(v.x-1, v.y, v.z))) return true;
            if (border.xr && (!mask.contains(v.x+1, v.y, v.z) || !mask.insideMask(v.x+1, v.y, v.z))) return true;
            if (border.yup && (!mask.contains(v.x, v.y-1, v.z) || !mask.insideMask(v.x, v.y-1, v.z))) return true;
            if (border.ydown && (!mask.contains(v.x, v.y+1, v.z) || !mask.insideMask(v.x, v.y+1, v.z))) return true;
            if (border.z && (!mask.contains(v.x, v.y, v.z-1) || !mask.insideMask(v.x, v.y, v.z-1) || !mask.contains(v.x, v.y, v.z+1) || !mask.insideMask(v.x, v.y, v.z+1))) return true;
            return false;
        }
        @Override
        public boolean contactWithOffset(Voxel v) {
            if (!mask.insideMaskWithOffset(v.x, v.y, v.z)) return false;
            if (border.xl && (!mask.containsWithOffset(v.x-1, v.y, v.z) || !mask.insideMaskWithOffset(v.x-1, v.y, v.z))) return true;
            if (border.xr && (!mask.containsWithOffset(v.x+1, v.y, v.z) || !mask.insideMaskWithOffset(v.x+1, v.y, v.z))) return true;
            if (border.yup && (!mask.containsWithOffset(v.x, v.y-1, v.z) || !mask.insideMaskWithOffset(v.x, v.y-1, v.z))) return true;
            if (border.ydown && (!mask.containsWithOffset(v.x, v.y+1, v.z) || !mask.insideMaskWithOffset(v.x, v.y+1, v.z))) return true;
            if (border.z && (!mask.containsWithOffset(v.x, v.y, v.z-1) || !mask.insideMaskWithOffset(v.x, v.y, v.z-1) || !mask.containsWithOffset(v.x, v.y, v.z+1) || !mask.insideMaskWithOffset(v.x, v.y, v.z+1))) return true;
            return false;
        }
        @Override
        public boolean intersect(BoundingBox bounds) {
            if (border.xl && bounds.xMin() <=0) return true;
            if (border.xr && bounds.xMax() > mask.sizeX()) return true;
            if (border.yup && bounds.yMin() <=0) return true;
            if (border.ydown && bounds.yMax() > mask.sizeY()) return true;
            if (border.z && (bounds.zMin() <=0 || bounds.zMax() > mask.sizeZ())) return true;
            return false;
        }
        @Override
        public boolean intersectWithOffset(BoundingBox bounds) {
            if (border.xl && bounds.xMin() <=mask.xMin()) return true;
            if (border.xr && bounds.xMax() >= mask.xMax()) return true;
            if (border.yup && bounds.yMin() <=mask.yMin()) return true;
            if (border.ydown && bounds.yMax() >= mask.yMin()) return true;
            if (border.z && (bounds.zMin() <=mask.zMin() || bounds.zMax() >= mask.zMax())) return true;
            return false;
        }
    }
    public static class MeanIntensity implements Filter {

        double threshold;
        Image intensityMap;
        boolean keepOverThreshold;
        
        public MeanIntensity(double threshold, boolean keepOverThreshold, Image intensityMap) {
            this.threshold = threshold;
            this.intensityMap = intensityMap;
            this.keepOverThreshold=keepOverThreshold;
        }
        @Override public void init(RegionPopulation population) {}
        @Override
        public boolean keepObject(Region object) {
            double mean = BasicMeasurements.getMeanValue(object, intensityMap);
            return mean >= threshold == keepOverThreshold;
        }
    }
    public static class QuantileIntensity implements Filter {
        double quantile;
        double threshold;
        Image intensityMap;
        boolean keepOverThreshold;
        
        public QuantileIntensity(double threshold, boolean keepOverThreshold, Image intensityMap) {
            this(0.5, threshold, keepOverThreshold, intensityMap);
        }
        public QuantileIntensity(double quantile, double threshold, boolean keepOverThreshold, Image intensityMap) {
            this.quantile=quantile;
            this.threshold = threshold;
            this.intensityMap = intensityMap;
            this.keepOverThreshold=keepOverThreshold;
        }
        @Override public void init(RegionPopulation population) {}
        @Override
        public boolean keepObject(Region object) {
            double median = BasicMeasurements.getQuantileValue(object, intensityMap, quantile)[0];
            return median >= threshold == keepOverThreshold;
        }
    }
        

    
    public static class Or implements Filter {
        Filter[] filters;
        public Or(Filter... filters) {
            this.filters=filters;
        }
        public void init(RegionPopulation population) {
            for (Filter f : filters) f.init(population);
        }

        public boolean keepObject(Region object) {
            //logger.debug("or filters #{}: {}", filters.length, Arrays.stream(filters).map(f->f.keepObject(object)).toArray(i->new Object[i]));
            for (Filter f : filters) {
                if (f.keepObject(object)) return true;
            }
            return false;
        }
    }

    public static class Overlap implements Filter {

        ImageInteger labelMap;
        Neighborhood n;

        public Overlap(ImageInteger labelMap, double... radius) {
            this.labelMap = labelMap;
            double rad, radZ;
            if (radius.length == 0) {
                rad = radZ = 1.5;
            } else {
                rad = radius[0];
                if (radius.length >= 2) {
                    radZ = radius[1];
                } else {
                    radZ = rad;
                }
            }            
            n = labelMap.sizeZ() > 1 ? new EllipsoidalNeighborhood(rad, radZ, false) : new EllipsoidalNeighborhood(rad, false);
        }
        @Override public void init(RegionPopulation population) {}
        @Override
        public boolean keepObject(Region object) {
            for (Voxel v : object.getVoxels()) {
                if (n.hasNonNullValue(v.x, v.y, v.z, labelMap, true)) return true;
            }
            return false;
        }
    }
    /**
     * 
     */
    private static class RemoveAndCombineOverlappingObjects implements Filter { // suppress objects that are already in other and combine the voxels

        RegionPopulation other;
        boolean distanceTolerance;

        public RemoveAndCombineOverlappingObjects(RegionPopulation other) { //, boolean distanceTolerance
            this.other = other;
            //this.distanceTolerance = distanceTolerance;
            
        }
        @Override public void init(RegionPopulation population) {
            if (!population.getImageProperties().sameDimensions(other.getImageProperties())) throw new IllegalArgumentException("Populations should have same dimensions");
        }
        @Override
        public boolean keepObject(Region object) {
            Region maxInterO = null;
            double maxInter = 0;
            for (Region o : other.getRegions()) {
                double inter = o.getOverlapArea(object, null, null);
                if (inter > maxInter) {
                    maxInter = inter;
                    maxInterO = o;
                }
            }
            /*if (maxInterO == null && distanceTolerance) {
                //TODO cherche l'objet le plus proche modulo une distance de 1 de distance et assigner les voxels
            }*/
            if (maxInterO != null) {
                maxInterO.merge(object);
                return false;
            }            
            return true;
        }
    }
}
