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

import bacmman.configuration.parameters.PostFilterSequence;
import bacmman.configuration.parameters.TrackPreFilterSequence;
import bacmman.data_structure.*;

import java.util.List;

/**
 *
 * @author Jean Ollion
 */
public interface TrackerSegmenter extends Tracker {
    /**
     * This method segment & assign the children of each element of the {@param parentTrack}, and sets the track links for each child. Segmenter might implement TrackParametrizable, should be honored in this method
     * @param objectClassIdx index of child structure to be segmented and tracked
     * @param parentTrack parent track, sorted in the order of increasing timePoint
     * @param trackPreFilters filters to apply before segmentation
     * @param postFilters filters to apply after segmentation
     * @param editor object allowing to edit track links of objects of class {@param objectClassIdx}
     * @param factory object allowing to create SegmentedObject from regions
     */
    void segmentAndTrack(int objectClassIdx, List<SegmentedObject> parentTrack, TrackPreFilterSequence trackPreFilters, PostFilterSequence postFilters, SegmentedObjectFactory factory, TrackLinkEditor editor);
    /**
     * Optional method, can return null
     * @return the segmenter used for the tracking process
     */
    Segmenter getSegmenter();
}
