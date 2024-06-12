package bacmman.processing;

import bacmman.image.Image;
import bacmman.image.SimpleBoundingBox;
import bacmman.image.wrappers.ImgLib2ImageWrapper;
import net.imglib2.interpolation.InterpolatorFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.function.IntPredicate;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public class ResizeUtils {
    public final static Logger logger = LoggerFactory.getLogger(ResizeUtils.class);


    /*public static <C extends Command> Module runModule(ModuleService moduleService, Class<C> commandClass, Map<String, Object> inputs) {
        CommandInfo command = Core.imagej2().command().getCommand(commandClass);
        if (command == null) command = new CommandInfo(commandClass);
        Module module = moduleService.createModule(command);

        Method method = null;
        try {
            method = moduleService.getClass().getDeclaredMethod("assignInputs", Module.class, Map.class);
            method.setAccessible(true);
            method.invoke(moduleService, module, inputs);
        } catch (NoSuchMethodException|IllegalAccessException| InvocationTargetException e) {
            logger.debug("error while assigning inputs", e);
        }
        final ModuleRunner runner = new ModuleRunner(moduleService.getContext(), module, null, null);
        runner.run();
        return module;
    }*/

    /**
     *
     * @param imagesNC matrix of images. All channel must have same dimension otherwise throws IllegalArgumentException
     * @return dimensions for each image: channels: in order X, Y, (Z)
     */
    public static int[][] getDimensions(Image[][] imagesNC) {
        int[][] dims = Arrays.stream(imagesNC).map(im -> im[0].dimensions()).toArray(int[][]::new);
        IntPredicate oneDimDiffers = idx -> IntStream.range(1, imagesNC[idx].length).anyMatch(i-> !Arrays.equals(imagesNC[idx][i].dimensions(), dims[idx]));
        if (IntStream.range(0, imagesNC.length).anyMatch(i->oneDimDiffers.test(i)))
            throw new IllegalArgumentException("at least two channels have different dimensions");
        return dims;
    }

    /**
     *
     * @param imagesN array of images
     * @return dimensions for each image: channels: in order X, Y, (Z)
     */
    public static int[][] getDimensions(Image[] imagesN) {
        return Arrays.stream(imagesN).map(Image::dimensions).toArray(int[][]::new);
    }

    public static Image[] getChannel(Image[][] imageNC, int channelIdx) {
        int cIdx = channelIdx>=0 ? channelIdx : imageNC[0].length + channelIdx;
        if (cIdx >= imageNC[0].length) throw new IllegalArgumentException("Requested Channel is "+cIdx+" but image set contains only "+imageNC[0].length +" channels");
        return Arrays.stream(imageNC).map(a -> a[cIdx]).toArray(Image[]::new);
    }

    public static Image[][] setZtoChannel(Image[] imageN) {
        return Arrays.stream(imageN).map(im -> im.splitZPlanes().toArray(new Image[0])).toArray(Image[][]::new);
    }

    public static Image[][] setZtoChannel(Image[][] imageNC, int channelIdx) {
        Image[] imageN = getChannel(imageNC, channelIdx);
        return Arrays.stream(imageN).map(im -> im.splitZPlanes().toArray(new Image[0])).toArray(Image[][]::new);
    }

    public static Image[][] setChanneltoZ(Image[][] imageNC) {
        Image[][] res = new Image[imageNC.length][1];
        for (int n = 0; n<imageNC.length; ++n) {
            res[n][0] = Image.mergeZPlanes(imageNC[n]);
        }
        return res;
    }

    public static int getSizeZ(Image[][] imageNC, int c) {
        int[] sizeZ = Arrays.stream(imageNC).map(imageC -> imageC[c]).mapToInt(Image::sizeZ).distinct().toArray();
        assert sizeZ.length==1 : "different sizeZ within channel: "+c;
        return sizeZ[0];
    }
    public static Image[][] setZtoBatch(Image[][] imageNC) {
        // assert same number of planes for each channels
        int[] sizeZ = IntStream.range(0, imageNC[0].length).map(c -> getSizeZ(imageNC, c)).distinct().toArray();
        assert sizeZ.length == 1 : "different sizeZ among channels";
        Image[][] imageNZC = new Image[imageNC.length * sizeZ[0]][imageNC[0].length];
        for (int n = 0; n<imageNC.length; ++n) {
            for (int z = 0; z<sizeZ[0]; ++z) {
                int idx = n * sizeZ[0] + z;
                for (int c = 0; c < imageNC[0].length; ++c) {
                    imageNZC[idx][c] = imageNC[n][c].getZPlane(z);
                }
            }
        }
        return imageNZC;
    }

    public static Image[][] setBatchToZ(Image[][] imageNzC, int sizeZ) { // reverse from setZtoBatch
        int[] sizeZa = IntStream.range(0, imageNzC[0].length).map(c -> getSizeZ(imageNzC, c)).distinct().toArray();
        assert sizeZa.length == 1 : "different sizeZ among channels";
        assert sizeZa[0] == 1 : "images should be only 2D";
        assert imageNzC.length % sizeZ == 0 : "batch should be divisible by sizeZ";
        Image[][] imageNCz = new Image[imageNzC.length/sizeZ][imageNzC[0].length];
        for (int n = 0; n<imageNCz.length; ++n) {
            for (int c = 0; c < imageNCz[0].length; ++c) {
                int cc=c;
                Image[] zPlanes = IntStream.range(n * sizeZ, (n+1) * sizeZ).mapToObj(i -> imageNzC[i][cc]).toArray(Image[]::new);
                imageNCz[n][c] = Image.mergeZPlanes(zPlanes);
            }
        }
        return imageNCz;
    }

    public static Image[] averageChannelOnOutputs(Image[][][] imageONC, int channelIdx, int... outputIdx) {
        if (outputIdx.length==1) return getChannel(imageONC[outputIdx[0]], channelIdx);
        IntStream range =  (outputIdx.length==0) ? IntStream.range(0, imageONC.length) : IntStream.of(outputIdx);
        Image[][] imageON = range.mapToObj(i -> imageONC[i]).map(imageNC -> getChannel(imageNC, channelIdx)).toArray(Image[][]::new);
        return IntStream.range(0, imageON[0].length)
                .mapToObj(n-> Arrays.stream(imageON).map(images -> images[n]).toArray(Image[]::new)) // for each batch index -> generate an array of outputs
                .map(a -> ImageOperations.average(null, a)) // average on outputs
                .toArray(Image[]::new);
    }

    public static Image[][] resample(Image[][] imagesNC, boolean[] isBinaryC, int[][] imageDimensionsN) {
        return IntStream.range(0, imagesNC.length).parallel()
                .mapToObj(idx ->  IntStream.range(0, imagesNC[idx].length)
                        .mapToObj(c -> Resize.resample(imagesNC[idx][c], isBinaryC[c], imageDimensionsN.length==1 ? imageDimensionsN[0] : imageDimensionsN[idx]))
                        .toArray(Image[]::new))
                .toArray(Image[][]::new);
    }
    @SuppressWarnings("unchecked")
    public static <T extends Image> Stream<T> resample(T[] imagesN, boolean isBinary, int[][] imageDimensionsN) {
        return IntStream.range(0, imagesN.length).parallel()
                .mapToObj(idx -> (T) Resize.resample(imagesN[idx], isBinary, imageDimensionsN.length == 1 ? imageDimensionsN[0] : imageDimensionsN[idx]));
    }
    public static <T extends Image> Stream<T> resample(T[] imagesN, ImgLib2ImageWrapper.INTERPOLATION interpolation, int[][] imageDimensionsN) {
        return IntStream.range(0, imagesN.length).parallel()
                .mapToObj(idx -> (T) Resize.resample(imagesN[idx], interpolation, imageDimensionsN.length == 1 ? imageDimensionsN[0] : imageDimensionsN[idx]));
    }
    public static <T extends Image> Stream<T> resample(T[] imagesN, InterpolatorFactory interpolation, int[][] imageDimensionsN) {
        return  IntStream.range(0, imagesN.length).parallel()
                .mapToObj(idx -> (T) Resize.resample(imagesN[idx], interpolation, imageDimensionsN.length == 1 ? imageDimensionsN[0] : imageDimensionsN[idx]));
    }
    public static Image[][] resample(Image[][] imagesNC, InterpolatorFactory interpolation, int[][] imageDimensionsN) {
        return IntStream.range(0, imagesNC.length).parallel()
                .mapToObj(idx ->  IntStream.range(0, imagesNC[idx].length)
                        .mapToObj(c -> Resize.resample(imagesNC[idx][c], interpolation, imageDimensionsN.length == 1 ? imageDimensionsN[0] : imageDimensionsN[idx]))
                        .toArray(Image[]::new))
                .toArray(Image[][]::new);
    }
    public static <T extends Image> Stream<T> pad(T[] imagesN, Resize.EXPAND_MODE mode, Resize.EXPAND_POSITION position, int[][] imageDimensionsN) {
        return IntStream.range(0, imagesN.length).parallel()
                .mapToObj(idx -> (T) Resize.pad(imagesN[idx], mode, position, imageDimensionsN.length == 1 ? imageDimensionsN[0] : imageDimensionsN[idx]));
    }
    public static Image[][] pad(Image[][] imagesNC, Resize.EXPAND_MODE mode, Resize.EXPAND_POSITION position, int[][] imageDimensionsN) {
        return IntStream.range(0, imagesNC.length).parallel()
                .mapToObj(idx ->  IntStream.range(0, imagesNC[idx].length)
                        .mapToObj(c -> Resize.pad(imagesNC[idx][c], mode, position, imageDimensionsN.length == 1 ? imageDimensionsN[0] : imageDimensionsN[idx]))
                        .toArray(Image[]::new))
                .toArray(Image[][]::new);
    }
    public static boolean allDimensionsEqual(int[][] dimensions, int[] referenceDimensions) {
        return IntStream.range(0, dimensions.length).allMatch(i -> Arrays.equals(dimensions[i], referenceDimensions));
    }
    public static long getVolume(int[] dimensions) {
        long res = 1;
        for (int d : dimensions) res*=d;
        return res;
    }
    public static long getVolume(long[] dimensions) {
        long res = 1;
        for (long d : dimensions) res*=d;
        return res;
    }
}
