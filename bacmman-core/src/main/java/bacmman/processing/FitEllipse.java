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
import bacmman.image.ImageByte;
import bacmman.image.ImageInteger;
import bacmman.image.ImageMask;
import bacmman.image.wrappers.IJImageWrapper;
import ij.ImagePlus;
import ij.gui.Arrow;
import ij.gui.EllipseRoi;
import ij.gui.Roi;
import ij.process.ImageProcessor;

import java.awt.Color;

import org.scijava.vecmath.Vector2d;

/**
 *
 * @author Jean Ollion
 */
public class FitEllipse {
    static final double HALFPI = 1.5707963267949;
    
    /** X centroid */
    public double xCenter;

    /** X centroid */
    public double  yCenter;
    
    /** Length of major axis */
    public double major;
    
    /** Length of minor axis */
    public double minor;
    
    /** Angle in degrees */
    public double angle;
    
    /** Angle in radians */
    public double theta;
    
    /** Initialized by makeRoi() */
    public int[] xCoordinates;
    /** Initialized by makeRoi() */
    public int[] yCoordinates;
    /** Initialized by makeRoi() */
    public int nCoordinates = 0;

    
    private int bitCount;
    private double  xsum, ysum, x2sum, y2sum, xysum;
    private ImageMask mask;
    private int left, top, width, height;
    private double   n;
    private double   xm, ym;   //mean values
    private double   u20, u02, u11;  //central moments

    //private double pw, ph;
    private boolean record;
        public void fit(ImageMask mask) {
        this.mask = mask;
        left = 0;
        top = 0;
        width = mask.sizeX();
        height = mask.sizeY();
        getEllipseParam();
    }
    
    void getEllipseParam() {
        double    sqrtPi = 1.772453851;
        double    a11, a12, a22, m4, z, scale, tmp, xoffset, yoffset;
        double    RealAngle;

        if (mask==null) {
            major = (width*2) / sqrtPi;
            minor = (height*2) / sqrtPi; // * Info->PixelAspectRatio;
            angle = 0.0;
            theta = 0.0;
            if (major < minor) {
                tmp = major;
                major = minor;
                minor = tmp;
                angle = 90.0;
                theta = Math.PI/2.0;
            }
            xCenter = left + width / 2.0;
            yCenter = top + height / 2.0;
            return;
        }

        computeSums();
        getMoments();
        m4 = 4.0 * Math.abs(u02 * u20 - u11 * u11);
        if (m4 < 0.000001)
            m4 = 0.000001;
        a11 = u02 / m4;
        a12 = u11 / m4;
        a22 = u20 / m4;
        xoffset = xm;
        yoffset = ym;

        tmp = a11 - a22;
        if (tmp == 0.0)
            tmp = 0.000001;
        theta = 0.5 * Math.atan(2.0 * a12 / tmp);
        if (theta < 0.0)
            theta += HALFPI;
        if (a12 > 0.0)
            theta += HALFPI;
        else if (a12 == 0.0) {
            if (a22 > a11) {
                theta = 0.0;
                tmp = a22;
                a22 = a11;
                a11 = tmp;
            } else if (a11 != a22)
                theta = HALFPI;
        }
        tmp = Math.sin(theta);
        if (tmp == 0.0)
            tmp = 0.000001;
        z = a12 * Math.cos(theta) / tmp;
        major = Math.sqrt (1.0 / Math.abs(a22 + z));
        minor = Math.sqrt (1.0 / Math.abs(a11 - z));
        scale = Math.sqrt (bitCount / (Math.PI * major * minor)); //equalize areas
        major = major*scale*2.0;
        minor = minor*scale*2.0;
        angle = 180.0 * theta / Math.PI;
        if (angle == 180.0)
            angle = 0.0;
        if (major < minor) {
            tmp = major;
            major = minor;
            minor = tmp;
        }
        xCenter = left + xoffset + 0.5;
        yCenter = top + yoffset + 0.5;
    }

    void computeSums () {
        xsum = 0.0;
        ysum = 0.0;
        x2sum = 0.0;
        y2sum = 0.0;
        xysum = 0.0;
        int bitcountOfLine;
        double   xe, ye;
        int xSumOfLine;
        for (int y=0; y<height; y++) {
            bitcountOfLine = 0;
            xSumOfLine = 0;
            int offset = y*width;
            for (int x=0; x<width; x++) {
                if (mask.insideMask(offset+x, 0)) {
                    bitcountOfLine++;
                    xSumOfLine += x;
                    x2sum += x * x;
                }
            } 
            xsum += xSumOfLine;
            ysum += bitcountOfLine * y;
            ye = y;
            xe = xSumOfLine;
            xysum += xe*ye;
            y2sum += ye*ye*bitcountOfLine;
            bitCount += bitcountOfLine;
        }
    }

