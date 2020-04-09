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

import bacmman.configuration.parameters.BooleanParameter;
import bacmman.configuration.parameters.BoundedNumberParameter;
import bacmman.configuration.parameters.ConditionalParameter;
import bacmman.configuration.parameters.Parameter;
import bacmman.core.Core;
import bacmman.image.Image;
import bacmman.image.ImageMask;
import bacmman.image.wrappers.IJImageWrapper;
import bacmman.plugins.Filter;
import bacmman.plugins.Hint;
import bacmman.plugins.PreFilter;
import de.biomedical_imaging.ij.nlMeansPlugin.NLMeansDenoising_;
import ij.ImagePlus;
import ij.process.ImageProcessor;

import java.util.ArrayList;
import java.util.List;

/**
 *
 * @author Jean Ollion
 */
public class NonLocalMeansDenoise implements PreFilter, Filter, Hint {
    BoundedNumberParameter sigma = new BoundedNumberParameter("Sigma", 0, 5, 0, null).setHint("Estimation of standard deviation of noise.").setEmphasized(true);
    BoundedNumberParameter smoothingFactor = new BoundedNumberParameter("Smoothing factor", 2, 1, 0.1, null);
    BooleanParameter autoEstimateSigma = new BooleanParameter("Auto Estimate Sigma", false);
    ConditionalParameter<Boolean> autoEstimateCond = new ConditionalParameter<>(autoEstimateSigma).setActionParameters(Boolean.TRUE, smoothingFactor).setActionParameters(Boolean.FALSE, sigma, smoothingFactor).setEmphasized(true).setHint("This parameter will appear as invalid if not installed").addValidationFunction(p -> {
        try {
            NLMeansDenoising_ nlmean = new NLMeansDenoising_();
        } catch (NoClassDefFoundError e) {
            return false;
        }
        return true;
    });
    Parameter[] parameters = new Parameter[]{autoEstimateCond};
    // TODO in Filter mode + autoestimate sigma -> configuration compute mean sigma on whole track
    public Image runPreFilter(Image input, ImageMask mask, boolean canModifyImage) {
        return run(input, !canModifyImage);
    }
    private Image run(Image input, boolean duplicate) {
        if (duplicate) input = input.duplicate();
        try {
            double smooth = this.smoothingFactor.getValue().doubleValue();
            List<Image> planes = input.splitZPlanes();
            List<Image> res = new ArrayList<>(planes.size());
            int s = sigma.getValue().intValue();
            for (Image plane : planes) {
                ImagePlus imp = IJImageWrapper.getImagePlus(plane);
                ImageProcessor ip = imp.getProcessor();
                NLMeansDenoising_ nlmean = new NLMeansDenoising_();
                double sig = s;
                if (autoEstimateSigma.getSelected()) {
                    sig = NLMeansDenoising_.getGlobalNoiseLevel(ip);
                }
                sig *= smooth;
                nlmean.applyNonLocalMeans(ip, (int)(sig+0.5));
                res.add(IJImageWrapper.wrap(imp));
            }
            return Image.mergeZPlanes(res);
        } catch (NoClassDefFoundError e) {
            Core.getProgressLogger().setMessage("NoNLocalMeans not installed, activate biomedgroup update site");
            throw e;
        }
    }

    public Parameter[] getParameters() {
        return parameters;
    }

    @Override
    public Image applyTransformation(int channelIdx, int timePoint, Image image) {
        return run(image, false);
    }

    @Override
    public String getHintText() {
        return "Non local means denoising<br /> " +
                "To use this plugin, activate the update site: <em>biomedgroup</em><br />"+
                "<a href='https://imagej.net/Non_Local_Means_Denoise'>https://imagej.net/Non_Local_Means_Denoise</a><br />" +
                "Method: Antoni Buades, Bartomeu Coll, and Jean-Michel Morel, Non-Local Means Denoising, Image Processing On Line, vol. 2011.<br />" +
                "Implementation: Darbon, J. et al., 2008. Fast nonlocal filtering applied to electron cryomicroscopy. In 2008 5th IEEE International Symposium on Biomedical Imaging: From Nano to Macro, Proceedings, ISBI. IEEE, pp. 1331–1334.";
    }
}
