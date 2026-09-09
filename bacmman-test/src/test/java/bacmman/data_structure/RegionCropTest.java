package bacmman.data_structure;

import bacmman.image.*;
import bacmman.utils.geom.Point;
import org.junit.Test;

import java.util.*;
import java.util.stream.Collectors;

import static org.junit.Assert.*;

/**
 * Tests for:
 *  - Region.getCroppedRegion(BoundingBox)
 *  - RegionPopulation.getCroppedRegionPopulation(BoundingBox, boolean), both the
 *    objects-based path (labelImage == null) and the label-image-based path (labelImage != null)
 *
 * All test data is synthetic: regions are simple filled rectangles (2D, single Z-plane),
 * built directly via mask / voxel constructors so no image files or real data are needed.
 */
public class RegionCropTest {

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    /** Builds an axis-aligned bounding box with the given absolute (offset) coordinates. */
    private static MutableBoundingBox bb(int xMin, int xMax, int yMin, int yMax, int zMin, int zMax) {
        return new MutableBoundingBox().setxMin(xMin).setxMax(xMax).setyMin(yMin).setyMax(yMax).setzMin(zMin).setzMax(zMax);
    }

    /** Builds a fully-filled rectangular Region (mask-based) at the given absolute bounds. */
    private static Region rectRegion(int xMin, int xMax, int yMin, int yMax, int zMin, int zMax, int label) {
        BoundingBox bounds = bb(xMin, xMax, yMin, yMax, zMin, zMax);
        ImageByte mask = new ImageByte("mask", new SimpleImageProperties(bounds, 1, 1));
        for (int z = zMin; z <= zMax; ++z)
            for (int y = yMin; y <= yMax; ++y)
                for (int x = xMin; x <= xMax; ++x)
                    mask.setPixelWithOffset(x, y, z, 1);
        return new Region(mask, label, zMin == zMax);
    }

    private static long rectVolume(int xMin, int xMax, int yMin, int yMax, int zMin, int zMax) {
        return (long) (xMax - xMin + 1) * (yMax - yMin + 1) * (zMax - zMin + 1);
    }

    private static void assertBoundsEqual(BoundingBox expected, BoundingBox actual) {
        assertEquals("xMin", expected.xMin(), actual.xMin());
        assertEquals("xMax", expected.xMax(), actual.xMax());
        assertEquals("yMin", expected.yMin(), actual.yMin());
        assertEquals("yMax", expected.yMax(), actual.yMax());
        assertEquals("zMin", expected.zMin(), actual.zMin());
        assertEquals("zMax", expected.zMax(), actual.zMax());
    }

    // ---------------------------------------------------------------------
    // Region.getCroppedRegion
    // ---------------------------------------------------------------------

    @Test
    public void testGetCroppedRegion_fullyContained_maskBased() {
        Region r = rectRegion(2, 4, 2, 4, 0, 0, 7); // 3x3 square
        BoundingBox bds = bb(0, 9, 0, 9, 0, 0);      // much larger crop window
        Region cropped = r.getCroppedRegion(bds, false);
        assertNotNull(cropped);
        assertBoundsEqual(bb(2, 4, 2, 4, 0, 0), cropped.getBounds());
        assertEquals(rectVolume(2, 4, 2, 4, 0, 0), (long) cropped.size());
        assertEquals(7, cropped.getLabel());
    }

    @Test
    public void testGetCroppedRegion_partialOverlap_maskBased() {
        // region spans x:8..12, y:8..12 ; crop window is x:0..9, y:0..9 -> intersection x:8..9, y:8..9
        Region r = rectRegion(8, 12, 8, 12, 0, 0, 3);
        BoundingBox bds = bb(0, 9, 0, 9, 0, 0);
        Region cropped = r.getCroppedRegion(bds, false);
        assertNotNull(cropped);
        assertBoundsEqual(bb(8, 9, 8, 9, 0, 0), cropped.getBounds());
        assertEquals(rectVolume(8, 9, 8, 9, 0, 0), (long) cropped.size()); // 2x2 = 4
        assertEquals(3, cropped.getLabel());
        // no pixel of the cropped region should fall outside bds
        cropped.loop((x, y, z) -> assertTrue(bds.containsWithOffset(x, y, z)));
    }

