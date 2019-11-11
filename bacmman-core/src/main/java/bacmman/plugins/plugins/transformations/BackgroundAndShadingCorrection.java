package bacmman.plugins.plugins.transformations;

import bacmman.configuration.parameters.BooleanParameter;
import bacmman.configuration.parameters.ConditionalParameter;
import bacmman.configuration.parameters.FileChooser;
import bacmman.configuration.parameters.Parameter;
import bacmman.data_structure.input_image.InputImages;
import bacmman.image.BoundingBox;
import bacmman.image.Image;
import bacmman.image.ImageFloat;
import bacmman.image.TypeConverter;
import bacmman.image.io.ImageIOCoordinates;
import bacmman.image.io.ImageReader;
import bacmman.plugins.ConfigurableTransformation;
import bacmman.plugins.Hint;
import bacmman.plugins.MultichannelTransformation;

import java.io.File;


public class BackgroundAndShadingCorrection implements ConfigurableTransformation, Hint {
    FileChooser flatField = new FileChooser("Flat-field image", FileChooser.FileChooserOption.FILE_ONLY, false).setEmphasized(true);
    FileChooser darkField = new FileChooser("Dark-field image", FileChooser.FileChooserOption.FILE_ONLY, false).setEmphasized(true);
    BooleanParameter correctDarkField = new BooleanParameter("Correct dark-field", false);
    ConditionalParameter correctDarkFieldCond = new ConditionalParameter(correctDarkField).setActionParameters("true", darkField).setEmphasized(true);
    Parameter[] parameters = new Parameter[]{flatField, correctDarkFieldCond};

    Image flatFieldImage, darkFieldImage;

    @Override
    public String getHintText() {
        return "Corrects shading and background illumination using pre-computed flat-field (image acquired with no sample, that represents the change in effective illumination across an image) and optionally a dark-field image (Image acquired with no light, that represents the additive term, which is dominated by thermal noise, camera offset)";
    }

    @Override
    public Image applyTransformation(int channelIdx, int timePoint, Image image) {
        Image res = (image instanceof ImageFloat) ? image : TypeConverter.toFloat(image, null);
        if (correctDarkField.getSelected())BoundingBox.loop(image, (x, y, z) -> res.setPixel(x, y, z, (image.getPixel(x, y, z)-darkFieldImage.getPixel(x, y, z))/flatFieldImage.getPixel(x, y, z)));
        else  BoundingBox.loop(image, (x, y, z) -> res.setPixel(x, y, z, image.getPixel(x, y, z)/flatFieldImage.getPixel(x, y, z)));
        return res;
    }

    @Override
    public Parameter[] getParameters() {
        return parameters;
    }

    @Override
    public void computeConfigurationData(int channelIdx, InputImages inputImages) {
        String path = flatField.getFirstSelectedFilePath();
        Image refImage = inputImages.getImage(channelIdx, 0);
        if (path==null || !new File(path).isFile()) throw new IllegalArgumentException("No flat-field image found");
        flatFieldImage = ImageReader.openImage(path, new ImageIOCoordinates());
        if (!flatFieldImage.sameDimensions(refImage)) {
            flatFieldImage=null;
            throw new IllegalArgumentException("Flat-field image's dimensions ("+flatFieldImage.getBoundingBox()+") differ from input image's dimensions: "+refImage.getBoundingBox());
        }
        if (correctDarkField.getSelected()) {
            path = darkField.getFirstSelectedFilePath();
            if (path==null || !new File(path).isFile()) throw new IllegalArgumentException("No dark-field image found");
            darkFieldImage = ImageReader.openImage(path, new ImageIOCoordinates());
            if (!darkFieldImage.sameDimensions(refImage)) {
                darkFieldImage = null;
                throw new IllegalArgumentException("Dark-field image's dimensions ("+darkFieldImage.getBoundingBox()+") differ from input image's dimensions:"+refImage.getBoundingBox());
            }
        }
    }

    @Override
    public boolean isConfigured(int totalChannelNumber, int totalTimePointNumber) {
        return flatFieldImage!=null && (darkFieldImage!=null || !correctDarkField.getSelected());
    }
}
