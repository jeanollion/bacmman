package bacmman.processing.track_post_processing;

import bacmman.data_structure.SegmentedObject;
import bacmman.data_structure.SegmentedObjectFactory;
import bacmman.data_structure.TrackLinkEditor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class TrackTree extends TreeMap<SegmentedObject, Track> {
    public final static Logger logger = LoggerFactory.getLogger(TrackTree.class);
    public int getFisrtFrame() {
        return values().stream().findFirst().get().getFirstFrame();
    }

    public Track getFirstMerge() {
        return values().stream().filter(Track::merge).findFirst().orElse(null);
    }

    public Track getNextMerge(Track from, Set<Track> seen) {
        return values().stream().filter(Track::merge).filter(t -> t.getFirstFrame() >= from.getFirstFrame() && !t.equals(from) && !seen.contains(t)).findFirst().orElse(null);
    }

    public Track getFirstSplit() {
        return values().stream().filter(Track::split).findFirst().orElse(null);
    }

    public Track getNextSplit(Track from, Set<Track> seen) {
        return values().stream().filter(Track::split).filter(t -> t.getFirstFrame() >= from.getFirstFrame() && !t.equals(from) && !seen.contains(t)).findFirst().orElse(null);
    }

    /*public Collection<TrackTree> split(Track t1, Track t2, SplitAndMerge sm, TrackAssigner assigner, SegmentedObjectFactory factory, TrackLinkEditor editor) {
        boolean change = split(t1, t2, false, sm, assigner, factory, editor) || split(t1, t2, true, sm, assigner, factory, editor);
        if (change) {
            TrackTreePopulation newPop = new TrackTreePopulation(this);
            return newPop.trees;
        }
        return null;
    }*/

    /*protected boolean split(Track t1, Track t2, boolean next, SplitAndMerge sm, TrackAssigner assigner, SegmentedObjectFactory factory, TrackLinkEditor editor) {
        Track toSplit = t1.getCommonTrack(t2, next, false);
        return split(toSplit, next, sm, assigner, factory, editor);
    }*/


    public Track split(Track toSplit, SplitAndMerge sm, TrackAssigner assigner, SegmentedObjectFactory factory, TrackLinkEditor editor) {
        if (toSplit.getSplitRegions()==null) toSplit.setSplitRegions(sm);
        Track newTrack = Track.splitTrack(toSplit, assigner, factory, editor);
        if (newTrack!=null) {
            toSplit = toSplit.simplifyTrack(editor, toRemove -> remove(toRemove.head()));
            if (get(toSplit.head())==null) throw new RuntimeException("Track is absent after simplify"+toSplit);
            newTrack = newTrack.simplifyTrack(editor, toRemove -> remove(toRemove.head()));
            if (newTrack.belongTo(this)) put(newTrack.head(), newTrack);
        }
        return newTrack;
    }

    @Override
    public boolean equals(Object object) {
        return this == object;
    }

    @Override
    public int hashCode() {
        return System.identityHashCode(this);
    }
}
