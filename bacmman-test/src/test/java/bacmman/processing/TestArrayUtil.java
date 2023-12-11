/* 
 * Copyright (C) 2018 Jean Ollion
 *
 * This File is part of BACMMAN
 *
 * BACMMAN is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * BACMMAN is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with BACMMAN.  If not, see <http://www.gnu.org/licenses/>.
 */
package bacmman.processing;

import static org.junit.Assert.assertArrayEquals;

/**
 *
 * @author Jean Ollion
 */
public class TestArrayUtil {
    

    //@Test
    public void testRegionalExtrema() {
        float[] values = new float[]{0, 0, 0, 0, 1, 2, 3, 4, 4, 3, 2, 3, 4, 3, 5};
        int[] localMax1 = new int[]{1, 7, 12, 14};
        int[] localMax2 = new int[]{0, 7, 14};
        int[] localMin1 = new int[]{1, 10, 13};
        int[] localMin3 = new int[]{1, 10};
        //logger.debug("local max 1: {}, local max 2: {}, local min 1: {} local min2: {}", ArrayUtil.getRegionalExtrema(values, 1, true), ArrayUtil.getRegionalExtrema(values, 2, true), ArrayUtil.getRegionalExtrema(values, 1, false), ArrayUtil.getRegionalExtrema(values, 3, false));
        //assertArrayEquals("local max 1", localMax1, ArrayUtil.getRegionalExtrema(values, 1, true));
        //assertArrayEquals("local max 2", localMax2, ArrayUtil.getRegionalExtrema(values, 2, true));
        //assertArrayEquals("local min 1", localMin1, ArrayUtil.getRegionalExtrema(values, 1, false));
        //assertArrayEquals("local min 1", localMin3, ArrayUtil.getRegionalExtrema(values, 3, false));
    }
}
