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
package bacmman.plugins.plugins.transformations;

/**
    Image stabilizer plugin for ImageJ.

    This plugin stabilizes jittery image stacks using the
      Lucas-Kanade algorithm.

    Authors:  Kang Li (kangli AT cs.cmu.edu)
              Steven Kang (sskang AT andrew.cmu.edu)

    Requires: ImageJ 1.38q or later
              JRE 5.0 or later

    Installation:
      Download  Image_Stabilizer.java  to the  plugins  folder or  subfolder.
      Compile and run  it using Plugins/Compile  and Run. Restart  ImageJ and
      there will be a new "Image  Stabilizer" command in the Plugins menu  or
      its submenu.

    History:
        2008/02/07: First version
        2008/02/10: Optimized for speed using gradient pyramids
        2008/02/12: Performed further speed optimizations and bug fixes
        2008/02/14: Added support for affine transformation
        2008/03/15: Made user interface improvements
        2008/05/02: Added support for macro recordering 
                      (thanks to Christophe Leterrier AT univmed.fr)
        2009/01/11: Added support for logging transformation coefficients 
                    The stabilization can be interrupted by pressing ESC or by
                      closing the image 
        2009/01/20: Fixed a runtime error when Log_Transformation_Coefficients
                      is not selected (thanks to Nico Stuurman)
        2009/06/12: Fixed a bug that affected 32-bit float input images
                      (thanks to Derek Bailey)

    References:
      [1]  S.   Baker  and   I. Matthews,   "Lucas-Kanade  20   Years  On:  A
      Unifying  Framework," International Journal  of Computer Vision,   Vol.
      56, No.  3,  March, 2004, pp. 221-255.

      [2]  B.d.  Lucas  and  T.  Kanade,  "An  Iterative  Image  Registration
      Technique with an Application  to Stereo Vision  (IJCAI),"  Proceedings
      of  the 7th International Joint Conference  on Artificial  Intelligence
      (IJCAI '81), April, 1981, pp. 674-679.

*/

/**
Copyright (C) 2008-2009 Kang Li. All rights reserved.

Permission to use, copy, modify, and distribute this software for any purpose
without fee is hereby granted,  provided that this entire notice  is included
in all copies of any software which is or includes a copy or modification  of
this software  and in  all copies  of the  supporting documentation  for such
software. Any for profit use of this software is expressly forbidden  without
first obtaining the explicit consent of the author.

THIS  SOFTWARE IS  BEING PROVIDED  "AS IS",  WITHOUT ANY  EXPRESS OR  IMPLIED
WARRANTY.  IN PARTICULAR,  THE AUTHOR  DOES NOT  MAKE ANY  REPRESENTATION OR
WARRANTY OF ANY KIND CONCERNING  THE MERCHANTABILITY OF THIS SOFTWARE  OR ITS
FITNESS FOR ANY PARTICULAR PURPOSE.
*/

import java.lang.*;

import ij.*;
import ij.process.*;


public class ImageStabilizerCore {
    static final int TRANSLATION = 0;
    static final int AFFINE = 1;

    ImagePlus  imp = null;
    ImageStack stack = null;
    ImageStack stackOut = null;
    String     outputDir = null;
    boolean    stackVirtual = false;
    boolean    outputNewStack = false;
    int        transform = TRANSLATION;
    int        pyramidLevel = 1;
    int        maxIter = 200;
    double     tol = 1e-7;
    double     alpha = 0.9;

    public ImageStabilizerCore(int method, int pyramidLevel, double alpha, int maxIterations, double tolerance) {
        this.transform=method;
        this.pyramidLevel=pyramidLevel;
        this.alpha=alpha;
        this.maxIter=maxIterations;
        this.tol=tolerance;
    }

