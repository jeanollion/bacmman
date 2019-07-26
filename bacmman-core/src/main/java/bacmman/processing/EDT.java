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

import bacmman.image.ImageFloat;
import bacmman.image.ImageMask;
import bacmman.utils.ThreadRunner;
import org.slf4j.LoggerFactory;

/* 
Modified from: in  order to take in acount z-anisotropy & borders
Bob Dougherty 8/8/2006
Saito-Toriwaki algorithm for Euclidian Distance Transformation.
Direct application of Algorithm 1.
Version S1A: lower memory usage.
Version S1A.1 A fixed indexing bug for 666-bin data set
Version S1A.2 Aug. 9, 2006.  Changed noResult value.
Version S1B Aug. 9, 2006.  Faster.
Version S1B.1 Sept. 6, 2006.  Changed comments.
Version S1C Oct. 1, 2006.  Option for inverse case.
Fixed inverse behavior in y and z directions.
Version D July 30, 2007.  Multithread processing for step 2.

This version assumes the input stack is already in memory, 8-bit, and
outputs to a new 32-bit stack.  Versions that are more stingy with memory
may be forthcoming.

License:
Copyright (c) 2006, OptiNav, Inc.
All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions
are met:

Redistributions of source code must retain the above copyright
notice, this list of conditions and the following disclaimer.
Redistributions in binary form must reproduce the above copyright
notice, this list of conditions and the following disclaimer in the
documentation and/or other materials provided with the distribution.
Neither the name of OptiNav, Inc. nor the names of its contributors
may be used to endorse or promote products derived from this software
without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
"AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
A PARTICULAR PURPOSE ARE DISCLAIMED.  IN NO EVENT SHALL THE COPYRIGHT OWNER OR
CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

 */
public class EDT {
    public final OutOfBoundPolicy outOfBoundPolicy = new OutOfBoundPolicy();
    public final static org.slf4j.Logger logger = LoggerFactory.getLogger(EDT.class);
    public static ImageFloat transform(ImageMask mask, boolean insideMask, double scaleXY, double scaleZ, boolean multithread) {
        return new EDT().run(mask, insideMask, scaleXY, scaleZ, multithread);
    }

    /**
     * In case EDT is computed inside the mask, this defines whether out-of-bound is background or foreground for each borders
     * @return
     */
    public OutOfBoundPolicy outOfBoundPolicy() {return outOfBoundPolicy;}

