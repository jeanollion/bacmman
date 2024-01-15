package bacmman.ui.gui.image_interaction;

import bacmman.core.DefaultWorker;
import bacmman.data_structure.SegmentedObject;
import bacmman.image.*;
import bacmman.image.io.TimeLapseInteractiveImageFactory;
import bacmman.utils.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public abstract class Kymograph extends TimeLapseInteractiveImage {
    private static final Logger logger = LoggerFactory.getLogger(Kymograph.class);
    public static int GAP =0;
    public static int SIZE =0;
    public static int OVERLAP =0;
    protected final int frameNumber;
    protected final BoundingBox[] view;
    protected final BoundingBox originalView;
    public static Kymograph generateKymograph(List<SegmentedObject> parentTrack, BoundingBox view, int... loadObjectClass) {
        TimeLapseInteractiveImageFactory.Data data = view == null ? TimeLapseInteractiveImageFactory.generateKymographData(parentTrack, false, GAP, SIZE, OVERLAP):
                TimeLapseInteractiveImageFactory.generateKymographViewData(parentTrack, view, GAP, SIZE, OVERLAP);
        switch (data.direction) {
            case X:
            default:
                return new KymographX(data, view, loadObjectClass);
            case Y:
                return new KymographY(data, view, loadObjectClass);
        }
    }
    public static Kymograph generateKymograph(List<SegmentedObject> parentTrack, BoundingBox view, int channelNumber, int sizeZ, BiFunction<SegmentedObject, Integer, Image> imageSupplier, int... loadObjectClass) {
        TimeLapseInteractiveImageFactory.Data data = view == null ? TimeLapseInteractiveImageFactory.generateKymographData(parentTrack, sizeZ, false, GAP, SIZE, OVERLAP) :
                TimeLapseInteractiveImageFactory.generateKymographViewData(parentTrack, view, sizeZ, GAP, SIZE, OVERLAP);
        switch (data.direction) {
            case X:
            default:
                return new KymographX(data, view, channelNumber, imageSupplier, loadObjectClass);
            case Y:
                return new KymographY(data, view, channelNumber, imageSupplier, loadObjectClass);
        }
    }
    public Kymograph(TimeLapseInteractiveImageFactory.Data data, BoundingBox view, int... loadObjectClass) {
        super(data, view);
        frameNumber = data.nFramePerSlice;
        loadObjectClasses(loadObjectClass);
        this.view = HyperStack.getViewArray(data.parentTrack, view);
        this.originalView = view;
    }

    public Kymograph(TimeLapseInteractiveImageFactory.Data data, BoundingBox view, int channelNumber, BiFunction<SegmentedObject, Integer, Image> imageSupplier, int... loadObjectClass) {
        super(data, view, channelNumber, imageSupplier);
        frameNumber = data.nFramePerSlice;
        loadObjectClasses(loadObjectClass);
        this.view = HyperStack.getViewArray(data.parentTrack, view);
        this.originalView = view;
    }

    protected void loadObjectClasses(int... loadObjectClass) {
        for (int loadOC: loadObjectClass) {
            DefaultWorker loadObjectsWorker = new DefaultWorker(i -> { // TODO parallel ?
                data.parentTrack.get(i).getChildren(loadOC);
                return "";
            }, data.parentTrack.size(), null).setCancel(() -> getAccessor().getDAO(getParent()).closeThreadResources());
            loadObjectsWorker.execute();
            loadObjectsWorker.setStartTime();
            worker.add(loadObjectsWorker);
        }
    }


    public abstract int getClosestFrame(int x, int y, int slice);

    protected int getStartParentIdx(int sliceIdx) {
        if (data.nSlices == 1) return 0;
        if (sliceIdx>=data.nSlices) throw new IllegalArgumentException("Invalid kymograph slice: "+sliceIdx+" / "+data.nSlices);
        if (sliceIdx == 0) return 0;
        if (sliceIdx == data.nSlices-1) return data.parentTrack.size() - data.nFramePerSlice;
        return (data.nFramePerSlice - data.frameOverlap) * sliceIdx;
    }

    @Override
    protected BoundingBox[] makeTrackOffset(int sliceIdx) {
        int startIdx = getStartParentIdx(sliceIdx);
        Offset off = data.trackOffset[startIdx].duplicate().reverseOffset();
        return IntStream.range(0, data.nFramePerSlice).mapToObj(i -> data.trackOffset[i+startIdx].duplicate().translate(off)).toArray(BoundingBox[]::new);
    }
    @Override
    protected SimpleInteractiveImage[] makeTrackObjects(int sliceIdx) {
        BoundingBox[] trackOffset = this.trackOffset.get(sliceIdx);
        int startIdx = getStartParentIdx(sliceIdx);
        logger.debug("start idx = {} for slice: {}", startIdx, sliceIdx);
        if (view == null) return IntStream.range(0, trackOffset.length).mapToObj(i-> new SimpleInteractiveImage(data.parentTrack.get(i+startIdx), trackOffset[i], data.maxSizeZ, sliceIdx, channelNumber, imageSupplier)).toArray(SimpleInteractiveImage[]::new);
        else return IntStream.range(0, trackOffset.length).mapToObj(i-> new SimpleInteractiveImageView(data.parentTrack.get(i+startIdx), view[i], trackOffset[i], data.maxSizeZ, sliceIdx, channelNumber, imageSupplier)).toArray(SimpleInteractiveImage[]::new);
    }
    public Stream<Integer> getSlice(int frame) {
        int parentIdx = frameMapParentIdx.get(frame);
        if (parentIdx<data.nFramePerSlice - data.frameOverlap) return Stream.of(0);
        else {
            int totalFrames = data.parentTrack.size();
            int sliceIdx = (parentIdx>=totalFrames - data.nFramePerSlice) ? data.nSlices - 1 : parentIdx / (data.nFramePerSlice - data.frameOverlap);
            List<Integer> res=new ArrayList<>();
            res.add(sliceIdx);
            int prevSlice = sliceIdx-1; // check if previous slices are also included
            while(prevSlice>=0) {
                int prevIdxEnd = getStartParentIdx(prevSlice) + data.nFramePerSlice;
                if (parentIdx<prevIdxEnd) res.add(prevSlice);
                else break;
                --prevSlice;
            }
            return res.stream();
        }
    }

    public void addObjectsFromOverlappingSlices(List<ObjectDisplay> list) {
        int lastIdx = list.size();
        for (int i = 0; i<lastIdx; ++i) {
            ObjectDisplay objectDisplay = list.get(i);
            getSlice(objectDisplay.object.getFrame()).filter(s -> s != objectDisplay.sliceIdx)
                    .map(s -> toObjectDisplay(objectDisplay.object, s)).forEach(list::add);
        }
    }

    @Override
    public BoundingBox getObjectOffset(SegmentedObject object, int slice) {
        SimpleInteractiveImage[] trackObjects = this.trackObjects.get(slice);
        if (object==null) return null;
        int idx = getOffsetIdx(object.getFrame(), slice);
        BoundingBox b = idx<0 ? null : trackObjects[idx].getObjectOffset(object, 0);
        if (b==null) { // search in adjacent slides
            if (slice>0 && this.trackObjects.get(slice-1)!=null && object.getFrame()<trackObjects[0].parent.getFrame()) {
                idx = getOffsetIdx(object.getFrame(), slice - 1);
                b = idx<0 ? null : this.trackObjects.get(slice-1)[idx].getObjectOffset(object, 0).duplicate();
            }
            if (b!=null) {
                Offset off = data.trackOffset[getStartParentIdx(slice)].duplicate().reverseOffset();
                Offset offPrev = data.trackOffset[getStartParentIdx(slice-1)];
                b.translate(offPrev).translate(off); // put offset in landmark of slice
            } else if (slice+1<data.nSlices && this.trackObjects.get(slice+1)!=null) {
                idx = getOffsetIdx(object.getFrame(), slice + 1);
                b = idx<0 ? null : this.trackObjects.get(slice+1)[idx].getObjectOffset(object, 0).duplicate();
                if (b!=null) {
                    Offset off = data.trackOffset[getStartParentIdx(slice)].duplicate().reverseOffset();
                    Offset offNext = data.trackOffset[getStartParentIdx(slice+1)];
                    b.translate(offNext).translate(off); // put offset in landmark of slice
                }
            }
        }
        return b;
    }

    @Override
    public Stream<ObjectDisplay> toObjectDisplay(Stream<SegmentedObject> objects) {
        return objects
            .flatMap(o -> getSlice(o.getFrame())// redundancy when overlap > 0
            .map(s -> {
                BoundingBox b = this.getObjectOffset(o, s);
                if (b==null) return null;
                else return new ObjectDisplay(o, b, s);
            }).filter(Objects::nonNull));
    }

    @Override
    public Stream<ObjectDisplay> getObjectDisplay(int objectClassIdx, int slice) {
        SimpleInteractiveImage[] trackObjects = this.trackObjects.get(slice);
        return Arrays.stream(trackObjects).flatMap(i -> i.getAllObjectDisplay(objectClassIdx));
    }

    @Override
    public Stream<ObjectDisplay> getAllObjectDisplay(int objectClassIdx) {
        return IntStream.range(0, data.nSlices).boxed().map(s -> trackObjects.get(s)).flatMap(Arrays::stream)
                .filter(Utils.distinctByKey(u -> u.parent.getFrame())).flatMap(i->i.getAllObjectDisplay(objectClassIdx));
    }

    @Override
    public Stream<SegmentedObject> getObjects(int objectClassIdx, int slice) {
        SimpleInteractiveImage[] trackObjects = this.trackObjects.get(slice);
        return Arrays.stream(trackObjects).flatMap(i -> i.getAllObjects(objectClassIdx));
    }

    @Override
    public Stream<SegmentedObject> getObjectsAtFrame(int objectClassIdx, int frame) {
        int sliceIdx = getSlice(frame).mapToInt(i->i).max().getAsInt();
        SimpleInteractiveImage[] trackObjects = this.trackObjects.get(sliceIdx);
        for (SimpleInteractiveImage i : trackObjects) {
            if (i.parent.getFrame() == frame) return i.getAllObjects(objectClassIdx);
        }
        return Stream.empty();
    }

    @Override
    public Stream<SegmentedObject> getAllObjects(int objectClassIdx) {
        return IntStream.range(0, data.nSlices).boxed().map(s -> trackObjects.get(s))
                .flatMap(Arrays::stream)
                .filter(Utils.distinctByKey(u -> u.parent.getFrame())).flatMap(i->i.getAllObjects(objectClassIdx));
    }

    protected void updateImage(Image image, final int channelIdx, final int slice) {
        Image image_;
        if (image instanceof LazyImage5D) {
            image_ = ((LazyImage5D)image).getImage(0, channelIdx);
        } else image_ = image;
        Image type = Image.copyType(image_);
        SimpleInteractiveImage[] trackObjects = this.trackObjects.get(slice);
        BoundingBox[] trackOffset = this.trackOffset.get(slice);
        IntStream.range(0, trackOffset.length).parallel().forEach(i->{
            Image subImage = trackObjects[i].imageSupplier.apply(trackObjects[i].parent, channelIdx);
            Image.pasteImage(TypeConverter.cast(subImage, type), image_, trackOffset[i]);
        });
        image.setName(getImageTitle());
    }

    @Override public LazyImage5D generateImage() {
        LazyImage5D im = trackObjects.get(0)[0].generateImage(); // will homogenize type
        ImageProperties props = getImageProperties();
        Function<int[], Image> generator = fc -> {
            int sizeZ = defaultImageSupplier ? parent.getExperimentStructure().sizeZ(parent.getPositionName(), fc[1]) : getMaxSizeZ();
            ImageProperties curProps = new SimpleImageProperties(props.sizeX(), props.sizeY(), sizeZ, props.getScaleXY(), props.getScaleZ());
            logger.debug("generate image for frame: {} channel : {} z = {}. default image supplier : {}", fc[0], fc[1], sizeZ, defaultImageSupplier);
            Image displayImage = Image.createEmptyImage(name, im.getImageType(), curProps);
            updateImage(displayImage, fc[1], fc[0]);
            return displayImage;
        };
        return new LazyImage5DStack(getImageTitle(), props, im.getImageType(), generator, new int[]{data.nSlices, channelNumber});
    }

}
