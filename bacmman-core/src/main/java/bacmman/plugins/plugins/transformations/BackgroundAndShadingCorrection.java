package bacmman.plugins.plugins.transformations;

import bacmman.configuration.parameters.*;
import bacmman.core.Core;
import bacmman.data_structure.input_image.InputImages;
import bacmman.image.BoundingBox;
import bacmman.image.Image;
import bacmman.image.ImageFloat;
import bacmman.image.TypeConverter;
import bacmman.image.io.ImageIOCoordinates;
import bacmman.image.io.ImageReaderFile;
import bacmman.plugins.ConfigurableTransformation;
import bacmman.plugins.Hint;
import bacmman.plugins.PluginWithLegacyInitialization;
import bacmman.plugins.TestableOperation;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import java.io.File;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;


public class BackgroundAndShadingCorrection implements ConfigurableTransformation, Hint, TestableOperation, PluginWithLegacyInitialization {

    FileChooser flatField = new FileChooser("Flat-field image", FileChooser.FileChooserOption.FILE_ONLY, false).setEmphasized(true);
    FileChooser darkField = new FileChooser("Dark-field image", FileChooser.FileChooserOption.FILE_ONLY, false).setEmphasized(true);
    BooleanParameter correctFlatField = new BooleanParameter("Correct flat-field", false);
    ConditionalParameter<Boolean> correctFlatFieldCond = new ConditionalParameter<>(correctFlatField).setActionParameters(true, flatField).setEmphasized(true);

    BooleanParameter correctDarkField = new BooleanParameter("Correct dark-field", false);
    ConditionalParameter<Boolean> correctDarkFieldCond = new ConditionalParameter<>(correctDarkField).setActionParameters(true, darkField).setEmphasized(true);

    BooleanParameter scaleImage = new BooleanParameter("Scale Image", false).setHint("resulting image is ( I - center ) / scale");
    BoundedNumberParameter center = new BoundedNumberParameter("Center", 5, 0, null, null).setEmphasized(true);
    BoundedNumberParameter scale = new BoundedNumberParameter("Scale", 5, 0, null, null).setEmphasized(true);
    ConditionalParameter<Boolean> scaleImageCond = new ConditionalParameter<>(scaleImage).setActionParameters(true, center, scale).setEmphasized(true);

    Parameter[] parameters = new Parameter[]{correctFlatFieldCond, correctDarkFieldCond, scaleImageCond};

    Image flatFieldImage, darkFieldImage;

    @Override
    public String getHintText() {
        return "Corrects shading and background illumination using optionally a pre-computed flat-field (image acquired with no sample, that represents the change in effective illumination across an image) and optionally a dark-field image (Image acquired with no light, that represents the additive term, which is dominated by thermal noise, camera offset)";
    }
    @Override
    public boolean highMemory() {return false;}
    @Override
    public Image applyTransformation(int channelIdx, int timePoint, Image image) {
        Image res = (image instanceof ImageFloat) ? image : TypeConverter.toFloat(image, null);
        double scale = scaleImage.getSelected() ? this.scale.getDoubleValue() : 1;
        double center = scaleImage.getSelected()? this.center.getDoubleValue() : 0;
        if (correctDarkField.getSelected() && correctFlatField.getSelected()) BoundingBox.loop(image, (x, y, z) -> res.setPixel(x, y, z, (image.getPixel(x, y, z)-darkFieldImage.getPixel(x, y, z)-center)/(scale * flatFieldImage.getPixel(x, y, z))));
        else if (correctFlatField.getSelected()) BoundingBox.loop(image, (x, y, z) -> res.setPixel(x, y, z, (image.getPixel(x, y, z)-center)/(scale  * flatFieldImage.getPixel(x, y, z))));
        else if (correctDarkField.getSelected()) BoundingBox.loop(image, (x, y, z) -> res.setPixel(x, y, z, (image.getPixel(x, y, z)-darkFieldImage.getPixel(x, y, z)-center)/scale));
        else if (scaleImage.getSelected()) BoundingBox.loop(image, (x, y, z) -> res.setPixel(x, y, z, (image.getPixel(x, y, z)-center)/scale));
        return res;
    }

    @Override
    public Parameter[] getParameters() {
        return parameters;
    }

    @Override
    public void computeConfigurationData(int channelIdx, InputImages inputImages) {
        if (correctFlatField.getSelected()) {
            String path = flatField.getFirstSelectedFilePath();
            Image refImage = inputImages.getImage(channelIdx, 0);
            if (path == null || !new File(path).isFile())
                throw new IllegalArgumentException("No flat-field image found");
            flatFieldImage = ImageReaderFile.openImage(path, new ImageIOCoordinates());
            if (!flatFieldImage.sameDimensions(refImage)) {
                flatFieldImage = null;
                throw new IllegalArgumentException("Flat-field image's dimensions (" + flatFieldImage.getBoundingBox() + ") differ from input image's dimensions: " + refImage.getBoundingBox());
            } else if (testMode.testSimple()) Core.showImage(flatFieldImage.setName("Flat Field"));
        }
        if (correctDarkField.getSelected()) {
            String path = darkField.getFirstSelectedFilePath();
            Image refImage = inputImages.getImage(channelIdx, 0);
            if (path==null || !new File(path).isFile()) throw new IllegalArgumentException("No dark-field image found");
            darkFieldImage = ImageReaderFile.openImage(path, new ImageIOCoordinates());
            if (!darkFieldImage.sameDimensions(refImage)) {
                darkFieldImage = null;
                throw new IllegalArgumentException("Dark-field image's dimensions ("+darkFieldImage.getBoundingBox()+") differ from input image's dimensions:"+refImage.getBoundingBox());
            } else if (testMode.testSimple()) Core.showImage(darkFieldImage.setName("Dark Field"));
        }
    }

    @Override
    public boolean isConfigured(int totalChannelNumber, int totalTimePointNumber) {
        return (flatFieldImage!=null || !correctFlatField.getSelected()) && (darkFieldImage!=null || !correctDarkField.getSelected());
    }

    TEST_MODE testMode=TEST_MODE.NO_TEST;
    @Override
    public void setTestMode(TEST_MODE testMode) {this.testMode=testMode;}

    @Override
    public void legacyInit(JSONArray parameters) {
        Stream<JSONObject> s = parameters.stream();
        Map<String, Object> params = s.map(o->(Map.Entry)o.entrySet().iterator().next()).collect(Collectors.toMap(e -> (String) e.getKey(), Map.Entry::getValue));
        if (params.containsKey("Flat-field image")) flatField.initFromJSONEntry(params.get("Flat-field image"));
    }
}
