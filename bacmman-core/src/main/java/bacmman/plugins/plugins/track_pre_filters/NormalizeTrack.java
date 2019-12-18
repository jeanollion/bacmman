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

import bacmman.configuration.parameters.BooleanParameter;
import bacmman.configuration.parameters.BoundedNumberParameter;
import bacmman.configuration.parameters.NumberParameter;
import bacmman.configuration.parameters.Parameter;
import bacmman.data_structure.SegmentedObject;
import bacmman.image.Histogram;
import bacmman.image.HistogramFactory;
import bacmman.image.Image;
import bacmman.plugins.Hint;
import bacmman.plugins.ProcessingPipeline;
import bacmman.processing.ImageOperations;

import java.util.TreeMap;
import bacmman.plugins.TrackPreFilter;

import java.util.Map.Entry;

/**
 *
 * @author Jean Ollion
 */
public class NormalizeTrack  implements TrackPreFilter, Hint {
    NumberParameter saturation = new BoundedNumberParameter("Saturation", 4, 0.996, 0, 1).setEmphasized(true).setHint("Determines the number of pixels in the image that will become saturated (proportion of saturated pixels = 1 - this value). Decreasing this value increases the contrast. This value should be lower than one to prevent a few outlying pixel from causing the normalization to not work as intended");
    BooleanParameter invert = new BooleanParameter("Invert", false).setEmphasized(true).setHint("If set to <em>true</em>, all images will be inverted");
    public NormalizeTrack() {}
    public NormalizeTrack(double saturation, boolean invert) {
        this.saturation.setValue(saturation);
        this.invert.setSelected(invert);
    }
    @Override
    public ProcessingPipeline.PARENT_TRACK_MODE parentTrackMode() {
        return ProcessingPipeline.PARENT_TRACK_MODE.WHOLE_PARENT_TRACK_ONLY;
    }
    @Override
    public void filter(int structureIdx, TreeMap<SegmentedObject, Image> preFilteredImages, boolean canModifyImage) {
        Histogram histo = HistogramFactory.getHistogram(()->Image.stream(preFilteredImages.values()).parallel(), HistogramFactory.BIN_SIZE_METHOD.AUTO_WITH_LIMITS);
        double[] minAndMax = new double[2];
        minAndMax[0] = histo.getMin();
        if (saturation.getValue().doubleValue()<1) minAndMax[1] = histo.getQuantiles(saturation.getValue().doubleValue())[0];
        else minAndMax[1] = histo.getMaxValue();
        double scale = 1 / (minAndMax[1] - minAndMax[0]);
        double offset = -minAndMax[0] * scale;
        if (invert.getSelected()) {
            scale = -scale;
            offset = 1 - offset;
        }
        logger.debug("Normalization: range: [{}-{}] scale: {} off: {}", minAndMax[0], minAndMax[1], scale, offset);
        for (Entry<SegmentedObject, Image> e : preFilteredImages.entrySet()) {
            Image trans = ImageOperations.affineOperation(e.getValue(), canModifyImage?e.getValue():null, scale, offset);
            e.setValue(trans);
        }
    }

    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{saturation, invert};
    }

    @Override
    public String getHintText() {
        return "Normalizes image values between 0 and 1, by dividing all values by the maximal value (or by a percentile, see <em>Saturation</em> parameter, available in advanced mode) of the images of the whole parent track.<br />" +
                "Optionally inverts the images (see parameter <em>Invert</em>)";
    }
}
