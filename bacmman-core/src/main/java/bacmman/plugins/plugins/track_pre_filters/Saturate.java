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
package bacmman.plugins.plugins.track_pre_filters;

import bacmman.data_structure.SegmentedObject;
import bacmman.plugins.ProcessingPipeline;
import bacmman.plugins.plugins.thresholders.IJAutoThresholder;
import bacmman.configuration.parameters.BoundedNumberParameter;
import bacmman.configuration.parameters.NumberParameter;
import bacmman.configuration.parameters.Parameter;
import bacmman.image.Histogram;
import bacmman.image.HistogramFactory;
import bacmman.image.Image;
import bacmman.image.ImageMask;
import bacmman.processing.ImageOperations;
import bacmman.plugins.DevPlugin;
import bacmman.plugins.Hint;
import bacmman.plugins.TrackPreFilter;
import ij.process.AutoThresholder;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TreeMap;

/**
 *
 * @author Jean Ollion
 */
public class Saturate implements TrackPreFilter, Hint, DevPlugin {
    NumberParameter maxSat = new BoundedNumberParameter("Max Saturation proportion", 3, 0.03, 0, 1);
    String toolTip = "<html>Saturation of bright values in Bright Field images. <br />Performed on all images of a track are considered at once. <br />A threshold is computed on the histogram of all images, using the MaxEntropy method. <br />The proportion of saturated pixels should not be higer than indicated in the \"Max Saturation proportion\" parameter.</html>";
    
    public Saturate() {}
    public Saturate(double maxProportion) {
        this.maxSat.setValue(maxProportion);
    }

    @Override
    public ProcessingPipeline.PARENT_TRACK_MODE parentTrackMode() {
        return ProcessingPipeline.PARENT_TRACK_MODE.WHOLE_PARENT_TRACK_ONLY;
    }

    @Override
    public void filter(int structureIdx, TreeMap<SegmentedObject, Image> preFilteredImages, boolean canModifyImages) {
        Map<Image, ImageMask> maskMap = TrackPreFilter.getMaskMap(preFilteredImages);
        Histogram histo = HistogramFactory.getHistogram(()->Image.stream(maskMap, true).parallel(), HistogramFactory.BIN_SIZE_METHOD.AUTO_WITH_LIMITS);
        double sv = IJAutoThresholder.runThresholder(AutoThresholder.Method.MaxEntropy, histo); //Shanbhag
        double svBin = (int)histo.getIdxFromValue(sv);
        // limit to saturagePercentage
        double sat = histo.getQuantiles(1-maxSat.getValue().doubleValue())[0];
        double satBin = histo.getIdxFromValue(sat);
        if (satBin>svBin) {
            svBin = satBin;
            sv = sat;
        }
        long[] hdata = histo.getData();
        for (int i = (int)svBin; i<hdata.length; ++i) hdata[i]=0;
        logger.debug("saturate value: {}", sv);
        for (Entry<SegmentedObject, Image> e : preFilteredImages.entrySet()) {
            Image im = e.getValue();
            if (!canModifyImages) im = im.duplicate();
            ImageOperations.trimValues(im, sv, sv, false);
            if (!canModifyImages) e.setValue(im);
        }
    }

    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{maxSat};
    }

    @Override
    public String getHintText() {
        return toolTip;
    }
    
}
