package bacmman.processing.ilastik;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

import bacmman.core.Core;
import bacmman.image.Image;
import bacmman.image.wrappers.ImgLib2ImageWrapper;
import bacmman.utils.Utils;
import ij.IJ;
import ij.ImagePlus;
import net.imagej.axis.AxisType;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.Img;
import net.imglib2.img.ImgView;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.view.IntervalView;
import net.imglib2.view.Views;
import org.ilastik.ilastik4ij.executors.AbstractIlastikExecutor;
import org.ilastik.ilastik4ij.executors.AbstractIlastikExecutor.PixelPredictionType;
import org.ilastik.ilastik4ij.executors.Autocontext;
import org.ilastik.ilastik4ij.executors.PixelClassification;
import org.ilastik.ilastik4ij.hdf5.Hdf5DataSetWriter;
import org.ilastik.ilastik4ij.ui.IlastikOptions;
import org.scijava.Context;
import org.scijava.app.StatusService;
import org.scijava.log.LogService;
import org.scijava.options.OptionsService;
import net.imagej.ImgPlus;
import net.imagej.axis.Axes;
import net.imglib2.Interval;
import net.imglib2.img.display.imagej.ImgPlusViews;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PixelClassificationRunner {
    public final static Logger logger = LoggerFactory.getLogger(PixelClassificationRunner.class);
    /**
     * Executes the ilastik process on the specified image and return the probability map
     *
     * @param input
     *            the source image.
     * @param autoContext
     *             whether autocontext or pixel classification algorithm is to be used
     * @param projectFilePath
     *            the path to the ilastik project containing the classifier.
     * @param classId
     *            the index of the class to extract.
     * @return the probability map
     * @throws IOException
     *             if the ilastik file cannot be found.
     */
    public static <T extends RealType<T> & NativeType<T>> Image[] run(
            final Image[] input,
            final boolean autoContext,
            final String projectFilePath,
            final long classId) throws IOException
    {
        Context context = Core.imagej2().getContext();
        final LogService logService = context.getService( LogService.class ); // TODO use bacmman log
        final StatusService statusService = context.getService( StatusService.class ); // TODO use bacmman progress
        final OptionsService optionService = context.getService( OptionsService.class );

        // Ilastik options
        final IlastikOptions ilastikOptions = optionService.getOptions( IlastikOptions.class );
        final File executableFilePath = ilastikOptions.getExecutableFile();
        final int numThreads = ilastikOptions.getNumThreads() < 0 ? Runtime.getRuntime().availableProcessors() : ilastikOptions.getNumThreads();
        final int maxRamMb = ilastikOptions.getMaxRamMb();

        final File projectFile = new File( projectFilePath );
        final AbstractIlastikExecutor classifier = autoContext ? new Autocontext( executableFilePath, projectFile, logService, statusService, numThreads, maxRamMb )
                : new PixelClassification( executableFilePath, projectFile, logService, statusService, numThreads, maxRamMb );
        Function<ImgPlus<T>, ImgPlus<T>> classify = autoContext ? i -> {
            try {
                return ((Autocontext)classifier).classifyPixels(i, PixelPredictionType.Probabilities);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        } : i -> {
            try {
                return ((PixelClassification)classifier).classifyPixels(i, PixelPredictionType.Probabilities);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        };
        // case 1: all images have same dimension: run batch
        Image[] result = new Image[input.length];
        if (input.length>1 && Utils.objectsAllHaveSameProperty(Arrays.asList(input), Image::sameDimensions)) {
            boolean is3D = input[0].sizeZ()>1;
            List<Img<T>> imgs = Arrays.stream(input).map(i->(Img<T>)ImgLib2ImageWrapper.getImage(i)).collect(Collectors.toList());
            IntervalView<T> v = (IntervalView<T>) Views.concatenate(is3D?3:2, imgs.stream().map(i->Views.addDimension(i, 0, 0)).collect(Collectors.toList()));
            Img<T> img = ImgView.wrap( v, imgs.get(0).factory() );
            AxisType[] axes = is3D ? new AxisType[]{Axes.X, Axes.Y, Axes.Z, Axes.TIME} : new AxisType[]{Axes.X, Axes.Y, Axes.TIME};
            ImgPlus<T> imgPlus = new ImgPlus(img, "input", axes);
            ImgPlus output = classify.apply(imgPlus);
            output = ImgPlusViews.hyperSlice( output, output.dimensionIndex( Axes.CHANNEL ), classId );
            for (int i = 0; i<result.length; ++i) result[i] = ImgLib2ImageWrapper.wrap(ImgPlusViews.hyperSlice( output, output.dimensionIndex( Axes.TIME ), i ));
        } else {
            Function<Image, Image> run = i -> {
                Img img = ImgLib2ImageWrapper.getImage(i);
                ImgPlus imgPlus = ImgPlus.wrap(img);
                ImgPlus output = classify.apply(imgPlus);
                output = ImgPlusViews.hyperSlice( output, output.dimensionIndex( Axes.CHANNEL ), classId );
                return ImgLib2ImageWrapper.wrap(output);
            };
            for (int i = 0; i<result.length; ++i) result[i] = run.apply(input[i]);
        }
        return result;
    }
}