    public class OutOfBoundPolicy {
        public final boolean[] outOfBoundIsBck =new boolean[]{true, true, true, true, true, true}; // xMin, xMax, yMin, yMax, zMin, zMax
        public OutOfBoundPolicy setX(boolean minIsBck, boolean maxIsBck) {
            outOfBoundIsBck[0]=minIsBck;
            outOfBoundIsBck[1]=maxIsBck;
            return this;
        }
        public OutOfBoundPolicy setY(boolean minIsBck, boolean maxIsBck) {
            outOfBoundIsBck[2]=minIsBck;
            outOfBoundIsBck[3]=maxIsBck;
            return this;
        }
        public OutOfBoundPolicy setZ(boolean minIsBck, boolean maxIsBck) {
            outOfBoundIsBck[4]=minIsBck;
            outOfBoundIsBck[5]=maxIsBck;
            return this;
        }
        public EDT edt() {
            return EDT.this;
        }
        public int getMinDistBorderX(int i, int size) {
            if (outOfBoundIsBck[0] && outOfBoundIsBck[1]) return Math.min(i+1, size-i);
            else if (outOfBoundIsBck[0]) return i+1;
            else return size-i;
        }
        public int getMinDistBorderY(int i, int size) {
            if (outOfBoundIsBck[2] && outOfBoundIsBck[3]) return Math.min(i+1, size-i);
            else if (outOfBoundIsBck[2]) return i+1;
            else return size-i;
        }
        public int getMinDistBorderZ(int i, int size) {
            if (outOfBoundIsBck[4] && outOfBoundIsBck[5]) return Math.min(i+1, size-i);
            else if (outOfBoundIsBck[4]) return i+1;
            else return size-i;
        }
    }
    public ImageFloat run(ImageMask mask, boolean insideMask, double scaleXY, double scaleZ , boolean multithread) {
        int w = mask.sizeX();
        int h = mask.sizeY();
        int d = mask.sizeZ();
        double scale=mask.sizeZ()>1?scaleZ/scaleXY:1;
        int nThreads = multithread ? ThreadRunner.getMaxCPUs() : 1;
        ImageFloat res = new ImageFloat("EDT of: "+mask.getName(), mask);
        float[][] s = res.getPixelArray();
        float[] sk;
        //Transformation 1.  Use s to store g.
        Step1Thread[] s1t = new Step1Thread[nThreads];
        for (int thread = 0; thread < nThreads; thread++) {
            s1t[thread] = new Step1Thread(thread, nThreads, w, h, d, s, mask, insideMask);
            s1t[thread].start();
        }
        try {
            for (int thread = 0; thread < nThreads; thread++) {
                s1t[thread].join();
            }
        } catch (InterruptedException ie) {
            throw new RuntimeException(ie);
        }
        //Transformation 2.  g (in s) -> h (in s)
        Step2Thread[] s2t = new Step2Thread[nThreads];
        for (int thread = 0; thread < nThreads; thread++) {
            s2t[thread] = new Step2Thread(thread, nThreads, w, h, d, insideMask, s);
            s2t[thread].start();
        }
        try {
            for (int thread = 0; thread < nThreads; thread++) {
                s2t[thread].join();
            }
        } catch (InterruptedException ie) {
            throw new RuntimeException(ie);
        }
        if (mask.sizeZ()>1) { //3D case
            //Transformation 3. h (in s) -> s
            Step3Thread[] s3t = new Step3Thread[nThreads];
            for (int thread = 0; thread < nThreads; thread++) {
                s3t[thread] = new Step3Thread(thread, nThreads, w, h, d, s, mask, insideMask, (float)scale);
                s3t[thread].start();
            }
            try {
                for (int thread = 0; thread < nThreads; thread++) {
                    s3t[thread].join();
                }
            } catch (InterruptedException ie) {
                throw new RuntimeException(ie);
            }
        }
        //Find the largest distance for scaling
        //Also fill in the background values.
        float distMax = 0;
        int wh = w * h;
        float dist;
        for (int k = 0; k < d; k++) {
            sk = s[k];
            for (int ind = 0; ind < wh; ind++) {
                if (mask.insideMask(ind, k)!=insideMask) { //xor
                    sk[ind] = 0;
                } else {
                    dist = (float) (Math.sqrt(sk[ind]) * scaleXY);
                    sk[ind] = dist;
                    distMax = (dist > distMax) ? dist : distMax;
                }
            }
        }
        //res.setMinAndMax(0, distMax);
        return res;
    }

    class Step1Thread extends Thread {
        int thread, nThreads, w, h, d;
        float[][] s;
        ImageMask mask;
        boolean insideMask;
        
        public Step1Thread(int thread, int nThreads, int w, int h, int d, float[][] s, ImageMask mask, boolean insideMask) {
            this.thread = thread;
            this.nThreads = nThreads;
            this.w = w;
            this.h = h;
            this.d = d;
            this.mask = mask;
            this.insideMask=insideMask;
            this.s = s;
        }

        public void run() {
            float[] sk;
            int n = w;
            if (h > n) n = h;
            if (d > n) n = d;
            
            int noResult = 3 * (n + 1) * (n + 1);
            boolean[] background = new boolean[w];
            float test, min;
            for (int k = thread; k < d; k += nThreads) {
                sk = s[k];
                for (int j = 0; j < h; j++) {
                    if (insideMask) for (int i = 0; i < w; i++) background[i] = !mask.insideMask(i + w * j, k);
                    else for (int i = 0; i < w; i++) background[i] = mask.insideMask(i + w * j, k);
                    
                    for (int i = 0; i < w; i++) {
                        if (insideMask) { // si insideMask: distance minimale = distance au bord le plus proche + 1
                            //min = Math.min(i+1, w-i);
                            min = outOfBoundPolicy.getMinDistBorderX(i,w);
                            min*=min;
                        } else min = noResult;
                        for (int x = i; x < w; x++) {
                            if (background[x]) {
                                test = i - x;
                                test *= test;
                                if (test < min) {
                                    min = test;
                                }
                                break;
                            }
                        }
                        for (int x = i - 1; x >= 0; x--) {
                            if (background[x]) {
                                test = i - x;
                                test *= test;
                                if (test < min) {
                                    min = test;
                                }
                                break;
                            }
                        }
                        sk[i + w * j] = min;
                    }
                }
            }
        }//run
    }//Step1Thread

    class Step2Thread extends Thread {

