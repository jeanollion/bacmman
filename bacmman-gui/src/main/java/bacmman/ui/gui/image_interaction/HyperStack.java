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
package bacmman.ui.gui.image_interaction;

import bacmman.core.DefaultWorker;
import bacmman.data_structure.SegmentedObject;
import bacmman.image.*;
import bacmman.image.io.TimeLapseInteractiveImageFactory;
import bacmman.processing.Resize;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static bacmman.image.LazyImage5DPlane.homogenizeType;

/**
 *
 * @author Jean Ollion
 */
public class HyperStack extends TimeLapseInteractiveImage {
    private static final Logger logger = LoggerFactory.getLogger(HyperStack.class);
    protected final int maxParentSizeX, maxParentSizeY;
    protected final int maxParentSizeZ;
    protected final BoundingBox bounds, bounds2D;

    public static HyperStack generateHyperstack(List<SegmentedObject> parentTrack, int... loadObjectClassIdx) {
        return new HyperStack(TimeLapseInteractiveImageFactory.generateHyperstackData(parentTrack, true), loadObjectClassIdx);
    }
    public static HyperStack generateHyperstack(List<SegmentedObject> parentTrack, int channelNumber, BiFunction<SegmentedObject, Integer, Image> imageSupplier, int... loadObjectClassIdx) {
        return new HyperStack(TimeLapseInteractiveImageFactory.generateHyperstackData(parentTrack, true), channelNumber, imageSupplier, loadObjectClassIdx);
    }
    public HyperStack(TimeLapseInteractiveImageFactory.Data data, int... loadObjectClassIdx) {
        super(data);
        maxParentSizeX = data.maxParentSizeX;
        maxParentSizeY = data.maxParentSizeY;
        maxParentSizeZ = data.maxParentSizeZ;
        this.bounds = new SimpleBoundingBox(0, maxParentSizeX-1, 0, maxParentSizeY-1, 0, maxParentSizeZ-1);
        this.bounds2D = new SimpleBoundingBox(0, maxParentSizeX-1, 0, maxParentSizeY-1, 0 ,0);
        if (!TimeLapseInteractiveImageFactory.DIRECTION.T.equals(data.direction)) throw new IllegalArgumentException("Invalid direction");
        loadObjectClasses(loadObjectClassIdx);
    }
    public HyperStack(TimeLapseInteractiveImageFactory.Data data, int channelNumber, BiFunction<SegmentedObject, Integer, Image> imageSupplier, int... loadObjectClassIdx) {
        super(data, channelNumber, imageSupplier);
        maxParentSizeX = data.maxParentSizeX;
        maxParentSizeY = data.maxParentSizeY;
        maxParentSizeZ = data.maxParentSizeZ;
        this.bounds = new SimpleBoundingBox(0, maxParentSizeX-1, 0, maxParentSizeY-1, 0, maxParentSizeZ-1);
        this.bounds2D = new SimpleBoundingBox(0, maxParentSizeX-1, 0, maxParentSizeY-1, 0 ,0);
        if (!TimeLapseInteractiveImageFactory.DIRECTION.T.equals(data.direction)) throw new IllegalArgumentException("Invalid direction");
        loadObjectClasses(loadObjectClassIdx);
    }
    public void loadObjectClasses(int... loadObjectClassIdx) {
        trackObjects.get(0)[0].reloadObjects(loadObjectClassIdx);
        for (int loadOC: loadObjectClassIdx) {
            DefaultWorker loadObjectsWorker = new DefaultWorker(i -> { // TODO parallel ?
                trackObjects.get(0)[i+1].reloadObjects(loadOC);
                return "";
            }, super.getParents().size() - 1, null).setCancel(() -> getAccessor().getDAO(getParent()).closeThreadResources());
            loadObjectsWorker.execute();
            loadObjectsWorker.setStartTime();
            worker.add(loadObjectsWorker);
        }
    }

    public int getSlice(int frame) {
        return frameMapParentIdx.get(frame);
    }

    @Override
    protected int getOffsetIdx(int frame, int sliceIdx) {
        return sliceIdx;
    }

    @Override
    protected BoundingBox[] makeTrackOffset(int sliceIdx) {
        Offset off = data.trackOffset[0].duplicate().reverseOffset();
        return Arrays.stream(data.trackOffset).map(boundingBox -> boundingBox.duplicate().translate(off)).toArray(BoundingBox[]::new);
    }
    @Override
    protected SimpleInteractiveImage[] makeTrackObjects(int sliceIdx) {
        BoundingBox[] trackOffset = this.trackOffset.get(0);
        return IntStream.range(0, trackOffset.length).mapToObj(i-> new SimpleInteractiveImage(data.parentTrack.get(i), trackOffset[i], data.maxParentSizeZ, i, channelNumber, imageSupplier)).toArray(SimpleInteractiveImage[]::new);
    }

