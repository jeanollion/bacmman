package bacmman.processing.jacop;

import ij.ImagePlus;
import ij.gui.NewImage;
import ij.measure.Calibration;
import ij.measure.CurveFitter;
import ij.process.ImageProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.function.IntPredicate;

/**
 *
 * @author Jean Ollion modified from the code of Fabrice Cordelieres: https://github.com/fabricecordelieres/IJ-Plugin_JACoP/blob/master/JACoP_/src/_JACoP/ImageColocalizer.java
 */
public class ImageColocalizer {
    public static final Logger logger = LoggerFactory.getLogger(ImageColocalizer.class);
    int width, height, nbSlices, depth, length, widthCostes, heightCostes, nbsliceCostes, lengthCostes;
    String titleA, titleB;
    int[] A, B;
    boolean[] mask, maskA, maskB;
    int Amin, Amax, Bmin, Bmax;
    double Amean, Bmean;
    Calibration cal, micronCal;

    //Values for stats
    boolean doThat;
    double sumA, sumB, sumAB, sumsqrA, Aarraymean, Barraymean;

    /** Creates a new instance of ImageColocalizer */
    public ImageColocalizer(ImagePlus ipA, ImagePlus ipB, ImagePlus mask, Calibration cal) {
        this.width=ipA.getWidth();
        this.height=ipA.getHeight();
        this.nbSlices=ipA.getNSlices();
        this.depth=ipA.getBitDepth();

        if (this.width!=ipB.getWidth() || this.height!=ipB.getHeight() || this.nbSlices!=ipB.getNSlices() || this.depth!=ipB.getBitDepth()){
            throw new IllegalArgumentException("ImageColocalizer expects both images to have the same size and depth");
        }
        this.length=this.width*this.height*this.nbSlices;
        this.A=new int[this.length];
        this.B=new int[this.length];
        if (mask != null) this.mask = new boolean[this.length];
        this.maskA = new boolean[this.length];
        this.maskB = new boolean[this.length];
        this.titleA=ipA.getTitle();
        this.titleB=ipB.getTitle();
        this.cal=cal;
        this.micronCal=(Calibration) cal.clone();

        this.micronCal.pixelDepth/=1000;
        this.micronCal.pixelHeight/=1000;
        this.micronCal.pixelWidth/=1000;
        this.micronCal.setUnit("µm");

        buildArray(ipA, ipB, mask);

    }

    /** Creates a new instance of ImageColocalizer */
    public ImageColocalizer(ImagePlus ipA, ImagePlus ipB, ImagePlus mask) {
        this(ipA, ipB, mask, new Calibration());
    }

    public ImageColocalizer setThresholdA(int thld) {
        for (int i=0; i<this.length; i++) maskA[i] = A[i]>=thld;
        return this;
    }

    public ImageColocalizer setThresholdB(int thld) {
        for (int i=0; i<this.length; i++) maskB[i] = B[i]>=thld;
        return this;
    }

    public ImageColocalizer setMaskA(ImagePlus maskA) {
        setMask(maskA, this.maskA);
        return this;
    }

    public ImageColocalizer setMaskB(ImagePlus maskB) {
        setMask(maskB, this.maskB);
        return this;
    }

    protected void setMask(ImagePlus mask, boolean[] a) {
        int index=0;
        for (int z=1; z<=this.nbSlices; z++) {
            mask.setSlice(z);
            ImageProcessor maskAp = mask.getProcessor();
            for (int y = 0; y < this.height; y++) {
                for (int x = 0; x < this.width; x++) {
                    a[index++] = maskAp.getPixel(x, y) != 0;
                }
            }
        }
    }

    public double pearson() {
        this.doThat=true;
        return linreg(A,B, mask, maskA, maskB)[2];
    }

    /**
     *
     * @return overlap , k1, k2
     */
    public double[] overlap(){
        double numThr=0;
        double den1Thr=0;
        double den2Thr=0;

        for (int i=0; i<this.length; i++){
            if (mask==null || mask[i]) {
                if (this.maskA[i] && this.maskB[i] ) {
                    numThr += this.A[i] * this.B[i];
                    den1Thr += Math.pow(this.A[i], 2);
                    den2Thr += Math.pow(this.B[i], 2);
                }
            }
        }

        double OverlapCoeffThr=numThr/(Math.sqrt(den1Thr*den2Thr));
        return new double[]{OverlapCoeffThr, numThr/den1Thr, numThr/den2Thr};
    }

