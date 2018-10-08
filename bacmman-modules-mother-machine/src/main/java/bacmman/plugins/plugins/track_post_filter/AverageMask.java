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
package bacmman.plugins.plugins.track_post_filter;

import bacmman.configuration.parameters.ChoiceParameter;
import bacmman.configuration.parameters.Parameter;
import bacmman.core.Core;
import bacmman.data_structure.SegmentedObjectFactory;
import bacmman.data_structure.SegmentedObject;
import bacmman.data_structure.SegmentedObjectUtils;
import bacmman.data_structure.TrackLinkEditor;
import bacmman.image.BlankMask;
import static bacmman.image.BoundingBox.loop;

import bacmman.image.ImageInteger;
import bacmman.image.ImageMask;
import bacmman.plugins.DevPlugin;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import bacmman.plugins.TrackPostFilter;

/**
 *
 * @author Jean Ollion
 */
public class AverageMask implements TrackPostFilter, DevPlugin{
    public static boolean debug = false;
    public static int debugIdx = 0;
    ChoiceParameter referencePoint = new ChoiceParameter("Reference Point", new String[]{"Upper-left corner"}, "Upper-left corner", false);
    //PostFilterSequence postFilters = new PostFilterSequence("Post-Filters");
    //BooleanParameter postFilterOnAverageImage = new BooleanParameter("Run post-filters on", "Average Image Over Frames", "Each Frame", true);
    @Override
    public void filter(int structureIdx, List<SegmentedObject> parentTrack, SegmentedObjectFactory factory, TrackLinkEditor editor)  {
        Map<SegmentedObject, List<SegmentedObject>> allTracks=  SegmentedObjectUtils.getAllTracks(parentTrack, structureIdx);
        for (List<SegmentedObject> track : allTracks.values()) averageMask(track);
    }
    private void averageMask(List<SegmentedObject> track) {
        //if (track.size()<=1) return;
        if (referencePoint.getSelectedIndex()==0) { // upper left corner
            // size = maximal size
            int maxX = Collections.max(track, Comparator.comparingInt(o1 -> o1.getMask().sizeX())).getMask().sizeX();
            int maxY = Collections.max(track, Comparator.comparingInt(o1 -> o1.getMask().sizeY())).getMask().sizeY();
            int maxZ = Collections.max(track, Comparator.comparingInt(o1 -> o1.getMask().sizeZ())).getMask().sizeZ();
            ImageInteger sum = ImageInteger.createEmptyLabelImage("average mask", track.size()+1, new BlankMask( maxX, maxY, maxZ));
            for (SegmentedObject o : track) {
                ImageMask mask = o.getMask();
                for (int z = 0; z<sum.sizeZ(); ++z) {
                    for (int y=0; y<sum.sizeY(); ++y) {
                        for (int x=0; x<sum.sizeX(); ++x) {
                            if (mask.contains(x, y, z) && mask.insideMask(x, y, z)) sum.setPixel(x, y, z, sum.getPixel(x, y, z)+1);
                        }
                    }
                }
            }
            int threshold = (int)((track.size()+1)/2d);
            if (debug && track.get(0).getIdx()==debugIdx) Core.showImage(sum.setName("sum thld: "+threshold));
            
            for (SegmentedObject o : track) {
                o.getRegion().ensureMaskIsImageInteger();
                ImageInteger mask = o.getRegion().getMaskAsImageInteger();
                loop(mask.getBoundingBox().resetOffset(), (x, y, z)->{ mask.setPixel(x, y, z, sum.getPixelInt(x, y, z)>=threshold?1:0);});
                o.getRegion().resetMask(); // reset mask because bounds might have changed
                o.getRegion().clearVoxels();
            }
        }
    }
    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{referencePoint};
    }
    
}
