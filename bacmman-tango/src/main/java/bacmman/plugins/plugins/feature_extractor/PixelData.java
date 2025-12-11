package bacmman.plugins.plugins.feature_extractor;

import bacmman.configuration.parameters.*;
import bacmman.data_structure.RegionPopulation;
import bacmman.data_structure.SegmentedObject;
import bacmman.data_structure.SegmentedObjectUtils;
import bacmman.data_structure.Selection;
import bacmman.image.*;
import bacmman.plugins.FeatureExtractorOneEntryPerInstance;
import bacmman.plugins.Hint;
import bacmman.processing.Medoid;
import bacmman.processing.Resize;
import bacmman.utils.ArrayUtil;
import bacmman.utils.geom.Point;
import net.imglib2.interpolation.InterpolatorFactory;

import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

public class PixelData implements FeatureExtractorOneEntryPerInstance, Hint {
    SimpleListParameter<ChannelImageParameter> channels = new SimpleListParameter<>("Channels to Extract", new ChannelImageParameter("Channel")).setMinChildCount(1)
            .setChildrenNumber(1)
            .setHint("Choose object classes associated to the channels to be extracted. Note that each object class can only be selected once")
            .addValidationFunction(l -> l.getActivatedChildren().stream().mapToInt(ChannelImageParameter::getSelectedClassIdx).distinct().count() == l.getActivatedChildren().size());

    SimpleListParameter<EVFParameter> evfList = new SimpleListParameter<>("EVF", new EVFParameter("EVF Parameters", false))
            .addValidationFunction(l -> l.getActivatedChildren().stream().map(EVFParameter::getResampleZ).distinct().count()<=1) // resample in Z should be equal
            .setHint("If items are added to this list, Eroded Volume Fraction (EVF) will be computed for each pixel and returned as an additional channel");

    BooleanParameter includeCoordinates = new BooleanParameter("Include Coordinates").setHint("If true, XYZ coordinates will be added after raw pixel data (and EVF)");
    BooleanParameter includeXCoordinate = new BooleanParameter("X", true).setHint("If true, X-coordinates will be added after raw pixel data (and EVF)");
    BooleanParameter includeYCoordinate = new BooleanParameter("Y", true).setHint("If true, Y-coordinates will be added after raw pixel data (and EVF)");
    BooleanParameter includeZCoordinate = new BooleanParameter("Z", true).setHint("If true, Z-coordinates will be added after raw pixel data (and EVF)");
    enum REFERENCE_POINT {ABSOLUTE, PARENT, GEOMETRICAL_CENTER, SEGMENTATION_CENTER, MEDOID}
    public EnumChoiceParameter<REFERENCE_POINT> referencePoint = new EnumChoiceParameter<>("Reference Point", REFERENCE_POINT.values(), REFERENCE_POINT.SEGMENTATION_CENTER).setHint("Reference Point for Coordinates. <ul><li>ABSOLUTE: upper left corner of viewfield</li><li>PARENT: upper left corner of parent object</li><li>GEOMETRICAL_CENTER: geometrical center of object</li><li>SEGMENTATION_CENTER: object center as defined by the segmentation (if segmentation do not define a center geometrical center is used)</li><li>MEDOID: medoid center of object (medoid is always located inside the object)</li></ul>");
    ConditionalParameter<Boolean> includeCoordsCond = new ConditionalParameter<>(includeCoordinates).setActionParameters(true, includeXCoordinate, includeYCoordinate, includeZCoordinate, referencePoint);

