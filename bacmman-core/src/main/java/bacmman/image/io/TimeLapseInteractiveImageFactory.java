package bacmman.image.io;

import bacmman.data_structure.ExperimentStructure;
import bacmman.data_structure.SegmentedObject;
import bacmman.image.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;

public class TimeLapseInteractiveImageFactory {
    public final static Logger logger = LoggerFactory.getLogger(TimeLapseInteractiveImageFactory.class);
    public static Data generateKymographData(List<SegmentedObject> parentTrack, boolean middle, int gap, int size, int overlap) {
        ExperimentStructure xp = parentTrack.get(0).getExperimentStructure();
        int maxParentSizeZ = IntStream.range(0, xp.getChannelNumber()).map(c -> xp.sizeZ(parentTrack.get(0).getPositionName(), c)).max().getAsInt();
        return generateKymographData(parentTrack, maxParentSizeZ, middle,gap, size, overlap);
    }
    public static Data generateKymographData(List<SegmentedObject> parentTrack, int maxSizeZ, boolean middle, int gap, int size, int overlap) {
        BoundingBox bb = parentTrack.get(0).getBounds();
        BoundingBox[] trackOffset =  parentTrack.stream().map(p-> new SimpleBoundingBox(p.getBounds()).resetOffset()).toArray(l -> new BoundingBox[l]);
        int currentOffset=0;
        if (bb.sizeY() >= bb.sizeX()) { // X direction.
            int maxParentSizeY = parentTrack.stream().mapToInt(p->p.getBounds().sizeY()).max().getAsInt();
            int averageSize = (int)Math.ceil(Arrays.stream(trackOffset).mapToDouble(BoundingBox::sizeX).average().getAsDouble());
            int avgNFramePerSlice = size == 0 ? 0 : Math.max(2, (int)Math.ceil((double)size / averageSize));
            int avgNFrameOverlap = (int)Math.ceil((double)overlap / averageSize);
            logger.debug("frame number: {} overlap: {} avg size: {} size: {} overlap: {}", avgNFramePerSlice, avgNFrameOverlap, averageSize, size, overlap);
            for (int i = 0; i<parentTrack.size(); ++i) {
                if (middle) trackOffset[i].translate(new SimpleOffset(currentOffset, (int)((maxParentSizeY)/2.0-(trackOffset[i].sizeY())/2.0), 0)); // Y middle of parent track
                else trackOffset[i].translate(new SimpleOffset(currentOffset, 0, 0)); // Y up of parent track
                currentOffset+=gap+averageSize;
            }
            return new Data(DIRECTION.X, -1, maxParentSizeY, maxSizeZ, trackOffset, parentTrack, avgNFramePerSlice, avgNFrameOverlap);
        } else { // Y direction
            int maxParentSizeX = parentTrack.stream().mapToInt(p->p.getBounds().sizeX()).max().getAsInt();
            int averageSize = (int)Math.ceil(Arrays.stream(trackOffset).mapToDouble(BoundingBox::sizeY).average().getAsDouble());
            int framePerSlice = size == 0 ? 0 : Math.max(2, (int)Math.ceil((double)size / averageSize));
            int frameOverlap = (int)Math.ceil((double)overlap / averageSize);
            for (int i = 0; i<parentTrack.size(); ++i) {
                if (middle) trackOffset[i].translate(new SimpleOffset((int)((maxParentSizeX)/2.0-(trackOffset[i].sizeX())/2.0), currentOffset , 0)); // Y  middle of parent track
                else trackOffset[i].translate(new SimpleOffset(0, currentOffset, 0)); // X  up of parent track
                currentOffset+=gap+averageSize;
            }
            return new Data(DIRECTION.Y, maxParentSizeX, -1, maxSizeZ, trackOffset, parentTrack, framePerSlice, frameOverlap);
        }
    }
    public static Data generateKymographViewData(List<SegmentedObject> parentTrack, BoundingBox view, int interval, int size, int overlap) {
        ExperimentStructure xp = parentTrack.get(0).getExperimentStructure();
        int maxSizeZ = IntStream.range(0, xp.getChannelNumber()).map(c -> xp.sizeZ(parentTrack.get(0).getPositionName(), c)).max().getAsInt();
        return generateKymographViewData(parentTrack, view, maxSizeZ, interval, size, overlap);
    }
    public static Data generateKymographViewData(List<SegmentedObject> parentTrack, BoundingBox view, int maxSizeZ, int interval, int size, int overlap) {
        BoundingBox[] trackOffset =  parentTrack.stream().map(p-> new SimpleBoundingBox(view).resetOffset()).toArray(l -> new BoundingBox[l]);
        int currentOffset=0;
        if (view.sizeY() * 1.5 >= view.sizeX()) { // X direction.
            int frameNumber = size == 0 ? 0 : Math.max(2, (int)Math.ceil((double)size / view.sizeX()));
            int frameOverlap = (int)Math.ceil((double)overlap / view.sizeX());
            for (int i = 0; i<parentTrack.size(); ++i) {
                trackOffset[i].translate(new SimpleOffset(currentOffset, 0, 0)); // Y up of parent track
                currentOffset+=interval+view.sizeX();
            }
            return new Data(DIRECTION.X, -1, view.sizeY(), maxSizeZ, trackOffset, parentTrack, frameNumber, frameOverlap);
        } else { // Y direction
            int frameNumber = size == 0 ? 0 : Math.max(2, (int)Math.ceil((double)size / view.sizeY()));
            int frameOverlap = (int)Math.ceil((double)overlap / view.sizeY());
            for (int i = 0; i<parentTrack.size(); ++i) {
                trackOffset[i].translate(new SimpleOffset(0, currentOffset, 0)); // X  up of parent track
                currentOffset+=interval+view.sizeY();
            }
            return new Data(DIRECTION.Y, view.sizeX(), -1, maxSizeZ, trackOffset, parentTrack, frameNumber, frameOverlap);
        }
    }
    public static Data generateHyperstackData(List<SegmentedObject> parentTrack, boolean middle) {
        ExperimentStructure xp = parentTrack.get(0).getExperimentStructure();
        int maxSizeZ = IntStream.range(0, xp.getChannelNumber()).map(c -> xp.sizeZ(parentTrack.get(0).getPositionName(), c)).max().getAsInt();
        return generateHyperstackData(parentTrack, maxSizeZ, middle);
    }
    public static Data generateHyperstackData(List<SegmentedObject> parentTrack, int maxSizeZ, boolean middle) {
        BoundingBox[] trackOffset =  parentTrack.stream().map(p-> new SimpleBoundingBox(p.getBounds()).resetOffset()).toArray(l -> new BoundingBox[l]);
        int maxParentSizeX = parentTrack.stream().mapToInt(p->p.getBounds().sizeX()).max().getAsInt();
        int maxParentSizeY = parentTrack.stream().mapToInt(p->p.getBounds().sizeY()).max().getAsInt();
        for (int i = 0; i<parentTrack.size(); ++i) {
            if (middle) trackOffset[i].translate(new SimpleOffset((int)((maxParentSizeX)/2.0-(trackOffset[i].sizeX())/2.0), (int)((maxParentSizeY)/2.0-(trackOffset[i].sizeY())/2.0), 0)).reverseOffset(); // X & Y  middle of parent track
        }
        return new Data(DIRECTION.T, maxParentSizeX, maxParentSizeY, maxSizeZ, trackOffset, parentTrack, parentTrack.size(), 0);
    }
    public static Data generateHyperstackViewData(List<SegmentedObject> parentTrack, BoundingBox view) {
        ExperimentStructure xp = parentTrack.get(0).getExperimentStructure();
        int maxSizeZ = IntStream.range(0, xp.getChannelNumber()).map(c -> xp.sizeZ(parentTrack.get(0).getPositionName(), c)).max().getAsInt();
        return generateHyperstackViewData(parentTrack, view, maxSizeZ);
    }
    public static Data generateHyperstackViewData(List<SegmentedObject> parentTrack, BoundingBox view, int maxSizeZ) {
        BoundingBox[] trackOffset =  parentTrack.stream().map(p-> new SimpleBoundingBox(view).resetOffset()).toArray(l -> new BoundingBox[l]);
        return new Data(DIRECTION.T, view.sizeX(), view.sizeY(), maxSizeZ, trackOffset, parentTrack, parentTrack.size(), 0);
    }

