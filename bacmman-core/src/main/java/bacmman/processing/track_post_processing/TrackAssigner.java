package bacmman.processing.track_post_processing;

import bacmman.data_structure.SegmentedObject;
import bacmman.data_structure.TrackLinkEditor;
import bacmman.processing.matching.LAPLinker;
import bacmman.utils.Pair;
import bacmman.utils.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

import static bacmman.processing.track_post_processing.Track.getTrack;

public interface TrackAssigner {
    public final static Logger logger = LoggerFactory.getLogger(TrackAssigner.class);
    void assignTracks(Collection<Track> prevTracks, Collection<Track> nextTracks, TrackLinkEditor editor);
    class TrackAssignerDistance implements TrackAssigner {

        @Override
        public void assignTracks(Collection<Track> prevTracks, Collection<Track> nextTracks, TrackLinkEditor editor) {
            if (prevTracks.isEmpty()) {
                nextTracks.forEach(n->n.getPrevious().clear());
                return;
            }
            if (nextTracks.isEmpty()) {
                prevTracks.forEach(p -> p.getNext().clear());
                return;
            }
            if (!Utils.objectsAllHaveSameProperty(prevTracks, Track::getLastFrame)) throw new IllegalArgumentException("prev tracks do not end at same frame");
            if (!Utils.objectsAllHaveSameProperty(nextTracks, Track::getFirstFrame)) throw new IllegalArgumentException("next tracks do not start at same frame");
            int prevFrame = prevTracks.iterator().next().getLastFrame();
            int nextFrame = nextTracks.iterator().next().getFirstFrame();
            if (prevFrame+1!=nextFrame) throw new IllegalArgumentException("frame should be successive");
            if (prevTracks.size()==2 && nextTracks.size()==2) { // quicker method for the most common case: 2 vs 2
                List<Track> prevL = prevTracks instanceof List ? (List<Track>)prevTracks : new ArrayList<>(prevTracks);
                List<Track> nextL = nextTracks instanceof List ? (List<Track>)nextTracks : new ArrayList<>(nextTracks);
                boolean matchOrder = Track.matchOrder(new Pair<>(prevL.get(0).tail().getRegion(), prevL.get(1).tail().getRegion()), new Pair<>(nextL.get(0).head().getRegion(), nextL.get(1).head().getRegion()));
                nextTracks.forEach(n -> n.getPrevious().clear());
                prevTracks.forEach(p -> p.getNext().clear());
                if (matchOrder) {
                    prevL.get(0).addNext(nextL.get(0));
                    prevL.get(1).addNext(nextL.get(1));
                    nextL.get(0).addPrevious(prevL.get(0));
                    nextL.get(1).addPrevious(prevL.get(1));
                    editor.setTrackLinks(prevL.get(0).tail(), nextL.get(0).head(), true, true, false);
                    editor.setTrackLinks(prevL.get(1).tail(), nextL.get(1).head(), true, true, false);
                } else {
                    prevL.get(0).addNext(nextL.get(1));
                    prevL.get(1).addNext(nextL.get(0));
                    nextL.get(0).addPrevious(prevL.get(1));
                    nextL.get(1).addPrevious(prevL.get(0));
                    editor.setTrackLinks(prevL.get(0).tail(), nextL.get(1).head(), true, true, false);
                    editor.setTrackLinks(prevL.get(1).tail(), nextL.get(0).head(), true, true, false);
                }
                nextTracks.forEach(n -> editor.setTrackHead(n.head(), n.head(), false, false)); // keep track head as long as tracks are not fused
            } else {
                Map<Integer, List<SegmentedObject>> map = new HashMap<>();
                map.put(prevFrame, prevTracks.stream().map(Track::tail).collect(Collectors.toList()));
                map.put(nextFrame, nextTracks.stream().map(Track::head).collect(Collectors.toList()));
                LAPLinker<LAPLinker.SpotImpl> tmi = new LAPLinker<>(LAPLinker.defaultFactory());
                tmi.addObjects(map);
                double dMax = Math.sqrt(Double.MAX_VALUE) / 100; // not Double.MAX_VALUE -> crash because squared..
                boolean ok = tmi.processFTF(dMax, prevFrame, nextFrame);
                tmi.processSegments(dMax, 1, true, true);
                if (ok) {
                    //logger.debug("assign: number of edges {}, number of objects: {}", tmi.edgeCount(), tmi.graphObjectMapper.graphObjects().size());
                    tmi.setTrackLinks(map, editor, false);
                    nextTracks.forEach(n -> n.getPrevious().clear());
                    prevTracks.forEach(p -> p.getNext().clear());
                    nextTracks.forEach(n -> {
                        List<SegmentedObject> allPrev = tmi.getAllPrevious(tmi.graphObjectMapper.getGraphObject(n.head().getRegion())).stream().map(np -> tmi.getSegmentedObject(map.get(prevFrame), np)).collect(Collectors.toList());
                        //logger.debug("next track {}, assigned prev: {}", n, allPrev);
                        allPrev.forEach(np -> {
                            Track p = getTrack(prevTracks, np.getTrackHead());
                            if (p != null) {
                                n.addPrevious(p);
                                p.addNext(n);
                            }
                        });
                    });
                    prevTracks.forEach(p -> {
                        List<SegmentedObject> allNexts = tmi.getAllNexts(tmi.graphObjectMapper.getGraphObject(p.tail().getRegion())).stream().map(np -> tmi.getSegmentedObject(map.get(prevFrame), np)).collect(Collectors.toList());
                        //logger.debug("prev track {}, assigned prev: {}", p, allNexts);
                        allNexts.forEach(pn -> {
                            Track n = getTrack(nextTracks, pn);
                            if (n != null) {
                                n.addPrevious(p);
                                p.addNext(n);
                            }
                        });
                    });
                } else {
                    logger.debug("Could not assign");
                }
            }
        }
    }
}
