package bacmman.data_structure;

import bacmman.utils.geom.Point;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

import static junit.framework.Assert.assertEquals;

public class TestOverlapRegions {
    public final static Logger logger = LoggerFactory.getLogger(TestOverlapRegions.class);
    @Test
    public void testOverlapSpotRegion() {
        Spot s2D = new Spot(new Point(1, 2, 0), 2, 0, 0, true, 1, 1);
        Spot s3D = new Spot(new Point(1, 2, 0), 2, 0, 0, false, 1, 1);
        logger.debug("bounds s2D: {}, s3D: {}", s2D.getBounds(), s3D.getBounds());

        List<Voxel> other2D = Arrays.asList(new Voxel(2, 2, 0), new Voxel(2, 3, 0), new Voxel(3, 2, 0), new Voxel(3, 3, 0));
        Region o2D = new Region(new HashSet<>(other2D), 1, true, 1, 1);
        List<Voxel> other2D1 = Arrays.asList(new Voxel(2, 2, 1), new Voxel(3, 2, 1));
        Region o2D1 = new Region(new HashSet<>(other2D1), 1, true, 1, 1);
        HashSet<Voxel> other3D = new HashSet<>(other2D1);
        other3D.addAll(other2D);
        Region o3D = new Region(other3D, 1, false, 1, 1);

        // spot - mask
        assertEquals("overlap spot2D - o 2D", 3, s2D.getOverlapArea(o2D, null, null));
        assertEquals("overlap spot3D - o 2D", 3, o2D.getOverlapArea(s3D, null, null));
        assertEquals("overlap spot2D - o 2D(z1)", 0, o2D1.getOverlapArea(s2D, null, null));
        assertEquals("overlap spot3D - o 2D(z1)", 2, o2D1.getOverlapArea(s3D, null, null));
        assertEquals("overlap spot2D - o 3D", 3, o2D.getOverlapArea(s3D, null, null));
        assertEquals("overlap spot3D - o 3D", 4, o3D.getOverlapArea(s3D, null, null));


        // intersect spot - voxels
        assertEquals("intersect spot2D - voxel 2D", new HashSet<>(other2D.subList(0, 3)), s2D.getIntersection(o2D));
        assertEquals("intersect spot3D - voxel 2D", new HashSet<>(other2D.subList(0, 3)), o2D.getIntersection(s3D));
        assertEquals("intersect spot2D - voxel 2D(z1)", Collections.emptySet(), o2D1.getIntersection(s2D));
        assertEquals("intersect spot3D - voxel 2D(z1)", new HashSet<>(other2D1), o2D1.getIntersection(s3D));
        assertEquals("intersect spot2D - voxel 3D", new HashSet<>(other2D.subList(0, 3)), o2D.getIntersection(s3D));
        Set<Voxel> inter3D = new HashSet<>(other2D.subList(0, 3));
        inter3D.add(new Voxel(2, 2, 1));
        assertEquals("intersect spot3D - o 3D", inter3D, o3D.getIntersection(s3D));


        // intersect spot - mask
        o2D.clearVoxels();
        o2D1.clearVoxels();
        o3D.clearVoxels();
        assertEquals("intersect spot2D - mask 2D", new HashSet<>(other2D.subList(0, 3)), s2D.getIntersection(o2D));
        assertEquals("intersect spot3D - mask 2D", new HashSet<>(other2D.subList(0, 3)), o2D.getIntersection(s3D));
        assertEquals("intersect spot2D - mask 2D(z1)", Collections.emptySet(), o2D1.getIntersection(s2D));
        assertEquals("intersect spot3D - mask 2D(z1)", new HashSet<>(other2D1), o2D1.getIntersection(s3D));
        assertEquals("intersect spot2D - mask 3D", new HashSet<>(other2D.subList(0, 3)), o2D.getIntersection(s3D));
        assertEquals("intersect spot3D - mask 3D", inter3D, o3D.getIntersection(s3D));

    }
}
