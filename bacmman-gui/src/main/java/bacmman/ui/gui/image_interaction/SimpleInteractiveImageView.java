package bacmman.ui.gui.image_interaction;

import bacmman.data_structure.SegmentedObject;
import bacmman.image.*;
import bacmman.utils.Utils;

import java.util.Collections;
import java.util.List;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public class SimpleInteractiveImageView extends SimpleInteractiveImage {
    final SimpleBoundingBox view, relView;
    final SimpleOffset viewRelOffRev;

    /**
     *
     * @param parent
     * @param view  relative to parent
     * @param additionalOffset
     * @param sizeZ
     * @param sliceIdx
     * @param channelNumber
     * @param imageSupplier
     */
    public SimpleInteractiveImageView(SegmentedObject parent, BoundingBox view, Offset additionalOffset, int sizeZ, int sliceIdx, int channelNumber, BiFunction<SegmentedObject, Integer, Image> imageSupplier) {
        super(parent, additionalOffset, sizeZ, sliceIdx, channelNumber, imageSupplierView(imageSupplier, view));
        this.view = new SimpleBoundingBox(view).translate(parent.getBounds()).translate(additionalOffset);
        this.relView = new SimpleBoundingBox(view).resetOffset().translate(additionalOffset);
        this.viewRelOffRev = new SimpleOffset(view).translate(parent.getBounds()).reverseOffset();
    }
    public static BiFunction<SegmentedObject, Integer, Image> imageSupplierView(BiFunction<SegmentedObject, Integer, Image> imageSupplier, BoundingBox view) {
        return (p, i) -> {
            Image<?> im = imageSupplier.apply(p, i);
            BoundingBox bds = new SimpleBoundingBox(view.xMin(), view.xMax(), view.yMin(), view.yMax(), 0, im.sizeZ()-1);
            return im.crop(bds).resetOffset();
        };
    }
    @Override
    protected void reloadObjects(int objectClassIdx) {
        if (objectClassIdx == parentStructureIdx) {
            objects.put(objectClassIdx, Collections.singletonList(parent));
            offsets.put(objectClassIdx, new BoundingBox[]{view.duplicate().resetOffset().translate(additionalOffset)});
        } else  {
            Stream<SegmentedObject> str = parent.getChildren(objectClassIdx);
            if (str==null) {
                objects.put(objectClassIdx, Collections.emptyList());
                offsets.put(objectClassIdx, new BoundingBox[0]);
            }
            else {
                objects.put(objectClassIdx, str.collect(Collectors.toList()));
                offsets.put(objectClassIdx, objects.get(objectClassIdx).stream().map(o->o.getBounds().duplicate().translate(viewRelOffRev).translate(additionalOffset)).toArray(BoundingBox[]::new));
            }
        }
    }

    @Override
    public BoundingBox getObjectOffset(SegmentedObject object, int slice) {
        List<SegmentedObject> objects = this.getObjectsAsList(object.getStructureIdx());
        int i = objects.indexOf(object);
        if (i >= 0) {
            return getOffsets(object.getStructureIdx())[i];
        } else {
            return object.getBounds().duplicate().translate(viewRelOffRev).translate(additionalOffset);
        }
    }

    @Override
    public List<SegmentedObject> getObjectsAsList(int objectClassIdx) {
        return getAllObjects(objectClassIdx).collect(Collectors.toList());
    }

    @Override
    public BoundingBox[] getOffsets(int objectClassIdx) {
        BoundingBox[] offsets = super.getOffsets(objectClassIdx);
        return getIncludedIndices(objectClassIdx).mapToObj(i->offsets[i]).toArray(BoundingBox[]::new);
    }

    @Override
    public Stream<SegmentedObject> getAllObjects(int objectClassIdx) {
        List<SegmentedObject> res = super.getObjectsAsList(objectClassIdx);
        return getIncludedIndices(objectClassIdx).mapToObj(res::get);
    }

    @Override
    public Stream<ObjectDisplay> getAllObjectDisplay(int objectClassIdx) {
        List<SegmentedObject> res = super.getObjectsAsList(objectClassIdx);
        BoundingBox[] offsets = super.getOffsets(objectClassIdx);
        return getIncludedIndices(objectClassIdx).mapToObj(i->new ObjectDisplay(res.get(i), offsets[i], sliceIdx));
    }

    protected IntStream getIncludedIndices(int objectClassIdx) {
        BoundingBox[] offsets = super.getOffsets(objectClassIdx);
        return IntStream.range(0, offsets.length).filter(i -> BoundingBox.isIncluded2D(offsets[i], relView, 5));
        //return IntStream.range(0, offsets.length);
    }

    @Override
    public ImageProperties getImageProperties() {
        return new SimpleImageProperties(view.sizeX(), view.sizeY(), sizeZ, parent.getScaleXY(), parent.getScaleZ());
    }
}
