package bacmman.data_structure;

import bacmman.data_structure.region_container.RegionContainer;
import bacmman.data_structure.region_container.RegionContainerIjRoi;
import bacmman.image.ImageByte;
import bacmman.image.ImageMask;
import bacmman.image.SimpleBoundingBox;
import bacmman.image.SimpleImageProperties;
import org.json.simple.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class RegionContainerIjRoiTest {

    // Regression: empty interior z-slices must not shift the following slices on ROI save/reload.
    @Test
    public void testRoiSerializationPreservesEmptyZSlices() {
        int zMin = 3;
        // mask spanning absolute z [3..7]; filled L-shape on absolute z = 3, 6, 7; empty at z = 4, 5.
        // an L (non-rectangular) ensures the ROI carries a real pixel mask rather than a plain Rectangle.
        ImageByte mask = new ImageByte("", new SimpleImageProperties(new SimpleBoundingBox(0, 9, 0, 9, zMin, zMin + 4), 1, 1));
        for (int z : new int[]{3, 6, 7})
            for (int y = 2; y < 8; ++y)
                for (int x = 2; x < 8; ++x)
                    if (x < 5 || y < 5) mask.setPixelWithOffset(x, y, z, 1); // 6x6 block minus bottom-right 3x3

        Region region = new Region(mask, 1, false);
        SegmentedObject so = new SegmentedObject(0, 0, 0, region, null);

        // encode: mask -> ROI -> JSON
        JSONObject json = new RegionContainerIjRoi(so).toJSON();

        // decode in a fresh container, as on reload from the DB: JSON -> ROI -> mask
        RegionContainer rc = RegionContainer.createFromJSON(so, json);
        ImageMask reloaded = rc.getRegion().getMask();

        // each filled slice must keep its absolute z, each empty slice must stay empty
        assertSameMask(mask, reloaded, zMin, zMin + 4);
    }

    // Regression: a rectangular slice smaller than the object's bounds must not be filled to the full bounds.
    @Test
    public void testRoiSerializationRectangleSliceNotFilledToBounds() {
        // object's XY bounds span [1..7]; z=0 holds a small 2x2 rectangle, z=1 a separate 3x3 rectangle.
        // a filled rectangle becomes a plain Rectangle ROI (getMask()==null), exercising the toMask rectangle branch.
        ImageByte mask = new ImageByte("", new SimpleImageProperties(new SimpleBoundingBox(0, 9, 0, 9, 0, 1), 1, 1));
        for (int y = 1; y < 3; ++y) for (int x = 1; x < 3; ++x) mask.setPixelWithOffset(x, y, 0, 1);
        for (int y = 5; y < 8; ++y) for (int x = 5; x < 8; ++x) mask.setPixelWithOffset(x, y, 1, 1);

        Region region = new Region(mask, 1, false);
        SegmentedObject so = new SegmentedObject(0, 0, 0, region, null);
        JSONObject json = new RegionContainerIjRoi(so).toJSON();
        RegionContainer rc = RegionContainer.createFromJSON(so, json);
        ImageMask reloaded = rc.getRegion().getMask();

        assertSameMask(mask, reloaded, 0, 1);
    }

    private static void assertSameMask(ImageMask expected, ImageMask actual, int zMin, int zMax) {
        for (int z = zMin; z <= zMax; ++z) {
            for (int y = 0; y < 10; ++y) {
                for (int x = 0; x < 10; ++x) {
                    boolean e = expected.containsWithOffset(x, y, z) && expected.insideMaskWithOffset(x, y, z);
                    boolean a = actual.containsWithOffset(x, y, z) && actual.insideMaskWithOffset(x, y, z);
                    assertEquals("mismatch at x=" + x + " y=" + y + " z=" + z, e, a);
                }
            }
        }
    }
}
