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
package bacmman.plugins.plugins.segmenters;

import bacmman.configuration.parameters.ConditionalParameter;
import bacmman.configuration.parameters.EnumChoiceParameter;
import bacmman.configuration.parameters.HistogramBinningParameter;
import bacmman.configuration.parameters.PluginParameter;
import bacmman.image.Histogram;
import bacmman.image.HistogramFactory;
import bacmman.image.Image;
import bacmman.image.ImageMask;
import bacmman.plugins.SimpleThresholder;
import bacmman.plugins.plugins.thresholders.BackgroundThresholder;
import bacmman.plugins.plugins.thresholders.ModeFit;
import bacmman.processing.ImageOperations;

/**
 * Estimation of the mean and standard deviation of the background within a cell, used for intensity normalization (spot segmenters, spot quality)
 * Parameters: choice of the estimation method. Legacy configurations, which only had the <em>Foreground Removal Thld</em> parameter, are initialized with {@link METHOD#THRESHOLD} and their thresholder.
 */
public class BackgroundEstimation {
    public enum METHOD {THRESHOLD, MODE_FIT}
    public static final String THLD_NAME = "Foreground Removal Thld";
    final EnumChoiceParameter<METHOD> method = new EnumChoiceParameter<>("Background Estimation", METHOD.values(), METHOD.THRESHOLD)
            .setHint("How the mean (MEAN) and standard deviation (SD) of the background intensity are measured within the cell.<ul>" +
                    "<li>"+METHOD.THRESHOLD+": values above <em>"+THLD_NAME+"</em> (foreground, e.g. spots) are excluded, MEAN and SD are computed on the remaining values. With <em>BackgroundThresholder</em>, the MEAN and SD of its last iteration are used</li>" +
                    "<li>"+METHOD.MODE_FIT+": a gaussian is fitted on the core of the main peak of the histogram of intensities (see <em>ModeFit</em>): MEAN and SD are its center and width. No threshold is needed, and the estimation is not influenced by the foreground (upper tail of the histogram)</li></ul>");
    final PluginParameter<SimpleThresholder> thresholder = new PluginParameter<>(THLD_NAME, SimpleThresholder.class, new BackgroundThresholder(6, 6, 2), true)
            .setHint("Values above this threshold (foreground, e.g. spots) are excluded from the computation of the MEAN and SD of the background. If no thresholder is set, all values within the cell are used");
    final HistogramBinningParameter binning = new HistogramBinningParameter();
    final PluginParameter<SimpleThresholder> legacyThresholder = new PluginParameter<>(THLD_NAME, SimpleThresholder.class, new BackgroundThresholder(6, 6, 2), true); // same default as thresholder: unchanged if absent from the legacy configuration
    final ConditionalParameter<METHOD> cond = new ConditionalParameter<>(method)
            .setActionParameters(METHOD.THRESHOLD, thresholder)
            .setActionParameters(METHOD.MODE_FIT, binning)
            .setLegacyInitializationValue(METHOD.THRESHOLD)
            .setLegacyParameter((p, c) -> thresholder.setContentFrom(p[0]), legacyThresholder);

    public ConditionalParameter<METHOD> getParameter() {
        return cond;
    }

    public BackgroundEstimation setMethod(METHOD m) {
        method.setSelectedEnum(m);
        return this;
    }

    public METHOD getMethod() {
        return method.getSelectedEnum();
    }

    /**
     * @return [MEAN, SD] of the background of {@param input} within {@param mask}
     */
    public double[] getMeanAndSigma(Image input, ImageMask mask) {
        if (METHOD.MODE_FIT.equals(method.getSelectedEnum())) {
            Histogram h = HistogramFactory.getHistogram(() -> input.stream(mask, true), binning.getBinning().getMethod());
            return ModeFit.fit(h);
        } else return getMeanAndSigma(input, mask, thresholder.instantiatePlugin());
    }

    /**
     * Background estimation with a thresholder
     * @param thlder background threshold: only values below it are considered (all values if null). With a {@link BackgroundThresholder}, the mean and sd of its last iteration are returned
     * @return [MEAN, SD]
     */
    public static double[] getMeanAndSigma(Image input, ImageMask mask, SimpleThresholder thlder) {
        if (thlder instanceof BackgroundThresholder) {
            double[] ms = new double[2];
            BackgroundThresholder bthlder  = (BackgroundThresholder)thlder;
            BackgroundThresholder.runThresholder(input, mask, bthlder.getSigma(), bthlder.getFinalSigma(), bthlder.getIterations(), Double.MAX_VALUE, bthlder.symmetrical(), ms);
            return ms;
        } else if (thlder != null) {
            double t = thlder.runSimpleThresholder(input, mask);
            return ImageOperations.getMeanAndSigma(input, mask, d -> d<=t);
        } else {
            return ImageOperations.getMeanAndSigma(input, mask, null);
        }
    }
}
