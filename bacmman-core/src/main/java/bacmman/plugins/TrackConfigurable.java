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
import bacmman.utils.Pair;
import bacmman.image.BlankMask;
import bacmman.image.Histogram;
import bacmman.image.HistogramFactory;
import bacmman.image.Image;
import bacmman.image.ImageInteger;
import bacmman.image.ImageMask;
import bacmman.image.TypeConverter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 *
 * @author Jean Ollion
 * @param <P> segmenter type
 */
public interface TrackConfigurable<P extends Plugin> {
    /**
     * Interface Allowing to configure a plugin using information from whole parent track
     * @param <P> type of plugin to be configured
     */
    @FunctionalInterface interface TrackConfigurer<P> {
        /**
         * Parametrizes the {@param segmenter}
         * This method may be called asynchronously with different pairs of {@param parent}/{@param segmenter}
         * @param parent parent object from the parent track used to create the TrackConfigurer object See: {@link #getTrackConfigurer(int, List, Plugin)}  }. This is not necessary the segmentation parent that will be used as argument in {@link Segmenter#runSegmenter(Image, int, SegmentedObject) }
         * @param plugin Segmenter instance that will be configured, prior to call the method {@link Segmenter#runSegmenter(Image, int, SegmentedObject) }
         */
        void apply(SegmentedObject parent, P plugin);
    }
    /**
     * 
     * @param structureIdx index of the structure to be segmented via call to {@link Segmenter#runSegmenter(Image, int, SegmentedObject) }
     * @param parentTrack parent track (elements are parent of structure {@param structureIdx}
     * @return ApplyToSegmenter object that will configure Segmenter instances before call to {@link Segmenter#runSegmenter(Image, int, SegmentedObject) }
     */
    TrackConfigurer<P> run(int structureIdx, List<SegmentedObject> parentTrack);
    ProcessingPipeline.PARENT_TRACK_MODE parentTrackMode();
    // + static helpers methods
    static <P extends Plugin> TrackConfigurer<P> getTrackConfigurer(int structureIdx, List<SegmentedObject> parentTrack, P plugin) {
        if (plugin instanceof TrackConfigurable) {
            TrackConfigurable<P> tp = (TrackConfigurable<P>)plugin;
            return tp.run(structureIdx, parentTrack);
        }
        return null;
    }

    static double getGlobalThreshold(int structureIdx, List<SegmentedObject> parentTrack, SimpleThresholder thlder) {
        Map<Image, ImageMask> maskMap = parentTrack.stream().collect(Collectors.toMap(p->p.getPreFilteredImage(structureIdx), p->p.getMask()));
        if (thlder instanceof ThresholderHisto) {
            Histogram hist = HistogramFactory.getHistogram(()->Image.stream(maskMap, true).parallel(), HistogramFactory.BIN_SIZE_METHOD.AUTO_WITH_LIMITS);
            return ((ThresholderHisto)thlder).runThresholderHisto(hist);
        } else {
            Supplier<Pair<List<Image>, List<ImageInteger>>> supplier = ()->new Pair<>(new ArrayList<>(), new ArrayList<>());
            BiConsumer<Pair<List<Image>, List<ImageInteger>>, Map.Entry<Image, ImageMask>> accumulator =  (p, e)->{
                p.key.add(e.getKey());
                if (!(e.getValue() instanceof BlankMask)) p.value.add((ImageInteger)TypeConverter.toCommonImageType(e.getValue()));
            };
            BiConsumer<Pair<List<Image>, List<ImageInteger>>, Pair<List<Image>, List<ImageInteger>>> combiner = (p1, p2) -> {p1.key.addAll(p2.key);p1.value.addAll(p2.value);};
            Pair<List<Image>, List<ImageInteger>> globalImagesList = maskMap.entrySet().stream().collect( supplier,  accumulator,  combiner);
            Image globalImage = (Image)Image.mergeImagesInZ(globalImagesList.key);
            ImageMask globalMask = globalImagesList.value.isEmpty() ? new BlankMask(globalImage) : (ImageInteger)Image.mergeImagesInZ(globalImagesList.value);
            return thlder.runSimpleThresholder(globalImage, globalMask);
        }
    }
    
    
}
