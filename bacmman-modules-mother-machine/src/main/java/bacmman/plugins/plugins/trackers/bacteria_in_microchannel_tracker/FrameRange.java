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
package bacmman.plugins.plugins.trackers.bacteria_in_microchannel_tracker;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 *
 * @author Jean Ollion
 */
public class FrameRange implements Comparable<FrameRange>{
    int min, max; 
    /**
     * 
     * @param fMin min frame, included
     * @param fMax max frame, included
     */
    public FrameRange(int fMin, int fMax) {
        this.min=fMin;
        this.max= fMax;
    }
    public FrameRange merge(FrameRange other) {
        this.min = Math.min(min, other.min);
        this.max = Math.max(max, other.max);
        return this;
    }
    public int size() {
        return max-min+1;
    }
    public boolean overlap(FrameRange other) {
        return overlap(other, 0);
    }
    public boolean overlap(FrameRange other, int tolerance) {
        if (other==null) return false;
        int c = compareTo(other);
        if (c==0) return true;
        if (c==1) return other.overlap(this);
        return max+tolerance>=other.min;
    }
    /**
     * 
     * @param frame
     * @return true if {@param frame} is included in this range
     */
    public boolean isIncluded(int frame) {
        return frame>=min && frame<=max;
    }
    /**
     * 
     * @param other
     * @return true if {@param other} is included in this frama range
     */
    public boolean isIncludedIn(FrameRange other) {
        return min>=other.min && max<=other.max;
    }
    @Override
    public int compareTo(FrameRange o) {
        int c = Integer.compare(min, o.min);
        if (c!=0) return c;
        return Integer.compare(max, o.max);
    }
    
    @Override
    public String toString() {
        return "["+min+";"+max+"]";
    }
    
    public static FrameRange getContainingRange(List<FrameRange> sortedRangeCandidates, FrameRange range) {
        int idx = Collections.binarySearch(sortedRangeCandidates, range, (r1, r2)->Integer.compare(r1.min, r2.min));
        if (idx>=0) return sortedRangeCandidates.get(idx);
        FrameRange fr = sortedRangeCandidates.get(-idx-2);
        if (!range.isIncludedIn(fr)) throw new RuntimeException("Could not find range");
        return fr;
    }
    
    public static void mergeOverlappingRanges(List<FrameRange> ranges) {
        mergeOverlappingRanges(ranges, 0);
    }
    public static void mergeOverlappingRanges(List<FrameRange> ranges, int tolerance) {
        if (ranges==null || ranges.size()<=1) return;
        Collections.sort(ranges);
        Iterator<FrameRange> it = ranges.iterator();
        FrameRange prev = it.next();
        while(it.hasNext()) {
            FrameRange cur = it.next();
            if (prev.overlap(cur)) {
                prev.merge(cur);
                it.remove();
            } else prev = cur;
        }
    }
    
    public static List<FrameRange> getContinuousRangesFromFrameIndices(int[] frameIndices) {
        if (frameIndices.length==0) return Collections.EMPTY_LIST;
        List<FrameRange> res = new ArrayList<>();
        FrameRange cur = new FrameRange(frameIndices[0], frameIndices[0]);
        int curIdx = 1;
        while (true) {
            while(curIdx<frameIndices.length && frameIndices[curIdx]==frameIndices[curIdx-1]+1) ++curIdx;
            cur.max = frameIndices[curIdx-1];
            res.add(cur);
            if (curIdx==frameIndices.length) break;
            cur = new FrameRange(frameIndices[curIdx], frameIndices[curIdx]);
            ++curIdx;
        }
        return res;
    }
    /**
     * Ensure ranges have a distance of 2 frames & explore the whole bounds of {@param bounds}
     * @param sortedRanges
     * @param bounds 
     */
    public static void ensureContinuousRanges(List<FrameRange> sortedRanges, FrameRange bounds) {
        if (!sortedRanges.get(sortedRanges.size()-1).isIncludedIn(bounds)) throw new IllegalArgumentException("Last frame range not included in bounds");
        if (bounds.size()<=sortedRanges.size()*3 || bounds.size()<=5) {
            sortedRanges.clear();
            sortedRanges.add(bounds);
            return;
        }
        sortedRanges.get(0).min = bounds.min;
        
        sortedRanges.get(sortedRanges.size()-1).max = bounds.max;
        for (int i = 1; i<sortedRanges.size(); ++i) {
            FrameRange prev = sortedRanges.get(i-1);
            FrameRange cur = sortedRanges.get(i);
            if (cur.min>prev.max+2) { // space between 2 frames range is > 2 -> set to 2
                cur.min = (cur.min+prev.max)/2+1;
                prev.max = cur.min-2;
            } else { // space <2 -> set to 2
                if (cur.size()>prev.size()) cur.min++;
                else prev.max--;
            }
        }
    }

    @Override
    public int hashCode() {
        int hash = 5;
        hash = 53 * hash + this.min;
        hash = 53 * hash + this.max;
        return hash;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        final FrameRange other = (FrameRange) obj;
        if (this.min != other.min) {
            return false;
        }
        if (this.max != other.max) {
            return false;
        }
        return true;
    }
    
    
}
