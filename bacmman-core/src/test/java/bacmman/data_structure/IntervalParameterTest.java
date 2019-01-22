package bacmman.data_structure;

import bacmman.configuration.parameters.IntervalParameter;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public class IntervalParameterTest {
    @Test
    public void testIntervalParameter() {
        IntervalParameter ip = new IntervalParameter("test", 2, -1, 10, 1, 2, 3, 4);
        ip.setValues(-2, -2, 3, 11);
        assertArrayEquals(new double[]{-1, -1, 3, 10}, ip.getValuesAsDouble(), 1E-10);
        ip.setValues(-1, 10, 13, 15);
        assertArrayEquals(new double[]{-1, 10, 10, 10}, ip.getValuesAsDouble(), 1E-10);
        ip.setValues(-1, 5, 4, 9);
        assertArrayEquals(new double[]{-1, 4, 5, 9}, ip.getValuesAsDouble(), 1E-10);
        ip.setValue(6, 1);
        assertArrayEquals(new double[]{-1, 5, 5, 9}, ip.getValuesAsDouble(), 1E-10);
        ip.setValue(4, 2);
        assertArrayEquals(new double[]{-1, 5, 5, 9}, ip.getValuesAsDouble(), 1E-10);
    }

}