    /**
     *
     * @return M1, M2
     */
    public double[] MM(){
        double sumAcolocThr=0;
        double sumAThr=0;
        double sumBcolocThr=0;
        double sumBThr=0;

        for (int i=0; i<this.length; i++){
            if (mask==null || mask[i]) {
                if (this.maskB[i]) {
                    sumBThr += this.B[i];
                    if (this.maskA[i]) sumAcolocThr += this.A[i];
                }
                if (this.maskA[i] ) {
                    sumAThr += this.A[i];
                    if (this.maskB[i]) sumBcolocThr += this.B[i];
                }
            }
        }

        double M1Thr=sumAcolocThr/sumAThr;
        double M2Thr=sumBcolocThr/sumBThr;
        return new double[]{M1Thr, M2Thr};
        //IJ.log("\nManders' Coefficients (original):\nM1="+round(M1,3)+" (fraction of A overlapping B)\nM2="+round(M2,3)+" (fraction of B overlapping A)");
        //IJ.log("\nManders' Coefficients (using threshold value of "+thrA+" for imgA and "+thrB+" for imgB):\nM1="+round(M1Thr,3)+" (fraction of A overlapping B)\nM2="+round(M2Thr,3)+" (fraction of B overlapping A)");
    }

    /**
     *
     * @return array: 0=Costes' automatic threshold for A; 1=Costes' automatic threshold for B; 2=Pearson's Coefficient; 3=pearson below thresholds; 4=M1; 5=M2;
     */
    public double[] CostesAutoThr() {
        int CostesThrA=this.Amax;
        int CostesThrB=this.Bmax;
        double CostesSumAThr=0;
        double CostesSumA=0;
        double CostesSumBThr=0;
        double CostesSumB=0;
        double CostesPearson=1;
        double [] rx= new double[this.Amax-this.Amin+1];
        double [] ry= new double[this.Amax-this.Amin+1];
        double rmax=0;
        double rmin=1;
        this.doThat=true;
        int count=0;

        //First Step: define line equation
        this.doThat=true;
        double[] tmp=linreg(this.A, this.B, this.mask, 0, 0);
        double a=tmp[0];
        double b=tmp[1];
        this.doThat=false;

        int LoopMin= (int) Math.max(this.Amin, (this.Bmin-b)/a);
        int LoopMax= (int) Math.min(this.Amax, (this.Bmax-b)/a);


        //Minimize r of points below (thrA,a*thrA+b)
        for (int i=LoopMax;i>=LoopMin;i--){
            CostesPearson=linregCostes(this.A, this.B, this.mask, i, (int) (a*i+b))[2];

            rx[count]=i;
            ry[count]=CostesPearson;
            if (((Double) CostesPearson).isNaN()){
                if (count!=LoopMax){
                    ry[count]=ry[count-1];
                }else{
                    ry[count]=1;
                }
            }

            if (CostesPearson<=rmin && i!=LoopMax){
                CostesThrA=i;
                CostesThrB=(int)(a*i+b);
                //i=Amin-1;
            }

            rmax=Math.max(rmax,ry[count]);
            rmin=Math.min(rmin,ry[count]);
            count++;


        }


        for (int i=0; i<this.length; i++){
            CostesSumA+=this.A[i];
            if (this.A[i]>CostesThrA) CostesSumAThr+=this.A[i];
            CostesSumB+=this.B[i];
            if (this.B[i]>CostesThrB) CostesSumBThr+=this.B[i];
        }

        /*Plot plot=new Plot("Costes' threshold "+this.titleA+" and "+this.titleB,"ThrA", "Pearson's coefficient below");
        plot.add("line",rx,ry);
        plot.setLimits(LoopMin, LoopMax, rmin, rmax);
        plot.setColor(Color.black);
        plot.draw();

        //Draw the zero line
        double[] xline={CostesThrA, CostesThrA};
        double[] yline={rmin, rmax};
        plot.setColor(Color.red);
        plot.addPoints(xline, yline, 2);

        plot.show();
        */
        ImagePlus CostesMask=NewImage.createRGBImage("Costes' mask",this.width,this.height,this.nbSlices,0);
        CostesMask.getProcessor().setValue(Math.pow(2, this.depth));
        for (int k=1; k<=this.nbSlices; k++){
            CostesMask.setSlice(k);
            for (int j=0; j<this.height; j++){
                for (int i=0; i<this.width; i++){
                    int position=offset(i,j,k);
                    int [] color=new int[3];
                    color[0]=this.A[position];
                    color[1]=this.B[position];
                    color[2]=0;
                    if (color[0]>CostesThrA && color[1]>CostesThrB){
                        //CostesMask.getProcessor().setValue(((A[position]-CostesThrA)/(LoopMax-CostesThrA))*Math.pow(2, depthA));
                        //CostesMask.getProcessor().drawPixel(i,j);
                        for (int l=0; l<=2; l++) color[l]=255;
                    }
                    CostesMask.getProcessor().putPixel(i,j,color);
                }
            }
        }
        CostesMask.setCalibration(this.cal);
        CostesMask.setSlice(1);
        //CostesMask.show();


        this.doThat=true;
        double pearsonCoeff = linreg(this.A, this.B, this.mask,CostesThrA,CostesThrB)[2];
        return new double[]{CostesThrA, CostesThrA, pearsonCoeff, CostesPearson, CostesSumAThr/CostesSumA, CostesSumBThr/CostesSumB};
    }

