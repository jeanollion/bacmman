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
import bacmman.processing.*;

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
import bacmman.plugins.plugins.trackers.ObjectOrderTracker.IndexingOrder;
import bacmman.processing.watershed.WatershedTransform;
import bacmman.processing.neighborhood.DisplacementNeighborhood;
import bacmman.processing.neighborhood.EllipsoidalNeighborhood;
import bacmman.processing.neighborhood.Neighborhood;
import bacmman.processing.watershed.WatershedTransform.WatershedConfiguration;
import bacmman.utils.ArrayUtil;
import bacmman.utils.HashMapGetCreate;
import bacmman.utils.Utils;
import bacmman.utils.geom.Point;
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

    // bds absolute offset
    public RegionPopulation getCroppedRegionPopulation(BoundingBox bds, boolean relabel) {
        RegionPopulation res;
        if (labelImage == null) {
            ImageProperties resProps = new SimpleImageProperties(bds, properties.getScaleXY(), properties.getScaleZ());
            List<Region> resObj = objects.stream().filter(r -> BoundingBox.intersect(bds, r.getBounds())).collect(Collectors.toList());
            res = new RegionPopulation(resObj, resProps);
            res.constructLabelImage();
            res.objects = null;
        } else { // from label map
            ImageInteger newImage = labelImage.cropWithOffset(bds);
            res = new RegionPopulation(newImage, true);
        }
        if (relabel) res.relabel(false);
        return res;
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
            if (objects.get(i) instanceof Analytical) {
                Region s = objects.get(i);
                if (s.voxelsCreated()) objects.set(i, new Region(s.getVoxels(), s.label, s.is2D, s.scaleXY, s.scaleZ));
                else objects.set(i, new Region(s.getMask(), s.label, s.is2D));
            }
        }
        return this;
    }

    public RegionPopulation addObjects(boolean relabel, Region... objects) {
        return addObjects(Arrays.asList(objects), relabel);
    }

    /**
     * Add objects to the list.
     *
     * @param objects
     * @return
     */
    public RegionPopulation addObjects(List<Region> objects, boolean relabel) {
        if (!objects.isEmpty()) {
            this.objects.addAll(objects);
            checkObjectValidity();
            if (relabel) relabel(false);
            else redrawLabelMap(false); // only if label image is not none
        }
        return this;
    }

    public RegionPopulation removeObjects(Collection<Region> objects, boolean relabel) {
        boolean modified = false;
        for (Region o : objects) {
            boolean removed = this.objects.remove(o);
            if (removed) {
                modified = true;
                if (labelImage != null) draw(o, 0);
            }
        }
        if (relabel && modified) relabel(false);
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
        o.draw(labelImage, label); // depends on object's landmark
    }

    private void constructLabelImage() {
        if (objects == null || objects.isEmpty()) {
            labelImage = ImageInteger.createEmptyLabelImage("labelImage", 0, getImageProperties());
        } else {
            labelImage = ImageInteger.createEmptyLabelImage("labelImage", objects.stream().mapToInt(Region::getLabel).max().getAsInt(), getImageProperties());
            //logger.debug("creating label map: properties: {} imagetype: {} number of objects: {}, bds: {}", properties, labelImage.getClass(), objects.size(), Utils.toStringList(objects, Region::getBounds));
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
    public RegionPopulation translate(Offset offset, boolean absoluteLandmark) {
        translate(offset, absoluteLandmark, false);
        return this;
    }
    public RegionPopulation translate(Offset offset, boolean absoluteLandmark, boolean onlyObjects) {
        for (Region o : getRegions()) {
            o.translate(offset);
            o.setIsAbsoluteLandmark(absoluteLandmark);
        }
        if (!onlyObjects) {
            this.properties = new BlankMask(this.properties).translate(offset);
            if (labelImage != null) labelImage.translate(offset);
        }
        return this;
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

    protected void fitToEdges(Image edgeMap, ImageMask mask, boolean parallel) {
        // get seeds outside label image
        ImageInteger seedMap = Filters.localExtrema(edgeMap, null, false, null, Filters.getNeighborhood(1.5, 1.5, edgeMap), parallel);
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
        List<Region> toRemove = new ArrayList<>();
        Map<Integer, Double> labelMapThld = Utils.toMapWithNullValues(getRegions().stream(), Region::getLabel, thresholdFunction, false);
        if (dilateRegionRadius>0) {
            labelImage =  (ImageInteger)Filters.applyFilter(getLabelMap(), null, new Filters.BinaryMaxLabelWise().setMask(mask), Filters.getNeighborhood(dilateRegionRadius, mask));
            constructObjects();
        }
        for (Region r : getRegions()) {
            if (!labelMapThld.containsKey(r.getLabel())) continue; // no threshold
            double thld = labelMapThld.get(r.getLabel());
            boolean change = r.erodeContours(erodeMap, thld, darkBackground, keepOnlyBiggestObject, r.getContour(), null);
            if (change) {
                List<Region> subRegions = ImageLabeller.labelImageListLowConnectivity(r.mask);
                if (subRegions.size()>1) {
                    subRegions.sort(Comparator.comparingDouble(sr -> -sr.size()));
                    subRegions.remove(0); // exclude biggest
                    r.ensureMaskIsImageInteger();
                    ImageInteger regionMask = r.getMaskAsImageInteger();
                    for (Region subR: subRegions) {
                        subR.draw(regionMask, 0);
                        subR.translate(r.getBounds()).setIsAbsoluteLandmark(r.absoluteLandmark).setIs2D(r.is2D);
                    }
                    if (!keepOnlyBiggestObject) addedObjects.addAll(subRegions);
                }
                r.resetMask(); // bounds may have changed -> will update bounds
            }
            if (change && r.size() == 0) toRemove.add(r);
        }
        objects.addAll(addedObjects);
        objects.removeAll(toRemove);
        labelImage = null;
        relabel(true);
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
        getLabelMap();
        constructObjects(); // updates bounds of objects
        return this;
    }

    public Region getBackground(ImageMask mask) {
        if (mask!=null && !mask.sameDimensions(getLabelMap())) throw new RuntimeException("Mask should have same size as label map");
        int bckLabel = getRegions().isEmpty() ? 1 : Collections.max(getRegions(), Comparator.comparingInt(Region::getLabel)).getLabel()+1;
        ImageByte bckMask = new ImageByte("", this.getImageProperties()).resetOffset();
        if (mask!=null) ImageOperations.andNot(mask, getLabelMap(), bckMask);
        else ImageOperations.not(getLabelMap(), bckMask);
        return new Region(bckMask, bckLabel, bckMask.sizeZ()==1);
    }
    public void smoothRegions(double radius, boolean eraseVoxelsIfConnectedToBackground, ImageMask mask) { // TODO use region's loop method
        if (mask!=null && !mask.sameDimensions(getLabelMap())) throw new RuntimeException("Mask should have same size as label map");
        Neighborhood n = Filters.getNeighborhood(radius, getImageProperties());
        HashMapGetCreate<Integer, int[]> count = new HashMapGetCreate<>(9, i->new int[1]);
        
        Region bck = getBackground(mask);
        getRegions().add(bck);
        this.redrawLabelMap(true);
        Map<Integer, Region> regionByLabel = getRegions().stream().collect(Collectors.toMap(Region::getLabel, r->r));
        Iterator<Region> rIt = getRegions().iterator();
        Set<Region> modified = new HashSet<>();
        while(rIt.hasNext()) {
            modified.clear();
            Region r = rIt.next();
            if (!eraseVoxelsIfConnectedToBackground && r==bck) continue;
            Set<Voxel> toErase = new HashSet<>();
            r.loop( (x, y, z) -> {
                n.setPixels(x, y, z, getLabelMap(), mask);
                for (int i = 0; i<n.getValueCount(); ++i) count.getAndCreateIfNecessary((int)n.getPixelValues()[i])[0]++;
                if (!eraseVoxelsIfConnectedToBackground) count.remove(bck.getLabel());
                
                if (!count.containsKey(r.getLabel())) {
                    logger.error("smooth interface: {} not present @Voxel: {};{};{}/ bck: {}, counts: {}", r.getLabel(), x, y, z, bck.getLabel(), Utils.toStringList(count.entrySet(), e->e.getKey()+"->"+e.getValue()[0]));
                    //continue; // TODO solve
                } else {
                    int maxLabel = Collections.max(count.entrySet(), Comparator.comparingInt(e -> e.getValue()[0])).getKey();
                    if (maxLabel != r.getLabel() && count.get(maxLabel)[0] > count.get(r.getLabel())[0]) {
                        Voxel v = new Voxel(x, y, z);
                        toErase.add(v);
                        modified.add(r);
                        //if (maxLabel>0) {
                        regionByLabel.get(maxLabel).addVoxels(Arrays.asList(v));
                        modified.add(regionByLabel.get(maxLabel));
                        //}
                        getLabelMap().setPixel(x, y, z, maxLabel);
                    }
                }
                count.clear();
            });
            r.removeVoxels(toErase);
            if (r.size()==0) {
                rIt.remove();
                modified.remove(r);
            }
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
        if (!removed.isEmpty()) mergeWithConnected(removed, true);
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
        if (getRegions().size()<=1) {
            return;
        }
        Region max = objects.get(0);
        double maxSize = max.size();
        for (int i = 1; i < objects.size(); ++i) {
            if (objects.get(i).size() > maxSize) {
                maxSize = objects.get(i).size();
                max = objects.get(i);
            }
        }
        ArrayList<Region> objectsTemp = new ArrayList<>();
        objectsTemp.add(max);
        max.setLabel(1);
        objects = objectsTemp;
        if (labelImage!=null) redrawLabelMap(true);
    }
    
    public void mergeAll() {
        if (getRegions().isEmpty()) return;
        for (Region o : getRegions()) o.setLabel(1);
        this.relabel(false);
        getLabelMap();
        constructObjects();
    }
    public void mergeAllConnected() {
        mergeAllConnected(Integer.MIN_VALUE);
    }
    /**
     * Merge all objects connected if their label is greater or equal {@param fromLabel}
     * Existing region are not modified, when merged, new regions are created
     * @param fromLabel 
     */
    private void mergeAllConnected(int fromLabel) {
        relabel(true);
        Map<Integer, Region> labelMapRegion = getRegions().stream().collect(Collectors.toMap(Region::getLabel, r->r));
        ImageInteger inputLabels = getLabelMap();
        int otherLabel;
        int[][] neigh = inputLabels.sizeZ()>1 ? (lowConnectivity ? ImageLabeller.neigh3DLowHalf : ImageLabeller.neigh3DHalf) : (lowConnectivity ? ImageLabeller.neigh2D4Half : ImageLabeller.neigh2D8Half);
        Voxel n;
        for (int z = 0; z<inputLabels.sizeZ(); z++) {
            for (int y = 0; y<inputLabels.sizeY(); y++) {
                for (int x = 0; x<inputLabels.sizeX(); x++) {
                    int label = inputLabels.getPixelInt(x, y, z);
                    if (label==0) continue;
                    Region currentRegion = labelMapRegion.get(label);
                    for (int i = 0; i<neigh.length; ++i) {
                        n = new Voxel(x+neigh[i][0], y+neigh[i][1], z+neigh[i][2]);
                        if (inputLabels.contains(n.x, n.y, n.z)) { 
                            otherLabel = inputLabels.getPixelInt(n.x, n.y, n.z);   
                            if (otherLabel>0 && otherLabel!=label) {
                                if (label>=fromLabel || otherLabel>=fromLabel) {
                                    Region otherRegion = labelMapRegion.get(otherLabel);
                                    if (otherLabel<label) { // switch so that lowest label remains
                                        Region temp = currentRegion;
                                        currentRegion = otherRegion;
                                        otherRegion = temp;
                                        label = currentRegion.getLabel();
                                        otherLabel = otherRegion.getLabel();
                                    }
                                    Region newRegion = Region.merge(false, currentRegion, otherRegion);
                                    newRegion.setLabel(label);
                                    draw(otherRegion, label);
                                    labelMapRegion.remove(otherLabel);
                                    labelMapRegion.put(label, newRegion);
                                    currentRegion = newRegion;
                                }
                            }
                        }
                    }
                }
            }
        }
        objects = new ArrayList<>(labelMapRegion.values());
    }

    public void mergeWithConnected(Collection<Region> objectsToMerge, boolean eraseUnMergedObjects) {
        // create a new list, with objects to merge at the end, and record the last label to merge
        ArrayList<Region> newObjects = new ArrayList<Region>();
        Set<Region> toMerge=  new HashSet<>(objectsToMerge);
        for (Region o : objects) if (!objectsToMerge.contains(o)) newObjects.add(o);
        int labelToMerge = newObjects.size()+1;
        newObjects.addAll(toMerge);
        this.objects=newObjects;
        relabel(false);
        mergeAllConnected(labelToMerge);
        if (eraseUnMergedObjects) {
            Iterator<Region> it = objects.iterator();
            while (it.hasNext()) {
                Region n = it.next();
                if (n.getLabel() >= labelToMerge) {
                    eraseObject(n, false);
                    it.remove();
                }
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
        Comparator<Region> comp = (arg0, arg1) -> compareCenters(getCenterArray(arg0.getBounds()), getCenterArray(arg1.getBounds()), order);
        Collections.sort(objects, comp);
        relabel(false);
    }

    public void sortBySize(boolean increasing) {
        Comparator<Region> comp = Comparator.comparingDouble(Region::size);
        if (!increasing) comp = comp.reversed();
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

    public Image getEDM(boolean correctionForObjectsTouchingBorder, boolean parallel) {
        Image edm = new ImageFloat("EDM"+getImageProperties().getScaleXY()+ " z:"+getImageProperties().getScaleZ(), getImageProperties());
        for (Region r : getRegions()) {
            EDT edt = new EDT();
            if (correctionForObjectsTouchingBorder) {
                boolean[] touchingBorders = BoundingBox.getTouchingEdges(this.properties, r.getBounds());
                edt.outOfBoundPolicy().setX(!touchingBorders[0], !touchingBorders[1]);
                edt.outOfBoundPolicy().setY(!touchingBorders[2], !touchingBorders[3]);
                edt.outOfBoundPolicy().setZ(!touchingBorders[4], !touchingBorders[5]);
            }
            Image edmR = edt.run(r.getMask(), true, 1, getImageProperties().getScaleZ()/getImageProperties().getScaleXY(), parallel);
            ImageMask.loopWithOffset(r.getMask(), (x, y, z)->{
                edm.setPixelWithOffset(x, y, z, edmR.getPixelWithOffset(x, y, z));
            });
        }
        return edm;
    }

    // euclidean distance to center
    public ImageFloat getECDM() {
        ImageFloat ecdm = new ImageFloat("ECDM"+getImageProperties().getScaleXY()+ " z:"+getImageProperties().getScaleZ(), getImageProperties());
        for (Region r : getRegions()) {
            Point center = r.getCenterOrGeomCenter();
            if (r.is2D()) {
                ImageMask.loopWithOffset(r.getMask(), (x, y, z) -> {
                    ecdm.setPixelWithOffset(x, y, z,  center.distXY(new Point(x, y)));
                });
            } else {
                double scale = getImageProperties().getScaleZ()/getImageProperties().getScaleXY();
                Point c = new Point(center.get(0), center.get(1), center.getWithDimCheck(2));
                ImageMask.loopWithOffset(r.getMask(), (x, y, z) -> {
                    ecdm.setPixelWithOffset(x, y, z,  c.dist(new Point(x, y, z * scale)));
                });
            }
        }
        return ecdm;
    }

    // geodesic distance to center
    public ImageFloat getGCDM(boolean parallel) {
        ImageFloat gcdm = new ImageFloat("GCDM"+getImageProperties().getScaleXY()+ " z:"+getImageProperties().getScaleZ(), getImageProperties());
        List<Image> gdcmList = Utils.parallel(getRegions().stream(),parallel).map(r -> GCDM.run(r.getCenterOrGeomCenter(), r.getMask(), 1, getImageProperties().getScaleZ()/getImageProperties().getScaleXY())).collect(Collectors.toList());
        for (int i = 0; i<gdcmList.size(); ++i) {
            Image gdcmR = gdcmList.get(i);
            Region r = getRegions().get(i);
            ImageMask.loopWithOffset(r.getMask(), (x, y, z)-> {
                gcdm.setPixelWithOffset(x, y, z, gdcmR.getPixelWithOffset(x, y, z));
            });
        }
        return gcdm;
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
        if (order.equals(IndexingOrder.IDX)) throw new RuntimeException("Invalid order");
        if (o1[order.i1] != o2[order.i1]) {
            return Double.compare(o1[order.i1], o2[order.i1]);
        } else if (o1[order.i2] != o2[order.i2]) {
            return Double.compare(o1[order.i2], o2[order.i2]);
        } else {
            return Double.compare(o1[order.i3], o2[order.i3]);
        }
    }
    
    public interface Filter extends SimpleFilter {
        void init(RegionPopulation population);
    }
    public interface SimpleFilter {
        boolean keepObject(Region object);
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
            if (Double.isNaN(testValue)) return false;
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
    public static class Border {
        boolean xl, xr, yup, ydown, zdown, zup;
        public static Border X = new Border(true, true, false, false, false, false), Xl=new Border(true, false, false, false, false, false), Xr=new Border(false, true, false, false, false, false), Y=new Border(false, false, true, true, false, false), YDown=new Border(false, false, false, true, false, false), YUp=new Border(false, false, true, false, false, false), Z=new Border(false, false, false, false, true, true), XY=new Border(true, true, true, true, false, false), XYup=new Border(true, true, true, false, false, false), XYZ=new Border(true, true, true, true, true, true), XlYup=new Border(true, false, true, false, false, false), XrYup=new Border(false, true, true, false, false, false);;
        public Border(boolean xl, boolean xr, boolean yup, boolean ydown, boolean zdown, boolean zup) {
            this.xl = xl;
            this.xr = xr;
            this.yup = yup;
            this.ydown = ydown;
            this.zdown = zdown;
            this.zup = zup;
        }
    };
    public static class ContactBorder implements Filter {

        int contactLimit;
        ImageProperties imageProperties;
        Border border;
        int tolerance, toleranceZ;
        int tolEnd = 1;
        int tolZEnd = 1;
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
        public ContactBorder setToleranceZ(int tolerance) {
            if (tolerance<=0) tolerance=0;
            this.toleranceZ=tolerance;
            this.tolZEnd=tolerance+1;
            return this;
        }
        public boolean contact(Voxel v) {
            if (border.xl && v.x <=tolerance) return true;
            if (border.xr && v.x >= imageProperties.sizeX() - tolEnd) return true;
            if (border.yup && v.y <=tolerance) return true;
            if (border.ydown && v.y >= imageProperties.sizeY() - tolEnd) return true;
            if (border.zdown && v.z <=toleranceZ ) return true;
            if (border.zup && v.z >= imageProperties.sizeZ() - tolZEnd) return true;
            return false;
        }
        public boolean contactWithOffset(Voxel v) {
            if (border.xl && v.x <= imageProperties.xMin() + tolerance) return true;
            if (border.xr && v.x > imageProperties.xMax() - tolEnd) return true;
            if (border.yup && v.y <= imageProperties.yMin() + tolerance) return true;
            if (border.ydown && v.y > imageProperties.yMax() - tolEnd) return true;
            if (border.zdown && v.z <= imageProperties.zMin() + toleranceZ ) return true;
            if (border.zup && v.z > imageProperties.zMax() - tolZEnd) return true;

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
        public boolean contact(Region object) {
            if (object.isAbsoluteLandMark()) {
                // first check if there is intersection of bounding box with considered borders
                if (!intersectWithOffset(object.getBounds())) return false;
                for (Voxel v : object.getContour()) {
                    if (contactWithOffset(v)) return true;
                }
                return false;
            } else {
                // first check if there is intersection of bounding box with considered borders
                if (!intersect(object.getBounds())) return false;
                for (Voxel v : object.getContour()) {
                    if (contact(v)) return true;
                }
                return false;
            }
        }
        public boolean intersect(BoundingBox bounds) {
            if (border.xl && bounds.xMin() <=tolerance) return true;
            if (border.xr && bounds.xMax() >= imageProperties.sizeX() - tolEnd) return true;
            if (border.yup && bounds.yMin() <=tolerance) return true;
            if (border.ydown && bounds.yMax() >= imageProperties.sizeY() - tolEnd) return true;
            if (border.zdown && bounds.zMin() <=toleranceZ) return true;
            if (border.zup && bounds.zMax() >= imageProperties.sizeZ() - tolZEnd) return true;
            return false;
        }
        public boolean intersectWithOffset(BoundingBox bounds) {
            if (border.xl && bounds.xMin() <= imageProperties.xMin() + tolerance) return true;
            if (border.xr && bounds.xMax() > imageProperties.xMax() - tolEnd) return true;
            if (border.yup && bounds.yMin() <= imageProperties.yMin() + tolerance) return true;
            if (border.ydown && bounds.yMax() > imageProperties.yMin() - tolEnd) return true;
            if (border.zdown && bounds.zMin() <= imageProperties.zMin() + toleranceZ ) return true;
            if (border.zup && bounds.zMax() > imageProperties.zMax() - tolZEnd) return true;
            return false;
        }
    }
    public static class ContactBorderMask extends ContactBorder {
        ImageMask mask;
        public ContactBorderMask(int contactLimit, ImageMask mask, Border border) {
            super(contactLimit, mask, border);
            this.mask = mask;
        }
        @Override
        public ContactBorder setTolerance(int tolerance) {
            throw new UnsupportedOperationException("no tolerance for ContactBorderMask");
        }
        @Override
        public ContactBorder setToleranceZ(int tolerance) {
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
            if (border.zdown && (!mask.contains(v.x, v.y, v.z-1) || !mask.insideMask(v.x, v.y, v.z-1))) return true;
            if (border.zup && (!mask.contains(v.x, v.y, v.z+1) || !mask.insideMask(v.x, v.y, v.z+1))) return true;

            return false;
        }
        @Override
        public boolean contactWithOffset(Voxel v) {
            if (!mask.insideMaskWithOffset(v.x, v.y, v.z)) return false;
            if (border.xl && (!mask.containsWithOffset(v.x-1, v.y, v.z) || !mask.insideMaskWithOffset(v.x-1, v.y, v.z))) return true;
            if (border.xr && (!mask.containsWithOffset(v.x+1, v.y, v.z) || !mask.insideMaskWithOffset(v.x+1, v.y, v.z))) return true;
            if (border.yup && (!mask.containsWithOffset(v.x, v.y-1, v.z) || !mask.insideMaskWithOffset(v.x, v.y-1, v.z))) return true;
            if (border.ydown && (!mask.containsWithOffset(v.x, v.y+1, v.z) || !mask.insideMaskWithOffset(v.x, v.y+1, v.z))) return true;
            if (border.zdown && (!mask.containsWithOffset(v.x, v.y, v.z-1) || !mask.insideMaskWithOffset(v.x, v.y, v.z-1) )) return true;
            if (border.zup && ( !mask.containsWithOffset(v.x, v.y, v.z+1) || !mask.insideMaskWithOffset(v.x, v.y, v.z+1))) return true;
            return false;
        }
        @Override
        public boolean intersect(BoundingBox bounds) {
            if (border.xl && bounds.xMin() <=0) return true;
            if (border.xr && bounds.xMax() >= mask.sizeX()-1) return true;
            if (border.yup && bounds.yMin() <=0) return true;
            if (border.ydown && bounds.yMax() >= mask.sizeY()-1) return true;
            if (border.zdown && bounds.zMin() <=0) return true;
            if (border.zup && bounds.zMax() >= mask.sizeZ()-1) return true;
            return false;
        }
        @Override
        public boolean intersectWithOffset(BoundingBox bounds) {
            if (border.xl && bounds.xMin() <=mask.xMin()) return true;
            if (border.xr && bounds.xMax() >= mask.xMax()) return true;
            if (border.yup && bounds.yMin() <=mask.yMin()) return true;
            if (border.ydown && bounds.yMax() >= mask.yMin()) return true;
            if (border.zdown && bounds.zMin() <=mask.zMin()) return true;
            if (border.zup && bounds.zMax() >= mask.zMax()) return true;

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