    @Test
    public void testGetCroppedRegion_noOverlap_returnsNull() {
        Region r = rectRegion(20, 22, 20, 22, 0, 0, 1);
        BoundingBox bds = bb(0, 5, 0, 5, 0, 0);
        assertNull(r.getCroppedRegion(bds, false));
    }

    @Test
    public void testGetCroppedRegion_voxelBased_partialOverlap() {
        Set<Voxel> voxels = new HashSet<>(Arrays.asList(
                new Voxel(0, 0, 0),
                new Voxel(1, 1, 0),
                new Voxel(5, 5, 0),
                new Voxel(6, 6, 0)
        ));
        Region r = new Region(voxels, 9, true, 1, 1);
        BoundingBox bds = bb(0, 2, 0, 2, 0, 0); // should keep (0,0,0) and (1,1,0) only
        Region cropped = r.getCroppedRegion(bds, false);
        assertNotNull(cropped);
        assertEquals(2, (long) cropped.size());
        assertTrue(cropped.contains(new Voxel(0, 0, 0)));
        assertTrue(cropped.contains(new Voxel(1, 1, 0)));
        assertFalse(cropped.contains(new Voxel(5, 5, 0)));
        assertFalse(cropped.contains(new Voxel(6, 6, 0)));
        assertEquals(9, cropped.getLabel());
        assertTrue(cropped.is2D());
    }

    @Test
    public void testGetCroppedRegion_preservesQualityAndCategory() {
        Region r = rectRegion(0, 9, 0, 9, 0, 0, 4).setQuality(0.85).setCategory(2, 0.7);
        BoundingBox bds = bb(0, 4, 0, 4, 0, 0); // straddling crop, forces the clipping branch
        Region cropped = r.getCroppedRegion(bds, false);
        assertNotNull(cropped);
        assertEquals(0.85, cropped.getQuality(), 1e-9);
        assertEquals(2, cropped.getCategory());
        assertEquals(0.7, cropped.getCategoryProbability(), 1e-9);
    }

    // ---------------------------------------------------------------------
    // Region.getCroppedRegion - Analytical subtypes (Ellipse2D)
    // ---------------------------------------------------------------------

    @Test
    public void testGetCroppedRegion_ellipseFullyContained_remainsAnalytical() {
        Ellipse2D e = new Ellipse2D(new Point(10, 10, 0), 6, 4, 0, 1.0, 1, true, 1, 1);
        BoundingBox bds = bb(0, 20, 0, 20, 0, 0); // large window, fully contains the ellipse
        Region cropped = e.getCroppedRegion(bds, false);
        assertNotNull(cropped);
        // fully contained -> same instance, still an Ellipse2D
        assertSame(e, cropped);
        assertTrue(cropped instanceof Ellipse2D);
        assertTrue(cropped instanceof Analytical);
    }

    @Test
    public void testGetCroppedRegion_ellipseStraddlingBoundary_isRasterized() {
        // ellipse centered near the crop edge so it genuinely straddles it
        Ellipse2D e = new Ellipse2D(new Point(9, 9, 0), 6, 6, 0, 1.0, 2, true, 1, 1);
        BoundingBox eBounds = e.getBounds();
        // sanity check: ellipse must actually extend past the crop window for this test to be meaningful
        BoundingBox bds = bb(0, 8, 0, 8, 0, 0);
        assertTrue("test setup: ellipse must not be fully included in bds", !BoundingBox.isIncluded(eBounds, bds));
        assertTrue("test setup: ellipse must intersect bds", BoundingBox.intersect(eBounds, bds));

        Region cropped = e.getCroppedRegion(bds, false);
        assertNotNull(cropped);
        // clipped shape can no longer be an ellipse -> must be a plain rasterized Region
        assertFalse(cropped instanceof Ellipse2D);
        assertFalse(cropped instanceof Analytical);
        assertEquals(2, cropped.getLabel());
        // every pixel of the rasterized result must lie within the crop window
        cropped.loop((x, y, z) -> assertTrue(bds.containsWithOffset(x, y, z)));
        // and every pixel must still be a real point of the original ellipse
        cropped.loop((x, y, z) -> assertTrue(e.contains(new Voxel(x, y, z))));
    }

