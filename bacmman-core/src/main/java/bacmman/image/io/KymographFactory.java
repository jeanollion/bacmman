package bacmman.image.io;

import bacmman.data_structure.SegmentedObject;
import bacmman.image.*;

import java.util.List;
import java.util.stream.IntStream;

public class KymographFactory {

    public static KymographData generateKymographData(List<SegmentedObject> parentTrack, boolean middle, int interval) {
        //setAllChildren(parentTrack, childStructureIdx); // if set -> tracking test cannot work ?
        BoundingBox bb = parentTrack.get(0).getBounds();
        int maxParentSizeZ = parentTrack.stream().mapToInt(p->p.getBounds().sizeZ()).max().getAsInt();
        BoundingBox[] trackOffset =  parentTrack.stream().map(p-> new SimpleBoundingBox(p.getBounds()).resetOffset()).toArray(l -> new BoundingBox[l]);
        int currentOffset=0;
        if (!parentTrack.get(0).isRoot() && bb.sizeY() >= bb.sizeX()) { // X direction. Root parent is always in Y direction
            int maxParentSizeY = parentTrack.stream().mapToInt(p->p.getBounds().sizeY()).max().getAsInt();
            for (int i = 0; i<parentTrack.size(); ++i) {
                if (middle) trackOffset[i].translate(new SimpleOffset(currentOffset, (int)((maxParentSizeY-1)/2.0-(trackOffset[i].sizeY()-1)/2.0), (int)((maxParentSizeZ-1)/2.0-(trackOffset[i].sizeZ()-1)/2.0))); // Y & Z middle of parent track
                else trackOffset[i].translate(new SimpleOffset(currentOffset, 0, 0)); // Y & Z up of parent track
                currentOffset+=interval+trackOffset[i].sizeX();
            }
            return new KymographData(DIRECTION.X, maxParentSizeY, maxParentSizeZ, trackOffset, parentTrack);
        } else { // Y direction
            int maxParentSizeX = parentTrack.stream().mapToInt(p->p.getBounds().sizeX()).max().getAsInt();
            for (int i = 0; i<parentTrack.size(); ++i) {
                if (middle) trackOffset[i].translate(new SimpleOffset((int)((maxParentSizeX-1)/2.0-(trackOffset[i].sizeX()-1)/2.0), currentOffset , (int)((maxParentSizeZ-1)/2.0-(trackOffset[i].sizeZ()-1)/2.0))); // Y & Z middle of parent track
                else trackOffset[i].translate(new SimpleOffset(0, currentOffset, 0)); // X & Z up of parent track
                currentOffset+=interval+trackOffset[i].sizeY();
            }
            return new KymographData(DIRECTION.Y, maxParentSizeX, maxParentSizeZ, trackOffset, parentTrack);
        }

    }

    public enum DIRECTION {X, Y}
    public static class KymographData {
        public final DIRECTION direction;
        public final int maxParentSize, maxParentSizeZ;
        public final BoundingBox[] trackOffset;
        public final List<SegmentedObject> parentTrack;
        public KymographData(DIRECTION direction, int maxParentSize, int maxParentSizeZ, BoundingBox[] trackOffset, List<SegmentedObject> parentTrack) {
            this.direction = direction;
            this.maxParentSize = maxParentSize;
            this.maxParentSizeZ = maxParentSizeZ;
            this.trackOffset = trackOffset;
            this.parentTrack=parentTrack;
        }

        public Image generateEmptyImage(String name, Image type) {
            switch (direction) {
                case X:
                default:
                    return  Image.createEmptyImage(name, type, new SimpleImageProperties(trackOffset[trackOffset.length-1].xMax()+1, this.maxParentSize, Math.max(type.sizeZ(), this.maxParentSizeZ),type.getScaleXY(), type.getScaleZ()));
                case Y:
                    return Image.createEmptyImage(name, type, new SimpleImageProperties( this.maxParentSize, trackOffset[trackOffset.length-1].yMax()+1, Math.max(type.sizeZ(), this.maxParentSizeZ), type.getScaleXY(), type.getScaleZ()));
            }
        }
        public Image generateImage(String name, int objectClassIdx, boolean raw) {
            Image type = raw ? parentTrack.get(0).getRawImage(objectClassIdx):parentTrack.get(0).getPreFilteredImage(objectClassIdx);
            Image res =  generateEmptyImage(name, type);
            IntStream.range(0, trackOffset.length).parallel().forEach(i->{
                Image subImage = raw ? parentTrack.get(i).getRawImage(objectClassIdx):parentTrack.get(i).getPreFilteredImage(objectClassIdx);
                Image.pasteImage(subImage, res, trackOffset[i]);
            });
            return res;
        }
    }
}
