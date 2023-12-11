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
        Spot s2D = new Spot(new Point(1, 2, 0), 2, 1, 0, 0, true, 1, 1);
        Spot s3D = new Spot(new Point(1, 2, 0), 2, 1, 0, 0, false, 1, 1);
        logger.debug("bounds s2D: {}, s3D: {}", s2D.getBounds(), s3D.getBounds());

        List<Voxel> other2D = Arrays.asList(new Voxel(2, 2, 0), new Voxel(2, 3, 0), new Voxel(3, 2, 0), new Voxel(3, 3, 0));
        Region o2D = new Region(new HashSet<>(other2D), 1, true, 1, 1);
        List<Voxel> other2D1 = Arrays.asList(new Voxel(2, 2, 1), new Voxel(3, 2, 1));
        Region o2D1 = new Region(new HashSet<>(other2D1), 1, true, 1, 1);
        HashSet<Voxel> other3D = new HashSet<>(other2D1);
        other3D.addAll(other2D);
        Region o3D = new Region(other3D, 1, false, 1, 1);
        logger.debug("bounds o2D: {}, o2D1{}, o3D: {}", o2D.getBounds(), o2D1.getBounds(), o3D.getBounds());
        // spot - mask
        assertEquals("overlap spot2D - o 2D", 3.0, s2D.getOverlapArea(o2D, null, null), 1e-3);
        assertEquals("overlap spot3D - o 2D", 7, o2D.getOverlapArea(s3D, null, null),1e-3);
        assertEquals("overlap spot2D - o 2D(z1)", 2, o2D1.getOverlapArea(s2D, null, null),1e-3);
        assertEquals("overlap spot3D - o 2D(z1)", 4, o2D1.getOverlapArea(s3D, null, null),1e-3);
        assertEquals("overlap spot2D - o 3D", 5, o3D.getOverlapArea(s2D, null, null),1e-3);
        assertEquals("overlap spot3D - o 3D", 4, o3D.getOverlapArea(s3D, null, null),1e-3);


        // intersect spot - voxels
        Set<Voxel> inter2D = new HashSet<>(other2D.subList(0, 3));
        assertEquals("intersect spot2D - o 2D", inter2D, s2D.getIntersectionVoxelSet(o2D));
        inter2D.addAll(Arrays.asList(new Voxel(2, 2, 1), new Voxel(2, 3, 1), new Voxel(2, 2, -1), new Voxel(2, 3, -1)));
        assertEquals("intersect spot3D - o 2D", inter2D, o2D.getIntersectionVoxelSet(s3D));
        inter2D = new HashSet<>();
        inter2D.addAll(Arrays.asList(new Voxel(3, 2, 0), new Voxel(2, 2, 0)));
        assertEquals("intersect spot2D - o 2D(z1)", inter2D, o2D1.getIntersectionVoxelSet(s2D));
        inter2D = new HashSet<>();
        inter2D.addAll(Arrays.asList(new Voxel(2, 2, 0), new Voxel(3, 2, 0), new Voxel(2, 2, 1), new Voxel(2, 2, -1)));
        assertEquals("intersect spot3D - o 2D(z1)", inter2D, o2D1.getIntersectionVoxelSet(s3D));
        inter2D = new HashSet<>(other2D.subList(0, 3));
        inter2D.addAll(other2D1);
        assertEquals("intersect spot2D - o 3D", inter2D, s2D.getIntersectionVoxelSet(o3D));
        Set<Voxel> inter3D = new HashSet<>(other2D.subList(0, 3));
        inter3D.add(new Voxel(2, 2, 1));
        assertEquals("intersect spot3D - o 3D", inter3D, o3D.getIntersectionVoxelSet(s3D));


        // intersect spot - mask
        o2D.clearVoxels();
        o2D1.clearVoxels();
        o3D.clearVoxels();
        inter2D = new HashSet<>(other2D.subList(0, 3));
        assertEquals("intersect spot2D - mask 2D", inter2D, s2D.getIntersectionVoxelSet(o2D));
        inter2D.addAll(Arrays.asList(new Voxel(2, 2, 1), new Voxel(2, 3, 1), new Voxel(2, 2, -1), new Voxel(2, 3, -1)));
        assertEquals("intersect spot3D - mask 2D", inter2D, o2D.getIntersectionVoxelSet(s3D));
        //assertEquals("intersect spot2D - mask 2D(z1)", Collections.emptySet(), o2D1.getIntersection(s2D));
        inter2D = new HashSet<>();
        inter2D.addAll(Arrays.asList(new Voxel(2, 2, 0), new Voxel(3, 2, 0), new Voxel(2, 2, 1), new Voxel(2, 2, -1)));
        assertEquals("intersect spot3D - mask 2D(z1)", inter2D, o2D1.getIntersectionVoxelSet(s3D));
        inter2D = new HashSet<>(other2D.subList(0, 3));
        inter2D.addAll(other2D1);
        assertEquals("intersect spot2D - mask 3D", inter2D, s2D.getIntersectionVoxelSet(o3D));
        assertEquals("intersect spot3D - mask3D", inter3D, o3D.getIntersectionVoxelSet(s3D));

    }
}