    /**
     * Van Steensel's Cross-correlation Coefficient
     * @param CCFx
     * @return 2D array: first array : 0=CCF min; 1=obtained for dxMin; 2=CCF max; 3=obtained for dxMax; second array: x third array: CCFarray
     *
     */
    public double[][] CCF(int CCFx){
        double meanA;
        double meanB;
        double nPoints;
        double num;
        double den1;
        double den2;
        double CCFmin=0;
        int lmin=-CCFx;
        double CCFmax=0;
        int lmax=-CCFx;

        double [] CCFarray=new double[2*CCFx+1];
        double [] x=new double[2*CCFx+1];

        int count=0;

        for (int l=-CCFx; l<=CCFx; l++){
            meanA=0;
            meanB=0;
            nPoints=0;

            for (int k=1; k<=this.nbSlices; k++){
                for (int j=0; j<this.height; j++){
                    for (int i=0; i<this.width; i++){
                        int coord=offset(i,j,k);
                        if (mask==null || mask[coord]) {
                            if (i + l >= 0 && i + l < this.width) {
                                int coordShift = offset(i + l, j, k);
                                if (mask==null || mask[coordShift]) {
                                    meanA += this.A[coord];
                                    meanB += this.B[coordShift];
                                    nPoints++;
                                }
                            }
                        }
                    }
                }
            }

            meanA/=nPoints;
            meanB/=nPoints;

            num=0;
            den1=0;
            den2=0;

            for (int k=1; k<=this.nbSlices; k++){
                for (int j=0; j<this.height; j++){
                    for (int i=0; i<this.width; i++){
                        int coord = offset(i, j, k);
                        if (mask==null || mask[coord]) {
                            if (i + l >= 0 && i + l < this.width) {
                                int coordShift = offset(i + l, j, k);
                                if (mask==null || mask[coordShift]) {
                                    num += (this.A[coord] - meanA) * (this.B[coordShift] - meanB);
                                    den1 += Math.pow((this.A[coord] - meanA), 2);
                                    den2 += Math.pow((this.B[coordShift] - meanB), 2);
                                }
                            }
                        }
                    }
                }
            }

            double CCF=num/(Math.sqrt(den1*den2));

            if (l==-CCFx){
                CCFmin=CCF;
                CCFmax=CCF;
            }else{
                if (CCF<CCFmin){
                    CCFmin=CCF;
                    lmin=l;
                }
                if (CCF>CCFmax){
                    CCFmax=CCF;
                    lmax=l;
                }
            }
            x[count]=l;
            CCFarray[count]=CCF;
            count++;
        }
        double[] res = new double[]{CCFmin, lmin, CCFmax, lmax};
        /*Plot plot=new Plot("Van Steensel's CCF between "+this.titleA+" and "+this.titleB,"dx", "CCF");
        plot.add("line", x, CCFarray);
        plot.setLimits(-CCFx, CCFx, CCFmin-(CCFmax-CCFmin)*0.05, CCFmax+(CCFmax-CCFmin)*0.05);
        plot.setColor(Color.white);
        plot.draw();

        //Previous plot is white, just to get values inserted into the plot list, the problem being that the plot is as default a line plot... Following line plots same values as circles.
        plot.setColor(Color.black);
        plot.addPoints(x, CCFarray, Plot.CIRCLE);

        double[] xline={0,0};
        double[] yline={CCFmin-(CCFmax-CCFmin)*0.05,CCFmax+(CCFmax-CCFmin)*0.05};
        plot.setColor(Color.red);
        plot.addPoints(xline, yline, 2);
        */
        CurveFitter cf=new CurveFitter(x, CCFarray);
        double[] param={CCFmin, CCFmax, lmax, (double) CCFx};
        cf.setInitialParameters(param);
        cf.doFit(CurveFitter.GAUSSIAN);
        param=cf.getParams();
        //IJ.log("\nResults for fitting CCF on a Gaussian (CCF=a+(b-a)exp(-(xshift-c)^2/(2d^2))):"+cf.getResultString()+"\nFWHM="+Math.abs(round(2*Math.sqrt(2*Math.log(2))*param[3], 3))+" pixels");
        for (int i=0; i<x.length; i++) CCFarray[i]=CurveFitter.f(CurveFitter.GAUSSIAN, param, x[i]);
        /*plot.setColor(Color.BLUE);
        plot.addPoints(x, CCFarray, 2);

        IJ.showStatus("");
        IJ.showProgress(2,1);

        plot.show();
        */
        return new double[][]{res, x, CCFarray};
    }

