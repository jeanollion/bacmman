package bacmman.py_dataset;

import bacmman.core.Core;
import bacmman.image.BoundingBox;
import bacmman.image.Image;
import bacmman.image.SimpleBoundingBox;
import bacmman.image.wrappers.ImgLib2ImageWrapper;
import net.imagej.ops.transform.scaleView.DefaultScaleView;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.Img;
import net.imglib2.interpolation.InterpolatorFactory;
import net.imglib2.interpolation.randomaccess.LanczosInterpolatorFactory;
import net.imglib2.interpolation.randomaccess.NearestNeighborInterpolatorFactory;
import org.scijava.command.Command;
import org.scijava.command.CommandInfo;
import org.scijava.module.Module;
import org.scijava.module.ModuleRunner;
import org.scijava.module.ModuleService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

public class Utils {
    public final static Logger logger = LoggerFactory.getLogger(Utils.class);
    public static Image resampleBack(Image im, BoundingBox target, boolean binary, int... dimensions) {
        if (im.sameDimensions(target)) return im;
        // if resampled dim negative: need to crop to size -> will set zeros!
        if (Arrays.stream(dimensions).anyMatch(i->i<0)) { // needs cropping
            BoundingBox cropBB = new SimpleBoundingBox(0, dimensions[0]<0 && im.sizeX()<target.sizeX() ? target.sizeX()-1 : im.sizeX()-1, 0, dimensions.length>1 && dimensions[1]<0 && im.sizeY()<target.sizeY() ? target.sizeY()-1 : im.sizeY()-1, 0, dimensions.length>2 && dimensions[2]<0 && im.sizeZ()<target.sizeZ() ? target.sizeZ()-1 : im.sizeZ()-1);
            im = im.crop(cropBB);
        }
        return resampleImage(im, binary, target.sizeX(), target.sizeY(), target.sizeZ());
    }
    /**
     *
     * @param im
     * @param dimensions dimension of the final image. If a dimension is negative, original will be cropped to that dimension if it is larger, or resampled if it is smaller
     * @return
     */
    public static Image resampleImage(Image im, boolean binary, int... dimensions) {
        if (dimensions==null || dimensions.length==0) return im;
        // negative dimension = crop

        if (Arrays.stream(dimensions).anyMatch(i->i<0)) { // needs cropping
            BoundingBox cropBB = new SimpleBoundingBox(0, dimensions[0]<0 && -dimensions[0]<im.sizeX() ? -dimensions[0]-1 : im.sizeX()-1, 0, dimensions.length>1 && dimensions[1]<0 && -dimensions[1]<im.sizeY() ? -dimensions[1]-1 : im.sizeY()-1, 0, dimensions.length>2 && dimensions[2]<0 && -dimensions[2]<im.sizeZ() ? -dimensions[2]-1 : im.sizeZ()-1);
            im = im.crop(cropBB);
            int[] dims = new int[dimensions.length];
            for (int i = 0; i<dimensions.length; ++i) dims[i] = Math.abs(dimensions[i]);
            dimensions=dims;
        }

        //Image res = Image.createEmptyImage("resampled", im, new SimpleImageProperties(dimensions[0]>0?dimensions[0]:im.sizeX(), dimensions.length>=2&&dimensions[1]>0?dimensions[1]:im.sizeY(), dimensions.length>=3&&dimensions[2]>0?dimensions[2]:im.sizeZ(), im.getScaleXY(), im.getScaleZ()));
        //Img out = ImgLib2ImageWrapper.getImage(res);
        //Resample resample = new Resample(Resample.Mode.LANCZOS);
        //resample.compute(in, out);
        //return res;
        double[] scaleFactors = new double[im.sizeZ()>1? 3:2];
        for (int i = 0; i<scaleFactors.length;++i) scaleFactors[i] = dimensions.length>i && dimensions[i]>0 ? (double)dimensions[i]/im.size(i) : 1;

        if (Arrays.stream(scaleFactors).anyMatch(i->i!=1)) { // needs resampling
            //logger.debug("resampling: scales: {}, dims: {}, binary: {}", scaleFactors, dimensions, binary);
            Img in = ImgLib2ImageWrapper.getImage(im);
            InterpolatorFactory inter = binary ? new NearestNeighborInterpolatorFactory() : new LanczosInterpolatorFactory(3, false);
            // Todo find why this is not running from GUI
            //final Future<CommandModule> future = Core.imagej2().command().run(DefaultScaleView.class, false, "scaleFactors", scaleFactors, "interpolator", inter, "in", in); // clipping will be performed during converion to byte
            //final Module module = Core.imagej2().module().waitFor(future);

            Map<String, Object> inputs = new HashMap<>();
            inputs.put("scaleFactors", scaleFactors);
            inputs.put("interpolator", inter);
            inputs.put("in", in);
            Module module = runModule(Core.imagej2().module(), DefaultScaleView.class, inputs);
            Image res = ImgLib2ImageWrapper.wrap((RandomAccessibleInterval) module.getOutput("out"));
            //logger.debug("scales: {}, dims: {}, output dims: {}", scaleFactors, dimensions, res.getBoundingBox());
            return res;
        } else return im;
    }


    public static <C extends Command> Module runModule(ModuleService moduleService, Class<C> commandClass, Map<String, Object> inputs) {
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
    }

}
