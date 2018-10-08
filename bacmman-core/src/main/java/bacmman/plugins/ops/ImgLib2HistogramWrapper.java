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
package bacmman.plugins.ops;

import bacmman.image.Histogram;

import java.lang.reflect.Field;
import net.imglib2.histogram.BinMapper1d;
import net.imglib2.histogram.DiscreteFrequencyDistribution;
import net.imglib2.histogram.Histogram1d;
import net.imglib2.histogram.Real1dBinMapper;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.real.FloatType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author Jean Ollion
 */
public class ImgLib2HistogramWrapper {
    public static final Logger logger = LoggerFactory.getLogger(ImgLib2HistogramWrapper.class);
    private static DiscreteFrequencyDistribution DFDfromHisto(Histogram histo) {
        DiscreteFrequencyDistribution res= new DiscreteFrequencyDistribution(new long[]{histo.data.length});
        long[] pos = new long[1];
        for (int i = 0; i<histo.data.length; ++i) {
            pos[0] = i;
            res.setFrequency(pos, histo.data[i]);
        }
        return res;
    }
    private static <T extends RealType< T >> BinMapper1d<T> binMapperfromHisto(Histogram histo) {
        return new Real1dBinMapper(histo.min, histo.getMaxValue(), histo.data.length, false);
    }
    public static <T extends RealType<T>> Histogram1d<T> wrap(Histogram histo) {
        try {
            Histogram1d res =  new Histogram1d(binMapperfromHisto(histo));
            Field dfdF = Histogram1d.class.getDeclaredField("distrib");
            dfdF.setAccessible(true);
            dfdF.set(res, DFDfromHisto(histo));
            Field fv = Histogram1d.class.getDeclaredField("firstValue");
            fv.setAccessible(true);
            fv.set(res, new FloatType((float)histo.min));
            return res;
        } catch (NoSuchFieldException | SecurityException | IllegalArgumentException | IllegalAccessException ex) {
            logger.debug("Error while converting histogram", ex);
        }
        return null;
    }
}