    /**
     * Li's Intensity correlation coefficient
     * @return
     */
    public double ICA(){
        double[] Anorm=new double[this.length];
        double[] Bnorm=new double[this.length];
        double AnormMean=0;
        double BnormMean=0;
        double prodMin=0;
        double prodMax=0;
        double lim=0;
        double[] x= new double[this.length];
        double ICQ=0;
        int count = 0;
        //Intensities are normalized to range from 0 to 1
        for (int i=0; i<this.length; i++){
            Anorm[i]=(double) (this.A[i]-this.Amin)/this.Amax;
            Bnorm[i]=(double) (this.B[i]-this.Bmin)/this.Bmax;
            if (mask==null || mask[i]) {
                AnormMean += Anorm[i];
                BnormMean += Bnorm[i];
                ++count;
            }
        }
        AnormMean=AnormMean/count;
        BnormMean=BnormMean/count;

        for (int i=0; i<this.length;i++){
            x[i]=(Anorm[i]-AnormMean)*(Bnorm[i]-BnormMean);
            if (mask==null || mask[i]) {
                if (x[i] > prodMax) prodMax = x[i];
                if (x[i] < prodMin) prodMin = x[i];
                if (x[i] > 0) ICQ++;
            }
        }

        if (Math.abs(prodMin)>Math.abs(prodMax)){
            lim=Math.abs(prodMin);
        }else{
            lim=Math.abs(prodMax);
        }

        ICQ=ICQ/count-0.5;

        /*Plot plotA = new Plot("ICA A ("+this.titleA+")", "(Ai-a)(Bi-b)", this.titleA);
        plotA.setColor(Color.white);
        plotA.setLimits(-lim, lim, 0, 1);
        plotA.draw();
        plotA.setColor(Color.black);
        plotA.addPoints(x, Anorm, Plot.DOT);
        plotA.draw();

        plotA.setColor(Color.red);
        plotA.drawLine(0, 0, 0, 1);
        plotA.show();

        Plot plotB = new Plot("ICA B ("+this.titleB+")", "(Ai-a)(Bi-b)", titleB);
        plotB.setColor(Color.white);
        plotB.setLimits(-lim, lim, 0, 1);
        plotB.draw();
        plotB.setColor(Color.black);
        plotB.addPoints(x, Bnorm, Plot.DOT);


        plotB.setColor(Color.red);
        plotB.drawLine(0, 0, 0, 1);
        //plotB.addPoints(xline, yline, Plot.LINE);

        plotB.show();
        */
        return ICQ;
    }