        int thread, nThreads, w, h, d;
        float[][] s;
        boolean insideMask;
        public Step2Thread(int thread, int nThreads, int w, int h, int d, boolean insideMask, float[][] s) {
            this.thread = thread;
            this.nThreads = nThreads;
            this.w = w;
            this.h = h;
            this.d = d;
            this.s = s;
            this.insideMask=insideMask;
        }

        public void run() {
            float[] sk;
            int n = w;
            if (h > n) n = h;
            if (d > n) n = d;
            int noResult = 3 * (n + 1) * (n + 1);
            float[] tempInt = new float[h];
            float[] tempS = new float[h];
            boolean nonempty;
            float test, min;
            int delta;
            for (int k = thread; k < d; k += nThreads) {
                sk = s[k];
                for (int i = 0; i < w; i++) {
                    nonempty = false;
                    for (int j = 0; j < h; j++) {
                        tempS[j] = sk[i + w * j];
                        if (tempS[j] > 0) {
                            nonempty = true;
                        }
                    }
                    if (nonempty) {
                        for (int j = 0; j < h; j++) {
                            if (insideMask) {
                                //min = Math.min(j+1, h-j);
                                min = outOfBoundPolicy.getMinDistBorderY(j,h);
                                min*=min;
                            } else min = noResult;
                            delta = j;
                            for (int y = 0; y < h; y++) {
                                test = tempS[y] + delta * delta--;
                                if (test < min) {
                                    min = test;
                                }
                            }
                            tempInt[j] = min;
                        }
                        for (int j = 0; j < h; j++) {
                            sk[i + w * j] = tempInt[j];
                        }
                    }
                }
            }
        }//run
    }//Step2Thread	

    class Step3Thread extends Thread {
        int thread, nThreads, w, h, d;
        float[][] s;
        ImageMask mask;
        float scaleZ;
        boolean insideMask;
        
        public Step3Thread(int thread, int nThreads, int w, int h, int d, float[][] s, ImageMask mask, boolean insideMask, float scaleZ) {
            this.thread = thread;
            this.nThreads = nThreads;
            this.w = w;
            this.h = h;
            this.d = d;
            this.s = s;
            this.mask = mask;
            this.scaleZ = scaleZ * scaleZ;
            this.insideMask=insideMask;
        }

        public void run() {
            int zStart, zStop, zBegin, zEnd;
            int n = w;
            if (h > n) {
                n = h;
            }
            if (d > n) {
                n = d;
            }
            int noResult = 3 * (n + 1) * (n + 1);
            float[] tempInt = new float[d];
            float[] tempS = new float[d];
            boolean nonempty;
            float test, min;
            int delta;
            for (int j = thread; j < h; j += nThreads) {
                for (int i = 0; i < w; i++) {
                    nonempty = false;
                    for (int k = 0; k < d; k++) {
                        tempS[k] = s[k][i + w * j];
                        if (tempS[k] > 0) {
                            nonempty = true;
                        }
                    }
                    if (nonempty) {
                        zStart = 0;
                        while ((zStart < (d - 1)) && (tempS[zStart] == 0)) {
                            zStart++;
                        }
                        if (zStart > 0) {
                            zStart--;
                        }
                        zStop = d - 1;
                        while ((zStop > 0) && (tempS[zStop] == 0)) {
                            zStop--;
                        }
                        if (zStop < (d - 1)) {
                            zStop++;
                        }

                        for (int k = 0; k < d; k++) {
                            //Limit to the non-background to save time,
                            if (insideMask==mask.insideMask(i+w*j, k)) { //!xor
                                if (insideMask) { //&& d>1
                                    //min=Math.min(k+1, d-k);
                                    min = outOfBoundPolicy.getMinDistBorderZ(k, d);
                                    min *= min * scaleZ;
                                }  else min = noResult;
                                zBegin = zStart;
                                zEnd = zStop;
                                if (zBegin > k) {
                                    zBegin = k;
                                }
                                if (zEnd < k) {
                                    zEnd = k;
                                }
                                delta = (k - zBegin);

                                for (int z = zBegin; z <= zEnd; z++) {
                                    test = tempS[z] + delta * delta-- * scaleZ;
                                    if (test < min) {
                                        min = test;
                                    }
                                    //min = (test < min) ? test : min;
                                }
                                tempInt[k] = min;
                            }
                        }
                        for (int k = 0; k < d; k++) {
                            s[k][i + w * j] = tempInt[k];
                        }
                    }
                }
            }
        }//run
    }//Step2Thread	
}
