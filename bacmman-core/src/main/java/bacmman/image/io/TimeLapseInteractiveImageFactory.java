package bacmman.image.io;

import bacmman.data_structure.ExperimentStructure;
import bacmman.data_structure.SegmentedObject;
import bacmman.image.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.stream.IntStream;

public class TimeLapseInteractiveImageFactory {
    public final static Logger logger = LoggerFactory.getLogger(TimeLapseInteractiveImageFactory.class);
    public static Data generateKymographData(List<SegmentedObject> parentTrack, boolean middle, int interval, int frameNumber, int frameOverlap) {
        ExperimentStructure xp = parentTrack.get(0).getExperimentStructure();
        BoundingBox bb = parentTrack.get(0).getBounds();
        int maxParentSizeZ = IntStream.range(0, xp.getChannelNumber()).map(c -> xp.sizeZ(parentTrack.get(0).getPositionName(), c)).max().getAsInt();
        BoundingBox[] trackOffset =  parentTrack.stream().map(p-> new SimpleBoundingBox(p.getBounds()).resetOffset()).toArray(l -> new BoundingBox[l]);
        int currentOffset=0;
        if (bb.sizeY() >= bb.sizeX()) { // X direction.
            int maxParentSizeY = parentTrack.stream().mapToInt(p->p.getBounds().sizeY()).max().getAsInt();
            for (int i = 0; i<parentTrack.size(); ++i) {
                if (middle) trackOffset[i].translate(new SimpleOffset(currentOffset, (int)((maxParentSizeY)/2.0-(trackOffset[i].sizeY())/2.0), 0)); // Y middle of parent track
                else trackOffset[i].translate(new SimpleOffset(currentOffset, 0, 0)); // Y up of parent track
                currentOffset+=interval+trackOffset[i].sizeX();
            }
            return new Data(DIRECTION.X, -1, maxParentSizeY, maxParentSizeZ, trackOffset, parentTrack, frameNumber, frameOverlap);
        } else { // Y direction
            int maxParentSizeX = parentTrack.stream().mapToInt(p->p.getBounds().sizeX()).max().getAsInt();
            for (int i = 0; i<parentTrack.size(); ++i) {
                if (middle) trackOffset[i].translate(new SimpleOffset((int)((maxParentSizeX)/2.0-(trackOffset[i].sizeX())/2.0), currentOffset , 0)); // Y  middle of parent track
                else trackOffset[i].translate(new SimpleOffset(0, currentOffset, 0)); // X  up of parent track
                currentOffset+=interval+trackOffset[i].sizeY();
            }
            return new Data(DIRECTION.Y, maxParentSizeX, -1, maxParentSizeZ, trackOffset, parentTrack, frameNumber, frameOverlap);
        }
    }

    public static Data generateKymographViewData(List<SegmentedObject> parentTrack, BoundingBox view, int interval, int frameNumber, int frameOverlap) {
        ExperimentStructure xp = parentTrack.get(0).getExperimentStructure();
        int maxParentSizeZ = IntStream.range(0, xp.getChannelNumber()).map(c -> xp.sizeZ(parentTrack.get(0).getPositionName(), c)).max().getAsInt();
        BoundingBox[] trackOffset =  parentTrack.stream().map(p-> new SimpleBoundingBox(view).resetOffset()).toArray(l -> new BoundingBox[l]);
        int currentOffset=0;
        if (view.sizeY() * 1.5 >= view.sizeX()) { // X direction.
            for (int i = 0; i<parentTrack.size(); ++i) {
                trackOffset[i].translate(new SimpleOffset(currentOffset, 0, 0)); // Y up of parent track
                currentOffset+=interval+view.sizeX();
            }
            return new Data(DIRECTION.X, -1, view.sizeY(), maxParentSizeZ, trackOffset, parentTrack, frameNumber, frameOverlap);
        } else { // Y direction
            for (int i = 0; i<parentTrack.size(); ++i) {
                trackOffset[i].translate(new SimpleOffset(0, currentOffset, 0)); // X  up of parent track
                currentOffset+=interval+view.sizeY();
            }
            return new Data(DIRECTION.Y, view.sizeX(), -1, maxParentSizeZ, trackOffset, parentTrack, frameNumber, frameOverlap);
        }
    }