    /**
     *
     * @param xyBlock
     * @param zBlock
     * @param nbRand
     * @param binWidth
     * @param fillMeth
     * @param xyRand
     * @param zRand
     * @param showRand
     * @return 2D array. 1rst array 0= r (original) 1= r (randomized) 2=SD (calculated from the fitted data) 3 = P-value (calculated from the fitted data)
     * 2nd array : r 3rd array probability density of r";
     */
    public double[][] CostesRand(int xyBlock, int zBlock, int nbRand, double binWidth, int fillMeth, boolean xyRand, boolean zRand, boolean showRand){
        int[] ACostes, BCostes, BRandCostes;
        boolean[] maskCostes;
        if (fillMeth==0){
            this.widthCostes=((int)(this.width/xyBlock))*xyBlock;
            this.heightCostes=((int)(this.height/xyBlock))*xyBlock;
        }else{
            this.widthCostes=(((int)(this.width/xyBlock))+1)*xyBlock;
            this.heightCostes=(((int)(this.height/xyBlock))+1)*xyBlock;
        }

        if (zRand){
            if (fillMeth==0){
                this.nbsliceCostes=((int)(this.nbSlices/zBlock))*zBlock;
            }else{
                this.nbsliceCostes=(((int)(this.nbSlices/zBlock))+1)*zBlock;
            }
            if (this.nbSlices==1) nbsliceCostes=1;

        }else{
            this.nbsliceCostes=this.nbSlices;
        }

        this.lengthCostes=this.widthCostes*this.heightCostes*this.nbsliceCostes;
        ACostes=new int[this.lengthCostes];
        maskCostes = mask != null ? new boolean[this.lengthCostes] : null;

        BCostes=new int[this.lengthCostes];
        BRandCostes=new int[this.lengthCostes];

        int index=0;
        for (int k=1; k<=this.nbsliceCostes; k++){
            for (int j=0; j<this.heightCostes; j++){
                for (int i=0; i<this.widthCostes; i++){
                    int offset=offset(i, j, k);
                    ACostes[index]=A[offset];
                    BCostes[index]=B[offset];
                    if (mask!=null) maskCostes[index] = mask[offset];
                    index++;
                }
            }
        }
        boolean[] maskB = mask != null ? Arrays.copyOf(maskCostes, maskCostes.length) : null;
        boolean[] randMask = mask != null ? new boolean[maskCostes.length] : null;

        double direction;
        int shift;
        int newposition;
        if (xyRand || this.nbsliceCostes==1){
            //If slices independent 2D there is no need to take into account the z thickness and ranndomization along z axis should not be done
            zBlock=1;
            zRand=false;
        }
        this.doThat=true;
        double r2test=linreg(ACostes, BCostes, maskCostes, 0, 0)[2];
        this.doThat=false;
        double[] arrayR= new double[nbRand];
        double mean=0;
        double SD=0;
        double Pval=0;
        double[] arrayDistribR= new double[(int)(2/binWidth+1)];
        double[] x= new double[arrayDistribR.length];


        for (int f=0; f<nbRand; f++){

            //Randomization by shifting along x axis
            for (int e=1; e<=this.nbsliceCostes-zBlock+1; e+=zBlock){
                for (int d=0; d<this.heightCostes-xyBlock+1; d+=xyBlock){

                    //Randomization of the shift's direction
                    direction=1;
                    if(Math.random()<0.5) direction=-1;
                    //Randomization of the shift: should be a multiple of the xy block size
                    shift=((int) (direction*Math.random()*this.widthCostes/xyBlock))*xyBlock;

                    for (int a=0; a<this.widthCostes; a++){
                        for (int b=d; b<d+xyBlock; b++){
                            for (int c=e; c<e+zBlock; c++){
                                newposition=a+shift;
                                if (newposition>=this.widthCostes) newposition-=this.widthCostes;
                                if (newposition<0) newposition+=this.widthCostes;
                                int targetIdx = offsetCostes(newposition,b,c);
                                int sourceIdx = offsetCostes(a,b,c);
                                BRandCostes[targetIdx]=BCostes[sourceIdx];
                                if (maskB!=null) randMask[targetIdx] = maskB[sourceIdx];
                            }
                        }
                    }
                }
            }
            for (int i=0; i<BCostes.length; i++) BCostes[i]=BRandCostes[i];
            if (maskB!=null) for (int i=0; i<BCostes.length; i++) maskB[i]=randMask[i];

            //Randomization by shifting along y axis
            for (int e=1; e<=this.nbsliceCostes-zBlock+1; e+=zBlock){
                for (int d=0; d<this.widthCostes-xyBlock+1; d+=xyBlock){

                    //Randomization of the shift's direction
                    direction=1;
                    if(Math.random()<0.5) direction=-1;
                    //Randomization of the shift: should be a multiple of the xy block size
                    shift=((int) (direction*Math.random()*this.heightCostes/xyBlock))*xyBlock;

                    for (int a=0; a<this.heightCostes; a++){
                        for (int b=d; b<d+xyBlock; b++){
                            for (int c=e; c<e+zBlock; c++){
                                newposition=a+shift;
                                if (newposition>=this.heightCostes) newposition-=this.heightCostes;
                                if (newposition<0) newposition+=this.heightCostes;
                                int targetIdx = offsetCostes(b,newposition,c);
                                int sourceIdx = offsetCostes(b,a,c);
                                BRandCostes[targetIdx]=BCostes[sourceIdx];
                                if (maskB!=null) randMask[targetIdx]=maskB[sourceIdx];
                            }
                        }
                    }
                }
            }
            for (int i=0; i<BCostes.length; i++) BCostes[i]=BRandCostes[i];
            if (maskB!=null) for (int i=0; i<BCostes.length; i++) maskB[i]=randMask[i];

            if (zRand){
                //Randomization by shifting along z axis
                for (int e=0; e<this.heightCostes-xyBlock+1; e+=xyBlock){
                    for (int d=0; d<this.widthCostes-xyBlock+1; d+=xyBlock){

                        //Randomization of the shift's direction
                        direction=1;
                        if(Math.random()<0.5) direction=-1;
                        //Randomization of the shift: should be a multiple of the z block size
                        shift=((int) (direction*Math.random()*this.nbsliceCostes/zBlock))*zBlock;

                        for (int a=1; a<=this.nbsliceCostes; a++){
                            for (int b=d; b<d+xyBlock; b++){
                                for (int c=e; c<e+xyBlock; c++){
                                    newposition=a+shift;
                                    if (newposition>this.nbsliceCostes) newposition-=this.nbsliceCostes;
                                    if (newposition<1) newposition+=this.nbsliceCostes;
                                    int targetIdx = offsetCostes(b,c,newposition);
                                    int sourceIdx = offsetCostes(b,a,c);
                                    BRandCostes[targetIdx]=BCostes[sourceIdx];
                                    if (maskB!=null) randMask[targetIdx]=maskB[sourceIdx];
                                }
                            }
                        }
                    }
                }
                for (int i=0; i<BCostes.length; i++) BCostes[i]=BRandCostes[i];
                if (maskB!=null) for (int i=0; i<BCostes.length; i++) maskB[i]=randMask[i];
            }
            if (mask!=null) for (int i=0; i<BCostes.length; i++) randMask[i]=maskCostes[i] && maskB[i];
            arrayR[f]=linreg(ACostes, BCostes, randMask, 0, 0)[2];
            //if (arrayR[f]<r2test) Pval++;
            mean+=arrayR[f];
            arrayDistribR[(int)((arrayR[f]+1)/binWidth)]++;
            x[(int)((arrayR[f]+1)/binWidth)]+=arrayR[f];
            //IJ.showStatus("Costes' randomization loop n°"+f+"/"+nbRand);
        }

        //Draw the last randomized image, if requiered
        /*if (showRand){
            ImagePlus Rand=NewImage.createImage("Randomized images of "+this.titleB,this.widthCostes,this.heightCostes,this.nbsliceCostes,this.depth, 1);

            index=0;
            for (int k=1; k<=this.nbsliceCostes; k++){
                Rand.setSlice(k);
                for (int j=0;j<this.heightCostes; j++){
                    for (int i=0; i<this.widthCostes;i++){
                        Rand.getProcessor().putPixel(i, j, BRandCostes[index]);
                        index++;
                    }
                }
            }
            Rand.setCalibration(this.cal);
            Rand.setSlice(1);
            Rand.show();
            IJ.setMinAndMax(this.Bmin,this.Bmax);
        }*/

        //Plots the r probability distribution
        double minx=-1;
        double maxx=1;
        double maxy=0;
        for (int i=0; i<arrayDistribR.length;i++) x[i]=arrayDistribR[i]==0?i*binWidth-1+binWidth/2:x[i]/arrayDistribR[i];
        for (int i=0; i<arrayDistribR.length;i++) arrayDistribR[i]/=nbRand;

        for (int i=0; i<arrayDistribR.length;i++){
            //x[i]=i*binWidth-1+binWidth/2;
            if (minx==-1 && arrayDistribR[i]!=0) minx=x[i];
            if (maxy<arrayDistribR[i]) maxy=arrayDistribR[i];
        }
        minx=Math.min(minx,r2test);

        int i=arrayDistribR.length-1;
        while (arrayDistribR[i]==0) {
            maxx=x[i];
            i--;
        }

        maxx=Math.max(maxx,r2test);

        //Remove from arraDistribR all values equals to zero.
        int newLength=0;
        for (i=0; i<arrayDistribR.length; i++) if(arrayDistribR[i]!=0) newLength++;
        double[] xNew=new double[newLength], arrayNew=new double[newLength];
        newLength=0;
        for (i=0; i<arrayDistribR.length; i++) if(arrayDistribR[i]!=0){ xNew[newLength]=x[i]; arrayNew[newLength++]=arrayDistribR[i];}
        x=xNew;
        arrayDistribR=arrayNew;

        /*
        Plot plot = new Plot("Costes' method ("+this.titleA+" & "+this.titleB+")", "r", "Probability density of r");
        plot.add("line", x, arrayDistribR);
        plot.setLimits(minx-10*binWidth, maxx+10*binWidth, 0, maxy*1.05);
        plot.setColor(Color.white);
        plot.draw();

        //Previous plot is white, just to get values inserted into the plot list, the problem being that the plot is as default a line plot... Following line plots same values as circles.
        plot.setColor(Color.black);
        plot.addPoints(x, arrayDistribR, Plot.CIRCLE);

        //Draw the r line
        double[] xline={r2test,r2test};
        double[] yline={0,maxy*1.05};
        plot.setColor(Color.red);
        plot.addPoints(xline, yline, 2);
        */

        //Retrieves the mean, SD and P-value of the r distribution
        for (i=1; i<nbRand; i++) SD+=Math.pow(arrayR[i]-mean,2);
        mean/=nbRand;
        SD=Math.sqrt(SD/(nbRand-1));
        //Pval/=nbRand;


        //IJ.log("\nCostes' randomization based colocalization:\nParameters: Nb of randomization rounds: "+nbRand+", Resolution (bin width): "+binWidth);


        CurveFitter cf=new CurveFitter(x, arrayDistribR);
        double[] param={0, maxy, mean, SD};
        cf.setInitialParameters(param);
        cf.doFit(CurveFitter.GAUSSIAN);
        param=cf.getParams();
        mean=param[2];
        SD=param[3];

        //Algorithm 26.2.17 from Abromowitz and Stegun, Handbook of Mathematical Functions for approximation of the cumulative density function (max. error=7.5e^-8).
        double[] b={0.319381530, -0.356563782, 1.781477937, -1.821255978, 1.330274429};
        double p=0.2316419;
        double z=(1/Math.sqrt(2*Math.PI))*Math.exp(-Math.pow((r2test-mean)/SD, 2)/2);
        double t=1/(1+p*Math.abs((r2test-mean)/SD));

        if(r2test>=0){
            Pval=1-z*t*(t*(t*(t*(t*b[4]+b[3])+b[2])+b[1])+b[0]);
        }else {
            Pval= z*t*(t*(t*(t*(t*b[4]+b[3])+b[2])+b[1])+b[0]);
        }
        double[] resR = new double[]{r2test, mean, SD, Pval*100};
        //IJ.log("r (original)="+round(r2test,3)+"\nr (randomized)="+round(mean,3)+"±"+round(SD,3)+" (calculated from the fitted data)\nP-value="+round(Pval*100,2)+"% (calculated from the fitted data)");

        //IJ.log("\nResults for fitting the probability density function on a Gaussian (Probability=a+(b-a)exp(-(R-c)^2/(2d^2))):"+cf.getResultString()+"\nFWHM="+Math.abs(round(2*Math.sqrt(2*Math.log(2))*param[3], 3)));
        for (i=0; i<x.length; i++) arrayDistribR[i]=CurveFitter.f(CurveFitter.GAUSSIAN, param, x[i]);
        /*plot.setColor(Color.BLUE);
        plot.addPoints(x, arrayDistribR, 2);
        plot.show();
        */
        return new double[][]{resR, x, arrayDistribR};
    }

