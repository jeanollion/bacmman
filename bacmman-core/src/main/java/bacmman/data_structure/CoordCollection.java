package bacmman.data_structure;

import bacmman.image.*;
import it.unimi.dsi.fastutil.ints.*;
import it.unimi.dsi.fastutil.longs.*;

import java.util.stream.DoubleStream;
import java.util.stream.LongStream;

public interface CoordCollection {
    LongStream stream();
    DoubleStream stream(Image image);
    boolean isEmpty();
    void clear();
    void removeIf(LongPredicate filter);
    int size();
    int sizeX();
    int sizeY();
    int sizeZ();
    double getPixel(Image image, long coord);
    int getPixelInt(ImageInteger image, long coord);
    void setPixel(Image image, long coord, double value);
    void setPixel(ImageInteger image, long coord, int value);
    boolean insideMask(ImageMask mask, long coord);
    boolean insideBounds(long coord, int dx, int dy, int dz);
    boolean containsCoord(long coord);
    boolean add(long coord);
    boolean addAll(long... coord);
    boolean addAll(CoordCollection coord);
    long translate(long coord, int dx, int dy, int dz);
    long toCoord(int x, int y, int z);
    int[] parse(long coord);
    BoundingBox getBounds();
    ImageMask getMask(double scaleXY, double scaleZ);
    static CoordCollection create(int sizeX, int sizeY, int sizeZ) {
        if (sizeZ==1) return new CoordCollection2D(sizeX, sizeY);
        else return new CoordCollection3D(sizeX, sizeY, sizeZ);
    }

    abstract class AbstractCoordCollection2D implements CoordCollection {
        final int sizeX, sizeY, z, sizeXY;
        public AbstractCoordCollection2D(int sizeX, int sizeY, int z) {
            this.sizeX = sizeX;
            this.sizeY = sizeY;
            this.z=z;
            this.sizeXY = sizeY * sizeX;
        }
        public AbstractCoordCollection2D(int sizeX, int sizeY) {
            this(sizeX, sizeY, 0);
        }
        @Override public double getPixel(Image image, long coord) {
            return image.getPixel((int)coord, z);
        }
        @Override public int getPixelInt(ImageInteger image, long coord) {
            return image.getPixelInt((int)coord, z);
        }
        @Override public void setPixel(Image image, long coord, double value) {
            image.setPixel((int)coord, z, value);
        }
        @Override public void setPixel(ImageInteger image, long coord, int value) {
            image.setPixel((int)coord, z, value);
        }
        @Override public boolean insideMask(ImageMask mask, long coord) {
            return mask.insideMask((int)coord, z);
        }
        @Override public boolean insideBounds(long coord, int dx, int dy, int dz) {
            long y = coord/sizeX;
            int x = (int)(coord - y * sizeX);
            y+=dy;
            if (y<0 || y>=sizeY) return false;
            x+=dx;
            return x>=0 && x<sizeX;
        }
        @Override public int[] parse(long coord) {
            long y = coord/sizeX;
            int x = (int)(coord - y * sizeX);
            return new int[]{x, (int)y, z};
        }

        @Override public long translate(long coord, int dx, int dy, int dz) {
            return coord + dy*sizeX + dx;
        }
        @Override public long toCoord(int x, int y, int z) {
            return x + y * sizeX;
        }
        @Override
        public int sizeX() {
            return sizeX;
        }
        @Override
        public int sizeY() {
            return sizeY;
        }
        @Override
        public int sizeZ() {
            return 1;
        }
        public DoubleStream stream(Image image) {
            return stream().mapToDouble(c -> getPixel(image, c));
        }
        @Override public BoundingBox getBounds() {
            MutableBoundingBox bds = new MutableBoundingBox();
            stream().forEach( coord -> {
                long y = coord/sizeX;
                bds.union((int)(coord - y * sizeX), (int)y, z);
            });
            return bds;
        }
        @Override public ImageMask getMask(double scaleXY, double scaleZ) {
            ImageByte mask = new ImageByte("", new SimpleImageProperties(getBounds(), scaleXY, scaleZ));
            stream().forEach(coord-> {
                long y = coord/sizeX;
                mask.setPixelWithOffset((int)(coord - y * sizeX), (int)y, z, 1);
            });
            return mask;
        }
        public abstract IntCollection getCoords();
    }

    abstract class AbstractCoordCollection3D implements CoordCollection {
        final int sizeX, sizeY, sizeZ;
        final long sizeXYZ, sizeXY;
        public AbstractCoordCollection3D(int sizeX, int sizeY, int sizeZ) {
            this.sizeX = sizeX;
            this.sizeY = sizeY;
            this.sizeZ = sizeZ;
            this.sizeXY = (long) sizeY * sizeX;
            this.sizeXYZ = (long) sizeZ * sizeXY;
        }
        @Override public double getPixel(Image image, long coord) {
            long z = coord / sizeXY;
            return image.getPixel((int)(coord - z * sizeXY), (int)z);
        }
        @Override public int getPixelInt(ImageInteger image, long coord) {
            long z = coord / sizeXY;
            return image.getPixelInt((int)(coord - z * sizeXY), (int)z);
        }
        @Override public void setPixel(Image image, long coord, double value) {
            long z = coord / sizeXY;
            image.setPixel((int)(coord - z * sizeXY), (int)z, value);
        }
        @Override public void setPixel(ImageInteger image, long coord, int value) {
            long z = coord / sizeXY;
            image.setPixel((int)(coord - z * sizeXY), (int)z, value);
        }
        @Override public boolean insideMask(ImageMask mask, long coord) {
            long z = coord / sizeXY;
            return mask.insideMask((int)(coord - z * sizeXY), (int)z);
        }
        @Override public boolean insideBounds(long coord, int dx, int dy, int dz) {
            long z = coord / sizeXY;
            int xy = (int)(coord - z * sizeXY);
            int y = xy/sizeX;
            int x = xy - y * sizeX;
            z+=dz;
            if (z<0 || z>=sizeZ) return false;
            y+=dy;
            if (y<0 || y>=sizeY) return false;
            x+=dx;
            return x>=0 && x<sizeX;
        }