    public enum DIRECTION {X, Y, T}
    public static class Data {
        public final DIRECTION direction;
        public final int maxParentSizeX, maxParentSizeY, maxSizeZ;
        public final BoundingBox[] trackOffset;
        public final List<SegmentedObject> parentTrack;
        public final int nFramePerSlice, frameOverlap, nSlices;
        public Data(DIRECTION direction, int maxParentSizeX, int maxParentSizeY, int maxSizeZ, BoundingBox[] trackOffset, List<SegmentedObject> parentTrack, int nFramePerSlice, int avgFrameOverlap) {
            this.direction = direction;
            this.maxParentSizeX = maxParentSizeX;
            this.maxParentSizeY = maxParentSizeY;
            this.maxSizeZ = maxSizeZ;
            this.trackOffset = trackOffset;
            this.parentTrack=parentTrack;
            this.nFramePerSlice = nFramePerSlice <=0 ? parentTrack.size() : Math.min(nFramePerSlice, parentTrack.size());
            avgFrameOverlap = this.nFramePerSlice == parentTrack.size() ? 0 : Math.max(0, Math.min(this.nFramePerSlice-1, avgFrameOverlap));
            if (this.nFramePerSlice == parentTrack.size()) nSlices = 1;
            else nSlices = (int)Math.ceil((double)(parentTrack.size() - avgFrameOverlap) / (this.nFramePerSlice - avgFrameOverlap));
            this.frameOverlap = nSlices==1 ? 0 : this.nFramePerSlice - (parentTrack.size() - this.nFramePerSlice) / (nSlices - 1);
            logger.debug("kymograph: total {}, per slice: {} overlap {} total slices: {}", parentTrack.size(), this.nFramePerSlice, this.frameOverlap, nSlices);
        }