    //----------------------------------------------------------------------------------------------------------------------------------------------
    private void buildArray(ImagePlus imgA, ImagePlus imgB, ImagePlus mask){
        int index=0;
        this.Amin=Integer.MAX_VALUE;
        this.Amax=0;
        this.Amean=0;
        this.Bmin=this.Amin;
        this.Bmax=0;
        this.Bmean=0;
        double count = 0;
        for (int z=1; z<=this.nbSlices; z++){
            imgA.setSlice(z);
            imgB.setSlice(z);
            if (mask!=null) mask.setSlice(z);
            ImageProcessor imgAp = imgA.getProcessor();
            ImageProcessor imgBp = imgB.getProcessor();
            ImageProcessor maskp = mask != null ? mask.getProcessor() : null;
            for (int y=0; y<this.height; y++){
                for (int x=0; x<this.width; x++){
                    this.A[index]=imgAp.getPixel(x,y);
                    this.B[index]=imgBp.getPixel(x,y);
                    if (mask!=null) this.mask[index] = maskp.getPixel(x, y)!=0;
                    if (mask==null || this.mask[index]) {
                        if (A[index] < Amin) Amin = A[index];
                        if (A[index] > Amax) Amax = A[index];
                        if (B[index] < Bmin) Bmin = B[index];
                        if (B[index] > Bmax) Bmax = B[index];
                        Amean += A[index];
                        Bmean += B[index];
                        ++count;
                    }
                    index++;
                }
            }

            this.Amean/=count;// TODO risk of overflow ?
            this.Bmean/=count;
        }
    }