    @Test
    public void testGetCroppedRegion_ellipseNoOverlap_returnsNull() {
        Ellipse2D e = new Ellipse2D(new Point(100, 100, 0), 4, 4, 0, 1.0, 3, true, 1, 1);
        BoundingBox bds = bb(0, 5, 0, 5, 0, 0);
        assertNull(e.getCroppedRegion(bds, false));
    }

    // ---------------------------------------------------------------------
    // RegionPopulation.getCroppedRegionPopulation - objects-based path (labelImage == null)
    // ---------------------------------------------------------------------

    @Test
    public void testGetCroppedRegionPopulation_objectsBased_mixedRegions() {
        Region fullyInside = rectRegion(2, 4, 2, 4, 0, 0, 1);      // entirely inside crop
        Region straddling = rectRegion(8, 12, 8, 12, 0, 0, 2);     // crosses crop boundary
        Region fullyOutside = rectRegion(20, 22, 20, 22, 0, 0, 3); // entirely outside crop

        BoundingBox outer = bb(0, 22, 0, 22, 0, 0);
        RegionPopulation pop = new RegionPopulation(
                Arrays.asList(fullyInside, straddling, fullyOutside),
                new SimpleImageProperties(outer, 1, 1));

        BoundingBox bds = bb(0, 9, 0, 9, 0, 0);
        RegionPopulation cropped = pop.getCroppedRegionPopulation(bds, false, false);

        List<Region> result = cropped.getRegions();
        assertEquals("fully-outside region must be dropped, others kept", 2, result.size());

        Map<Integer, Region> byLabel = result.stream().collect(Collectors.toMap(Region::getLabel, r -> r));
        assertTrue(byLabel.containsKey(1));
        assertTrue(byLabel.containsKey(2));
        assertFalse(byLabel.containsKey(3));

        // fully-contained region bounds are unchanged
        assertBoundsEqual(bb(2, 4, 2, 4, 0, 0), byLabel.get(1).getBounds());
        // straddling region is clipped to the crop window
        assertBoundsEqual(bb(8, 9, 8, 9, 0, 0), byLabel.get(2).getBounds());

        // no region pixel lies outside the crop window
        for (Region r : result) {
            r.loop((x, y, z) -> assertTrue(bds.containsWithOffset(x, y, z)));
        }
    }

    @Test
    public void testGetCroppedRegionPopulation_objectsBased_reusesFullyContainedInstance() {
        Region fullyInside = rectRegion(2, 4, 2, 4, 0, 0, 1);
        BoundingBox outer = bb(0, 9, 0, 9, 0, 0);
        RegionPopulation pop = new RegionPopulation(
                Collections.singletonList(fullyInside),
                new SimpleImageProperties(outer, 1, 1));

        BoundingBox bds = bb(0, 9, 0, 9, 0, 0); // same size as outer -> region fully included
        RegionPopulation cropped = pop.getCroppedRegionPopulation(bds, false, false);

        Region result = cropped.getRegions().get(0);
        // the exact same Region instance should be reused, not a copy, since it is fully included
        assertSame(fullyInside, result);
    }

    @Test
    public void testGetCroppedRegionPopulation_objectsBased_emptyResultWhenAllOutside() {
        Region outside = rectRegion(20, 22, 20, 22, 0, 0, 1);
        BoundingBox outer = bb(0, 30, 0, 30, 0, 0);
        RegionPopulation pop = new RegionPopulation(
                Collections.singletonList(outside),
                new SimpleImageProperties(outer, 1, 1));

        BoundingBox bds = bb(0, 5, 0, 5, 0, 0);
        RegionPopulation cropped = pop.getCroppedRegionPopulation(bds, false, false);
        assertTrue(cropped.getRegions().isEmpty());
    }

