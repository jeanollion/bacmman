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
package bacmman.plugins;

import bacmman.data_structure.SegmentedObject;
import bacmman.image.*;

/**
 *
 * @author Jean Ollion
 */
public interface ThresholderHisto extends SimpleThresholder {
    /**
     * Computes the threshold on a histogram provided by the caller (e.g. by a parent module, which then defines the binning). The histogram is used as is: the binning of this thresholder is not applied (use {@link #runThresholderHisto(HistogramSource)} for that)
     * @param histogram must not be modified
     */
    double runThresholderHisto(Histogram histogram);

    /**
     * @return binning of the histogram on which this thresholder is computed when it computes the histogram itself or when it receives a {@link HistogramSource}
     */
    default HistogramBinning getHistogramBinning() {
        return HistogramBinning.DEFAULT;
    }

    /**
     * Computes the threshold on the histogram of the source with this thresholder's binning (see {@link #getHistogramBinning()}). Use this method when the caller computes the values distribution, so that each thresholder can choose its binning while the values are read only once.
     * For LOG and POWER functions, the threshold is computed in transformed space, see {@link #runThresholderHistoInBinningSpace(Histogram)}
     */
    default double runThresholderHisto(HistogramSource source) {
        return runThresholderHistoInBinningSpace(source.getHistogram(getHistogramBinning()));
    }

    /**
     * If histogram is a {@link TransformedHistogram}, the threshold is computed on the histogram of the transformed values (as if the values had been transformed, i.e. as if the histogram was linear in transformed space), and mapped back to values with the inverse function. Otherwise, it is computed on histogram
     */
    default double runThresholderHistoInBinningSpace(Histogram histogram) {
        if (histogram instanceof TransformedHistogram) {
            TransformedHistogram th = (TransformedHistogram) histogram;
            return th.inverse(runThresholderHisto(th.getTransformedSpaceHistogram()));
        } else return runThresholderHisto(histogram);
    }

    default double runThresholder(Image input, SegmentedObject structureObject) {
        ImageMask mask = structureObject!=null?structureObject.getMask():new BlankMask(input);
        return runSimpleThresholder(input, mask);
    }

    default double runSimpleThresholder(Image image, ImageMask mask) {
        Histogram histo = HistogramSource.of(()-> image.stream(mask, true)).getHistogram(getHistogramBinning()); // not shared: can be modified
        histo.removeSaturatingValue(4, true);
        return runThresholderHistoInBinningSpace(histo);
    }
}
