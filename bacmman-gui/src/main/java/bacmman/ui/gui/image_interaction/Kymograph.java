package bacmman.ui.gui.image_interaction;

import bacmman.core.DefaultWorker;
import bacmman.data_structure.SegmentedObject;
import bacmman.data_structure.SegmentedObjectEditor;
import bacmman.image.*;
import bacmman.image.Image;
import bacmman.image.io.TimeLapseInteractiveImageFactory;
import bacmman.utils.HashMapGetCreate;
import bacmman.utils.UnaryPair;
import bacmman.utils.Utils;
import bacmman.utils.geom.Point;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public abstract class Kymograph extends TimeLapseInteractiveImage {
    private static final Logger logger = LoggerFactory.getLogger(Kymograph.class);
    public static int GAP =0;
    public static int SIZE =0;
    public static int OVERLAP =0;
    public static double DISPLAY_DISTANCE = 20;
    protected final int frameNumber;
    protected final BoundingBox[] viewArray;
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
        this.viewArray = HyperStack.getViewArray(data.parentTrack, view);
    }

    public Kymograph(TimeLapseInteractiveImageFactory.Data data, BoundingBox view, int channelNumber, BiFunction<SegmentedObject, Integer, Image> imageSupplier, int... loadObjectClass) {
        super(data, view, channelNumber, imageSupplier);
        frameNumber = data.nFramePerSlice;
        loadObjectClasses(loadObjectClass);
        this.viewArray = HyperStack.getViewArray(data.parentTrack, view);
    }

    protected void loadObjectClasses(int... loadObjectClass) {
        for (int loadOC: loadObjectClass) {
            DefaultWorker loadObjectsWorker = new DefaultWorker(i -> {
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
        //logger.debug("start idx = {} for slice: {}", startIdx, sliceIdx);
        if (viewArray == null) return IntStream.range(0, trackOffset.length).mapToObj(i-> new SimpleInteractiveImage(data.parentTrack.get(i+startIdx), trackOffset[i], data.maxSizeZ, sliceIdx, channelNumber, imageSupplier)).toArray(SimpleInteractiveImage[]::new);
        else return IntStream.range(0, trackOffset.length).mapToObj(i-> new SimpleInteractiveImageView(data.parentTrack.get(i+startIdx), viewArray[i], trackOffset[i], data.maxSizeZ, sliceIdx, channelNumber, imageSupplier)).toArray(SimpleInteractiveImage[]::new);
    }
    public Stream<Integer> getSlice(int frame) {
        if (frameMapParentIdx.get(frame)==null) { // this can happen when displayed image is a subset as in tests
            //logger.debug("null parent for frame: {} all parents : {}", frame, data.parentTrack);
            return Stream.empty();
        }
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
            if (res.contains(data.nSlices)) {
                logger.debug("end slice contained. frame: {} parentIdx: {} total frames: {} nFrames per Slice {} overlap: {} pIdx/(nps-overlap): {} ", frame, parentIdx, totalFrames, data.nFramePerSlice, data.frameOverlap, parentIdx / (data.nFramePerSlice - data.frameOverlap));
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
        long t0 = System.currentTimeMillis();
        SimpleInteractiveImage[] trackObjects = this.trackObjects.get(slice);
        BoundingBox[] trackOffset = this.trackOffset.get(slice);
        long t1 = System.currentTimeMillis();
        IntStream.range(0, trackOffset.length).parallel().forEach(i->{
            Image subImage = trackObjects[i].imageSupplier.apply(trackObjects[i].parent, channelIdx);
            Image.pasteImage(TypeConverter.cast(subImage, type), image_, trackOffset[i]);
        });
        long t2 = System.currentTimeMillis();
        //logger.debug("generate image: s={} c={}, get objects: {} paste images: {}", slice, channelIdx, t1-t0, t2-t1);
        image.setName(getImageTitle());
    }

    @Override public LazyImage5D generateImage() {
        LazyImage5D im = trackObjects.get(0)[0].generateImage(); // to homogenize type
        ImageProperties props = getImageProperties();
        Function<int[], Image> generator = fc -> {
            int sizeZ = im.getImage(fc[0], fc[1]).sizeZ();
            ImageProperties curProps = new SimpleImageProperties(props.sizeX(), props.sizeY(), sizeZ, props.getScaleXY(), props.getScaleZ());
            //logger.debug("generate image for frame: {} channel : {} z = {}", fc[0], fc[1], sizeZ);
            Image displayImage = Image.createEmptyImage(name, im.getImageType(), curProps);
            updateImage(displayImage, fc[1], fc[0]);
            return displayImage;
        };
        LazyImage5D image = new LazyImage5DStack(getImageTitle(), props, im.getImageType(), generator, new int[]{data.nSlices, channelNumber});
        if (defaultImageSupplier) {
            image.setChannelNames(parent.getExperimentStructure().getChannelNames());
            image.setChannelColors(parent.getExperimentStructure().getChannelColorNames());
        }
        return image;
    }

    public Set<SegmentedObject> getNextTracks(int objectClassIdx, int sliceIdx, List<SegmentedObject> currentTracks, boolean next) {
        Map<SegmentedObject, Point> centers = new HashMapGetCreate.HashMapGetCreateRedirected<>(so -> so.getRegion().getCenterOrGeomCenter());
        int startFrame = parentIdxMapFrame.get(getStartParentIdx(sliceIdx));
        int endFrame = startFrame + data.nFramePerSlice;
        BiFunction<SegmentedObject, SegmentedObject, UnaryPair<SegmentedObject>> getComparableObjects = (o1, o2) -> {
            // compare centers at common slice >= startFrame, or closest slice
            if (o1.getFrame()<startFrame) o1 = o1.getNextAtFrame(startFrame, true);
            if (o2.getFrame()<startFrame) o2 = o2.getNextAtFrame(startFrame, true);
            if (o1.getFrame()>startFrame && o2.getFrame()==startFrame) o2 = o2.getNextAtFrame(o1.getFrame(), true);
            else if (o1.getFrame()==startFrame && o2.getFrame()>startFrame) o1 = o1.getNextAtFrame(o2.getFrame(), true);
            else if (o1.getFrame()<o2.getFrame()) o1 = o1.getNextAtFrame(o2.getFrame(), true);
            else if (o2.getFrame()<o1.getFrame()) o2 = o2.getNextAtFrame(o1.getFrame(), true);
            return new UnaryPair<>(o1, o2);
        };
        Comparator<SegmentedObject> comp = (o1, o2) -> {
            UnaryPair<SegmentedObject> c = getComparableObjects.apply(o1, o2);
            int mul = next ? -1 : 1; // inverse comparator to speed up removal from list
            return mul * compareCenters(centers.get(c.key), centers.get(c.value));
        };
        List<SegmentedObject> tracks = getObjects(objectClassIdx, sliceIdx).map(SegmentedObject::getTrackHead).distinct().sorted(comp).collect(Collectors.toList());
        currentTracks.retainAll(tracks); // remove tracks that are not included in this slice
        List<SegmentedObject> toRemove = new ArrayList<>(); // remove directly connected tracks that could have been added in a previous call of this function and that are located after in the list
        if (!currentTracks.isEmpty()) { // remove all tracks before currently selected tracks
            currentTracks.sort(comp);
            for (int i = 0; i<currentTracks.size(); ++i) {
                SegmentedObject th  = currentTracks.get(i);
                if (th.getPrevious()!=null) {
                    int j = currentTracks.indexOf(th.getPrevious().getTrackHead());
                    if (j>=0 && j>i) toRemove.add(currentTracks.get(i));

                }
            }
            currentTracks.removeAll(toRemove);
            if (!currentTracks.isEmpty()) {
                int idx = tracks.indexOf(currentTracks.get(0));
                //logger.debug("idx: {} tracks: {} currently selected tracks: {} to remove: {}", idx, tracks, currentTracks, toRemove);
                tracks = tracks.subList(0, idx);
            } //else logger.debug("no current tracks. linked removed: {}", toRemove);
            tracks.removeAll(toRemove); // already displayed
        }

        Set<SegmentedObject> selectedTracks = new HashSet<>();
        if (!tracks.isEmpty()) selectedTracks.add(tracks.remove(tracks.size()-1));
        while(!tracks.isEmpty()) { // add all remaining tracks that are distant enough from tracks to be displayed
            SegmentedObject cur = tracks.get(tracks.size() -1);
            if (selectedTracks.stream().anyMatch( o -> {
                UnaryPair<SegmentedObject> c = getComparableObjects.apply(o, cur);
                if (c.key.getFrame()!=c.value.getFrame()) return false; // no overlap between tracks
                return getDistance(centers.get(c.key), centers.get(c.value)) < DISPLAY_DISTANCE;
            })) break;
            else {
                tracks.remove(tracks.size()-1);
                selectedTracks.add(cur);
            }
        }

        // also add directly connected tracks
        List<SegmentedObject> toAdd = new ArrayList<>();
        for (SegmentedObject th : selectedTracks) {
            // for parents and siblings
            /*if (th.getFrame()>startFrame && th.getPrevious()!=null && !selectedTracks.contains(th.getPrevious().getTrackHead()) && !currentTracks.contains(th.getPrevious().getTrackHead())) {
                toAdd.add(th.getPrevious().getTrackHead());
                SegmentedObjectEditor.getNext(th.getPrevious()).filter(nTh -> !nTh.equals(th)).forEach(toAdd::add); // add siblings
            }*/
            SegmentedObject tail = th.getTrackTail(endFrame);
            if (tail != null) { // track has a tail in this slice
                SegmentedObjectEditor.getNext(tail).forEach(toAdd::add); // add children
            }
        }
        selectedTracks.addAll(toAdd);

        return selectedTracks;
    }

    protected abstract int compareCenters(Point c1, Point c2);
    protected abstract double getDistance(Point c1, Point c2);
}