    public static ImageProcessor[][] initWorkspace(int width, int height, int pyramidLevel) {
        // workspaces
        ImageProcessor[] ipPyramid = { null, null, null, null, null };
        ImageProcessor[] ipRefPyramid = { null, null, null, null, null };

        ipPyramid[0] = new FloatProcessor(width, height);
        ipRefPyramid[0] = new FloatProcessor(width, height);

        if (pyramidLevel >= 1 && width >= 100 && height >= 100) {
            int width2 = width / 2;
            int height2 = height / 2;
            ipPyramid[1] = new FloatProcessor(width2, height2);
            ipRefPyramid[1] = new FloatProcessor(width2, height2);
            if (pyramidLevel >= 2 && width >= 200 && height >= 200) {
                int width4 = width / 4;
                int height4 = height / 4;
                ipPyramid[2] = new FloatProcessor(width4, height4);
                ipRefPyramid[2] = new FloatProcessor(width4, height4);
                if (pyramidLevel >= 3 && width >= 400 && height >= 400) {
                    int width8 = width / 8;
                    int height8 = height / 8;
                    ipPyramid[3] = new FloatProcessor(width8, height8);
                    ipRefPyramid[3] = new FloatProcessor(width8, height8);
                    if (pyramidLevel >= 4 && width >= 800 && height >= 800) {
                        int width16 = width / 16;
                        int height16 = height / 16;
                        ipPyramid[4] = new FloatProcessor(width16, height16);
                        ipRefPyramid[4] = new FloatProcessor(width16, height16);
                    }
                }
            }
        }
        return new ImageProcessor[][]{ipPyramid, ipRefPyramid};
    }
    
    public static double[][] estimateTranslation(ImageProcessor   ip,
                                   ImageProcessor   ipRef,
                                   ImageProcessor[] ipPyramid,
                                   ImageProcessor[] ipRefPyramid, boolean computeGradientForRef, 
                                   int              maxIter,
                                   double           tol, 
                                   Double[] estimateShift, 
                                   double[] outParameters)
    {
        double[][] wp = {{0.0}, {0.0}};
        
        if (estimateShift!=null) wp = new double[][]{{estimateShift[0]}, {estimateShift[1]}} ;
        
        // We operate on the gradient magnitude of the image
        //   rather than on the original pixel intensity.
        gradient(ipPyramid[0], ip); 
        if (computeGradientForRef) gradient(ipRefPyramid[0], ipRef);
        
        if (ipPyramid[4] != null && ipRefPyramid[4] != null) {
            resize(ipPyramid[4], ipPyramid[0]);
            resize(ipRefPyramid[4], ipRefPyramid[0]);
            wp = estimateTranslation(wp, ipPyramid[4], ipRefPyramid[4], maxIter, tol, outParameters);
            wp[0][0] *= 16;
            wp[1][0] *= 16;
        }

        if (ipPyramid[3] != null && ipRefPyramid[3] != null) {
            resize(ipPyramid[3], ipPyramid[0]);
            resize(ipRefPyramid[3], ipRefPyramid[0]);
            wp = estimateTranslation( wp, ipPyramid[3], ipRefPyramid[3], maxIter, tol, outParameters);
            wp[0][0] *=  8;
            wp[1][0] *=  8;
        }

        if (ipPyramid[2] != null && ipRefPyramid[2] != null) {
            resize(ipPyramid[2], ipPyramid[0]);
            resize(ipRefPyramid[2], ipRefPyramid[0]);
            wp = estimateTranslation(wp, ipPyramid[2], ipRefPyramid[2], maxIter, tol, outParameters);
            wp[0][0] *=  4;
            wp[1][0] *=  4;
        }

        if (ipPyramid[1] != null && ipRefPyramid[1] != null) {
            resize(ipPyramid[1], ipPyramid[0]);
            resize(ipRefPyramid[1], ipRefPyramid[0]);
            wp = estimateTranslation(wp, ipPyramid[1], ipRefPyramid[1], maxIter, tol, outParameters);
            wp[0][0] *=  2;
            wp[1][0] *=  2;
        }

        wp = estimateTranslation(wp, ipPyramid[0], ipRefPyramid[0], maxIter, tol, outParameters);

        return wp;
    }


