package bacmman.image;

import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PredicateMaskTest {
    @Test
    public void OrMaskTest() {
        ImageByte m1 = new ImageByte("", 10, 10, 10);
        ImageByte m2 = new ImageByte("", 5, 4, 3).translate(5,6,7);
        m2.setPixelWithOffset(9, 9, 9, 1);
        m1.setPixelWithOffset(0, 0, 0, 1);
        assertTrue("m1", m1.insideMaskWithOffset(0, 0, 0));
        assertTrue("m2", m2.insideMaskWithOffset(9, 9, 9));
        ImageMask orMask = PredicateMask.or(m1, m2);
        assertTrue("or1", orMask.insideMaskWithOffset(0, 0, 0));
        assertTrue("or2", orMask.insideMaskWithOffset(9, 9, 9));
        assertTrue("orXY", orMask.insideMask(99, 9));

        ImageByte m3 = new ImageByte("", 5, 4, 1).translate(5,6,0);
        m3.setPixelWithOffset(9, 9, 0, 1);
        ImageMask orMask2 = PredicateMask.or(m1, m3);
        assertFalse("or2D1", orMask2.insideMaskWithOffset(9, 9, 9));
        ImageMask orMask2D = PredicateMask.or(m1, new ImageMask2D(m3));
        assertTrue("or2D2", orMask2D.insideMaskWithOffset(9, 9, 9));
    }

    @Test
    public void AndMaskTest() {
        ImageByte m1 = new ImageByte("", 10, 10, 10);
        ImageByte m2 = new ImageByte("", 5, 4, 3).translate(5,6,7);
        m2.setPixelWithOffset(8, 8, 8, 1);
        m1.setPixelWithOffset(8, 8, 8, 1);
        ImageMask andMask = PredicateMask.and(m1, m2);
        assertTrue("and", andMask.insideMaskWithOffset(8, 8, 8));
    }

    @Test
    public void AndNotMaskTest() {
        ImageByte m1 = new ImageByte("", 10, 10, 10);
        ImageByte m2 = new ImageByte("", 5, 4, 3).translate(5,6,7);
        m2.setPixelWithOffset(8, 8, 8, 1);
        m1.setPixelWithOffset(8, 8, 8, 1);
        ImageMask andNotMask = PredicateMask.andNot(m1, m2);
        assertFalse("andNot", andNotMask.insideMaskWithOffset(8, 8, 8));
    }
    @Test
    public void XorMaskTest() {
        ImageByte m1 = new ImageByte("", 10, 10, 10);
        ImageByte m2 = new ImageByte("", 5, 4, 3).translate(5,6,7);
        m2.setPixelWithOffset(8, 8, 8, 1);
        m1.setPixelWithOffset(8, 8, 8, 1);
        ImageMask andMask = PredicateMask.xor(m1, m2);
        assertFalse("xor", andMask.insideMaskWithOffset(8, 8, 8));
    }
}
