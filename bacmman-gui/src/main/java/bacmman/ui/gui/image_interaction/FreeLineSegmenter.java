package bacmman.ui.gui.image_interaction;

import bacmman.configuration.parameters.Parameter;
import bacmman.data_structure.*;
import bacmman.image.*;
import bacmman.plugins.ManualSegmenter;
import bacmman.processing.FillHoles2D;
import bacmman.processing.Filters;
import bacmman.utils.Pair;
import bacmman.utils.geom.Point;
import bacmman.utils.geom.Vector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public class FreeLineSegmenter {
    public final static Logger logger = LoggerFactory.getLogger(FreeLineSegmenter.class);
    public static List<SegmentedObject> segment(SegmentedObject parent, Offset parentOffset, Collection<SegmentedObject> touchedObjects, int[] xContour, int[] yContour, int objectClassIdx, Consumer<Collection<SegmentedObject>> saveToDB) {
        if (xContour.length!=yContour.length) throw new IllegalArgumentException("xPoints & yPoints should have same length");


        Offset revOff = new SimpleOffset(parentOffset).reverseOffset();
        Set<Voxel> voxels = IntStream.range(0, xContour.length).mapToObj(i->new Voxel(xContour[i], yContour[i], 0)).map(v->v.translate(revOff)).collect(Collectors.toSet());
        boolean isClosed = Math.pow(xContour[0]-xContour[xContour.length-1], 2) + Math.pow(yContour[0]-yContour[xContour.length-1], 2) <=1;
        if (!isClosed) {
            Point start = new Point(xContour[0], yContour[0]).translate(revOff);
            Point end = new Point(xContour[xContour.length-1], yContour[xContour.length-1]).translate(revOff);
            Vector dir = Vector.vector(start, end).normalize().multiply(0.5);
            while(start.distSq(end)>1) {
                start.translate(dir);
                voxels.add(start.asVoxel());
            }
        }

        RegionPopulation pop = parent.getChildRegionPopulation(objectClassIdx);
        boolean is2D = pop.getRegions().isEmpty() ? parent.is2D() : pop.getRegions().get(0).is2D();
        Region r = new Region(voxels, pop.getRegions().size()+1, is2D, pop.getImageProperties().getScaleXY(), pop.getImageProperties().getScaleZ());
        FillHoles2D.fillHoles(r.getMaskAsImageInteger(), 2);
        Filters.binaryOpen(r.getMaskAsImageInteger(), (ImageInteger)r.getMaskAsImageInteger(), Filters.getNeighborhood(1, r.getMaskAsImageInteger()), true);
        r.clearVoxels();
        SegmentedObjectFactory factory = getFactory(objectClassIdx);
        //if (isClosed || touchedObjects.isEmpty()) {
        logger.debug("region size {}", r.size());
        ImageMask parentMask = parent.getMask();
        r.removeVoxels(r.getVoxels().stream().filter(v -> !parentMask.contains(v.x, v.y, v.z) || !parentMask.insideMask(v.x, v.y, v.z)).collect(Collectors.toList()));
        logger.debug("region size after overlap with parent {}", r.size());
        if (!touchedObjects.isEmpty()) { // remove points already segmented
            r.removeVoxels(r.getVoxels().stream().filter(v->pop.getLabelMap().insideMask(v.x, v.y, v.z)).collect(Collectors.toList()));
            logger.debug("region size after erase overlap {}", r.size());
        }

        if (r.getVoxels().isEmpty()) return Collections.emptyList();
        r.translate(parent.getBounds());
        r.setIsAbsoluteLandmark(true);

        SegmentedObject so = new SegmentedObject(parent.getFrame(), objectClassIdx, r.getLabel()-1, r ,parent);
        List<SegmentedObject> objects = parent.getChildren(objectClassIdx).collect(Collectors.toList());
        objects.add(so);
        factory.setChildren(parent, objects);
        Set<SegmentedObject> modified = new HashSet<>();
        factory.relabelChildren(parent, modified);
        modified.add(so);
        saveToDB.accept(modified);
        //}
        return new ArrayList<SegmentedObject>(){{add(so);}};
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