        @Override public long translate(long coord, int dx, int dy, int dz) {
            return coord + dz * sizeXY + dy * sizeX + dx;
        }
        @Override public long toCoord(int x, int y, int z) {
            return x + y * sizeX + z * sizeXY;
        }

        @Override public int[] parse(long coord) {
            long z = coord / sizeXY;
            int xy = (int)(coord - z * sizeXY);
            int y = xy/sizeX;
            int x = xy - y * sizeX;
            return new int[]{x, y, (int)z};
        }

        @Override
        public int sizeX() {
            return sizeX;
        }
        @Override
        public int sizeY() {
            return sizeY;
        }
        @Override
        public int sizeZ() {
            return sizeZ;
        }
        public DoubleStream stream(Image image) {
            return stream().mapToDouble(c -> getPixel(image, c));
        }
        @Override public BoundingBox getBounds() {
            MutableBoundingBox bds = new MutableBoundingBox();
            stream().forEach( coord -> {
                long z = coord / sizeXY;
                int xy = (int)(coord - z * sizeXY);
                int y = xy/sizeX;
                bds.union(xy - y * sizeX, y, (int)z);
            });
            return bds;
        }
        @Override public ImageMask getMask(double scaleXY, double scaleZ) {
            ImageByte mask = new ImageByte("", new SimpleImageProperties(getBounds(), scaleXY, scaleZ));
            stream().forEach(coord-> {
                long z = coord/sizeXY;
                int xy = (int)(coord - z * sizeXY);
                int y = xy/sizeX;
                mask.setPixelWithOffset(xy - y * sizeX, y, (int)z, 1);
            });
            return mask;
        }
        public abstract LongCollection getCoords();
    }

    class CoordCollection2D extends AbstractCoordCollection2D {
        final IntCollection coords;
        public CoordCollection2D(int sizeX, int sizeY) {
            this(sizeX, sizeY, new IntArrayList());
        }
        public CoordCollection2D(int sizeX, int sizeY, IntCollection coords) {
            super(sizeX, sizeY);
            this.coords = coords;
        }
        public LongStream stream() {
            return coords.intStream().asLongStream();
        }

        @Override
        public boolean isEmpty() {
            return coords.isEmpty();
        }

        @Override
        public void clear() {
            coords.clear();
        }

        @Override
        public void removeIf(LongPredicate filter) {
            coords.removeIf(filter::test);
        }

        @Override
        public int size() {
            return coords.size();
        }

        @Override
        public boolean containsCoord(long coord) {
            return coords.contains((int)coord);
        }

        @Override public boolean add(long coord) {
            return coords.add((int)coord);
        }
        @Override public boolean addAll(long... coord) {
            for (long c: coord) coords.add((int)c);
            return true;
        }
        @Override public IntCollection getCoords() {return coords;}
        @Override public boolean addAll(CoordCollection coordCollection) {
            if (coordCollection instanceof AbstractCoordCollection2D) {
                return coords.addAll(((AbstractCoordCollection2D) coordCollection).getCoords());
            } else throw new IllegalArgumentException("Invalid coordset");
        }
    }

    class CoordCollection3D extends AbstractCoordCollection3D {
        final LongCollection coords;
        public CoordCollection3D(int sizeX, int sizeY, int sizeZ) {
            this(sizeX, sizeY, sizeZ, new LongArrayList());
        }
        public CoordCollection3D(int sizeX, int sizeY, int sizeZ, LongCollection coords) {
            super(sizeX, sizeY, sizeZ);
            this.coords = coords;
        }
        public LongStream stream() {
            return coords.longStream();
        }

        @Override
        public boolean isEmpty() {
            return coords.isEmpty();
        }

        @Override
        public void clear() {
            coords.clear();
        }

        @Override
        public void removeIf(LongPredicate filter) {
            coords.removeIf(filter);
        }

        @Override
        public int size() {
            return coords.size();
        }
        @Override
        public boolean containsCoord(long coord) {
            return coords.contains(coord);
        }
        @Override public boolean add(long coord) {
            return coords.add(coord);
        }
        @Override public boolean addAll(long... coord) {
            for (long c: coord) coords.add(c);
            return true;
        }
        @Override public LongCollection getCoords() {return coords;}
        @Override public boolean addAll(CoordCollection coordCollection) {
            if (coordCollection instanceof AbstractCoordCollection3D) {
                return coords.addAll(((AbstractCoordCollection3D) coordCollection).getCoords());
            } else throw new IllegalArgumentException("Invalid coordset");
        }
    }


}

