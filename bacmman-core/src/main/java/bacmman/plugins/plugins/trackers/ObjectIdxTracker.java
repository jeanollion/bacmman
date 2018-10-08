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
package bacmman.plugins.plugins.trackers;

import bacmman.configuration.parameters.ChoiceParameter;
import bacmman.configuration.parameters.Parameter;
import bacmman.data_structure.*;
import bacmman.image.BoundingBox;
import bacmman.plugins.Hint;
import bacmman.plugins.Tracker;
import bacmman.utils.Utils;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 *
 * @author Jean Ollion
 */
public class ObjectIdxTracker implements Tracker, Hint {

    @Override
    public String getHintText() {
        return "Link objects according to their index";
    }

    
    public static enum IndexingOrder {XYZ(0, 1, 2), YXZ(1, 0, 2), XZY(0, 2, 1), ZXY(2, 0, 1), ZYX(2, 1, 0);
        public final int i1, i2, i3;
        IndexingOrder(int i1, int i2, int i3) {
            this.i1=i1;
            this.i2=i2;
            this.i3=i3;
        }
    };
    ChoiceParameter order = new ChoiceParameter("Indexing order", Utils.toStringArray(IndexingOrder.values()), IndexingOrder.XYZ.toString(), false);
    
    public void assignPrevious(List<SegmentedObject> previous, List<SegmentedObject> next, TrackLinkEditor editor) {
        int lim = Math.min(previous.size(), next.size());
        for (int i = 0; i<lim; ++i) {
            editor.setTrackLinks(previous.get(i), next.get(i), true, true, true);
            //Plugin.logger.trace("assign previous {} to next {}", previous.get(i), next.get(i));
        }
        for (int i = lim; i<next.size(); ++i) editor.resetTrackLinks(next.get(i),true, true, true);
    }
    
    public void track(int structureIdx, List<SegmentedObject> parentTrack, TrackLinkEditor editor) {
        if (parentTrack.isEmpty()) return;
        List<SegmentedObject> previousChildren = parentTrack.get(0).getChildren(structureIdx).collect(Collectors.toList());
        Collections.sort(previousChildren, getComparator(IndexingOrder.valueOf(order.getSelectedItem())));
        for (int i = 1; i<parentTrack.size(); ++i) {
            List<SegmentedObject> currentChildren = parentTrack.get(i).getChildren(structureIdx).collect(Collectors.toList());
            Collections.sort(currentChildren, getComparator(IndexingOrder.valueOf(order.getSelectedItem())));
            assignPrevious(previousChildren, currentChildren, editor);
            previousChildren = currentChildren;
        }
    }
    
    public static Comparator<? super SegmentedObject> getComparator(final IndexingOrder order) {
        return (Comparator<SegmentedObject>) (arg0, arg1) -> compareCenters(getCenterArray(arg0.getRegion().getBounds()), getCenterArray(arg1.getRegion().getBounds()), order);
    }
    
    public static Comparator<Region> getComparatorRegion(final IndexingOrder order) {
        return (arg0, arg1) -> compareCenters(getCenterArray(arg0.getBounds()), getCenterArray(arg1.getBounds()), order);
    }
    
    private static double[] getCenterArray(BoundingBox b) {
        return new double[]{b.xMean(), b.yMean(), b.zMean()};
    }
    
    public static int compareCenters(double[] o1, double[] o2, IndexingOrder order) {
        if (o1[order.i1]!=o2[order.i1]) return Double.compare(o1[order.i1], o2[order.i1]);
        else if (o1[order.i2]!=o2[order.i2]) return Double.compare(o1[order.i2], o2[order.i2]);
        else return Double.compare(o1[order.i3], o2[order.i3]);
    }

    public Parameter[] getParameters() {
        return new Parameter[0];
    }

    public boolean does3D() {
        return true;
    }
    
}