    public static Data generateHyperstackData(List<SegmentedObject> parentTrack, boolean middle) {
        ExperimentStructure xp = parentTrack.get(0).getExperimentStructure();
        int maxParentSizeZ = IntStream.range(0, xp.getChannelNumber()).map(c -> xp.sizeZ(parentTrack.get(0).getPositionName(), c)).max().getAsInt();
        BoundingBox[] trackOffset =  parentTrack.stream().map(p-> new SimpleBoundingBox(p.getBounds()).resetOffset()).toArray(l -> new BoundingBox[l]);
        int maxParentSizeX = parentTrack.stream().mapToInt(p->p.getBounds().sizeX()).max().getAsInt();
        int maxParentSizeY = parentTrack.stream().mapToInt(p->p.getBounds().sizeY()).max().getAsInt();
        for (int i = 0; i<parentTrack.size(); ++i) {
            if (middle) trackOffset[i].translate(new SimpleOffset((int)((maxParentSizeX)/2.0-(trackOffset[i].sizeX())/2.0), (int)((maxParentSizeY)/2.0-(trackOffset[i].sizeY())/2.0), 0)); // X & Y  middle of parent track
        }
        return new Data(DIRECTION.T, maxParentSizeX, maxParentSizeY, maxParentSizeZ, trackOffset, parentTrack, parentTrack.size(), 0);
    }

    public static Data generateHyperstackViewData(List<SegmentedObject> parentTrack, BoundingBox view) {
        ExperimentStructure xp = parentTrack.get(0).getExperimentStructure();
        int maxParentSizeZ = IntStream.range(0, xp.getChannelNumber()).map(c -> xp.sizeZ(parentTrack.get(0).getPositionName(), c)).max().getAsInt();
        BoundingBox[] trackOffset =  parentTrack.stream().map(p-> new SimpleBoundingBox(view).resetOffset()).toArray(l -> new BoundingBox[l]);
        return new Data(DIRECTION.T, view.sizeX(), view.sizeY(), maxParentSizeZ, trackOffset, parentTrack, parentTrack.size(), 0);
    }

    public enum DIRECTION {X, Y, T}
    public static class Data {
        public final DIRECTION direction;
        public final int maxParentSizeX, maxParentSizeY, maxParentSizeZ;
        public final BoundingBox[] trackOffset;
        public final List<SegmentedObject> parentTrack;
        public final int nFramePerSlice, frameOverlap, nSlices;
        public Data(DIRECTION direction, int maxParentSizeX, int maxParentSizeY, int maxParentSizeZ, BoundingBox[] trackOffset, List<SegmentedObject> parentTrack, int nFramePerSlice, int frameOverlap) {
            this.direction = direction;
            this.maxParentSizeX = maxParentSizeX;
            this.maxParentSizeY = maxParentSizeY;
            this.maxParentSizeZ = maxParentSizeZ;
            this.trackOffset = trackOffset;
            this.parentTrack=parentTrack;
            this.nFramePerSlice = nFramePerSlice <=0 ? parentTrack.size() : Math.min(nFramePerSlice, parentTrack.size());
            this.frameOverlap = nFramePerSlice ==parentTrack.size() ? 0 : frameOverlap;
            if (frameOverlap>= nFramePerSlice) throw new IllegalArgumentException("Frame overlap must be lower than frame number");
            if (nFramePerSlice == parentTrack.size()) nSlices = 1;
            else nSlices = (int)Math.ceil((double)(parentTrack.size() - frameOverlap) / (nFramePerSlice -frameOverlap));
            logger.debug("kymograph: total {}, per slice: {} overlap {} total slices: {}", parentTrack.size(), nFramePerSlice, frameOverlap, nSlices);
        }

        public Image generateEmptyImage(String name, Image type) {
            switch (direction) {
                case X:
                default:
                    return  Image.createEmptyImage(name, type, new SimpleImageProperties(trackOffset[this.nFramePerSlice -1].xMax()+1, this.maxParentSizeY, Math.max(type.sizeZ(), this.maxParentSizeZ),type.getScaleXY(), type.getScaleZ()));
                case Y:
                    return Image.createEmptyImage(name, type, new SimpleImageProperties( this.maxParentSizeX, trackOffset[this.nFramePerSlice -1].yMax()+1, Math.max(type.sizeZ(), this.maxParentSizeZ), type.getScaleXY(), type.getScaleZ()));
                case T:
                    return Image.createEmptyImage(name, type, new SimpleImageProperties( this.maxParentSizeX, maxParentSizeY, Math.max(type.sizeZ(), this.maxParentSizeZ), type.getScaleXY(), type.getScaleZ()));
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
