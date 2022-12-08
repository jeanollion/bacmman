package bacmman.plugins.plugins.thresholders;

import bacmman.configuration.parameters.BoundedNumberParameter;
import bacmman.configuration.parameters.Parameter;
import bacmman.data_structure.SegmentedObject;
import bacmman.image.Image;
import bacmman.plugins.Hint;
import bacmman.plugins.Thresholder;
import bacmman.processing.ImageOperations;

public class FactorOfMean implements Thresholder, Hint {
    BoundedNumberParameter thldFactor = new BoundedNumberParameter("Intensity Threshold Factor", 5, 10, 0, null).setEmphasized(true).setHint("Final threshold on intensity is mean(I) * this value");

    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{thldFactor};
    }

    public FactorOfMean setFactor(double factor) {
        this.thldFactor.setValue(factor);
        return this;
    }

    @Override
    public double runThresholder(Image input, SegmentedObject structureObject) {
        double mean = ImageOperations.getMeanAndSigma(input, structureObject.getMask(), null)[0];
        return this.thldFactor.getDoubleValue() * mean;
    }

    @Override
    public String getHintText() {
        return "Mean of the image multiplied by a constant factor";
    }
}