    public double[] linreg(int[] Aarray, int[] Barray, boolean[] mask, boolean[] maskA, boolean[] maskB){
        double num=0;
        double den1=0;
        double den2=0;
        double[] coeff=new double[6];
        int count=0;

        if (doThat){
            sumA=0;
            sumB=0;
            sumAB=0;
            sumsqrA=0;
            Aarraymean=0;
            Barraymean=0;
            for (int m=0; m<Aarray.length; m++){
                if ( (mask==null||mask[m]) && maskA[m] && maskB[m] ){
                    sumA+=Aarray[m];
                    sumB+=Barray[m];
                    sumAB+=Aarray[m]*Barray[m];
                    sumsqrA+=Math.pow(Aarray[m],2);
                    count++;
                }
            }

            Aarraymean=sumA/count;
            Barraymean=sumB/count;
        }

        for (int m=0; m<Aarray.length; m++){
            if (((mask==null||mask[m])) && maskA[m] && maskB[m] ){
                num+=(Aarray[m]-Aarraymean)*(Barray[m]-Barraymean);
                den1+=Math.pow((Aarray[m]-Aarraymean), 2);
                den2+=Math.pow((Barray[m]-Barraymean), 2);
            }
        }

        //0:a, 1:b, 2:corr coeff, 3: num, 4: den1, 5: den2
        coeff[0]=(count*sumAB-sumA*sumB)/(count*sumsqrA-Math.pow(sumA,2));
        coeff[1]=(sumsqrA*sumB-sumA*sumAB)/(count*sumsqrA-Math.pow(sumA,2));
        coeff[2]=num/(Math.sqrt(den1*den2));
        coeff[3]=num;
        coeff[4]=den1;
        coeff[5]=den2;
        return coeff;
    }

