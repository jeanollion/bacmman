package bacmman.processing;

import bacmman.image.Image;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.IntPredicate;
import java.util.stream.Collectors;
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
     * @param imagesNC
     * @return shape for each image: channels, (Z) Y, X if {@param includeChannelNumber} true else X, Y, (Z)
     */
    public static int[][] getShapes(Image[][] imagesNC, boolean includeChannelNumber) {
        BiFunction<Image, Integer, int[]> getShape;
        if (includeChannelNumber) getShape = (im , nC)-> im.sizeZ()>1 ?new int[]{nC, im.sizeZ(), im.sizeY(), im.sizeX()} :  new int[]{nC, im.sizeY(), im.sizeX()};
        else getShape = (im , nC)-> im.shape();
        int[][] shapes = Arrays.stream(imagesNC).map(im -> getShape.apply(im[0], im.length)).toArray(int[][]::new);
        IntPredicate oneShapeDiffers = idx -> IntStream.range(1, imagesNC[idx].length).anyMatch(i-> !Arrays.equals(getShape.apply(imagesNC[idx][i], imagesNC[idx].length), shapes[idx]));
        if (IntStream.range(0, imagesNC.length).anyMatch(i->oneShapeDiffers.test(i)))
            throw new IllegalArgumentException("at least two channels have different shapes");
        return shapes;
    }
    public static int[][] getShapes(Image[] imagesN, boolean includeChannelNumber) {
        Function<Image, int[]> getShape;
        if (includeChannelNumber) getShape = (im )-> im.sizeZ()>1 ?new int[]{1, im.sizeZ(), im.sizeY(), im.sizeX()} :  new int[]{1, im.sizeY(), im.sizeX()};
        else getShape = Image::shape;
        return Arrays.stream(imagesN).map(getShape::apply).toArray(int[][]::new);
    }
    public static Image[] getChannel(Image[][] imageNC, int channelIdx) {
        return Arrays.stream(imageNC).map(a -> a[channelIdx]).toArray(Image[]::new);
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
    public static Image[][] resample(Image[][] imagesNC, boolean[] isBinaryC, int[][] imageShapeN) {
        return IntStream.range(0, imagesNC.length).parallel()
                .mapToObj(idx ->  IntStream.range(0, imagesNC[idx].length)
                        .mapToObj(c -> Resample.resample(imagesNC[idx][c], isBinaryC[c], imageShapeN.length==1 ? imageShapeN[0] : imageShapeN[idx]))
                        .toArray(Image[]::new))
                .toArray(Image[][]::new);
    }
    @SuppressWarnings("unchecked")
    public static <T extends Image> T[] resample(T[] imagesN, T[] output, boolean isBinary, int[][] imageShapeN) {
        logger.debug("resample: shape l :{} shape0 '= {}", imageShapeN.length, imageShapeN[0]);
        Stream<T> s =  IntStream.range(0, imagesN.length).parallel()
                .mapToObj(idx -> (T)Resample.resample(imagesN[idx], isBinary, imageShapeN.length == 1 ? imageShapeN[0] : imageShapeN[idx]));
        List<T> res = s.collect(Collectors.toList());
        res.toArray(output);
        return output;
    }
    public static <T extends Image> T[] resample(T[] imagesN, T[] output, Resample.INTERPOLATION interpolation, int[][] imageShapeN) {
        logger.debug("resample: shape l :{} shape0 '= {}", imageShapeN.length, imageShapeN[0]);
        Stream<T> s =  IntStream.range(0, imagesN.length).parallel()
                .mapToObj(idx -> (T)Resample.resample(imagesN[idx], interpolation, imageShapeN.length == 1 ? imageShapeN[0] : imageShapeN[idx]));
        List<T> res = s.collect(Collectors.toList());
        res.toArray(output);
        return output;
    }
    public static boolean allShapeEqual(int[][] shapes, int[] referenceShape) {
        return IntStream.range(0, shapes.length).allMatch(i -> Arrays.equals(shapes[i], referenceShape));
    }
    public static long getSize(int[] shape) {
        long res = 1;
        for (int d : shape) res*=d;
        return res;
    }
    public static long getSize(long[] shape) {
        long res = 1;
        for (long d : shape) res*=d;
        return res;
    }
}
