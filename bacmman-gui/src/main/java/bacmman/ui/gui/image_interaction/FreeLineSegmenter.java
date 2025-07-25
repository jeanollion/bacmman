package bacmman.ui.gui.image_interaction;

import bacmman.core.Core;
import bacmman.data_structure.*;
import bacmman.image.*;
import bacmman.processing.FillHoles2D;
import bacmman.processing.Filters;
import bacmman.processing.neighborhood.EllipsoidalNeighborhood;
import bacmman.processing.neighborhood.Neighborhood;
import bacmman.utils.ArrayUtil;
import bacmman.utils.Utils;
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
    public static SegmentedObject segment(SegmentedObject parent, Offset parentOffset, int[] xContour, int[] yContour, int z, int objectClassIdx, boolean relabel, Consumer<Collection<SegmentedObject>> saveToDB) {
        if (xContour.length!=yContour.length) throw new IllegalArgumentException("xPoints & yPoints should have same length");
        Offset revOff = new SimpleOffset(parentOffset).reverseOffset(); // translate to offset relative to parent
        RegionPopulation pop = parent.getChildRegionPopulation(objectClassIdx);
        boolean allowOverlap = parent.getExperimentStructure().allowOverlap(objectClassIdx);
        boolean isClosed = Math.pow(xContour[0]-xContour[xContour.length-1], 2) + Math.pow(yContour[0]-yContour[xContour.length-1], 2) <=1;
        int modifyObjectLabel;
        if (!isClosed) { // check that the 2 extremities touch the same object & only one object -> close this object
            Function<Voxel, int[]> getTouchingLabels = v -> {
                Neighborhood n = new EllipsoidalNeighborhood(1.5, false);
                n.setPixels(v, pop.getLabelMap(), null);
                return ArrayUtil.stream(n.getPixelValues()).filter(d->d>0).distinct().mapToInt(d->(int)d).toArray();
            };
            int[] n1 = getTouchingLabels.apply(new Voxel(xContour[0], yContour[0], z).translate(revOff));
            logger.debug("touching labels 1: {}", n1);
            if (n1.length==1) {
                int[] n2 = getTouchingLabels.apply(new Voxel(xContour[xContour.length-1], yContour[xContour.length-1], z).translate(revOff));
                logger.debug("touching labels 2: {}", n2);
                if (n2.length==1 && n2[0]==n1[0]) modifyObjectLabel = n1[0];
                else modifyObjectLabel = 0;
            } else modifyObjectLabel = 0;
        } else modifyObjectLabel = 0;
        Set<Voxel> voxels = IntStream.range(0, xContour.length).mapToObj(i->new Voxel(xContour[i], yContour[i], z)).map(v->v.translate(revOff)).collect(Collectors.toSet());
        if (modifyObjectLabel==0) { // create a new object
            if (!isClosed) { // close the object by tracing a line between 2 extremities
                Point start = new Point(xContour[0], yContour[0], z).translate(revOff);
                Point end = new Point(xContour[xContour.length - 1], yContour[xContour.length - 1], z).translate(revOff);
                Vector dir = Vector.vector(start, end).normalize().multiply(0.5);
                while (start.distSq(end) > 1) {
                    start.translate(dir);
                    voxels.add(start.asVoxel());
                }
            }
            boolean is2D = pop.getRegions().isEmpty() ? parent.getExperimentStructure().is2D(objectClassIdx, parent.getPositionName()) : pop.getRegions().get(0).is2D();
            if (is2D) voxels.forEach(v -> v.z = 0);
            Region r = new Region(voxels, pop.getRegions().size() + 1, is2D, pop.getImageProperties().getScaleXY(), pop.getImageProperties().getScaleZ());
            r.ensureMaskIsImageInteger();
            r.clearVoxels();
            ImageInteger mask = r.getMaskAsImageInteger();
            FillHoles2D.fillHoles(mask, 2);
            //Filters.binaryOpen(r.getMaskAsImageInteger(), (ImageInteger) r.getMaskAsImageInteger(), Filters.getNeighborhood(1, r.getMaskAsImageInteger()), true);
            //logger.debug("region size {} bb: {}", r.size(), r.getBounds());
            ImageMask parentMask = !is2D && parent.is2D()  ? new ImageMask2D(parent.getMask()) : parent.getMask();
            ImageInteger previousLabels = pop.getLabelMap();
            r.loop((x, y, zz) -> {
                if (!parentMask.contains(x, y, zz) || !parentMask.insideMask(x, y, zz) || (!allowOverlap && previousLabels.insideMask(x, y, zz))) mask.setPixelWithOffset(x, y, zz, 0);
            });
            r.clearMask();
            //logger.debug("region size after overlap with other objects {}", r.size());
            if (r.getVoxels().isEmpty()) return null;
            return createSegmentedObject(r, parent, objectClassIdx, relabel, saveToDB);
        } else { // close the object using border of touching object
            Region rOld = pop.getRegions().stream().filter(rr->rr.getLabel()==modifyObjectLabel).findAny().get();
            if (rOld.is2D()) voxels.forEach(v -> v.z = 0);
            ImageInteger previousLabels = pop.getLabelMap();
            Region r = rOld.duplicate();
            //logger.debug("region is 2D: {} original: {}", r.is2D(), rOld.is2D());
            r.translate(new SimpleOffset(parent.getBounds()).reverseOffset()); // working in relative landmark
            r.setIsAbsoluteLandmark(false);
            //logger.debug("region size before modify {}, bb: {} original bb: {}", r.size(), r.getBounds(), rOld.getBounds());
            r.addVoxels(voxels);
            r.ensureMaskIsImageInteger();
            ImageInteger mask = r.getMaskAsImageInteger();
            r.clearVoxels();
            FillHoles2D.fillHoles(mask, 2);
            //logger.debug("region size after add voxels {}, bb: {}", r.size(), r.getBounds());
            ImageMask parentMask = parent.getMask();
            r.loop((x, y, zz) -> {
                if (!parentMask.contains(x, y, zz) || !parentMask.insideMask(x, y, zz) || (!allowOverlap && previousLabels.insideMask(x, y, zz))) mask.setPixelWithOffset(x, y, zz, 0);
            });
            r.clearMask();
            //logger.debug("region size after modify {}, bb: {}", r.size(), r.getBounds());
            return createSegmentedObject(r, parent, objectClassIdx, relabel, saveToDB);
        }
    }

    public static SegmentedObject createSegmentedObject(Region r, SegmentedObject parent, int objectClassIdx, boolean relabel, Consumer<Collection<SegmentedObject>> saveToDB) {
        if (!r.isAbsoluteLandMark()) {
            r.translate(parent.getBounds());
            r.setIsAbsoluteLandmark(true);
        }
        if (!BoundingBox.isIncluded(r.getBounds(), parent.getBounds())) {
            r = r.getIntersection(parent.getRegion());
            if (r == null) {
                logger.debug("cannot create object : no intersection with parent mask");
                return null;
            }
        }
        SegmentedObjectFactory factory = getFactory(objectClassIdx);
        SegmentedObject so = new SegmentedObject(parent.getFrame(), objectClassIdx, r.getLabel() - 1, r, parent);
        List<SegmentedObject> children = parent.getChildren(objectClassIdx).collect(Collectors.toList());
        Set<SegmentedObject> modified = new HashSet<>();
        if (relabel) {
            // HEURISTIC TO FIND INSERTION POINT // TODO ALSO USE IN OBJECT CREATOR
            Point ref = r.getBounds().getCenter();
            SegmentedObject[] twoClosest = children.stream().sorted(Comparator.comparingDouble(ob -> ob.getBounds().getCenter().distSq(ref))).limit(2).sorted(Comparator.comparingInt(SegmentedObject::getIdx)).toArray(SegmentedObject[]::new);
            if (twoClosest.length < 2) children.add(so);
            else {
                Vector dir1 = Vector.vector(twoClosest[0].getBounds().getCenter(), twoClosest[1].getBounds().getCenter());
                Vector dir2 = Vector.vector(twoClosest[1].getBounds().getCenter(), ref);
                int idx = dir1.dotProduct(dir2) > 0 ? children.indexOf(twoClosest[1]) : children.indexOf(twoClosest[0]);
                children.add(idx + 1, so);
            }
            factory.setChildren(parent, children);
            factory.relabelChildren(parent, modified);
            modified.add(so);
        } else { // just ensure a non-existing label
            int[] idxAndIP = SegmentedObjectFactory.getUnusedIndexAndInsertionPoint(children);
            factory.setIdx(so, idxAndIP[0]);
            modified.add(so);
            if (idxAndIP[1]>=0) children.add(idxAndIP[1], so);
            else children.add(so);
            factory.setChildren(parent, children);
        }
        saveToDB.accept(modified);
        return so;
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






