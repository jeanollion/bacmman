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
package bacmman.processing.localthickness;

import ij.*;
import ij.process.*;

// adapted to take anisotropy in account. 2012 jean Ollion

/* Bob Dougherty August 10, 2006

Input: 3D Distance map (32-bit stack)
Output: Distance ridge resulting from a local scan of the distance map.  Overwrites the input.
Note: Non-background points that are not part of the distance ridge are assiged a VERY_SMALL_VALUE.
This is used for subsequent processing by other plugins to find the local thickness.
Reference: T. Holdegrand and P. Ruegsegger, "A new method for the model-independent assessment of
thickness in three-dimensional images," Journal of Microscopy, Vol. 185 Pt. 1, January 1997 pp 67-75.

Version 1: August 10-11, 2006.  Subtracts 0.5 from the distances.
Version 1.01: September 6, 2006.  Corrected some typos in the comments.
Version 1.01: Sept. 7, 2006.  More tiny edits.
Version 2: Sept. 25, 2006.  Creates a separate image stack for symmetry.
                            Temporary version that is very conservative.
                            Admittedly does not produce much impovement on real images.
Version 3: Sept. 30, 2006.  Ball calculations based on grid points.  Should be much more accurate.
Version 3.1 Oct. 1, 2006.  Faster scanning of search points.



 License:
	Copyright (c)  2006, OptiNav, Inc.
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

public class DistanceRidge {
	public float[][] data;
	public int w,h,d;
        public float scale, scaleInv, scaleSq, scaleInvSq;
	public ImagePlus run(ImagePlus imp, float scaleZScaleXYRatio, boolean multithread) {
		ImageStack stack = imp.getStack();
		w = stack.getWidth();
		h = stack.getHeight();
		d = imp.getStackSize();
                this.scale=d>1?1/scaleZScaleXYRatio:1;
                this.scaleInv=d>1?scaleZScaleXYRatio:1;
                this.scaleSq=scale*scale;
                this.scaleInvSq=scaleInv*scaleInv;
		//Create 32 bit floating point stack for output, s.  Will also use it for g in Transormation 1.
		ImageStack newStack = new ImageStack(w,h);
		float[][] sNew = new float[d][];
		for(int k = 0; k < d; k++){
			ImageProcessor ipk = new FloatProcessor(w,h);
			newStack.addSlice(null,ipk);
			sNew[k] = (float[])ipk.getPixels();
		}
		//Create reference to input data
		float[][] s = new float[d][];
		for (int k = 0; k < d; k++)s[k] = (float[])stack.getPixels(k+1);
		//Do it
		int k1,j1,i1,dz,dy,dx;
		boolean notRidgePoint;
		float[] sk1;
		float[] sk, skNew;
		int sk0Sq,sk0SqInd;
                float sk1Sq;
		//Find the largest distance in the data
		IJ.showStatus("Distance Ridge: scanning the data");
		float distMax = 0;
		for (int k = 0; k < d; k++){
			sk = s[k];
			for (int j = 0; j < h; j++){
				for (int i = 0; i < w; i++){
					int ind = i + w*j;
					if(sk[ind] > distMax)distMax = sk[ind];
				}
			}
		}
		int rSqMax = (int)(distMax*distMax + 0.5f)+1;
		boolean[] occurs = new boolean[rSqMax];
		for(int i = 0; i < rSqMax; i++)occurs[i] = false;
		for (int k = 0; k < d; k++){
			sk = s[k];
			for (int j = 0; j < h; j++){
				for (int i = 0; i < w; i++){
					int ind = i + w*j;
					occurs[(int)(sk[ind]*sk[ind] + 0.5f)] = true;
				}
			}
		}
		int numRadii = 0;
		for (int i = 0; i < rSqMax; i++){
			if(occurs[i])numRadii++;
		}
		//Make an index of the distance-squared values
		int[] distSqIndex = new int[rSqMax];
		int[] distSqValues = new int[numRadii];
		int indDS = 0;
		for (int i = 0; i < rSqMax; i++){
			if(occurs[i]){
				distSqIndex[i] = indDS;
				distSqValues[indDS++] = i;
			}
		}
		//Build template
		//The first index of the template is the number of nonzero components
		//in the offest from the test point to the remote point.  The second
		//index is the radii index (of the test point).  The value of the template
		//is the minimum square radius of the remote point required to cover the
		//ball of the test point.
		// IJ.showStatus("Distance Ridge: creating search templates");
		float[][] rSqTemplate = createTemplate(distSqValues);
		int numCompZ,numCompY,numCompX,numComp;
		// TODO multithread??
                for (int k = 0; k < d; k++){
			//IJ.showStatus("Distance Ridge: processing slice "+(k+1)+"/"+(d+1));
			//IJ.showProgress(k/(1.*d));
			sk = s[k];
			skNew = sNew[k];
			for (int j = 0; j < h; j++){
				for (int i = 0; i < w; i++){
					int ind = i + w*j;
					if(sk[ind] > 0){
						notRidgePoint = false;
						sk0Sq = (int)(sk[ind]*sk[ind] + 0.5f);
						sk0SqInd = distSqIndex[sk0Sq];
						for (dz = -1; dz <= 1; dz++){
							k1 = k + dz;
							if((k1 >= 0)&&(k1 < d)){
								sk1 = s[k1];
								if(dz == 0){
									numCompZ = 0;
								}else{
									numCompZ = 1;
								}
								for (dy = -1; dy <= 1; dy++){
									j1 = j + dy;
									if((j1 >= 0)&&(j1 < h)){
										if(dy == 0){
											numCompY = 0;
										}else{
											numCompY = 1;
										}
										for (dx = -1; dx <= 1; dx++){
											i1 = i + dx;
											if((i1 >= 0)&&(i1 < w)){
												if(dx == 0){
													numCompX = 0;
												}else{
													numCompX = 1;
												}
												numComp = numCompX + numCompY + numCompZ;
												if(numComp > 0){
													// FIXE : why integers?
                                                                                                        sk1Sq = sk1[i1+w*j1]*sk1[i1+w*j1];
														if(sk1Sq >= rSqTemplate[numComp-1][sk0SqInd])
															notRidgePoint = true;
												}
											}//if in grid for i1
											if(notRidgePoint)break;
										}//dx
									}//if in grid for j1
									if(notRidgePoint)break;
								}//dy
							}//if in grid for k1
							if(notRidgePoint)break;
						}//dz
						if(!notRidgePoint)skNew[ind] = sk[ind];
					}//if not in background
				}//i
			}//j
		}//k
		//IJ.showStatus("Distance Ridge complete");
		String title = stripExtension(imp.getTitle());
		ImagePlus impOut = new ImagePlus(title+"_DR",newStack);
		return impOut;
	}
	//For each offset from the origin, (dx,dy,dz), and each radius-squared,
	//rSq, find the smallest radius-squared, r1Squared, such that a ball
	//of radius r1 centered at (dx,dy,dz) includes a ball of radius
	//rSq centered at the origin.  These balls refer to a 3D integer grid.
	//The set of (dx,dy,dz) points considered is a cube center at the origin.
	//The size of the comptued array could be considerably reduced by symmetry,
	//but then the time for the calculation using this array would increase
	//(and more code would be needed).
	float[][] createTemplate(int[] distSqValues){
		float[][] t = new float[3][];
		t[0] = scanCube(1,0,0,distSqValues);
		t[1] = scanCube(1,1,0,distSqValues);
		t[2] = scanCube(1,1,1,distSqValues);
		return t;
	}
	//For a list of r^2 values, find the smallest r1^2 values such
	//that a "ball" of radius r1 centered at (dx,dy,dz) includes a "ball"
	//of radius r centered at the origin.  "Ball" refers to a 3D integer grid.
	float[] scanCube(int dx, int dy, int dz,int[] distSqValues){
		int numRadii = distSqValues.length;
		float[] r1Sq = new float[numRadii];
		if((dx==0)&&(dy==0)&&(dz==0)){
			for(int rSq = 0; rSq < numRadii; rSq++){
				r1Sq[rSq] = Integer.MAX_VALUE;
			}
		}else{
			float dxAbs = -(float)Math.abs(dx);
			float dyAbs = -(float)Math.abs(dy);
			float dzAbs = -(float)Math.abs(dz);
			for(int rSqInd = 0; rSqInd < numRadii; rSqInd++){
				int rSq = distSqValues[rSqInd];
				float max = 0;
				int r = 1 + (int)Math.sqrt(rSq);
                                int rz = (int)((r-1)*scale+0.5)+1;
				float scank,scankj;
				float dk,dkji;
				int iBall;
				float iPlus;
				for(int k = 0; k <= rz; k++){
					scank = k*k*scaleInvSq;
					dk = (k-dzAbs)*(k-dzAbs)*scaleInvSq;
					for (int j = 0; j <= r; j++){
						scankj = scank* + j*j;
						if(scankj <= rSq){
							iPlus = ((int)Math.sqrt(rSq - scankj)) - dxAbs;
							dkji = dk + (j-dyAbs)*(j-dyAbs) + iPlus*iPlus;
							if(dkji > max) max = dkji;
						}
					}
				}
				r1Sq[rSqInd] = max;
			}
		}
		return r1Sq;
	}	//Modified from ImageJ code by Wayne Rasband
    String stripExtension(String name) {
        if (name!=null) {
            int dotIndex = name.lastIndexOf(".");
            if (dotIndex>=0)
                name = name.substring(0, dotIndex);
		}
		return name;
    }
}
