package bacmman.image;

import bacmman.utils.ArrayUtil;

import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.DoubleStream;
import java.util.stream.IntStream;

public abstract class LazyImage5D<I extends Image<I>> extends Image<I> {
    String[] channelNames, channelColors;
    final I imageType;
    int f, c;
    public LazyImage5D(String name, I imageType) {
        super(name, 0, 0, 0);
        this.imageType = imageType;
    }

    public static <I extends Image<I>> Function<int[], I> homogenizeType(I type, Function<int[], Image> generatorFCZ) {
        return fcz -> {
            Image plane = generatorFCZ.apply(fcz);
            if (!plane.getClass().equals(type.getClass())) return TypeConverter.cast(plane, type);
            return (I)plane;
        };
    }

    public abstract int getSizeF();

    public abstract int getSizeC();

    public LazyImage5D setPosition(int f, int c) {
        this.f = f;
        this.c = c;
        return this;
    }

    public int getChannel() {
        return c;
    }

    public int getFrame() {
        return f;
    }

    public abstract boolean isSinglePlane(int f, int c);

    public I getImageType() {
        return imageType;
    }

    public abstract boolean isImageOpen(int f, int c, int z);
    public abstract boolean isImageOpen(int f, int c);

    public abstract I getImage(int f, int c, int z);
    public abstract I getImage(int f, int c);

    public void setChannelNames(String[] channelNames) {
        if (channelNames.length != getSizeC()) throw new IllegalArgumentException("Invalid channel length");
        this.channelNames = channelNames;
    }

    public void setChannelColors(String[] channelColors) {
        if (channelColors.length != getSizeC()) throw new IllegalArgumentException("Invalid channel length");
        this.channelColors = channelColors;
    }

    public String[] getChannelNames() {
        return channelNames;
    }

    public String[] getChannelColors() {
        return channelColors;
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
        return getImage(f, c).duplicate(name);
    }

    public abstract LazyImage5D<I> duplicateLazyImage();

    @Override
    public I newImage(String name, ImageProperties properties) {
        return imageType.newImage(name, properties);
    }

    @Override
    public ImageMask duplicateMask() {
        return duplicate(getName());
    }
}
