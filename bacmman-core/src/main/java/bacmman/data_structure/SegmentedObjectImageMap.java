package bacmman.data_structure;

import bacmman.data_structure.dao.DiskBackedImageManager;
import bacmman.image.Image;
import bacmman.image.SimpleDiskBackedImage;
import bacmman.utils.HashMapGetCreate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;

public class SegmentedObjectImageMap {
    Logger logger = LoggerFactory.getLogger(SegmentedObjectImageMap.class);
    final List<SegmentedObject> track;
    final Function<SegmentedObject, Image> imageSupplier;
    final Map<SegmentedObject, Image> imageMap;
    boolean canModifyImages = false;
    public SegmentedObjectImageMap(List<SegmentedObject> track, int objectClassIdx) {
        this(track, o -> o.getRawImage(objectClassIdx));
    }
    public SegmentedObjectImageMap(List<SegmentedObject> track, Function<SegmentedObject, Image> imageSupplier) {
        this.track = track;
        this.imageSupplier=imageSupplier;
        imageMap = new HashMapGetCreate.HashMapGetCreateRedirectedSyncKey<>(imageSupplier::apply);
    }
    public void setModifyImages(boolean canModifyImages) {
        this.canModifyImages = canModifyImages;
    }
    public boolean canModifyImages() {
        return canModifyImages;
    }
    public int size() {
        return track.size();
    }
    public boolean isEmpty() {
        return track.isEmpty();
    }
    public Image get(SegmentedObject o) {
        Image im = imageMap.get(o);
        if (im instanceof SimpleDiskBackedImage) return ((SimpleDiskBackedImage)im).getImage();
        else return im;
    }
    public Image getOriginal(SegmentedObject o) {
        return imageMap.get(o);
    }
    public Stream<Image> streamValues() {
        return track.stream().map(this::get);
    }
    public Stream<SegmentedObject> streamKeys() {
        return track.stream();
    }
    public synchronized void set(SegmentedObject o, Image image) {
        Image existingImage = imageMap.get(o);
        if (existingImage instanceof SimpleDiskBackedImage) {
            SimpleDiskBackedImage existingSDBI = (SimpleDiskBackedImage)existingImage;
            boolean replaced = existingSDBI.setImage(image);
            if (!replaced) { // delete old image and replace with new one
                logger.debug("could not replace existing image {} @ {} with {} will create a new disk backed image", existingImage.getName(), o, image.getName());
                DiskBackedImageManager manager = existingSDBI.getManager();
                manager.detach(existingSDBI, true);
                Image newSDBI = manager.createSimpleDiskBackedImage(image, true, false);
                imageMap.put(o, newSDBI);
            }
        } else imageMap.put(o, image);
    }
    public void setModified(SegmentedObject o) {
        Image existingImage = imageMap.get(o);
        if (!canModifyImages) throw new RuntimeException("Cannot set modified when canModifyImages is false");
        if (existingImage instanceof SimpleDiskBackedImage) {
            SimpleDiskBackedImage existingSDBI = (SimpleDiskBackedImage)existingImage;
            existingSDBI.setModified(true);
        }
    }
}
