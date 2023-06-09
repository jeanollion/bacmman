package bacmman.processing;

import bacmman.data_structure.SegmentedObject;
import bacmman.data_structure.Spot;
import bacmman.image.BoundingBox;
import bacmman.image.Image;
import bacmman.image.ImageFloat;
import bacmman.image.SimpleImageProperties;
import bacmman.image.wrappers.ImgLib2ImageWrapper;
import bacmman.plugins.Plugin;
import bacmman.plugins.plugins.pre_filters.IJSubtractBackground;
import bacmman.plugins.plugins.transformations.Flip;
import bacmman.processing.neighborhood.Neighborhood;
import bacmman.utils.ArrayUtil;
import bacmman.utils.HashMapGetCreate;
import bacmman.utils.Utils;
import bacmman.utils.geom.Point;
import net.imglib2.RealLocalizable;
import net.imglib2.RealRandomAccessible;
import net.imglib2.img.Img;
import net.imglib2.interpolation.InterpolatorFactory;
import net.imglib2.type.numeric.RealType;
import net.imglib2.view.Views;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.DoublePredicate;
import java.util.function.DoubleUnaryOperator;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public class PSFAlign {
    public final static Logger logger = LoggerFactory.getLogger(PSFAlign.class);
    public static List<SegmentedObject> filterBySize(List<SegmentedObject> spots, double[] minMaxQuantiles) {
        double[] sizes = spots.stream().mapToDouble(s -> s.getRegion().size()).toArray();
        double[] limits = ArrayUtil.quantiles(sizes, minMaxQuantiles);
        DoublePredicate valid = s -> s>limits[0] && s<limits[1];
        List<SegmentedObject> res =  spots.stream().filter(s -> valid.test(s.getRegion().size())).collect(Collectors.toList());
        logger.debug("filtered spots (size): {} / {}", res.size(), spots.size());
        return res;
    }
    public static Image getPSF(List<SegmentedObject> spots, InterpolatorFactory interpolation, boolean[] avgFlip, int... dimension) {
        if (spots.isEmpty()) throw new IllegalArgumentException("No spots provided");
        double scaleXY = spots.get(0).getScaleXY();
        double scaleZ = spots.get(0).getScaleZ();
        Image output;
        if (dimension.length==0) throw new IllegalArgumentException("No size provided for output image");
        else if (dimension.length==1) output = new ImageFloat("PSF", new SimpleImageProperties(dimension[0]*2+1, dimension[0]*2+1, 1, scaleXY, scaleZ));
        else if (dimension.length==2) output = new ImageFloat("PSF", new SimpleImageProperties(dimension[0]*2+1, dimension[1]*2+1, 1, scaleXY, scaleZ));
        else if (dimension.length==3) output = new ImageFloat("PSF", new SimpleImageProperties(dimension[0]*2+1, dimension[1]*2+1, dimension[2]==0 ? 1 : dimension[2]*2+1, scaleXY, scaleZ));
        else throw new IllegalArgumentException("Invalid output dimension");
        int ocIdx = spots.get(0).getStructureIdx();

        int[] dims = ArrayUtil.generateIntegerArray(output.sizeZ()==1?2:3);
        if (!Utils.objectsAllHaveSameProperty(spots, s -> s.getStructureIdx() == ocIdx)) throw new IllegalArgumentException("All spots must have same object class");
        Map<SegmentedObject, Point> centers = new HashMapGetCreate.HashMapGetCreateRedirected<>(s -> s.getRegion().getCenterOrGeomCenter());
        Predicate<SegmentedObject> filter = o -> { // remove spots too close from edges
            BoundingBox bds = o.getParent().getBounds();
            return Arrays.stream(dims).allMatch(d -> BoundingBox.innerDistance1D(centers.get(o).get(d), bds, d) >= (output.size(d)-1)/2 );
        };
        Map<SegmentedObject, List<SegmentedObject>> parentMapSpots = spots.stream().collect(Collectors.groupingBy(SegmentedObject::getParent, Utils.collectToList(o->o, filter)));
        int count = parentMapSpots.values().stream().mapToInt(List::size).sum();
        logger.debug("filtered spots (edge dist): {} / {}", count, spots.size());
        // remove spots too close to each other
        parentMapSpots.forEach( (p, sList) -> {
            Map<SegmentedObject, Point> closest = sList.stream().collect(Collectors.toMap(s->s, s->sList.stream()
                    .filter(ss->!ss.equals(s))
                    .map(centers::get)
                    .min(Comparator.comparingDouble(c -> c.distSq(centers.get(s))))
                    .get()));
            sList.removeIf( s -> Arrays.stream(dims).anyMatch( d -> closest.get(s).dist1D(centers.get(s), d) < (output.size(d)-1)/2  ));
        } );
        count = parentMapSpots.values().stream().mapToInt(List::size).sum();
        logger.debug("filtered spots (min dist): {} / {}", count, spots.size());
        if (count==0) {
            logger.error("No spot after filtering");
            return null;
        }
        double zRatio = output.sizeZ()==1 ? 1 : scaleXY / scaleZ;
        DoubleUnaryOperator sizeToRad = output.sizeZ()==1 ? s -> Math.sqrt(s/Math.PI) : s -> Math.pow( (3 * s / (4 * Math.PI * zRatio)), 1/3. );
        //double rad = parentMapSpots.values().stream().flatMap(Collection::stream).mapToDouble(s -> (s.getRegion() instanceof Spot) ? ((Spot)s.getRegion()).getRadius() : sizeToRad.applyAsDouble(s.getRegion().size())).average().getAsDouble();
        //Neighborhood n = Filters.getNeighborhood(rad * 2, output);
        Image tempPSF = output.duplicate();
        parentMapSpots.entrySet().parallelStream().forEach( e -> {
            if (!e.getValue().isEmpty()) {
                // filter image to have zero-min // TODO parametrize this step ?
                Image input = e.getKey().getRawImage(ocIdx);
                input = IJSubtractBackground.filter(input, output.sizeX(), false, false, true, false);
                //Image input  = Filters.tophat(p.getRawImage(ocIdx), null, n, false);
                RealRandomAccessible image = getRRA(input, interpolation);
                e.getValue().forEach( s -> {
                    set(image, centers.get(s), tempPSF);
                    double mean = ImageOperations.getMeanAndSigma(tempPSF, null, null)[0];
                    ImageOperations.addImage(output, tempPSF, output, 1./mean);
                } );
            }
        });
        ImageOperations.multiplyValue(output, 1./(count * output.sizeXYZ()), output ); // sum to 1
        if (avgFlip.length>0) {
            Image o = output;
            if (avgFlip[0]) o = ImageOperations.average(o, o, ImageTransformation.flip(o.duplicate(), ImageTransformation.Axis.X));
            if (avgFlip.length>1 && avgFlip[1]) o = ImageOperations.average(o, o, ImageTransformation.flip(o.duplicate(), ImageTransformation.Axis.Y));
            if (output.sizeZ()>1 && avgFlip.length>2 && avgFlip[2]) o = ImageOperations.average(o, o, ImageTransformation.flip(o.duplicate(), ImageTransformation.Axis.Z));
            return o;
        }
        return output;
    }
    public static <T extends RealType<T>> RealRandomAccessible<T> getRRA(Image image, InterpolatorFactory interpolation) {
        Img in = ImgLib2ImageWrapper.getImage(image);
        return Views.interpolate(Views.extendMirrorSingle(in), interpolation);
    }
    public static <T extends RealType<T>> void set(RealRandomAccessible<T> parentImage, Point center, Image targetImage) {
        boolean is2D = parentImage.numDimensions()==2;
        Point off = center.duplicate().translateRev(targetImage.getCenter());
        Point offset = is2D ? Point.asPoint2D((RealLocalizable)off) : off;
        Point cursor = is2D ? new Point(0, 0) : new Point(0, 0, 0);
        BoundingBox.loop(targetImage, (x, y, z) -> {
            if (is2D) cursor.setData(x, y);
            else cursor.setData(x, y, z);
            cursor.translate(offset);
            targetImage.setPixel(x, y, z, parentImage.getAt(cursor).getRealDouble());
        });
    }
}
