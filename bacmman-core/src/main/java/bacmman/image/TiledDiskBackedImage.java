package bacmman.image;

import bacmman.data_structure.dao.DiskBackedImageManager;
import bacmman.utils.StreamConcatenation;

import java.util.ArrayList;
import java.util.stream.DoubleStream;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public class TiledDiskBackedImage<I extends Image<I>> extends DiskBackedImage<I> {
    static int targetTileSize = 512 * 512;
    int[] tileDimensions;
    DiskBackedImage<I>[][][] tilesZYX;
    I image;

    public TiledDiskBackedImage(I image, DiskBackedImageManager manager, boolean writable) {
        super(image.getName(), image, Image.copyType(image), manager, writable);
        this.image = image;
    }

    public Stream<DiskBackedImage<I>> streamTiles() {
        if (tilesZYX == null) return Stream.empty();
        return Stream.of(tilesZYX).flatMap(Stream::of).flatMap(Stream::of);
    }

    protected void tileImage(boolean freeMemory) {
        if (tileDimensions == null) tileDimensions = TileUtils.getOptimalTileSize(image.dimensions(), targetTileSize);
        if (tileDimensions.length == 2) tileDimensions = new int[]{tileDimensions[0], tileDimensions[1], 1};
        int[] size = image.dimensions();
        int[] nTilesAxis = IntStream.range(0, 3).map(i -> i>size.length-1 ? 1 : (int)Math.ceil((double)size[i] / tileDimensions[i])).toArray();
        tilesZYX = new DiskBackedImage[nTilesAxis[2]][nTilesAxis[1]][nTilesAxis[0]];
        BoundingBox imageBds = image.getBoundingBox().resetOffset();
        int zCoord = 0;
        int yCoord = 0;
        int xCoord = 0;
        for (int z = 0; z< tilesZYX.length; ++z) {
            for (int y = 0; y< tilesZYX[0].length; ++y) {
                for (int x = 0; x< tilesZYX[0][0].length; ++x) {
                    MutableBoundingBox bds = new MutableBoundingBox(xCoord, xCoord + tileDimensions[0] - 1, yCoord, yCoord + tileDimensions[1] - 1, zCoord, size.length==3? zCoord + tileDimensions[2] - 1 : 0)
                            .contract(imageBds);
                    //logger.debug("tile idx: {}x{}x{} coord: [{}; {}; {}] target tile dims: {} contracted dims: {} bds: {} crop bds: {}", z, y, x, xCoord, yCoord, zCoord, tileDimensions, bds.dimensions(), new SimpleBoundingBox(image), bds);
                    tilesZYX[z][y][x] = manager.createDiskBackedImage(image.crop(bds), writable, freeMemory);
                    xCoord += tileDimensions[0];
                }
                yCoord += tileDimensions[1];
                xCoord = 0;
            }
            zCoord += tileDimensions[2];
            yCoord = 0;
        }
        if (freeMemory) image = null;
    }

    protected void stitchImage() {
        image = newImage(name, this);
        for (int z = 0; z< tilesZYX.length; ++z) {
            for (int y = 0; y< tilesZYX[0].length; ++y) {
                for (int x = 0; x< tilesZYX[0][0].length; ++x) {
                    Image.pasteImage(tilesZYX[z][y][x].getImage(), image, new SimpleOffset(tilesZYX[z][y][x].xMin - xMin, tilesZYX[z][y][x].yMin - yMin, tilesZYX[z][y][x].zMin - zMin));
                }
            }
        }
    }

    @Override
    public I getZPlane(int idxZ) {
        if (image != null) return image.getZPlane(idxZ);
        if (sizeZ == 0 && idxZ == 0) return getImage();
        I plane = newImage("plane"+idxZ, new SimpleImageProperties(sizeX, sizeY, 1, scaleXY, scaleZ));
        int z = idxZ/tileDimensions[2];
        for (int y = 0; y< tilesZYX[0].length; ++y) {
            for (int x = 0; x< tilesZYX[0][0].length; ++x) {
                Image.pasteImage(tilesZYX[z][y][x].getZPlane(idxZ%tileDimensions[2]), image, new SimpleOffset(tilesZYX[z][y][x].xMin - xMin, tilesZYX[z][y][x].yMin - yMin, tilesZYX[z][y][x].zMin - zMin));
            }
        }
        plane.translate(xMin, yMin, zMin + idxZ);
        return plane;
    }

    @Override
    public synchronized I getImage() {
        if (image == null ) {
            stitchImage();
        } else { // case calibration / offset have been modified on this object
            if (!image.getOffset().sameOffset(this)) image.resetOffset().translate(this);
            image.setCalibration(scaleXY, scaleZ);
        }
        return image;
    }

    protected boolean hasOpenedTile() {
        if (tilesZYX == null) return false;
        return streamTiles().anyMatch(DiskBackedImage::isOpen);
    }

    @Override
    public void freeMemory(boolean storeIfModified) {
        if (isOpen()) {
            synchronized (this) {
                if (isOpen()) {
                    if (modified && storeIfModified) {
                        if (image!=null && tilesZYX == null) tileImage(true);
                        modified = false;
                    }
                    image = null;
                    if (tilesZYX != null) streamTiles().forEach(t -> t.freeMemory(storeIfModified));
                }
            }
        }
    }

    @Override
    public boolean isOpen() {
        return image != null || hasOpenedTile();
    }

    @Override
    public long usedHeapMemory() {
        long sum = image != null ? heapMemory() : 0;
        if (tilesZYX != null) sum += streamTiles().mapToLong(DiskBackedImage::usedHeapMemory).sum();
        return sum;
    }

    @Override
    public double getPixel(int x, int y, int z) {
        if (image != null) return image.getPixel(x, y, z);
        return tilesZYX[z/tileDimensions[2]][y/tileDimensions[1]][x/tileDimensions[0]].getPixel(x%tileDimensions[0], y%tileDimensions[1], z%tileDimensions[2]);
    }

    @Override
    public double getPixelWithOffset(int x, int y, int z) {
        if (image != null) return image.getPixelWithOffset(x, y, z);
        return getPixel(x - xMin, y - yMin, z - zMin);
    }

    @Override
    public double getPixelLinInterX(int x, int y, int z, float dx) {
        if (image != null) return image.getPixelLinInterX(x, y, z, dx);
        return tilesZYX[z/tileDimensions[2]][y/tileDimensions[1]][x/tileDimensions[0]].getPixelLinInterX(x%tileDimensions[0], y%tileDimensions[1], z%tileDimensions[2], dx);
    }

    @Override
    public double getPixel(int xy, int z) {
        if (image != null) return image.getPixel(xy, z);
        int x = xy % sizeX;
        int y = xy / sizeX;
        return tilesZYX[z/tileDimensions[2]][y/tileDimensions[1]][x/tileDimensions[0]].getPixel(x%tileDimensions[0], y%tileDimensions[1], z%tileDimensions[2]);
    }

    @Override
    public void setPixel(int x, int y, int z, double value) {
        if (writable) {
            if (!modified) {
                modified = true;
            }
        } else throw new RuntimeException("Image not writable");
        if (tilesZYX != null) tilesZYX[z/tileDimensions[2]][y/tileDimensions[1]][x/tileDimensions[0]].setPixel(x%tileDimensions[0], y%tileDimensions[1], z%tileDimensions[2], value);
        if (image != null) image.setPixel(x, y, z, value);
    }

    @Override
    public void setPixelWithOffset(int x, int y, int z, double value) {
        setPixel(x - xMin, y - yMin, z - zMin, value);
    }

    @Override
    public void addPixel(int x, int y, int z, double value) {
        if (writable) {
            if (!modified) {
                modified = true;
            }
        } else throw new RuntimeException("Image not writable");
        if (tilesZYX != null) tilesZYX[z/tileDimensions[2]][y/tileDimensions[1]][x/tileDimensions[0]].addPixel(x%tileDimensions[0], y%tileDimensions[1], z%tileDimensions[2], value);
        if (image != null) image.addPixel(x, y, z, value);
    }

    @Override
    public void addPixelWithOffset(int x, int y, int z, double value) {
        addPixel(x - xMin, y - yMin, z - zMin, value);
    }

    @Override
    public void setPixel(int xy, int z, double value) {
        if (writable) {
            if (!modified) {
                modified = true;
            }
        } else throw new RuntimeException("Image not writable");
        if (tilesZYX != null) {
            int x = xy % sizeX;
            int y = xy / sizeX;
            tilesZYX[z/tileDimensions[2]][y/tileDimensions[1]][x/tileDimensions[0]].setPixel(x%tileDimensions[0], y%tileDimensions[1], z%tileDimensions[2], value);
        }
        if (image != null) image.setPixel(xy, z, value);
    }

    @Override
    public Object[] getPixelArray() {
        if (writable) modified = true;
        Object[] res = getImage().getPixelArray();
        if (tilesZYX != null) { // as image can be modified through pixel array : erase all tiles to ensure consistency
            synchronized (this) {
                if (tilesZYX != null) {
                    for (int z = 0; z < tilesZYX.length; ++z) {
                        for (int y = 0; y < tilesZYX[0].length; ++y) {
                            for (int x = 0; x < tilesZYX[0][0].length; ++x) {
                                manager.detach(tilesZYX[z][y][x], true);
                            }
                        }
                    }
                    tilesZYX = null;
                }
            }
        }
        return res;
    }

    @Override
    public int byteCount() {
        return imageType.byteCount();
    }

    @Override
    public boolean floatingPoint() {
        return imageType.floatingPoint();
    }

    @Override
    public I duplicate(String name) {
        return getImage().duplicate();
    }

    @Override
    public I newImage(String name, ImageProperties properties) {
        return imageType.newImage(name, properties);
    }

    @Override
    public DoubleStream streamPlane(int z) {
        if (image != null) return image.streamPlane(z);
        int zTile = z / tileDimensions[2];
        int localZ = z % tileDimensions[2];
        java.util.List<DoubleStream> allStreams = new ArrayList<>();
        for (int y = 0; y< tilesZYX[0].length; ++y) {
            for (int x = 0; x< tilesZYX[0][0].length; ++x) {
                allStreams.add(tilesZYX[zTile][y][x].streamPlane(localZ));
            }
        }
        return StreamConcatenation.concatDouble(allStreams);
    }

    @Override
    public DoubleStream streamPlane(int z, ImageMask mask, boolean maskHasAbsoluteOffset) {
        if (image != null) return image.streamPlane(z, mask, maskHasAbsoluteOffset);
        if (maskHasAbsoluteOffset) {
            if (!(mask instanceof ImageMask2D) && (z<0 || z>=sizeZ || z+zMin-mask.zMin()<0 || z+zMin-mask.zMin()>=mask.sizeZ())) return DoubleStream.empty();
            SimpleBoundingBox inter = BoundingBox.getIntersection2D(this, mask);
            if (inter.isEmpty()) return DoubleStream.empty();
            if (inter.sameBounds(this) && (inter.sameBounds(mask) || (mask instanceof ImageMask2D && inter.sameBounds2D(mask)))) {
                if (mask instanceof BlankMask) return this.streamPlane(z);
                else return IntStream.range(0,sizeXY).mapToDouble(i->mask.insideMask(i, z)?getPixel(i, z):Double.NaN).filter(v->!Double.isNaN(v));
            }
            else { // loop within intersection
                int sX = inter.sizeX();
                int offX = inter.xMin();
                int offY = inter.yMin();
                return IntStream.range(0,inter.getSizeXY()).mapToDouble(i->{
                    int x = i%sX+offX;
                    int y = i/sX+offY;
                    return mask.insideMaskWithOffset(x, y, z+zMin)?getPixelWithOffset(x, y, z+zMin):Double.NaN;}
                ).filter(v->!Double.isNaN(v));
            }
        }
        else { // masks is relative to image
            if (!(mask instanceof ImageMask2D) && (z<0 || z>=sizeZ || z+zMin-mask.zMin()<0 || z+zMin-mask.zMin()>=mask.sizeZ())) return DoubleStream.empty();
            SimpleBoundingBox inter = BoundingBox.getIntersection2D(new SimpleBoundingBox(this).resetOffset(), mask);
            if (inter.isEmpty()) return DoubleStream.empty();
            if (inter.sameBounds(this) && (inter.sameBounds(mask) || (mask instanceof ImageMask2D && inter.sameBounds2D(mask)))) {
                if (mask instanceof BlankMask) return this.streamPlane(z);
                else return IntStream.range(0, sizeXY).mapToDouble(i->mask.insideMask(i, z)?getPixel(i, z):Double.NaN).filter(v->!Double.isNaN(v));
            }
            else {
                int sX = inter.sizeX();
                int offX = inter.xMin();
                int offY = inter.yMin();
                return IntStream.range(0,inter.getSizeXY()).mapToDouble(i->{
                    int x = i%sX+offX;
                    int y = i/sX+offY;
                    return mask.insideMaskWithOffset(x, y, z)?getPixelWithOffset(x, y, z):Double.NaN;}
                ).filter(v->!Double.isNaN(v));
            }
        }
    }

    @Override
    public void invert() {
        if (writable) {
            if (!modified) {
                modified = true;
            }
        } else throw new RuntimeException("Image not writable");
        if (image != null) image.invert();
        if (tilesZYX != null) {
            for (int z = 0; z< tilesZYX.length; ++z) {
                for (int y = 0; y< tilesZYX[0].length; ++y) {
                    for (int x = 0; x< tilesZYX[0][0].length; ++x) {
                        tilesZYX[z][y][x].invert();
                    }
                }
            }
        }
    }

    @Override
    public boolean insideMask(int x, int y, int z) {
        if (image != null) return image.insideMask(x, y, z);
        return tilesZYX[z/tileDimensions[2]][y/tileDimensions[1]][x/tileDimensions[0]].insideMask(x%tileDimensions[0], y%tileDimensions[1], z%tileDimensions[2]);
    }

    @Override
    public boolean insideMask(int xy, int z) {
        if (image != null) return image.insideMask(xy, z);
        int x = xy % sizeX;
        int y = xy / sizeX;
        return tilesZYX[z/tileDimensions[2]][y/tileDimensions[1]][x/tileDimensions[0]].insideMask(x%tileDimensions[0], y%tileDimensions[1], z%tileDimensions[2]);
    }

    @Override
    public boolean insideMaskWithOffset(int x, int y, int z) {
        return insideMask(x - xMin, y - yMin, z - zMin);
    }

    @Override
    public int count() {
        if (image != null) return image.count();
        int count = 0;
        for (int z = 0; z< tilesZYX.length; ++z) {
            for (int y = 0; y< tilesZYX[0].length; ++y) {
                for (int x = 0; x< tilesZYX[0][0].length; ++x) {
                    count += tilesZYX[z][y][x].count();
                }
            }
        }
        return count;
    }

    @Override
    public ImageMask duplicateMask() {
        return getImage().duplicateMask();
    }

    @Override
    public I crop(BoundingBox bounds) {
        if (image != null) return image.crop(bounds);
        //bounds.trimToImage(this);
        I res = newImage(name, new SimpleImageProperties(bounds, scaleXY, scaleZ));
        res.setCalibration(this);
        res.translate(this); // bounds are relative to this image
        if (!BoundingBox.intersect(getBoundingBox().resetOffset(), bounds)) return res; // no data is copied
        pasteView(res, null, bounds);
        return res;
    }

    public void pasteView(I dest, Offset destOffset, BoundingBox view) {
        if (image != null) Image.pasteImageView(image, dest, destOffset, view);
        else {
            int minZIdx = view.zMin() / tileDimensions[2];
            int maxZIdx = view.zMax() / tileDimensions[2];
            int minYIdx = view.yMin() / tileDimensions[1];
            int maxYIdx = view.yMax() / tileDimensions[1];
            int minXIdx = view.xMin() / tileDimensions[0];
            int maxXIdx = view.xMax() / tileDimensions[0];
            for (int z = minZIdx; z <= maxZIdx; ++z) {
                for (int y = minYIdx; y <= maxYIdx; ++y) {
                    for (int x = minXIdx; x <= maxXIdx; ++x) {
                        BoundingBox inter = BoundingBox.getIntersection(tilesZYX[z][y][x], view);
                        Offset dOff = new SimpleOffset(inter).translateReverse(view);
                        if (destOffset != null) dOff.translate(destOffset);
                        Image.pasteImageView(tilesZYX[z][y][x], dest, dOff, (BoundingBox) inter.duplicate().translateReverse(tilesZYX[z][y][x]));
                    }
                }
            }
        }
    }
}
