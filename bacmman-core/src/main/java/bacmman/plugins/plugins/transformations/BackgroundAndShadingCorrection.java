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
import bacmman.plugins.plugins.pre_filters.ImageFeature;
import bacmman.processing.ImageOperations;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;


public class BackgroundAndShadingCorrection implements ConfigurableTransformation, Hint, TestableOperation, PluginWithLegacyInitialization {

    FileChooser flatField = new FileChooser("Flat-field image", FileChooser.FileChooserOption.FILES_ONLY, false).setEmphasized(true).setHint("Choose a Flat-Field image (image acquired with no sample, that represents the change in effective illumination across an image) : output images will be divided by the values of this images: I = I / flat-field. <br>If several images are selected, they will be averaged");
    FileChooser darkField = new FileChooser("Dark-field image", FileChooser.FileChooserOption.FILES_ONLY, false).setEmphasized(true).setHint("Choose a Dark-field image (image acquired with no light, that represents the additive term, which is dominated by thermal noise, camera offset) : dark-field will be subtracted to output images: I = I - dark-field.<br>If several images are selected, they will be averaged");
    PreFilterSequence flatFieldPF = new PreFilterSequence("Pre-Filters").add(new ImageFeature().setFeature(ImageFeature.Feature.GAUSS).setScale(50, 0).set2D(true)).setEmphasized(true).setHint("Pre-Filters applied to flat-field image. If several images are selected, filter will be applied after averaging.");
    PreFilterSequence darkFieldPF = new PreFilterSequence("Pre-Filters").add(new ImageFeature().setFeature(ImageFeature.Feature.GAUSS).setScale(50, 0).set2D(true)).setEmphasized(true).setHint("Pre-Filters applied to dark-field image. If several images are selected, filter will be applied after averaging.");

    BooleanParameter correctFlatField = new BooleanParameter("Correct flat-field", false);
    ConditionalParameter<Boolean> correctFlatFieldCond = new ConditionalParameter<>(correctFlatField).setActionParameters(true, flatField, flatFieldPF).setEmphasized(true);

    BooleanParameter correctDarkField = new BooleanParameter("Correct dark-field", false);
    ConditionalParameter<Boolean> correctDarkFieldCond = new ConditionalParameter<>(correctDarkField).setActionParameters(true, darkField, darkFieldPF).setEmphasized(true);

    BooleanParameter scaleImage = new BooleanParameter("Scale Image", false).setHint("Resulting image is ( I - center ) / scale. If flat-field and/or dark field is selected, this scaling is applied in addition to flat/dark field correction: I -> ( I - center - dark-field ) / (scale * flat-field)");
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

    private Image getImage(String[] path, PreFilterSequence prefilters, Image refImage, String imageName) throws IOException {
        if (path == null || path.length==0) throw new IllegalArgumentException("No "+imageName+" image found");
        for (String p : path) {
            if (!new File(p).isFile()) throw new IllegalArgumentException("Invalid "+imageName+" image:"+p);
        }
        List<Image> images = new ArrayList<>(path.length);
        for (String p : path) {
            Image i = ImageReaderFile.openImage(p, new ImageIOCoordinates());
            if (i==null) throw new IllegalArgumentException("Invalid "+imageName+" image:"+p);
            if (!refImage.sameDimensions(i)) {
                String err = imageName + " image @ "+p+" dimensions (" + i.getBoundingBox() + ") differ from input image's dimensions: " + refImage.getBoundingBox();
                throw new IllegalArgumentException(err);
            }
            images.add(i);
        }
        Image res;
        if (images.size()>1) {
            res = ImageOperations.average(null, images.toArray(new Image[0]));
        } else {
            res = images.get(0);
            res = (res instanceof ImageFloat) ? res : TypeConverter.toFloat(res, null);
        }
        res.setName(imageName);
        if (!prefilters.isEmpty()) res = prefilters.filter(res, null);
        if (testMode.testSimple()) Core.showImage(res);
        return res;
    }
    @Override
    public void computeConfigurationData(int channelIdx, InputImages inputImages) throws IOException {
        if (correctFlatField.getSelected()) {
            String[] path = flatField.getSelectedFilePath();
            Image refImage = inputImages.getImage(channelIdx, 0);
            flatFieldImage = getImage(path, flatFieldPF, refImage, "Flat-Field");
        }
        if (correctDarkField.getSelected()) {
            String[] path = darkField.getSelectedFilePath();
            Image refImage = inputImages.getImage(channelIdx, 0);
            darkFieldImage = getImage(path, darkFieldPF, refImage, "Dark-Field");
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