    void getMoments () {
        double   x1, y1, x2, y2, xy;

        if (bitCount == 0)
            return;

        x2sum += 0.08333333 * bitCount;
        y2sum += 0.08333333 * bitCount;
        n = bitCount;
        x1 = xsum/n;
        y1 = ysum / n;
        x2 = x2sum / n;
        y2 = y2sum / n;
        xy = xysum / n;
        xm = x1;
        ym = y1;
        u20 = x2 - (x1 * x1);
        u02 = y2 - (y1 * y1);
        u11 = xy - x1 * y1;
    }
    
    public ImageByte getEllipseMask() {
        double dx = major*Math.cos(theta)/2.0;
        double dy = - major*Math.sin(theta)/2.0;
        double x1 = xCenter - dx;
        double x2 = xCenter + dx;
        double y1 = yCenter - dy;
        double y2 = yCenter + dy;
        double aspectRatio = minor/major;
        Roi roi = new EllipseRoi(x1,y1,x2,y2,aspectRatio);
        ImageProcessor ip  = roi.getMask();
        ImagePlus imp = new ImagePlus("Ellipse Mask", ip);
        ImageByte res=  (ImageByte) IJImageWrapper.wrap(imp);
        res.translate(left-(int)((double)res.sizeX()/2d-xCenter), top-(int)((double)res.sizeY()/2d-yCenter), 0);
        // a point in this mask needs to be shifted by the offset value to correspond to the original mask
        //logger.debug("offsetX: {}, offsetY: {}", res.getBoundingBox().getxMin(), res.getBoundingBox().getyMin());
        return res;
    }

    
    public static EllipseFit2D fitEllipse2D(Region object) {
        FitEllipse fitter = new FitEllipse();
        ImageInteger<? extends ImageInteger> mask = object.getMaskAsImageInteger();
        if (mask.sizeZ()>1) mask = mask.getZPlane(mask.sizeZ()/2);
        
        fitter.fit(mask);
        // compute the error = nPixels outside the ROI / total pixels count
        //ImageByte b = fitter.getEllipseMask();
        //new IJImageDisplayer().showImage(b);
        //new IJImageDisplayer().showImage(mask);
        EllipseFit2D res = new EllipseFit2D();
        res.angleDegrees=fitter.angle;
        res.angleRadians=fitter.theta;
        res.major=fitter.major;
        res.minor=fitter.minor;
        res.xCenter=fitter.xCenter+object.getBounds().xMin();
        res.yCenter=fitter.yCenter+object.getBounds().yMin();
        
        //logger.debug("ellipse fit: angle: {}, major: {}, minor: {}, xCenter: {}, yCenter: {}", fitter.angle, fitter.major, fitter.minor, fitter.xCenter, fitter.yCenter);
        return res;
    }
    
    public static class EllipseFit2D {
        //double error;
        double angleDegrees, angleRadians, major, minor, xCenter, yCenter;
        public double[] getAlignement(EllipseFit2D other) {
            Vector2d cc = getCenterCenter(other, true);
            return new double[]{getAngle(cc, getVector()), getAngle(cc, other.getVector()) };
        }
        private static double getAngle(Vector2d v1, Vector2d v2) {
            double angle = regularizeAngle(v1.angle(v2));
            double sign = v1.angle(new Vector2d(-v2.y, v2.x));
            if (sign>Math.PI/2) return -angle;
            else return angle;
        }
        private Vector2d getCenterCenter(EllipseFit2D other, boolean normalize) {
            Vector2d centerToCenter = new Vector2d(xCenter-other.xCenter, yCenter-other.yCenter);
            if (normalize) centerToCenter.normalize();
            return centerToCenter;
        }
        public double getAspectRatio() {
            return major / minor;
        }
        public Vector2d getVector() {
            return new Vector2d(Math.cos(angleRadians), -Math.sin(angleRadians));
        }
        private static double regularizeAngle(double angleRad) {
            if (angleRad<0) return -regularizeAngle(-angleRad);
            if (angleRad>Math.PI/2 && angleRad<=Math.PI) return Math.PI-angleRad;
            else if (angleRad>Math.PI && angleRad<=Math.PI*3d/4d) return angleRad-Math.PI;
            else if (angleRad>Math.PI*3d/4d) return 2*Math.PI-angleRad;
            return angleRad;
        }
        public Roi getAxisRoi() {
            Vector2d axis = getVector();
            return new Arrow(xCenter, yCenter, xCenter+axis.x*major, yCenter+axis.y*major);
        }
        public Roi getCenterRoi(EllipseFit2D other) {
            Vector2d axis = getCenterCenter(other, false);
            Arrow res= new Arrow(xCenter, yCenter, xCenter-axis.x, yCenter-axis.y);
            res.setStrokeColor(Color.red);
            return res;
        }
    }
}
