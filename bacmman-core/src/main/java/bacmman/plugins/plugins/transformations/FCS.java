package bacmman.plugins.plugins.transformations;

import bacmman.configuration.parameters.*;
import bacmman.data_structure.input_image.InputImages;
import bacmman.image.BlankMask;
import bacmman.image.Image;
import bacmman.image.ImageFloat;
import bacmman.plugins.*;
import bacmman.processing.Filters;
import bacmman.processing.ImageOperations;
import bacmman.processing.neighborhood.Neighborhood;

import java.io.IOException;

public class FCS implements Filter, DevPlugin {
    FloatParameter radiusXY = new FloatParameter("Radius", 0).setLowerBound(0);
    IntegerParameter dT = new IntegerParameter("dT", 1).setLowerBound(1);
    BooleanParameter norm = new BooleanParameter("Normalize", true).setHint("Normalize by stdev");
    enum MODE {MEAN, STD, AUTOCORRELATION}
    EnumChoiceParameter<MODE> mode = new EnumChoiceParameter<>("Mode", MODE.values(), MODE.MEAN);
    ConditionalParameter<MODE> modeCond = new ConditionalParameter<>(mode).setActionParameters(MODE.AUTOCORRELATION, dT, norm);

    @Override
    public Image applyTransformation(int channelIdx, int timePoint, Image image) {
        if (radiusXY.getDoubleValue() == 0) {
            switch (mode.getSelectedEnum()) {
                case STD:
                    return stdZProjection(image, null);
                case MEAN:
                    return ImageOperations.meanZProjection(image);
                case AUTOCORRELATION:
                    return autoCorrelationProjection(image, null, dT.getIntValue(), norm.getSelected());
            }
        } else {
            Neighborhood n = Filters.getNeighborhood(radiusXY.getDoubleValue(), image);
            Image mean = Filters.mean(image, null, n, false);
            switch (mode.getSelectedEnum()) {
                case STD:
                    Image var = Filters.sigma(image, null, n, false);
                    ImageOperations.applyFunction(var, d -> d*d, true);
                    Image mean2 = ImageOperations.applyFunction(mean, d -> d * d, false);
                    Image sum2 = ImageOperations.addImage(var, mean2, null, 1);
                    sum2 = ImageOperations.meanZProjection(sum2);
                    mean = ImageOperations.meanZProjection(mean);
                    sum2 = ImageOperations.applyFunction2(sum2, mean, (s2, m) -> Math.sqrt(s2 - m * m), true);
                    return sum2;
                case MEAN:
                    return ImageOperations.meanZProjection(mean);
                case AUTOCORRELATION:
                    return autoCorrelationProjection(mean, null, dT.getIntValue(), norm.getSelected());
            }
        }
        return null;
    }

    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{radiusXY, modeCond};
    }

    public static <T extends Image<T>> T stdZProjection(Image input, T output) {
        BlankMask properties =  new BlankMask( input.sizeX(), input.sizeY(), 1, input.xMin(), input.yMin(), input.zMin(), input.getScaleXY(), input.getScaleZ());
        if (output ==null) output = (T)new ImageFloat("Std Z projection", properties);
        else if (!output.sameDimensions(properties)) output = Image.createEmptyImage("Std Z projection", output, properties);
        double size = input.sizeZ();
        for (int xy = 0; xy<input.sizeXY(); ++xy) {
            double sum = 0;
            double sum2 = 0;
            for (int z = 0; z<input.sizeZ(); ++z) {
                double v= input.getPixel(xy, z);
                sum+=v;
                sum2+=v*v;
            }
            output.setPixel(xy, 0, Math.sqrt(sum2/size - Math.pow(sum/size, 2)));
        }
        return output;
    }

    public static <T extends Image<T>> T autoCorrelationProjection(Image input, T output, int dT, boolean norm) {
        BlankMask properties =  new BlankMask( input.sizeX(), input.sizeY(), 1, input.xMin(), input.yMin(), input.zMin(), input.getScaleXY(), input.getScaleZ());
        if (output ==null) output = (T)new ImageFloat("Autocorrelation Z projection", properties);
        else if (!output.sameDimensions(properties)) output = Image.createEmptyImage("Std Z projection", output, properties);
        double size = input.sizeZ();
        if (norm) {
            for (int xy = 0; xy < input.sizeXY(); ++xy) {
                double mean = 0;
                for (int z = 0; z<input.sizeZ(); ++z) mean+=input.getPixel(xy, z);
                mean /= size;
                double ac = 0;
                double std = 0;
                for (int z = 0; z < size - dT; ++z) {
                    double v = input.getPixel(xy, z) - mean;
                    double vt = input.getPixel(xy, z + dT) - mean;
                    ac += v * vt;
                    std += v * v;
                }
                output.setPixel(xy, 0, ac / std);
            }
        } else {
            for (int xy = 0; xy<input.sizeXY(); ++xy) {
                double mean = 0;
                for (int z = 0; z<input.sizeZ(); ++z) mean+=input.getPixel(xy, z);
                mean /= size;
                double ac = 0;
                for (int z = 0; z<size - dT; ++z) ac += (input.getPixel(xy, z) - mean) * (input.getPixel(xy, z+dT) - mean) / size;
                output.setPixel(xy, 0, ac);
            }
        }
        return output;
    }

}
