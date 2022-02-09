package bacmman.plugins.plugins.measurements;

import bacmman.configuration.parameters.*;
import bacmman.data_structure.SegmentedObject;
import bacmman.image.Image;
import bacmman.image.ImageInteger;
import bacmman.image.ImageMask;
import bacmman.measurement.MeasurementKey;
import bacmman.measurement.MeasurementKeyObject;
import bacmman.plugins.Measurement;
import bacmman.plugins.ObjectFeature;
import bacmman.processing.ImageOperations;
import bacmman.processing.Resize;
import bacmman.processing.neighborhood.Neighborhood;
import bacmman.processing.neighborhood.NeighborhoodFactory;
import net.imglib2.interpolation.InterpolatorFactory;
import net.imglib2.interpolation.randomaccess.NearestNeighborInterpolatorFactory;

import java.util.ArrayList;
import java.util.List;

import static bacmman.plugins.plugins.measurements.RadialAutoCorrelation.ANISOTROPY_CORRECTION.CUSTOM_SCALE;
import static bacmman.plugins.plugins.measurements.RadialAutoCorrelation.ANISOTROPY_CORRECTION.RESAMPLE;

public class RadialAutoCorrelation implements Measurement {
    ArrayNumberParameter radii = new ArrayNumberParameter("Radii", 0, new BoundedNumberParameter("Radius", 3, 1, 1, null));
    BoundedNumberParameter zFactor = new BoundedNumberParameter("Z-Factor", 3, 1, 0, 1).setHint("Z-radius = radius / Z-Factor");
    ObjectClassParameter segOC = new ObjectClassParameter("Segmented Object Class");
    ObjectClassParameter intensityOC = new ObjectClassParameter("Intensity");
    PreFilterSequence filters = new PreFilterSequence("Pre-filters");
    TextParameter key = new TextParameter("Key", "RAC", false);
    enum ANISOTROPY_CORRECTION {NONE, IMAGE_SCALE, CUSTOM_SCALE, RESAMPLE}
    EnumChoiceParameter<ANISOTROPY_CORRECTION> anisotropyCorrection = new EnumChoiceParameter<>("Z-Anisotropy Correction", ANISOTROPY_CORRECTION.values(), RESAMPLE );
    InterpolationParameter interpolation = new InterpolationParameter("Interpolation");
    ConditionalParameter<ANISOTROPY_CORRECTION> anisotropyCorrectionCond=  new ConditionalParameter<>(anisotropyCorrection)
            .setActionParameters(CUSTOM_SCALE, zFactor)
            .setActionParameters(RESAMPLE, interpolation);

    @Override
    public int getCallObjectClassIdx() {
        return segOC.getSelectedClassIdx();
    }

    @Override
    public boolean callOnlyOnTrackHeads() {
        return false;
    }

    @Override
    public List<MeasurementKey> getMeasurementKeys() {
        ArrayList<MeasurementKey> res=  new ArrayList<>(radii.getChildCount());
        if (radii.getChildCount()==1) res.add(new MeasurementKeyObject(key.getValue(), segOC.getSelectedClassIdx()));
        else for (int i = 0; i<radii.getChildCount(); ++i) res.add(new MeasurementKeyObject(key.getValue()+"_"+ i, segOC.getSelectedClassIdx()));
        return res;
    }

    @Override
    public void performMeasurement(SegmentedObject object) {
        ImageMask mask = object.getMask();
        Image intensity = object.getRawImage(intensityOC.getSelectedClassIdx());
        intensity = filters.filter(intensity, mask);

        double zFactor;
        if (mask.sizeZ()>1) {
            switch (anisotropyCorrection.getSelectedEnum()) {
                case RESAMPLE:
                default:
                    InterpolatorFactory interpF = interpolation.getInterpolation();
                    int newZ = (int) (mask.sizeZ() * mask.getScaleZ() / mask.getScaleXY() + 0.5);
                    mask = Resize.resample((ImageInteger)object.getRegion().getMaskAsImageInteger() , new NearestNeighborInterpolatorFactory() , mask.sizeX(), mask.sizeY(), newZ);
                    intensity = Resize.resample(intensity, interpF, intensity.sizeX(), intensity.sizeY(), newZ);
                    zFactor = 1;
                    break;
                case IMAGE_SCALE:
                    zFactor = intensity.getScaleZ() / intensity.getScaleXY();
                    break;
                case CUSTOM_SCALE:
                    zFactor = this.zFactor.getDoubleValue();
                    break;
            }
        } else zFactor = 0;
        double[] meanStd = ImageOperations.getMeanAndSigma(intensity, mask, null);
        double meanValue = meanStd[0];
        double sigma2 = meanStd[1] * meanStd[1];
        double[] radii = this.radii.getArrayDouble();
        for (int i = 0; i<radii.length; ++i) {
            double value = getCorrelation(intensity, mask, radii[i], radii[i] / zFactor, meanValue, sigma2);
            String key = radii.length==1 ? this.key.getValue() : this.key.getValue()+"_"+ i;
            object.getMeasurements().setValue(key, value);
        }

    }

    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{segOC, intensityOC, radii, anisotropyCorrectionCond, filters, key};
    }

    public static double getCorrelation(Image intensity, ImageMask mask, double radius, double radiusZ, double mean, double sigma2) {
        int[][] neighbor = NeighborhoodFactory.getHalfNeighbourhood(radius, radiusZ, 1);
        double sum=0;
        double count=0;
        int zz, xx, yy, xy2;
        for (int z = 0; z<mask.sizeZ(); z++) {
            for (int y = 0; y<mask.sizeY(); y++) {
                for (int x= 0; x<mask.sizeX(); x++) {
                    int xy = x+y*mask.sizeX();
                    if (mask.insideMask(xy, z)) {
                        double value = intensity.getPixel(xy, z)-mean;
                        for (int i = 0; i<neighbor[0].length; i++) {
                            zz = z + neighbor[2][i];
                            if (zz<mask.sizeZ() && zz>=0) {
                                xx= neighbor[0][i]+x;
                                if (xx<mask.sizeX() && xx>=0) {
                                    yy= neighbor[1][i]+y;
                                    if (yy<mask.sizeY() && yy>=0) {
                                        xy2 = xx+yy*mask.sizeX();
                                        if (mask.insideMask(xy2, zz)) {
                                            sum += value * (intensity.getPixel(xy2, zz)-mean);
                                            count++;
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (count>0) sum/=count;
        if (sigma2>0) return sum/sigma2;
        return 0;
    }
}
