package bacmman.image;

public interface DiskBackedImage<I extends Image<I>> {
    void freeMemory(boolean storeIfModified);
    boolean isOpen();
    boolean isWritable();
    void setModified(boolean modified);
    I getImageType();
    long heapMemory();
}
