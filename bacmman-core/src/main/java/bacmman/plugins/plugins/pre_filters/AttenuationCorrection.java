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
package bacmman.plugins.plugins.pre_filters;
import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.WindowManager;
import ij.gui.GenericDialog;
import ij.plugin.PlugIn;
import ij.plugin.filter.RankFilters;
import ij.process.ImageProcessor;
/**
 *
 * @author Jean Ollion
 */
public class AttenuationCorrection {



public class Attenuation_Correction
implements PlugIn {
    private int _openingRadius = 1;
    private int _referenceSlice = 1;
    private double _minValidValue;
    private double _maxValidValue;

    public void run(String arg0) {
        ImagePlus imagePlus = WindowManager.getCurrentImage();
        if (imagePlus == null) {
            IJ.error((String)"There is currently no image stack to process.");
            return;
        }
        switch (imagePlus.getType()) {
            case 0: {
                this._minValidValue = 0.0;
                this._maxValidValue = 255.0;
                break;
            }
            case 1: {
                this._minValidValue = 0.0;
                this._maxValidValue = 65535.0;
                break;
            }
            case 2: {
                this._minValidValue = -3.4028234663852886E38;
                this._maxValidValue = 3.4028234663852886E38;
                break;
            }
            default: {
                IJ.error((String)"This plugin cannot process color images.");
                return;
            }
        }
        if (this.runDialog(imagePlus)) {
            this.exec(imagePlus);
        }
    }

    public boolean runDialog(ImagePlus imagePlus) {
        int numSlices = imagePlus.getNSlices();
        GenericDialog genericDialog = new GenericDialog("Attenuation correction");
        genericDialog.addNumericField("Opening radius", 3.0, 1);
        genericDialog.addSlider("Reference slice", 1.0, (double)numSlices, 1.0);
        genericDialog.showDialog();
        this.setOpeningRadius((int)genericDialog.getNextNumber());
        this.setReferenceSlice((int)genericDialog.getNextNumber());
        return genericDialog.wasOKed();
    }

    public void setOpeningRadius(int openingRadius) {
        this._openingRadius = openingRadius;
    }

    public int getOpeningRadius() {
        return this._openingRadius;
    }

    public void setReferenceSlice(int referenceSlice) {
        this._referenceSlice = referenceSlice;
    }

    public int getReferenceSlice() {
        return this._referenceSlice;
    }

    public void exec(ImagePlus inputImagePlus) {
        ImagePlus backgroundImagePlus = this.estimateBackground(inputImagePlus);
        backgroundImagePlus.show();
        ImagePlus correctedImagePlus = this.correctAttenuation(inputImagePlus, backgroundImagePlus);
        correctedImagePlus.copyScale(inputImagePlus);
        correctedImagePlus.show();
        correctedImagePlus.setSlice(inputImagePlus.getSlice());
    }

    public ImagePlus estimateBackground(ImagePlus imagePlus) {
        int numSlices = imagePlus.getNSlices();
        ImageStack inputStack = imagePlus.getStack();
        ImageStack backgroundStack = new ImageStack(inputStack.getWidth(), inputStack.getHeight());
        RankFilters rankFilters = new RankFilters();
        for (int s = 0; s < numSlices; ++s) {
            ImageProcessor inputImage = inputStack.getProcessor(s + 1);
            ImageProcessor backgroundImage = inputImage.duplicate();
            rankFilters.rank(backgroundImage, (double)this._openingRadius, 1);
            rankFilters.rank(backgroundImage, (double)this._openingRadius, 2);
            backgroundStack.addSlice(backgroundImage);
            IJ.showProgress((int)(s + 1), (int)(2 * numSlices));
        }
        return new ImagePlus("Background of " + imagePlus.getTitle(), backgroundStack);
    }

    public ImagePlus correctAttenuation(ImagePlus inputImagePlus, ImagePlus backgroundImagePlus) {
        int numSlices = inputImagePlus.getNSlices();
        ImageStack inputStack = inputImagePlus.getStack();
        ImageStack correctedStack = new ImageStack(inputStack.getWidth(), inputStack.getHeight());
        double[] meanIntensityProfile = this.computeMeanIntensityProfile(backgroundImagePlus);
        double[] standardDeviationProfile = this.computeStandardDeviationProfile(backgroundImagePlus, meanIntensityProfile);
        double refMean = meanIntensityProfile[this._referenceSlice - 1];
        double refSd = standardDeviationProfile[this._referenceSlice - 1];
        for (int s = 0; s < numSlices; ++s) {
            ImageProcessor inputImage = inputStack.getProcessor(s + 1);
            ImageProcessor correctedImage = inputImage.duplicate();
            if (s != this._referenceSlice - 1) {
                int pixelCount = correctedImage.getPixelCount();
                double average = meanIntensityProfile[s];
                double sd = standardDeviationProfile[s];
                if (sd > 0.0) {
                    for (int p = 0; p < pixelCount; ++p) {
                        double initialValue = inputImage.getf(p);
                        double correctedValue = refMean + refSd * (initialValue - average) / sd;
                        if (correctedValue < this._minValidValue) {
                            correctedValue = this._minValidValue;
                        } else if (correctedValue > this._maxValidValue) {
                            correctedValue = this._maxValidValue;
                        }
                        correctedImage.setf(p, (float)correctedValue);
                    }
                } else {
                    IJ.log((String)("Warning: Attenuation correction: slice " + (s + 1) + ": constant background (slice ignored)"));
                }
            }
            correctedStack.addSlice(correctedImage);
            IJ.showProgress((int)(numSlices + s + 1), (int)(2 * numSlices));
        }
        return new ImagePlus("Correction of " + inputImagePlus.getTitle(), correctedStack);
    }

    public double computeMeanIntensity(ImageProcessor imageProcessor) {
        int numPixels = imageProcessor.getPixelCount();
        double sum = 0.0;
        for (int i = 0; i < numPixels; ++i) {
            sum+=(double)imageProcessor.getf(i);
        }
        return sum / (double)numPixels;
    }

    public double computeStandardDeviation(ImageProcessor imageProcessor, double mean) {
        int numPixels = imageProcessor.getPixelCount();
        double sum = 0.0;
        for (int i = 0; i < numPixels; ++i) {
            double diff = (double)imageProcessor.getf(i) - mean;
            sum+=diff * diff;
        }
        return Math.sqrt(sum / (double)(numPixels - 1));
    }

    public double[] computeMeanIntensityProfile(ImagePlus imagePlus) {
        int numSlices = imagePlus.getNSlices();
        double[] meanIntensityProfile = new double[numSlices];
        for (int s = 0; s < numSlices; ++s) {
            meanIntensityProfile[s] = this.computeMeanIntensity(imagePlus.getStack().getProcessor(s + 1));
        }
        return meanIntensityProfile;
    }

    public double[] computeStandardDeviationProfile(ImagePlus imagePlus, double[] meanIntensityProfile) {
        int numSlices = imagePlus.getNSlices();
        double[] standardDeviationProfile = new double[numSlices];
        for (int s = 0; s < numSlices; ++s) {
            standardDeviationProfile[s] = this.computeStandardDeviation(imagePlus.getStack().getProcessor(s + 1), meanIntensityProfile[s]);
        }
        return standardDeviationProfile;
    }
}
}
