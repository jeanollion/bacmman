package bacmman.image;

public interface DiskBackedImage<I extends Image<I>> {
    void freeMemory(boolean storeIfModified);
    void detach();
    boolean detached();
    boolean isOpen();
    boolean isWritable();
    void setModified(boolean modified);
    I getImageType();
    long heapMemory();
}