    public static double[][] estimateTranslation(double[][]     wp,
                                   ImageProcessor ip,
                                   ImageProcessor ipRef,
                                   int            maxIter,
                                   double         tol, 
                                   double[] outParam)
    {
        float[] dxRef = dx(ipRef);
        float[] dyRef = dy(ipRef);

        ImageProcessor ipOut = ip.duplicate();

        double[] dp = { 0.0, 0.0 };

        double[][] bestWp = new double[2][1];
        bestWp[0][0] = wp[0][0];
        bestWp[1][0] = wp[1][0];

        double[][] d = { {1.0, 0.0, 0.0},
                         {0.0, 1.0, 0.0},
                         {0.0, 0.0, 1.0} };
        double[][] w = { {1.0, 0.0, 0.0},
                         {0.0, 1.0, 0.0},
                         {0.0, 0.0, 1.0} };

        double[][] h = new double[2][2];

        h[0][0] = dotSum(dxRef, dxRef);
        h[1][0] = dotSum(dxRef, dyRef);
        h[0][1] = dotSum(dyRef, dxRef);
        h[1][1] = dotSum(dyRef, dyRef);
        h = invert(h);

        double oldRmse = Double.MAX_VALUE;
        double minRmse = Double.MAX_VALUE;
        int iter=0;

        for (iter = 0; iter < maxIter; ++iter) {

            warpTranslation(ipOut, ip, wp);

            subtract(ipOut, ipRef);

            double rmse = rootMeanSquare(ipOut);

            if (iter > 0) {
                if (rmse < minRmse) {
                    bestWp[0][0] = wp[0][0];
                    bestWp[1][0] = wp[1][0];
                    minRmse      = rmse;
                }
                if (Math.abs((oldRmse - rmse) / (oldRmse + Double.MIN_VALUE)) < tol) {
                    break;
                }
                    
            }
            oldRmse = rmse;

            float[] error = (float[])ipOut.getPixels();

            dp[0] = dotSum(dxRef, error);
            dp[1] = dotSum(dyRef, error);

            dp = prod(h, dp);

            d[0][0] = 1.0; d[0][1] = 0.0; d[0][2] = dp[0];
            d[1][0] = 0.0; d[1][1] = 1.0; d[1][2] = dp[1];
            d[2][0] = 0.0; d[2][1] = 0.0; d[2][2] = 1.0;

            w[0][0] = 1.0; w[0][1] = 0.0; w[0][2] = wp[0][0];
            w[1][0] = 0.0; w[1][1] = 1.0; w[1][2] = wp[1][0];
            w[2][0] = 0.0; w[2][1] = 0.0; w[2][2] = 1.0;

            w = prod(w, invert(d));

            wp[0][0] = w[0][2];
            wp[1][0] = w[1][2];
        }
        if (outParam!=null) {
            outParam[0] = minRmse;
            outParam[1] = iter;
        }
        return bestWp;
    }
    
    
    public static void gradient(ImageProcessor ipOut, ImageProcessor ip) { // TODO multithread
        int width = ip.getWidth();
        int height = ip.getHeight();
        float[] pixels = (float[])ip.getPixels();
        float[] outPixels = (float[])ipOut.getPixels();

        for (int y = 1; y + 1 < height; ++y) {
            int offset = 1 + y * width;

            //
            // nw---n---ne
            //  |   |   |
            //  w---o---e
            //  |   |   |
            // sw---s---se
            //

            double p1 = 0f;
            double p2 = pixels[offset - width - 1]; // nw
            double p3 = pixels[offset - width];     // n
            double p4 = 0f;                         // ne
            double p5 = pixels[offset - 1];         // w
            double p6 = pixels[offset];             // o
            double p7 = 0f;                         // e
            double p8 = pixels[offset + width - 1]; // sw
            double p9 = pixels[offset + width];     // s

            for (int x = 1; x + 1 < width; ++x) {
                p1 = p2; p2 = p3; p3 = pixels[offset - width + 1];
                p4 = p5; p5 = p6; p6 = pixels[offset + 1];
                p7 = p8; p8 = p9; p9 = pixels[offset + width + 1];
                double a = p1 + 2 * p2 + p3 - p7 - 2 * p8 - p9;
                double b = p1 + 2 * p4 + p7 - p3 - 2 * p6 - p9;
                outPixels[offset++] = (float)Math.sqrt(a * a + b * b);
            }
        }
    }
    
    public static void copy(ImageProcessor ipOut, ImageProcessor ip) {
        if (ip.getWidth() != ipOut.getWidth()) throw new IllegalArgumentException("Images do not have same witdh");
        if (ip.getHeight() != ipOut.getHeight()) throw new IllegalArgumentException("Images do not have same height");
        float[] pixels = (float[])ip.getPixels();
        float[] outPixels = (float[])ipOut.getPixels();
        System.arraycopy(pixels, 0, outPixels, 0, outPixels.length);
    }


