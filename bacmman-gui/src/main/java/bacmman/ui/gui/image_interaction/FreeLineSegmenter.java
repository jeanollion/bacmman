package bacmman.ui.gui.image_interaction;

import bacmman.configuration.parameters.Parameter;
import bacmman.data_structure.*;
import bacmman.image.*;
import bacmman.plugins.ManualSegmenter;
import bacmman.processing.FillHoles2D;
import bacmman.processing.Filters;
import bacmman.processing.neighborhood.EllipsoidalNeighborhood;
import bacmman.processing.neighborhood.Neighborhood;
import bacmman.utils.ArrayUtil;
import bacmman.utils.Pair;
import bacmman.utils.geom.Point;
import bacmman.utils.geom.Vector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public class FreeLineSegmenter {
    public final static Logger logger = LoggerFactory.getLogger(FreeLineSegmenter.class);
    public static Collection<SegmentedObject> segment(SegmentedObject parent, Offset parentOffset, Collection<SegmentedObject> touchedObjects, int[] xContour, int[] yContour, int objectClassIdx, Consumer<Collection<SegmentedObject>> saveToDB) {
        if (xContour.length!=yContour.length) throw new IllegalArgumentException("xPoints & yPoints should have same length");


        Offset revOff = new SimpleOffset(parentOffset).reverseOffset();

        RegionPopulation pop = parent.getChildRegionPopulation(objectClassIdx);
        boolean isClosed = Math.pow(xContour[0]-xContour[xContour.length-1], 2) + Math.pow(yContour[0]-yContour[xContour.length-1], 2) <=1;
        int modifyObjectLabel;
        if (!isClosed) { // check that the 2 extremities touch the same object & only one object -> close this object
            Function<Voxel, int[]> getTouchingLabels = v -> {
                Neighborhood n = new EllipsoidalNeighborhood(1.5, false);
                n.setPixels(v, pop.getLabelMap(), null);
                return ArrayUtil.stream(n.getPixelValues()).filter(d->d>0).distinct().mapToInt(d->(int)d).toArray();
            };
            int[] n1 = getTouchingLabels.apply(new Voxel(xContour[0], yContour[0], 0).translate(revOff));
            logger.debug("touching labels 1: {}", n1);
            if (n1.length==1) {
                int[] n2 = getTouchingLabels.apply(new Voxel(xContour[xContour.length-1], yContour[xContour.length-1], 0).translate(revOff));
                logger.debug("touching labels 2: {}", n2);
                if (n2.length==1 && n2[0]==n1[0]) modifyObjectLabel = n1[0];
                else modifyObjectLabel = 0;
            } else modifyObjectLabel = 0;
        } else modifyObjectLabel = 0;
        Set<Voxel> voxels = IntStream.range(0, xContour.length).mapToObj(i->new Voxel(xContour[i], yContour[i], 0)).map(v->v.translate(revOff)).collect(Collectors.toSet());
        if (modifyObjectLabel==0) { // create a new object
            if (!isClosed) {
                Point start = new Point(xContour[0], yContour[0]).translate(revOff);
                Point end = new Point(xContour[xContour.length - 1], yContour[xContour.length - 1]).translate(revOff);
                Vector dir = Vector.vector(start, end).normalize().multiply(0.5);
                while (start.distSq(end) > 1) {
                    start.translate(dir);
                    voxels.add(start.asVoxel());
                }
            }
            boolean is2D = pop.getRegions().isEmpty() ? parent.is2D() : pop.getRegions().get(0).is2D();
            Region r = new Region(voxels, pop.getRegions().size() + 1, is2D, pop.getImageProperties().getScaleXY(), pop.getImageProperties().getScaleZ());
            FillHoles2D.fillHoles(r.getMaskAsImageInteger(), 2);
            //Filters.binaryOpen(r.getMaskAsImageInteger(), (ImageInteger) r.getMaskAsImageInteger(), Filters.getNeighborhood(1, r.getMaskAsImageInteger()), true);
            r.clearVoxels();

            //logger.debug("region size {}", r.size());
            ImageMask parentMask = parent.getMask();
            r.removeVoxels(r.getVoxels().stream().filter(v -> !parentMask.contains(v.x, v.y, v.z) || !parentMask.insideMask(v.x, v.y, v.z)).collect(Collectors.toList()));
            //logger.debug("region size after overlap with parent {}", r.size());
            r.removeVoxels(r.getVoxels().stream().filter(v -> pop.getLabelMap().insideMask(v.x, v.y, v.z)).collect(Collectors.toList())); // remove points already segmented

            if (r.getVoxels().isEmpty()) return Collections.emptyList();
            r.translate(parent.getBounds());
            r.setIsAbsoluteLandmark(true);
            return createSegmentedObject(r, parent, objectClassIdx, saveToDB);

        } else {
            Region rOld = pop.getRegions().stream().filter(rr->rr.getLabel()==modifyObjectLabel).findAny().get();
            Region r = rOld.duplicate();
            r.translate(new SimpleOffset(parent.getBounds()).reverseOffset()); // working in relative landmark
            r.setIsAbsoluteLandmark(false);
            logger.debug("region size before modify {}", r.size());
            r.addVoxels(voxels);
            FillHoles2D.fillHoles(r.getMaskAsImageInteger(), 2);
            Filters.binaryOpen(r.getMaskAsImageInteger(), (ImageInteger) r.getMaskAsImageInteger(), Filters.getNeighborhood(1, r.getMaskAsImageInteger()), true);
            logger.debug("region size after modify {}", r.size());
            r.clearVoxels(); // update voxels because mask has changed
            ImageMask parentMask = parent.getMask();
            r.removeVoxels(r.getVoxels().stream().filter(v -> !parentMask.contains(v.x, v.y, v.z) || !parentMask.insideMask(v.x, v.y, v.z)).collect(Collectors.toList()));
            logger.debug("region size after overlap with parent {}", r.size());
            r.removeVoxels(r.getVoxels().stream().filter(v -> pop.getLabelMap().insideMask(v.x, v.y, v.z) && pop.getLabelMap().getPixelInt(v.x, v.y, v.z)!=modifyObjectLabel).collect(Collectors.toList()));
            logger.debug("region size after erase overlap {}", r.size());
            r.translate(parent.getBounds());
            r.setIsAbsoluteLandmark(true);
            r.remove(rOld);
            return createSegmentedObject(r, parent, objectClassIdx, saveToDB);
        }
    }

    private static Collection<SegmentedObject> createSegmentedObject(Region r, SegmentedObject parent, int objectClassIdx, Consumer<Collection<SegmentedObject>> saveToDB) {
        SegmentedObjectFactory factory = getFactory(objectClassIdx);
        SegmentedObject so = new SegmentedObject(parent.getFrame(), objectClassIdx, r.getLabel() - 1, r, parent);
        List<SegmentedObject> objects = parent.getChildren(objectClassIdx).collect(Collectors.toList());
        objects.add(so);
        factory.setChildren(parent, objects);
        Set<SegmentedObject> modified = new HashSet<>();
        factory.relabelChildren(parent, modified);
        modified.add(so);
        saveToDB.accept(modified);
        return new ArrayList<SegmentedObject>() {{
            add(so);
        }};
    }

    private static SegmentedObjectFactory getFactory(int objectClassIdx) {
        try {
            Constructor<SegmentedObjectFactory> constructor = SegmentedObjectFactory.class.getDeclaredConstructor(int.class);
            constructor.setAccessible(true);
            return constructor.newInstance(objectClassIdx);
        } catch (InstantiationException | IllegalAccessException | NoSuchMethodException | InvocationTargetException e) {
            throw new RuntimeException("Could not create track link editor", e);
        }
    }
}






