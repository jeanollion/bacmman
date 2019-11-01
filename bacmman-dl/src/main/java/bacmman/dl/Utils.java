package bacmman.dl;

import bacmman.image.Image;
import bacmman.plugins.HistogramScaler;
import bacmman.processing.Resample;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.IntPredicate;
import java.util.stream.IntStream;

public class Utils {
    public final static Logger logger = LoggerFactory.getLogger(Utils.class);


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

    public static Image[][] resample(Image[][] imagesNC, boolean[] isBinaryC, int[][] shapeN) {
        return IntStream.range(0, imagesNC.length).parallel()
                .mapToObj(idx ->  IntStream.range(0, imagesNC[idx].length)
                        .mapToObj(c -> Resample.resample(imagesNC[idx][c], isBinaryC[c], shapeN.length==1 ? shapeN[0] : shapeN[idx]))
                        .toArray(Image[]::new))
                .toArray(Image[][]::new);
    }
    public static Image[] resample(Image[] imagesN, boolean isBinary, int[][] shapeN) {
        logger.debug("resample: shape l :{} shape0 '= {}", shapeN.length, shapeN[0]);
        return IntStream.range(0, imagesN.length).parallel()
                .mapToObj(idx -> Resample.resample(imagesN[idx], isBinary, shapeN.length==1 ? shapeN[0] : shapeN[idx]))
                .toArray(Image[]::new);
    }
    public static boolean allShapeEqual(int[][] shapes, int[] referenceShape) {
        return IntStream.range(0, shapes.length).allMatch(i -> Arrays.equals(shapes[i], referenceShape));
    }

    public static void scale(Image[][] imageNC, HistogramScaler scaler) {
        if (scaler==null) return;
        int n_im = imageNC.length;
        int n_chan = imageNC[0].length;
        IntStream.range(0, n_im * n_chan).parallel().forEach(i -> {
            int im_idx = i / n_chan;
            int chan_idx = i % n_chan;
            imageNC[im_idx][chan_idx] =  scaler.scale(imageNC[im_idx][chan_idx]);
        });
    }
}