    public static void resize(ImageProcessor ipOut, ImageProcessor ip) {
        int widthOut = ipOut.getWidth();
        int heightOut = ipOut.getHeight();
        double xScale = ip.getWidth() / (double)widthOut;
        double yScale = ip.getHeight() / (double)heightOut;
        float[] pixelsOut = (float[])ipOut.getPixels();
        for (int i = 0, y = 0; y < heightOut; ++y) {
            double ys = y * yScale;
            for (int x = 0; x < widthOut; ++x) {
                pixelsOut[i++] = (float)ip.getInterpolatedPixel(x * xScale, ys);
            }
        }
    }
    
    
    public static double[] prod(double[][] m, double[] v) {
        int n = v.length;
        double[] out = new double[n];
        for (int j = 0; j < n; ++j) {
            out[j] = 0.0;
            for (int i = 0; i < n; ++i)
                out[j] = out[j] + m[j][i] * v[i];
        }
        return out;
    }


    public static double[][] prod(double[][] a, double[][] b) {
        double[][] out = new double[a.length][b[0].length];
        for (int i = 0; i < a.length; ++i) {
            for (int j = 0; j < b[i].length; ++j) {
                out[i][j] = 0.0;
                for (int k = 0; k < a[i].length; ++k)
                    out[i][j] = out[i][j] + a[i][k] * b[k][j];
            }
        }
        return out;
    }


    public static float[] dx(ImageProcessor ip) { //TODO multithread
        int width = ip.getWidth();
        int height = ip.getHeight();

        float[] pixels = (float[])ip.getPixels();
        float[] outPixels = new float[width * height];

        for (int y = 0; y < height; ++y) {
            // Take forward/backward difference on edges.
            outPixels[y * width] = (float)(pixels[y * width + 1] - pixels[y * width]);
            outPixels[y * width + width - 1] = (float)(pixels[y * width + width - 1]
                                                     - pixels[y * width + width - 2]);

            // Take central difference in interior.
            for (int x = 1; x + 1 < width; ++x) {
                outPixels[y * width + x] = (float)((pixels[y * width + x + 1] -
                                                    pixels[y * width + x - 1]) * 0.5);
            } // x
        } // y

        return outPixels;
    }


    public static float[] dy(ImageProcessor ip) {
        int width = ip.getWidth();
        int height = ip.getHeight();

        float[] pixels = (float[])ip.getPixels();
        float[] outPixels = new float[width * height];

        for (int x = 0; x < (int)width; ++x) {
            // Take forward/backward difference on edges.
            outPixels[x] = (float)(pixels[width + x] - pixels[x]);
            outPixels[(height - 1) * width + x] = (float)(pixels[width * (height - 1) + x]
                                                        - pixels[width * (height - 2) + x]);

            // Take central difference in interior.
            for (int y = 1; y + 1 < (int)height; ++y) {
                outPixels[y * width + x] = (float)((pixels[width * (y + 1) + x] -
                                                    pixels[width * (y - 1) + x]) * 0.5);
            } // y
        } // x

        return outPixels;
    }


    public static float[] dot(float[] p1, float[] p2) {
        int n = p1.length < p2.length ? p1.length : p2.length;
        float[] output = new float[n];
        for (int i = 0; i < n; ++i)
            output[i] = p1[i] * p2[i];
        return output;
    }


    public static double dotSum(float[] p1, float[] p2) {
        double sum = 0.0;
        int n = p1.length < p2.length ? p1.length : p2.length;
        for (int i = 0; i < n; ++i)
            sum += p1[i] * p2[i];
        return sum;
    }