    @Override
    public BoundingBox getObjectOffset(SegmentedObject object, int slice) {
        if (object==null) return null;
        SimpleInteractiveImage[] trackObjects = this.trackObjects.get(0);
        return trackObjects[slice].getObjectOffset(object, 0);
    }

    @Override
    public List<ObjectDisplay> toObjectDisplay(Collection<SegmentedObject> objects) {
        return objects.stream().map(o -> {
            int slice = getSlice(o.getFrame());
            BoundingBox b = this.getObjectOffset(o, slice);
            return b==null ? null : new ObjectDisplay(o, b, slice);
        }).filter(Objects::nonNull).collect(Collectors.toList());
    }

    @Override
    public Stream<ObjectDisplay> getObjectDisplay(int objectClassIdx, int slice) {
        return trackObjects.get(0)[slice].getAllObjectDisplay(objectClassIdx);
    }

    @Override
    public Stream<ObjectDisplay> getAllObjectDisplay(int objectClassIdx) {
        return IntStream.range(0, trackObjects.get(0).length).boxed().flatMap(i -> trackObjects.get(0)[i].getAllObjectDisplay(objectClassIdx));
    }

    @Override
    public Stream<SegmentedObject> getObjects(int objectClassIdx, int slice) {
        return trackObjects.get(0)[slice].getAllObjects(objectClassIdx);
    }

    @Override
    public Stream<SegmentedObject> getAllObjects(int objectClassIdx) {
        SimpleInteractiveImage[] trackObjects = this.trackObjects.get(0);
        return IntStream.range(0, trackObjects.length).boxed().flatMap(i -> trackObjects[i].getAllObjects(objectClassIdx));
    }

    @Override
    public ObjectDisplay getObjectAtPosition(int x, int y, int z, int objectClassIdx, int slice) {
        if (is2D()) z=0; //do not take in account z in 2D case.
        return trackObjects.get(0)[slice].getObjectAtPosition(x, y, z, objectClassIdx, slice);
    }
    
    @Override
    public void addObjectsWithinBounds(BoundingBox selection, int objectClassIdx, int slice, List<ObjectDisplay> list) {
        if (is2D() && selection.sizeZ()>0) selection=new SimpleBoundingBox(selection.xMin(), selection.xMax(), selection.yMin(), selection.yMax(), 0, 0);
        //logger.debug("kymo: {}, idx: {}, all objects: {}", this, idx, trackObjects[idx].objects);
        trackObjects.get(0)[slice].addObjectsWithinBounds(selection, objectClassIdx, slice, list);
    }

    public String getImageTitle() {
        return getName() == null || getName().isEmpty() ? (getParents().get(0).isRoot() ? "HyperStack of Position: #"+getParents().get(0).getPositionIdx() : "HyperStack of Track: "+getParents().get(0)): getName();
    }
    @Override public LazyImage5D generateImage() {
        int frames = getParents().size();
        int[] fczSize = new int[]{frames, channelNumber, data.maxParentSizeZ};
        Function<int[], Image> imageOpenerCT  = (fcz) -> getPlane(fcz[2], fcz[1], fcz[0], Resize.EXPAND_MODE.BORDER);
        return new LazyImage5DPlane(getImageTitle(), homogenizeType(channelNumber, imageOpenerCT), fczSize);
    }

    @Override 
    public ImageProperties getImageProperties() {
        return new SimpleImageProperties( maxParentSizeX, maxParentSizeY, maxParentSizeZ, data.parentTrack.get(0).getMaskProperties().getScaleXY(), data.parentTrack.get(0).getMaskProperties().getScaleZ());
    }
    public Image getImage(int channelIdx, int slice, Resize.EXPAND_MODE paddingMode) {
        Image image = imageSupplier.apply(data.parentTrack.get(slice), channelIdx);
        if (bounds.sameDimensions(image)) return image; // no need for padding
        else { // TODO larger crop instead of PAD
            Image resized = Resize.pad(image, paddingMode, new SimpleBoundingBox(0, maxParentSizeX-1, 0, maxParentSizeY-1, 0, maxParentSizeZ-1).translate(trackOffset.get(0)[slice]));
            return resized;
        }
    }
    public Image getPlane(int z, int channelIdx, int slice, Resize.EXPAND_MODE paddingMode) {
        Image image = imageSupplier.apply(data.parentTrack.get(slice), channelIdx);
        if (z>0 && image.sizeZ()==1) z = 0;
        image = image.getZPlane(z);
        if (bounds2D.sameDimensions(image)) return image; // no need for padding
        else { // TODO larger crop instead of PAD
            Image resized = Resize.pad(image, paddingMode, new SimpleBoundingBox(0, maxParentSizeX-1, 0, maxParentSizeY-1, 0, 0).translate(trackOffset.get(0)[slice]).translate(new SimpleOffset(0, 0, z)));
            return resized;
        }
    }
}
