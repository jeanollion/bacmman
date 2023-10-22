package bacmman.processing.track_post_processing;

import bacmman.data_structure.SegmentedObject;
import bacmman.data_structure.TrackLinkEditor;
import bacmman.processing.matching.LAPLinker;
import bacmman.utils.Pair;
import bacmman.utils.Utils;
import org.apache.commons.lang.NotImplementedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static bacmman.processing.track_post_processing.Track.getTrack;

public interface TrackAssigner {
    public final static Logger logger = LoggerFactory.getLogger(TrackAssigner.class);
    void assignTracks(Collection<Track> prevTracks, Collection<Track> nextTracks, TrackLinkEditor editor);

    static void setLinks(Collection<Track> tracks, boolean setNext, TrackLinkEditor editor) {
        if (setNext) {
            tracks.forEach(pt -> {
                if (pt.getNext().size() == 1) editor.setTrackLinks(pt.tail(), pt.getNext().get(0).head(), false, true, false, false);
                else editor.setTrackLinks(pt.tail(), null, false, true, false, false);
            });
        } else {
            tracks.forEach(nt -> {
                if (nt.getPrevious().size() == 1) editor.setTrackLinks(nt.getPrevious().get(0).tail(), nt.head(), true, false, false, false);
                else editor.setTrackLinks(null, nt.head(), true, false, false, false);
            });
        }

    }
    static void assignOneToN(Collection<Track> prevTracks, Collection<Track> nextTracks, TrackLinkEditor editor) {
        if (prevTracks.size() == 1 ) {
            prevTracks.forEach(pt -> {
                nextTracks.forEach(nt -> {
                    pt.addNext(nt);
                    nt.addPrevious(pt);
                });
            });
            setLinks(prevTracks, true, editor);
        }
        if (nextTracks.size() == 1 ) {
            nextTracks.forEach(nt -> {
                prevTracks.forEach(pt -> {
                    pt.addNext(nt);
                    nt.addPrevious(pt);
                });
            });
            setLinks(nextTracks, false, editor);
        }
    }
    static void removeLinks(Collection<Track> prevs, Collection<Track> nexts) {
        prevs.forEach(p -> {
            nexts.forEach(n -> {
                p.getNext().remove(n);
                n.getPrevious().remove(p);
            });
        });
    }
    class TrackAssignerDistance implements TrackAssigner {
        final double dMax;
        public TrackAssignerDistance(double dMax) {
            this.dMax = dMax;
        }
        @Override
        public void assignTracks(Collection<Track> prevTracks, Collection<Track> nextTracks, TrackLinkEditor editor) {
            if (prevTracks.isEmpty() || nextTracks.isEmpty()) return;
            removeLinks(prevTracks, nextTracks);
            if (!Utils.objectsAllHaveSameProperty(prevTracks, Track::getLastFrame)) throw new IllegalArgumentException("prev tracks do not end at same frame");
            if (!Utils.objectsAllHaveSameProperty(nextTracks, Track::getFirstFrame)) throw new IllegalArgumentException("next tracks do not start at same frame");
            int prevFrame = prevTracks.iterator().next().getLastFrame();
            int nextFrame = nextTracks.iterator().next().getFirstFrame();
            if (prevFrame+1!=nextFrame) throw new IllegalArgumentException("frame should be successive");
            if (prevTracks.size() == 1 || nextTracks.size() == 1) {
                assignOneToN(prevTracks, nextTracks, editor);
            } else if (prevTracks.size()==2 && nextTracks.size()==2) { // quicker method for the most common case: 2 vs 2
                List<Track> prevL = prevTracks instanceof List ? (List<Track>)prevTracks : new ArrayList<>(prevTracks);
                List<Track> nextL = nextTracks instanceof List ? (List<Track>)nextTracks : new ArrayList<>(nextTracks);
                boolean matchOrder = Track.matchOrder(new Pair<>(prevL.get(0).tail().getRegion(), prevL.get(1).tail().getRegion()), new Pair<>(nextL.get(0).head().getRegion(), nextL.get(1).head().getRegion()));
                if (matchOrder) {
                    prevL.get(0).addNext(nextL.get(0));
                    prevL.get(1).addNext(nextL.get(1));
                    nextL.get(0).addPrevious(prevL.get(0));
                    nextL.get(1).addPrevious(prevL.get(1));
                } else {
                    prevL.get(0).addNext(nextL.get(1));
                    prevL.get(1).addNext(nextL.get(0));
                    nextL.get(0).addPrevious(prevL.get(1));
                    nextL.get(1).addPrevious(prevL.get(0));
                }
                setLinks(prevTracks, true, editor);
                setLinks(nextTracks, false, editor);
            } else {
                Map<Integer, List<SegmentedObject>> map = new HashMap<>();
                map.put(prevFrame, prevTracks.stream().map(Track::tail).collect(Collectors.toList()));
                map.put(nextFrame, nextTracks.stream().map(Track::head).collect(Collectors.toList()));
                LAPLinker<LAPLinker.SpotImpl> tmi = assign(dMax, map, prevFrame, nextFrame);
                if (tmi!=null) {
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
                    setLinks(prevTracks, true, editor);
                    setLinks(nextTracks, false, editor);
                } else {
                    logger.debug("Could not assign");
                }
            }
        }
    }

    static LAPLinker<LAPLinker.SpotImpl> assign(double dMax, Map<Integer, List<SegmentedObject>> map,  int prevFrame, int nextFrame) {
        //logger.error("assign {} to {}", map.get(prevFrame), map.get(nextFrame));
        LAPLinker<LAPLinker.SpotImpl> tmi = new LAPLinker<>(LAPLinker.defaultFactory());
        tmi.addObjects(map);
        boolean ok = tmi.processFTF(dMax, prevFrame, nextFrame);
        //logger.debug("assign FTF: {} -> {}", map.get(nextFrame), map.get(nextFrame).stream().map(o->tmi.getAllPrevious(tmi.graphObjectMapper.getGraphObject(o.getRegion())).stream().map(oo -> tmi.getSegmentedObject(map.get(prevFrame), oo)).collect(Collectors.toList())).collect(Collectors.toList()));
        if (!ok) return null;
        Predicate<SegmentedObject> hasNoNext = so -> tmi.getAllNexts(tmi.graphObjectMapper.getGraphObject(so.getRegion())).isEmpty();
        Predicate<SegmentedObject> hasNoPrev = so -> tmi.getAllPrevious(tmi.graphObjectMapper.getGraphObject(so.getRegion())).isEmpty();
        while(map.get(prevFrame).stream().anyMatch(hasNoNext) || map.get(nextFrame).stream().anyMatch(hasNoPrev)) {
            tmi.processSegments(dMax, 0, true, true);
            //logger.debug("assign SEG: {} -> {}", map.get(nextFrame), map.get(nextFrame).stream().map(o->tmi.getAllPrevious(tmi.graphObjectMapper.getGraphObject(o.getRegion())).stream().map(oo -> tmi.getSegmentedObject(map.get(prevFrame), oo)).collect(Collectors.toList())).collect(Collectors.toList()));
        }
        return tmi;
    }
}
