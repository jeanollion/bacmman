package bacmman.ui.gui.image_interaction;

import bacmman.data_structure.*;
import bacmman.image.*;
import bacmman.processing.FillHoles2D;
import bacmman.processing.Filters;
import bacmman.processing.neighborhood.EllipsoidalNeighborhood;
import bacmman.processing.neighborhood.Neighborhood;
import bacmman.utils.ArrayUtil;
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

public class FreeLineSegmenter {
    public final static Logger logger = LoggerFactory.getLogger(FreeLineSegmenter.class);
    public static Collection<SegmentedObject> segment(SegmentedObject parent, Offset parentOffset, int[] xContour, int[] yContour, int z, int objectClassIdx, boolean relabel, Consumer<Collection<SegmentedObject>> saveToDB) {
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
        Set<Voxel> voxels = IntStream.range(0, xContour.length).mapToObj(i->new Voxel(xContour[i], yContour[i], z)).map(v->v.translate(revOff)).collect(Collectors.toSet());
        if (modifyObjectLabel==0) { // create a new object
            if (!isClosed) { // close the object by tracing a line between 2 extremities
                Point start = new Point(xContour[0], yContour[0]).translate(revOff);
                Point end = new Point(xContour[xContour.length - 1], yContour[xContour.length - 1]).translate(revOff);
                Vector dir = Vector.vector(start, end).normalize().multiply(0.5);
                while (start.distSq(end) > 1) {
                    start.translate(dir);
                    voxels.add(start.asVoxel());
                }
            }
            boolean is2D = pop.getRegions().isEmpty() ? parent.is2D() : pop.getRegions().get(0).is2D();
            if (is2D) voxels.forEach(v -> v.z = 0);
            Region r = new Region(voxels, pop.getRegions().size() + 1, is2D, pop.getImageProperties().getScaleXY(), pop.getImageProperties().getScaleZ());
            FillHoles2D.fillHoles(r.getMaskAsImageInteger(), 2);
            //Filters.binaryOpen(r.getMaskAsImageInteger(), (ImageInteger) r.getMaskAsImageInteger(), Filters.getNeighborhood(1, r.getMaskAsImageInteger()), true);
            r.clearVoxels();

            logger.debug("region size {}", r.size());
            ImageMask parentMask = parent.getMask();
            r.removeVoxels(r.getVoxels().stream().filter(v -> !parentMask.contains(v.x, v.y, v.z) || !parentMask.insideMask(v.x, v.y, v.z)).collect(Collectors.toList()));
            logger.debug("region size after overlap with parent {}", r.size());
            r.removeVoxels(r.getVoxels().stream().filter(v -> pop.getLabelMap().insideMask(v.x, v.y, v.z)).collect(Collectors.toList())); // remove points already segmented
            logger.debug("region size after overlap with other objects {}", r.size());
            if (r.getVoxels().isEmpty()) return Collections.emptyList();
            return createSegmentedObject(r, parent, objectClassIdx, relabel, saveToDB);
        } else { // close the object using border of touching object
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
            r.remove(rOld);
            return createSegmentedObject(r, parent, objectClassIdx, relabel, saveToDB);
        }
    }

    public static Collection<SegmentedObject> createSegmentedObject(Region r, SegmentedObject parent, int objectClassIdx, boolean relabel, Consumer<Collection<SegmentedObject>> saveToDB) {
        if (!r.isAbsoluteLandMark()) {
            r.translate(parent.getBounds());
            r.setIsAbsoluteLandmark(true);
        }
        SegmentedObjectFactory factory = getFactory(objectClassIdx);
        SegmentedObject so = new SegmentedObject(parent.getFrame(), objectClassIdx, r.getLabel() - 1, r, parent);
        List<SegmentedObject> objects = parent.getChildren(objectClassIdx).collect(Collectors.toList());
        Set<SegmentedObject> modified = new HashSet<>();
        if (relabel) {
            // HEURISTIC TO FIND INSERTION POINT // TODO ALSO USE IN OBJECT CREATOR
            Point ref = r.getBounds().getCenter();
            SegmentedObject[] twoClosest = objects.stream().sorted(Comparator.comparingDouble(ob -> ob.getBounds().getCenter().distSq(ref))).limit(2).sorted(Comparator.comparingInt(SegmentedObject::getIdx)).toArray(SegmentedObject[]::new);
            if (twoClosest.length < 2) objects.add(so);
            else {
                Vector dir1 = Vector.vector(twoClosest[0].getBounds().getCenter(), twoClosest[1].getBounds().getCenter());
                Vector dir2 = Vector.vector(twoClosest[1].getBounds().getCenter(), ref);
                int idx = dir1.dotProduct(dir2) > 0 ? objects.indexOf(twoClosest[1]) : objects.indexOf(twoClosest[0]);
                objects.add(idx + 1, so);
            }
            factory.setChildren(parent, objects);
            factory.relabelChildren(parent, modified);
            modified.add(so);
        } else { // just ensure label is not existing
            int[] idxAndIP = SegmentedObjectFactory.getUnusedIndexAndInsertionPoint(objects);
            factory.setIdx(so, idxAndIP[0]);
            modified.add(so);
            factory.reassignDuplicateIndices(modified);
            if (idxAndIP[1]>=0) objects.add(idxAndIP[1], so);
            else objects.add(so);
            factory.setChildren(parent, objects);
        }
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






