package bacmman.processing.track_post_processing;

import bacmman.data_structure.SegmentedObject;

import java.util.*;
import java.util.stream.Collectors;

public class TrackTree extends TreeMap<SegmentedObject, Track> {

    public Track getFirstMerge() {
        return values().stream().filter(Track::merge).findFirst().orElse(null);
    }

    public Track getLastMerge() {
        return descendingMap().values().stream().filter(Track::merge).findFirst().orElse(null);
    }

    public static List<TrackTree> getIndependentTrackTrees(Map<SegmentedObject, Track> tracks) {
        List<TrackTree> trees = new ArrayList<>();
        tracks.forEach((head, track) -> {
            List<TrackTree> connected = trees.stream().filter(track::belongTo).collect(Collectors.toList());
            if (connected.isEmpty()) {
                TrackTree t = new TrackTree();
                t.put(head, track);
                trees.add(t);
            } else {
                connected.get(0).put(head, track); // add to existing tree
                if (connected.size()>1) { // fusion
                    for (int i = 1; i<connected.size(); ++i) connected.get(0).putAll(connected.get(i));
                }
            }
        });
        return trees;
    }
}
