package bacmman.plugins.plugins.transformations;

import bacmman.configuration.parameters.EnumChoiceParameter;
import bacmman.configuration.parameters.FloatParameter;
import bacmman.configuration.parameters.Parameter;
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

    enum MODE {MEAN, STD}
    EnumChoiceParameter<MODE> mode = new EnumChoiceParameter<>("Mode", MODE.values(), MODE.STD);
    @Override
    public Image applyTransformation(int channelIdx, int timePoint, Image image) {
        if (radiusXY.getDoubleValue() == 0) {
            switch (mode.getSelectedEnum()) {
                case STD:
                    return stdZProjection(image, null);
                case MEAN:
                    return ImageOperations.meanZProjection(image);
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
            }
        }
        return null;
    }

    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{radiusXY, mode};
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
}