        public Image generateEmptyImage(String name, Image type) {
            switch (direction) {
                case X:
                default:
                    return  Image.createEmptyImage(name, type, new SimpleImageProperties(trackOffset[this.nFramePerSlice -1].xMax()+1, this.maxParentSizeY, Math.max(type.sizeZ(), this.maxSizeZ),type.getScaleXY(), type.getScaleZ()));
                case Y:
                    return Image.createEmptyImage(name, type, new SimpleImageProperties( this.maxParentSizeX, trackOffset[this.nFramePerSlice -1].yMax()+1, Math.max(type.sizeZ(), this.maxSizeZ), type.getScaleXY(), type.getScaleZ()));
                case T:
                    return Image.createEmptyImage(name, type, new SimpleImageProperties( this.maxParentSizeX, maxParentSizeY, Math.max(type.sizeZ(), this.maxSizeZ), type.getScaleXY(), type.getScaleZ()));
            }
        }
        public Image generateKymograph(String name, int objectClassIdx, boolean raw) {
            if (direction.equals(DIRECTION.T)) throw new UnsupportedOperationException("Do not generate hyperstack this way");
            Image type = raw ? parentTrack.get(0).getRawImage(objectClassIdx):parentTrack.get(0).getPreFilteredImage(objectClassIdx);
            Image res =  generateEmptyImage(name, type);
            updateKymograph(res, objectClassIdx, raw, 0);
            return res;
        }
        public void updateKymograph(Image image, int objectClassIdx, boolean raw, int fromFrame) {
            if (fromFrame<0) throw new IllegalArgumentException("start frame is negative");
            fromFrame = Math.min(trackOffset.length-fromFrame, fromFrame);
            IntStream.range(fromFrame, fromFrame+ nFramePerSlice).parallel().forEach(i->{
                Image subImage = raw ? parentTrack.get(i).getRawImage(objectClassIdx):parentTrack.get(i).getPreFilteredImage(objectClassIdx);
                Image.pasteImage(subImage, image, trackOffset[i]);
            });
        }
    }
}