    @Test
    public void testGetCroppedRegionPopulation_objectsBased_relabelReindexesFromOne() {
        Region r1 = rectRegion(2, 4, 2, 4, 0, 0, 5);
        Region r2 = rectRegion(6, 8, 6, 8, 0, 0, 9);
        BoundingBox outer = bb(0, 9, 0, 9, 0, 0);
        RegionPopulation pop = new RegionPopulation(
                Arrays.asList(r1, r2),
                new SimpleImageProperties(outer, 1, 1));

        RegionPopulation cropped = pop.getCroppedRegionPopulation(bb(0, 9, 0, 9, 0, 0), true, false);
        List<Integer> labels = cropped.getRegions().stream().map(Region::getLabel).sorted().collect(Collectors.toList());
        assertEquals(Arrays.asList(1, 2), labels);
    }

    // ---------------------------------------------------------------------
    // RegionPopulation.getCroppedRegionPopulation - label-image-based path (labelImage != null)
    // ---------------------------------------------------------------------

    @Test
    public void testGetCroppedRegionPopulation_labelImageBased_clipsStraddlingRegion() {
        // Build a 10x10 label image (x,y: 0..9, z:0..0) with two labeled rectangles:
        //   label 1 : x 2..4, y 2..4  (fully inside the crop window we'll use below)
        //   label 2 : x 7..9, y 7..9  (straddles the crop window boundary)
        BoundingBox imgBounds = bb(0, 9, 0, 9, 0, 0);
        ImageByte labelImage = new ImageByte("labels", new SimpleImageProperties(imgBounds, 1, 1));
        for (int y = 2; y <= 4; ++y)
            for (int x = 2; x <= 4; ++x)
                labelImage.setPixelWithOffset(x, y, 0, 1);
        for (int y = 7; y <= 9; ++y)
            for (int x = 7; x <= 9; ++x)
                labelImage.setPixelWithOffset(x, y, 0, 2);

        RegionPopulation pop = new RegionPopulation(labelImage, true);

        // crop window excludes the last row/column (x=9, y=9)
        BoundingBox bds = bb(0, 8, 0, 8, 0, 0);
        RegionPopulation cropped = pop.getCroppedRegionPopulation(bds, false, false);

        List<Region> result = cropped.getRegions();
        assertEquals(2, result.size());
        Map<Integer, Region> byLabel = result.stream().collect(Collectors.toMap(Region::getLabel, r -> r));

        // label 1 (fully inside the crop) is untouched
        assertBoundsEqual(bb(2, 4, 2, 4, 0, 0), byLabel.get(1).getBounds());
        assertEquals(rectVolume(2, 4, 2, 4, 0, 0), (long) byLabel.get(1).size());

        // label 2 must be clipped: it can never extend past the cropped image it was
        // reconstructed from, regardless of its original (7..9) extent
        BoundingBox region2Bounds = byLabel.get(2).getBounds();
        assertTrue("region must not exceed crop window in x", region2Bounds.xMax() <= bds.xMax());
        assertTrue("region must not exceed crop window in y", region2Bounds.yMax() <= bds.yMax());
        assertEquals(rectVolume(7, 8, 7, 8, 0, 0), (long) byLabel.get(2).size()); // 2x2, row/col 9 dropped

        // every region pixel from the cropped population lies within the crop window
        for (Region r : result) {
            r.loop((x, y, z) -> assertTrue(bds.containsWithOffset(x, y, z)));
        }
    }

    @Test
    public void testGetCroppedRegionPopulation_labelImageBased_noRegionsWhenCropMissesAll() {
        BoundingBox imgBounds = bb(0, 9, 0, 9, 0, 0);
        ImageByte labelImage = new ImageByte("labels", new SimpleImageProperties(imgBounds, 1, 1));
        for (int y = 0; y <= 2; ++y)
            for (int x = 0; x <= 2; ++x)
                labelImage.setPixelWithOffset(x, y, 0, 1);

        RegionPopulation pop = new RegionPopulation(labelImage, true);
        BoundingBox bds = bb(5, 9, 5, 9, 0, 0); // does not overlap the only region
        RegionPopulation cropped = pop.getCroppedRegionPopulation(bds, false, false);
        assertTrue(cropped.getRegions().isEmpty());
    }
}