    /**
        Gaussian elimination (required by invert).

        This Java program is part of the book, "An Introduction to Computational
        Physics, 2nd Edition,"  written by Tao  Pang and published  by Cambridge
        University Press on January 19, 2006.
    */
    public static void gaussian(double a[][], int index[]) {

        int n = index.length;
        double[] c = new double[n];

        // Initialize the index
        for (int i = 0; i < n; ++i) index[i] = i;

        // Find the rescaling factors, one from each row
        for (int i = 0; i < n; ++i) {
            double c1 = 0;
            for (int j = 0; j < n; ++j) {
                double c0 = Math.abs(a[i][j]);
                if (c0 > c1) c1 = c0;
            }
            c[i] = c1;
        }

        // Search the pivoting element from each column
        int k = 0;
        for (int j = 0; j < n-1; ++j) {
            double pi1 = 0;
            for (int i = j; i < n; ++i) {
                double pi0 = Math.abs(a[index[i]][j]);
                pi0 /= c[index[i]];
                if (pi0 > pi1) {
                    pi1 = pi0;
                    k = i;
                }
            }

            // Interchange rows according to the pivoting order
            int itmp = index[j];
            index[j] = index[k];
            index[k] = itmp;
            for (int i= j + 1; i < n; ++i) {
                double pj = a[index[i]][j] / a[index[j]][j];
                // Record pivoting ratios below the diagonal
                a[index[i]][j] = pj;
                // Modify other elements accordingly
                for (int l = j + 1; l < n; ++l)
                    a[index[i]][l] -= pj * a[index[j]][l];
            }
        }
    }


    /**
        Matrix inversion with the Gaussian elimination scheme.

        This Java program is part of the book, "An Introduction to Computational
        Physics, 2nd Edition,"  written by Tao  Pang and published  by Cambridge
        University Press on January 19, 2006.
    */
    public static double[][] invert(double a[][]) {
        int n = a.length;
        double[][] x = new double[n][n];
        double[][] b = new double[n][n];
        int index[] = new int[n];
        for (int i = 0; i < n; ++i) b[i][i] = 1;

            // Transform the matrix into an upper triangle
            gaussian(a, index);

            // Update the matrix b[i][j] with the ratios stored
            for (int i = 0; i < n - 1; ++i)
                for (int j = i + 1; j < n; ++j)
                    for (int k = 0; k < n; ++k)
                        b[index[j]][k] -= a[index[j]][i] * b[index[i]][k];

                    // Perform backward substitutions
                    for (int i = 0; i < n; ++i) {
                        x[n - 1][i] = b[index[n - 1]][i] / a[index[n - 1]][n - 1];
                        for (int j = n - 2; j >= 0; --j) {
                            x[j][i] = b[index[j]][i];
                            for (int k = j + 1; k < n; ++k) {
                                x[j][i] -= a[index[j]][k] * x[k][i];
                }
                x[j][i] /= a[index[j]][j];
            }
        }
        return x;
    }


    public static double rootMeanSquare(ImageProcessor ip) {
        double mean = 0.0;
        float[] pixels = (float[])ip.getPixels();
        for (int i = 0; i < pixels.length; ++i)
            mean += pixels[i] * pixels[i];
        mean /= pixels.length;
        return Math.sqrt(mean);
    }


    public static void combine(ImageProcessor ipOut, ImageProcessor ip, double alpha) {
        float[] pixels = (float[])ip.getPixels();
        float[] outPixels = (float[])ipOut.getPixels();
        double beta = 1.0 - alpha;
        for (int i = 0; i < pixels.length; ++i) {
            if (pixels[i] != 0)
                outPixels[i] = (float)(alpha * outPixels[i] + beta * pixels[i]);
        }
    }


    public static void subtract(ImageProcessor ipOut, ImageProcessor ip) {
        float[] pixels = (float[])ip.getPixels();
        float[] outPixels = (float[])ipOut.getPixels();
        for (int i = 0; i < pixels.length; ++i)
            outPixels[i] = outPixels[i] - pixels[i];
    }

    public static void warpTranslation(ImageProcessor ipOut,
                         ImageProcessor ip,
                         double[][]     wp)
    {
        float[] outPixels = (float[])ipOut.getPixels();
        int width = ipOut.getWidth();
        int height = ipOut.getHeight();
        for (int p = 0, y = 0; y < height; ++y) {
            for (int x = 0; x < width; ++x) {
                double xx = x + wp[0][0];
                double yy = y + wp[1][0];
                outPixels[p] = (float)ip.getInterpolatedPixel(xx, yy);
                ++p;
            } // x
        } // y
    }
}