package bacmman.plugins.plugins;

import bacmman.configuration.parameters.FloatParameter;
import bacmman.configuration.parameters.Parameter;
import bacmman.data_structure.SegmentedObject;
import bacmman.data_structure.TrackLinkEditor;
import bacmman.plugins.ImageProcessingPlugin;
import java.util.List;
import java.util.Comparator;

public interface ManualTracker extends ImageProcessingPlugin.ImageProcessingPluginTemporalNeighborhood {
    void manualLink(List<SegmentedObject> currentObjects, List<SegmentedObject> previousCandidates, List<SegmentedObject> nextCandidates, TrackLinkEditor editor);

    class OverlapTracker implements ManualTracker {
        FloatParameter minOverlap = new FloatParameter("Min Overlap", 0.25).setLowerBound(0).setUpperBound(1).setHint("min value for candidate overlap fraction");
        @Override
        public void manualLink(List<SegmentedObject> currentObjects, List<SegmentedObject> previousCandidates, List<SegmentedObject> nextCandidates, TrackLinkEditor editor) {
            if (currentObjects.stream().anyMatch(o -> o.getStructureIdx() != editor.getEditableObjectClassIdx())) throw new RuntimeException("Object class differ from editable object class");
            if (previousCandidates!=null && previousCandidates.stream().anyMatch(o -> o.getStructureIdx() != editor.getEditableObjectClassIdx())) throw new RuntimeException("Object class differ from editable object class");
            if (nextCandidates!=null && nextCandidates.stream().anyMatch(o -> o.getStructureIdx() != editor.getEditableObjectClassIdx())) throw new RuntimeException("Object class differ from editable object class");
            currentObjects.sort(Comparator.comparingDouble(o -> o.getRegion().size()));
            if (previousCandidates != null) {
                previousCandidates.removeIf(o -> o.getNext() != null);
                if (!previousCandidates.isEmpty()) {
                    for (SegmentedObject o : currentObjects) {
                        if (o.getPrevious() == null) {
                            SegmentedObject prev = getMostOverlapping(o, previousCandidates, minOverlap.getDoubleValue());
                            if (prev != null) {
                                editor.setTrackLinks(prev, o, true, true, true);
                                previousCandidates.remove(prev);
                            }
                        }
                    }
                }
            }
            if (nextCandidates != null) {
                nextCandidates.removeIf(o -> o.getPrevious() != null);
                if (!nextCandidates.isEmpty()) {
                    for (SegmentedObject o : currentObjects) {
                        if (o.getNext() == null) {
                            SegmentedObject next = getMostOverlapping(o, nextCandidates, minOverlap.getDoubleValue());
                            if (next != null) {
                                editor.setTrackLinks(o, next, true, true, true);
                                nextCandidates.remove(next);
                            }
                        }
                    }
                }
            }
        }

        @Override
        public Parameter[] getParameters() {
            return new Parameter[]{minOverlap};
        }

        static SegmentedObject getMostOverlapping(SegmentedObject o, List<SegmentedObject> candidates, double minOverlap) {
            double[] ovA = new double[1];
            SegmentedObject[] mostOverlapping = new SegmentedObject[1];
            for (SegmentedObject c : candidates) {
                double ov = o.getRegion().getOverlapArea(c.getRegion());
                if (ov > ovA[0]) {
                    ovA[0] = ov;
                    mostOverlapping[0] = c;
                }
            }
            if (mostOverlapping[0] != null && ovA[0] / o.getRegion().size() >= minOverlap) return mostOverlapping[0];
            else return null;
        }
    }
}

