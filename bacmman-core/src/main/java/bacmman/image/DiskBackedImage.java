package bacmman.image;

public interface DiskBackedImage<I extends Image<I>> {
    void freeMemory();
    boolean isOpen();
    boolean isWritable();
    boolean hasModifications();
    void setModified();
    I getImageType();
    long heapMemory();
}
