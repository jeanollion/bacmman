package bacmman.ui.gui.image_interaction;

import bacmman.core.DefaultWorker;
import bacmman.image.Image;
import bacmman.image.io.TimeLapseInteractiveImageFactory;
import bacmman.ui.GUI;

import java.util.stream.IntStream;

public abstract class Kymograph extends TimeLapseInteractiveImage {
    protected final int maxParentSizeZ, frameNumber;
    int startFrame;
    public Kymograph(TimeLapseInteractiveImageFactory.Data data, int childStructureIdx, boolean loadObjects) {
        super(data, childStructureIdx);
        maxParentSizeZ = data.maxParentSizeZ;
        frameNumber = data.frameNumber;
        loadObjectsWorker = new DefaultWorker(i -> { // TODO parallel ?
            data.parentTrack.get(i).getChildren(childStructureIdx);
            return "";
        }, data.parentTrack.size(), null).setCancel(() -> getAccessor().getDAO(getParent()).closeThreadResources());
        if (loadObjects) {
            loadObjectsWorker.execute();
            loadObjectsWorker.setStartTime();
        }
    }

    public boolean canMoveView(boolean next) {
        if (next) return startFrame + frameNumber < data.parentTrack.size();
        else return startFrame > 0;
    }

    public void setStartFrame(int startFrame) {
        this.startFrame = Math.min(startFrame, data.parentTrack.size() - data.frameNumber);
        updateData(this.startFrame);
    }

    protected void updateImage(Image image, final int structureIdx) {
        IntStream.range(0, trackOffset.length).parallel().forEach(i->{
            Image subImage = trackObjects[i].generateImage(structureIdx);
            Image.pasteImage(subImage, image, trackOffset[i]);
        });
    }
    @Override public Image generateImage(final int structureIdx) {
        // use track image only if parent is first element of track image
        //if (trackObjects[0].parent.getOffsetInTrackImage()!=null && trackObjects[0].parent.getOffsetInTrackImage().xMin()==0 && trackObjects[0].parent.getTrackImage(structureIdx)!=null) return trackObjects[0].parent.getTrackImage(structureIdx);
        long t0 = System.currentTimeMillis();
        Image image0 = trackObjects[0].generateImage(structureIdx);
        //GUI.logger.debug("image bounds: {}, parent {} bounds: {}. is2D: {}", image0.getBoundingBox(), trackObjects[0].parent, trackObjects[0].parent.getBounds(), is2D());
        if (image0==null) return null;
        String structureName;
        if (getParent().getExperimentStructure()!=null) structureName = getParent().getExperimentStructure().getObjectClassName(structureIdx);
        else structureName= structureIdx+"";
        String pStructureName;
        if (getParent().getExperimentStructure()!=null) pStructureName = getParent().getStructureIdx()<0? "": " " + getParent().getExperimentStructure().getObjectClassName(getParent().getStructureIdx());
        else pStructureName= getParent().getStructureIdx()+"";
        final Image displayImage =  generateEmptyImage("Kymograph@"+pStructureName+"/P"+getParent().getPositionIdx()+"/Idx"+getParent().getIdx()+"/F["+getParent().getFrame()+";"+parents.get(parents.size()-1).getFrame()+"]: "+structureName, image0);
        updateImage(displayImage, structureIdx);
        long t1 = System.currentTimeMillis();
        GUI.logger.debug("generate image: {} for structure: {}, time: {}ms", parents.get(0), structureIdx, t1-t0);


        return displayImage;
    }

}