    public double[] linreg(int[] Aarray, int[] Barray, boolean[] mask, int TA, int TB){
        double num=0;
        double den1=0;
        double den2=0;
        double[] coeff=new double[6];
        int count=0;

        if (doThat){
            sumA=0;
            sumB=0;
            sumAB=0;
            sumsqrA=0;
            Aarraymean=0;
            Barraymean=0;
            for (int m=0; m<Aarray.length; m++){
                if ( (mask==null||mask[m]) && Aarray[m]>=TA && Barray[m]>=TB ){
                    sumA+=Aarray[m];
                    sumB+=Barray[m];
                    sumAB+=Aarray[m]*Barray[m];
                    sumsqrA+=Math.pow(Aarray[m],2);
                    count++;
                }
            }

            Aarraymean=sumA/count;
            Barraymean=sumB/count;
        }

        for (int m=0; m<Aarray.length; m++){
            if (((mask==null||mask[m])) && Aarray[m]>=TA && Barray[m]>=TB ){
                num+=(Aarray[m]-Aarraymean)*(Barray[m]-Barraymean);
                den1+=Math.pow((Aarray[m]-Aarraymean), 2);
                den2+=Math.pow((Barray[m]-Barraymean), 2);
            }
        }

        //0:a, 1:b, 2:corr coeff, 3: num, 4: den1, 5: den2
        coeff[0]=(count*sumAB-sumA*sumB)/(count*sumsqrA-Math.pow(sumA,2));
        coeff[1]=(sumsqrA*sumB-sumA*sumAB)/(count*sumsqrA-Math.pow(sumA,2));
        coeff[2]=num/(Math.sqrt(den1*den2));
        coeff[3]=num;
        coeff[4]=den1;
        coeff[5]=den2;
        return coeff;
    }

    public double[] linregCostes(int[] Aarray, int[] Barray, boolean[] mask, int TA, int TB){
        double num=0;
        double den1=0;
        double den2=0;
        double[] coeff=new double[3];
        int count=0;

        sumA=0;
        sumB=0;
        sumAB=0;
        sumsqrA=0;
        Aarraymean=0;
        Barraymean=0;

        for (int m=0; m<Aarray.length; m++){
            if ((mask==null||mask[m]) && Aarray[m]<TA && Barray[m]<TB){
                sumA+=Aarray[m];
                sumB+=Barray[m];
                sumAB+=Aarray[m]*Barray[m];
                sumsqrA+=Math.pow(Aarray[m],2);
                count++;
            }
        }

        Aarraymean=sumA/count;
        Barraymean=sumB/count;


        for (int m=0; m<Aarray.length; m++){
            if ((mask==null||mask[m]) && Aarray[m]<TA && Barray[m]<TB){
                num+=(Aarray[m]-Aarraymean)*(Barray[m]-Barraymean);
                den1+=Math.pow((Aarray[m]-Aarraymean), 2);
                den2+=Math.pow((Barray[m]-Barraymean), 2);
            }
        }

        coeff[0]=(count*sumAB-sumA*sumB)/(count*sumsqrA-Math.pow(sumA,2));
        coeff[1]=(sumsqrA*sumB-sumA*sumAB)/(count*sumsqrA-Math.pow(sumA,2));
        coeff[2]=num/(Math.sqrt(den1*den2));
        return coeff;
    }

    private double[] int2double(int[] input){
        double[] output=new double[input.length];
        for (int i=0; i<input.length; i++) output[i]=input[i];
        return output;
    }

    /** Returns the index where to find the informations corresponding to pixel (x, y, z).
     * @param x coordinate of the pixel.
     * @param y coordinate of the pixel.
     * @param z coordinate of the pixel.
     * @return the index where to find the informations corresponding to pixel (x, y, z).
     */
    private int offset(int x, int y, int z){
        if (x+y*this.width+(z-1)*this.width*this.height>=this.width*this.height*this.nbSlices){
            return this.width*this.height*this.nbSlices-1;
        }else{
            if (x+y*this.width+(z-1)*this.width*this.height<0){
                return 0;
            }else{
                return x+y*this.width+(z-1)*this.width*this.height;
            }
        }
    }

    public int offsetCostes(int m,int n,int o){
        if (m+n*this.widthCostes+(o-1)*this.widthCostes*this.heightCostes>=this.widthCostes*this.heightCostes*this.nbsliceCostes){
            return this.widthCostes*this.heightCostes*this.nbsliceCostes-1;
        }else{
            if (m+n*this.widthCostes+(o-1)*this.widthCostes*this.heightCostes<0){
                return 0;
            }else{
                return m+n*this.widthCostes+(o-1)*this.widthCostes*this.heightCostes;
            }
        }
    }

    public double round(double y, int z){
        //Special tip to round numbers to 10^-2
        y*=Math.pow(10,z);
        y=(int) y;
        y/=Math.pow(10,z);
        return y;
    }
}
