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

import bacmman.data_structure.Region;
import bacmman.data_structure.Voxel;
import bacmman.image.Image;
import bacmman.image.wrappers.ImgLib2ImageWrapper;

import java.util.ArrayList;
import java.util.List;

import bacmman.utils.geom.Point;
import net.imglib2.algorithm.localextrema.RefinedPeak;
import net.imglib2.algorithm.localextrema.SubpixelLocalization;
import net.imglib2.img.Img;
import net.imglib2.type.numeric.RealType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import bacmman.utils.Utils;

/**
 *
 * @author Jean Ollion
 */
public class SubPixelLocalizator {
    public static boolean debug;
    public final static Logger logger = LoggerFactory.getLogger(SubPixelLocalizator.class);
    public static List<Point> getPeaks(Image img, List<Region> objects) {
        List<Point> peaks = new ArrayList<>(objects.size());
        for (Region o : objects) { // get max value within map
            final double[] max = new double[]{Double.NEGATIVE_INFINITY};
            final int[] maxV= new int[3];
            o.loop((x, y, z) -> {
                double value = img.getPixel(x, y, z);
                if (value>max[0]) {
                    max[0] = value;
                    maxV[0] = x;
                    maxV[1] = y;
                    maxV[2] = z;

                }
            });
            Point p = img.sizeZ()>1 ? new Point(maxV[0], maxV[1], maxV[2]) : new Point(maxV[0], maxV[1]);
            if (o.isAbsoluteLandMark()) p.translate(o.getBounds().duplicate().reverseOffset());
            peaks.add(p);
        }
        return peaks;
    }
    public static ArrayList< RefinedPeak< Point >> getSubLocPeaks(Image img, List<Point> peaks) {
        Img source = ImgLib2ImageWrapper.getImage(img);
        final SubpixelLocalization< Point, ? extends RealType > spl = new SubpixelLocalization<>( source.numDimensions() );
        //logger.debug("source sizeZ: {}, numDim: {}", img.getSizeZ(), source.numDimensions());
        spl.setNumThreads( 1 );
        spl.setReturnInvalidPeaks( true );
        spl.setCanMoveOutside( false );
        spl.setAllowMaximaTolerance( true );
        spl.setMaxNumMoves( 10 );
        if (img.sizeZ()>1) spl.setAllowedToMoveInDim(new boolean[]{true, true, false});
        else spl.setAllowedToMoveInDim(new boolean[]{true, true});
        return spl.process( peaks, source, source );
    }
    
    public static void setSubPixelCenter(Image img, List<Region> objects, boolean setQuality) {
        if (objects.isEmpty()) return;
        List<Point> peaks = getPeaks(img, objects);
        List<RefinedPeak< Point >> refined = getSubLocPeaks(img, peaks);
        if (debug) {
            logger.debug("num peaks: {}, refined: {}", peaks.size(), refined.size());
            logger.debug("peaks: {}", Utils.toStringList(peaks, p->p.toString()));
            logger.debug("refined: {}", Utils.toStringList(refined, p->p.getValue()==0? "NaN" : p.toString()));
            //logger.debug("refined: {}", Utils.toStringList(refined, p->"["+p.getDoublePosition(0)+"; "+p.getDoublePosition(1)+(img.getSizeZ()>1? ";"+p.getDoublePosition(2): "")+"]"));
        }
        for (RefinedPeak< Point > r : refined) {
            Region o = objects.get(peaks.indexOf(r.getOriginalPeak()));
            double originalValue = img.getPixel(r.getOriginalPeak().getFloatPosition(0), r.getOriginalPeak().getFloatPosition(1), img.sizeZ()>1 ? r.getOriginalPeak().getFloatPosition(2):0);
            boolean useOriginalPeak = r.getValue()==0
                    || !o.contains(new Voxel(Math.round(r.getFloatPosition(0)), Math.round(r.getFloatPosition(1)), img.sizeZ()>1?Math.round(r.getFloatPosition(2)) : o.getBounds().zMin()));
            //logger.debug("use {} peak: original: {}, refined: {}, original value: {} refined: {} (fitted : {})", useOriginalPeak?"original":"refined", r.getOriginalPeak(), new bacmman.utils.geom.Point(r.getFloatPosition(0), r.getFloatPosition(1), img.sizeZ()>1?r.getFloatPosition(2):0), originalValue, value, r.getValue());
            float[] position= new float[img.sizeZ()>1?3 : 2];
            position[0] = useOriginalPeak? r.getOriginalPeak().getFloatPosition(0) : r.getFloatPosition(0);
            position[1] = useOriginalPeak? r.getOriginalPeak().getFloatPosition(1) : r.getFloatPosition(1);
            if (img.sizeZ()>1) position[2] =r.getOriginalPeak().getFloatPosition(2);
            o.setCenter(new bacmman.utils.geom.Point(position));
            if (setQuality) {
                if (useOriginalPeak) o.setQuality(originalValue);
                else o.setQuality(r.getValue());
            }
        }
    }
   
}
