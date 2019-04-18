package bacmman.processing;

import bacmman.image.BoundingBox;
import bacmman.image.Image;
import bacmman.image.ImageFloat;
import bacmman.image.ImageShort;

public class FastRadialSymetryTransformUtil {
    public enum GRADIENT_SIGN {POSITIVE_ONLY, NEGATIVE_ONLY, BOTH}
    /**
     *
     * @param input image to compute transform on
     * @param radii radius of successive neighborhoods in pixels
     * @param useOrientationOnly if true: only use the gradient orientation and ignore the gradient magnitude
     * @param gradientSign == -1 --> only negative / flag == 1 --> only positive / otherwise --> both
     * @param alpha alpha increase the symmetry condition (higher -> more symmetric)
     * @param smallGradientThreshold proportion of  discarded gradients (lower than this value)
     * @return
     */
    public static Image runTransform(Image input, double[] radii, boolean useOrientationOnly, GRADIENT_SIGN gradientSign, double alpha, double smallGradientThreshold){
        ImageFloat[] grad = ImageFeatures.getGradient(input, 1, 1, false); // which gradient scale should be chosen ? should sobel filter be chosen as in the origial publication ?
        Image gradX = grad[0];
        Image gradY = grad[1];
        Image gradM = new ImageFloat("", input).resetOffset();

        BoundingBox.loop(gradM, (x, y, z)->((ImageFloat)gradM).setPixel(x, y, z, (float)(Math.sqrt(Math.pow(gradX.getPixel(x, y, z), 2)+Math.pow(gradY.getPixel(x, y, z), 2))))); // computes gradient magnitude
        double[] mm = gradM.getMinAndMax(null);
        Image Omap = GRADIENT_SIGN.POSITIVE_ONLY.equals(gradientSign)? new ImageShort("", input) : new ImageFloat("", input);
        Image Mmap = useOrientationOnly ? null : new ImageFloat("", input);
        Image F = new ImageFloat("", input);
        Image output = new ImageFloat("", input);
        // over all radii that are in the set
        for (int i = 0; i < radii.length; i++) {
            double radius = radii[i];
            float kappa = (Math.round(radius) <= 1) ? 8 : 9.9f;
            // get the O and M maps
            computeOandMmap(Omap, Mmap, gradX, gradY, gradM, radius, kappa, smallGradientThreshold>0 ? smallGradientThreshold * (mm[1]-mm[0]) + mm[0] : 0, gradientSign);
            // Unsmoothed symmetry measure at this radius value
            if (Mmap==null) BoundingBox.loop(gradM, (x, y, z) -> F.setPixel(x, y, z, (float)(Math.pow(Math.abs(Omap.getPixel(x, y, z)/kappa),alpha))));
            else BoundingBox.loop(gradM, (x, y, z) -> F.setPixel(x, y, z, (float)((Mmap.getPixel(x, y, z)/kappa) * Math.pow(Math.abs(Omap.getPixel(x, y, z)/kappa),alpha))));

            Image smoothed = ImageFeatures.gaussianSmooth(F, 0.25*radius, 0.25*radius, true);
            ImageOperations.addImage(output, smoothed, output, radius); // multiplied by radius so that sum of all elements of the kernel is radius
        }
        return output;
    }


    private static void computeOandMmap(Image Omap, Image Mmap, Image gradX, Image gradY, Image gradM, double radius, float kappa, double gradientThld, GRADIENT_SIGN gradientSign){
        // go over all pixels and create the O and M maps
        BoundingBox.loop(gradM,
            (x, y, z)-> {
                int xg = Math.round((float)radius*gradX.getPixel(x, y, z)/gradM.getPixel(x, y, z));
                int yg = Math.round((float)radius*gradY.getPixel(x, y, z)/gradM.getPixel(x, y, z));

                // compute the 'positively' and/or 'negatively' affected pixels
                if (!GRADIENT_SIGN.NEGATIVE_ONLY.equals(gradientSign)){
                    int posx = correctRange(x + xg,Omap.sizeX()-1);
                    int posy = correctRange(y + yg,Omap.sizeY()-1);
                    Omap.setPixel(posx, posy, z, (Omap.getPixel(posx, posy, z)+1 > kappa) ? kappa : Omap.getPixel(posx, posy, z)+1);
                    if (Mmap != null) Mmap.setPixel(posx, posy, z, Mmap.getPixel(posx, posy, z)+gradM.getPixel(x, y, z));
                }

                if (!GRADIENT_SIGN.POSITIVE_ONLY.equals(gradientSign)){
                    int negx = correctRange(x - xg,Omap.sizeX()-1);
                    int negy = correctRange(y - yg,Omap.sizeY()-1);
                    Omap.setPixel(negx, negy, z, (Omap.getPixel(negx, negy, z)-1 < -kappa) ? -kappa : Omap.getPixel(negx, negy, z)-1);
                    if (Mmap != null) Mmap.setPixel(negx, negy, z, Mmap.getPixel(negx, negy, z)-gradM.getPixel(x, y, z));
                }
            },
            (x, y, z)->gradientThld==0 || gradM.getPixel(x, y, z)>gradientThld, // do not consider gradients with too small magnitudes
            false);
    }

    // truncate value
    private static int correctRange(int val, int max){
        return ((val < 0) ? 0 : ((val > max) ? (max) : val));
    }

    public String getBibtexCitation() {
        return "@article{Loy2003," +
                "abstract = {A new transform is presented that utilizes local radial symmetry to highlight points of interest within a scene. Its low-computational complexity and fast runtimes makes this method well-suited for real-time vision applications. The performance of the transform is demonstrated on a wide variety of images and compared with leading techniques from the literature. Both as a facial feature detector and as a generic region of interest detector the new transform is seen to offer equal or superior performance to contemporary techniques at a relatively low-computational cost. A real-time implementation of the transform is presented running at over 60 frames per second on a standard Pentium III PC.}," +
                "author = {Loy, G. and Zelinsky, A.}," +
                "doi = {10.1109/TPAMI.2003.1217601}," +
                "issn = {0162-8828}," +
                "journal = {IEEE Transactions on Pattern Analysis and Machine Intelligence}," +
                "keywords = {Computer vision,Costs,Detectors,Eyes,Face detection,Facial features,Humans,Layout,Pentium III PC,Psychology,Runtime,computational complexity,facial feature detector,fast radial symmetry,feature extraction,local radial symmetry,performance,point of interest detection,real-time systems,real-time vision applications,transform,transforms}," +
                "month = Aug," +
                "number = {8}," +
                "pages = {959--973}," +
                "title = {{Fast radial symmetry for detecting points of interest}}," +
                "volume = {25}," +
                "year = {2003}" +
                "}";
    }


    public String getMedlineCitation() {
        return "Loy, G., & Zelinsky, A. (2003). Fast radial symmetry for detecting points of interest. IEEE Transactions on Pattern Analysis and Machine Intelligence, 25(8), 959�973.";
    }

}
