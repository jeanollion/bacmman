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

import bacmman.image.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/* The author of this software is Christopher Philip Mauer.  Copyright (c) 2003.
Permission to use, copy, modify, and distribute this software for any purpose 
without fee is hereby granted, provided that this entire notice is included in 
all copies of any software which is or includes a copy or modification of this 
software and in all copies of the supporting documentation for such software.
Any for profit use of this software is expressly forbidden without first
obtaining the explicit consent of the author. 
THIS SOFTWARE IS BEING PROVIDED "AS IS", WITHOUT ANY EXPRESS OR IMPLIED WARRANTY. 
IN PARTICULAR, THE AUTHOR DOES NOT MAKE ANY REPRESENTATION OR WARRANTY 
OF ANY KIND CONCERNING THE MERCHANTABILITY OF THIS SOFTWARE OR ITS FITNESS FOR ANY 
PARTICULAR PURPOSE. 
*/
/* This PlugInFilter implements a recursive prediction/correction 
algorithm based on the Kalman Filter.  The application for which
it was designed is cleaning up timelapse image streams.  It operates 
in linear space by filtering a previously opened stack of images and
producing a new filtered stack.
													Christopher Philip Mauer 
	cpmauer@northwestern.edu
*/
public class KalmanFilter { // implements Filter
   private static double defpercentvar = 0.05;
   private static double defgain = 0.8;;
   public static final Logger logger = LoggerFactory.getLogger(KalmanFilter.class);

   
   
    public static List<Image> run (List<Image> images, double percentvar, double gain) {
        if (images.size()<=1)
                {logger.error("Stack required"); return null;}
        if(percentvar>1.0||gain>1.0||percentvar<0.0||gain<0.0){
                logger.error("Invalid parameters"); 
                percentvar = defpercentvar;
                gain = defgain;
        }
        return filter(images, percentvar, gain);
    }

    public static List<Image> filter(List<Image> images, double percentvar, double gain) {
        Image image = images.get(0);
        int width = image.sizeX();
        int height = image.sizeY();
        int dimension = width*height;
        int stacksize = images.size();
        double[] stackslice;
        double[] noisevar = new double[dimension];
        double[] predicted;
        double[] predictedvar;
        double[] observed;
        double[] Kalman = new double[dimension];
        double[] corrected = new double[dimension];
        double[] correctedvar = new double[dimension];

        for (int i=0; i<dimension; ++i)
                noisevar[i] = percentvar;
        predicted = toDouble(image);
        predictedvar = noisevar;
        List<Image> res = new ArrayList<>(images.size());
        res.add(image);
        for(int i=1; i<stacksize; ++i) {
                stackslice = toDouble(images.get(i));
                observed = Arrays.copyOf(stackslice, stackslice.length);
                for(int k=0;k<Kalman.length;++k)
                        Kalman[k] = predictedvar[k]/(predictedvar[k]+noisevar[k]);
                for(int k=0;k<corrected.length;++k)
                        corrected[k] = gain*predicted[k]+(1.0-gain)*observed[k]+Kalman[k]*(observed[k] - predicted[k]);
                for(int k=0;k<correctedvar.length;++k)
                        correctedvar[k] = predictedvar[k]*(1.0 - Kalman[k]);
                predictedvar = correctedvar;
                predicted = corrected;
                res.add(fromDouble(corrected, images.get(i), width));
        }
        return res;
    }

    public static Image fromDouble(double[] array, Image type, int sizeX) {
        ImageDouble im = new ImageDouble("", sizeX, array);
        return TypeConverter.convert(im, Image.copyType(type), false);
    }

    public static double[] toDouble(Image image) {
        ImageDouble im = TypeConverter.toDouble(image, null, false);
        return im.getPixelArray()[0];
    }
    /*
    @Override
    public Image applyTransformation(int channelIdx, int timePoint, Image image) {
        throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
    }

    @Override
    public Parameter[] getParameters() {
        throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
    }
    boolean testMode;
    @Override public void setTestMode(boolean testMode) {this.testMode=testMode;}
   
   */
}