    @Override
    public Image extractFeature(SegmentedObject parent, int objectClassIdx, Map<Integer, Map<SegmentedObject, RegionPopulation>> resampledPopulations, int downsamplingFactor, int[] resampleDimensions) {
        if (objectClassIdx != parent.getStructureIdx()) throw new IllegalArgumentException("invalid object class: should correspond to parent selection that has object class==: "+parent.getStructureIdx());
        double zAspectRatio = parent.getScaleZ()/parent.getScaleXY();
        boolean resample = !evfList.isEmpty() && evfList.getChildAt(0).getResampleZ() || downsamplingFactor>1;
        UnaryOperator<Image> resampleZFun = im -> {
            if (!resample || zAspectRatio==1 || im.sizeZ()==1) return im;
            return Resize.resample(im, false, im.sizeX()/downsamplingFactor, im.sizeY()/downsamplingFactor, (int)Math.round(im.sizeZ() * zAspectRatio));
        };
        ImageMask parentMask = parent.getMask();
        if (resample && (zAspectRatio!=1 || downsamplingFactor>1) ) parentMask = Resize.resample(TypeConverter.maskToImageInteger(parent.getMask(), null), true, parentMask.sizeX()/downsamplingFactor, parentMask.sizeY()/downsamplingFactor, (int)Math.round(parentMask.sizeZ() * zAspectRatio));
        List<Image> images = channels.getActivatedChildren().stream().mapToInt(ObjectClassOrChannelParameter::getSelectedClassIdx)
                .mapToObj(parent::getRawImageByChannel)
                .map(resampleZFun)
                .collect(Collectors.toList());
        for (EVFParameter p : evfList.getActivatedChildren()) {
            images.add(p.computeEVF(parent));
        }
        int cx = includeCoordinates.getSelected() && includeXCoordinate.getSelected() ? images.size() : -1;
        int cy = includeCoordinates.getSelected() && includeYCoordinate.getSelected() ? (cx>=0?cx+1 : images.size()) : -1;
        int cz = includeCoordinates.getSelected() && includeZCoordinate.getSelected() ? (cy>=0? cy+1: ( cx>=0 ? cx+1 : images.size() ) ) : -1 ;
        int coords = includeCoordinates.getSelected() ? (cx>=0?1:0) + (cy>=0?1:0) + (cz>=0?1:0) : 0;
        Image type = TypeConverter.castToIJ1ImageType(Image.copyType(images.stream().max(PrimitiveType.typeComparator()).get()));
        int count = parentMask.count();
        Offset off = parent.getBounds();
        Point offP = new Point(off.xMin(), off.yMin(), off.zMin());
        Point ref;
        if (count > 0) {
            switch (this.referencePoint.getSelectedEnum()) {
                case ABSOLUTE:
                default: {
                    ref = offP;
                    break;
                }
                case PARENT: {
                    ref = offP.translateRev(parent.getParent().getBounds());
                    break;
                }
                case GEOMETRICAL_CENTER: {
                    ref = offP.translateRev(parent.getRegion().getGeomCenter(false));
                    break;
                } case SEGMENTATION_CENTER: {
                    ref = offP.translateRev(parent.getRegion().getCenterOrGeomCenter());
                    break;
                } case MEDOID: {
                    ref = offP.translateRev(Medoid.computeMedoid(parent.getRegion()));
                    break;
                }
            }
        } else ref = null;
        double[] referencePoint = ref == null ? new double[]{0, 0, 0} : new double[] {ref.get(0), ref.get(1), ref.getWithDimCheck(2)};
        if (count > 0 && referencePoint[ArrayUtil.min(referencePoint)] < 0 ) type = new ImageFloat("", 0, 0, 0); // will generate negative coordinates -> needs to be floating point
        boolean addZDim = parent.is2D() && images.stream().anyMatch(im -> im.sizeZ()>1);
        int sizeZ = images.stream().mapToInt(Image::sizeZ).max().getAsInt();
        if (addZDim) count *= sizeZ;
        Image res = Image.createEmptyImage(Selection.indicesToString(SegmentedObjectUtils.getIndexTree(parent)), type, new SimpleImageProperties(count, images.size() + coords, 1, 1, 1));
        int[] count2 = new int[1];
        ImageMask.loop(parentMask, (x, y, z) -> count2[0]++);
        int[] idx = new int[1];
        if (!addZDim) {
            ImageMask.loop(parentMask, (x, y, z) -> {
                for (int c = 0; c < images.size(); ++c) res.setPixel(idx[0], c, 0, images.get(c).getPixel(x, y, z));
                if (cx >= 0) res.setPixel(idx[0], cx, 0, x + referencePoint[0]);
                if (cy >= 0) res.setPixel(idx[0], cy, 0, y + referencePoint[1]);
                if (cz >= 0) res.setPixel(idx[0], cz, 0, z + referencePoint[2]);
                ++idx[0];
            });
        } else { // 2D parent but 3D images
            for (int z = 0; z<sizeZ; ++z) {
                int fz = z;
                ImageMask.loop(parentMask, (x, y, zz) -> {
                    for (int c = 0; c < images.size(); ++c) {
                        res.setPixel(idx[0], c, 0, images.get(c).getPixel(x, y, images.get(c).sizeZ()==1?0:fz));
                    }
                    if (cx >= 0) res.setPixel(idx[0], cx, 0, x + referencePoint[0]);
                    if (cy >= 0) res.setPixel(idx[0], cy, 0, y + referencePoint[1]);
                    if (cz >= 0) res.setPixel(idx[0], cz, 0, fz + referencePoint[2]);
                    ++idx[0];
                });
            }
        }
        return res;
    }

    public ExtractZAxisParameter.ExtractZAxis getExtractZDim() {
        return ExtractZAxisParameter.ExtractZAxis.IMAGE3D;
    }

    @Override
    public InterpolatorFactory interpolation() {
        return null;
    }

    @Override
    public String defaultName() {
        return "PixelData";
    }

    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{channels, evfList, includeCoordsCond};
    }

    @Override
    public String getHintText() {
        return "Extracts values of one or several channels within segmented objects of a selection. The segmented object class must correspond to the object class of the selected selection or a children." +
                "<br/>For each object A of the selection, one entry will be created in the output file. The path of the entry is selection-name/dataset-name/position-name/feature-name/trackhead-indices_fXXXXX (frame index padded up to 5 zeros)" +
                "<br/>The 1st axis corresponds to the selected channels in the parameter <em>Channels to Extract</em>(in the defined order), then the EVF's (in the defined order) then the coordinates in the order XYZ (if selected). <br/>The 2nd axis correspond to the different locations of A, thus the size along X-axis corresponds to the volume of the A";
    }

}
