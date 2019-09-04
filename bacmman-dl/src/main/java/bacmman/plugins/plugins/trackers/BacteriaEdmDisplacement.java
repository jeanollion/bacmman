package bacmman.plugins.plugins.trackers;

import bacmman.configuration.parameters.*;
import bacmman.data_structure.*;
import bacmman.image.Image;
import bacmman.image.Offset;
import bacmman.measurement.BasicMeasurements;
import bacmman.plugins.Segmenter;
import bacmman.plugins.TestableProcessingPlugin;
import bacmman.plugins.Tracker;
import bacmman.plugins.TrackerSegmenter;
import bacmman.plugins.plugins.segmenters.BacteriaEDM;
import bacmman.plugins.plugins.track_pre_filters.ImportH5File;
import bacmman.plugins.plugins.trackers.trackmate.TrackMateInterface;
import bacmman.utils.geom.Point;
import fiji.plugin.trackmate.Spot;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class BacteriaEdmDisplacement implements Tracker, TestableProcessingPlugin {
    ImportH5File h5data = new ImportH5File().setDatasetName("dy");
    GroupParameter h5dataParameters=new GroupParameter("Displacement Predictions", h5data.h5File, h5data.groupName, h5data.datasetName.setName("Displacement dataset name")).setEmphasized(true);
    BoundedNumberParameter maxLinkingDistance = new BoundedNumberParameter("Max linking distnace", 1, 50, 0, null);
    @Override
    public void track(int structureIdx, List<SegmentedObject> parentTrack, TrackLinkEditor editor) {
        Map<SegmentedObject, Image> dy = h5data.getImages(parentTrack);
        if (stores!=null) dy.forEach((o, im) -> stores.get(o).addIntermediateImage("dy", im));
        Map<Region, Double> displacementMap = parentTrack.stream().flatMap(p->p.getChildren(structureIdx)).parallel().collect(Collectors.toMap(
                o->o.getRegion(),
                o-> BasicMeasurements.getMeanValue(o.getRegion(), dy.get(o.getParent())) * o.getParent().getBounds().sizeY() / 256d // factor is due to rescaling: dy is computed in pixels in the 32x256 image.
        ));
        Map<Region, Offset> parentOffset = parentTrack.stream().flatMap(p->p.getChildren(structureIdx)).parallel().collect(Collectors.toMap(o->o.getRegion(), o->o.getParent().getBounds()));
        TrackMateInterface<TrackingObject> tmi = new TrackMateInterface<>(new TrackMateInterface.SpotFactory<TrackingObject>() {
            @Override
            public TrackingObject toSpot(Region o, int frame) {
                return new TrackingObject(o, parentOffset.get(o), frame, displacementMap.get(o));
            }

            @Override
            public TrackingObject duplicate(TrackingObject trackingObject) {
                return new TrackingObject(trackingObject.r, parentOffset.get(trackingObject.r), trackingObject.frame(), trackingObject.getFeature("dy"));
            }
        });
        Map<Integer, List<SegmentedObject>> objectsF = SegmentedObjectUtils.getChildrenByFrame(parentTrack, structureIdx);
        tmi.addObjects(objectsF);
        tmi.processFTF(maxLinkingDistance.getValue().doubleValue());
        tmi.processGC(maxLinkingDistance.getValue().doubleValue(), 0, true, false); // division
        tmi.setTrackLinks(objectsF, editor);
    }
    class TrackingObject extends Spot {
        Region r;
        public TrackingObject(Region r, Offset offset, int frame, double dy) {
            super( r.getGeomCenter(false).translateRev(offset), 1, 1);
            this.r=r;
            getFeatures().put(Spot.FRAME, (double)frame);
            getFeatures().put("dy", dy);
        }
        public int frame() {
            return getFeature(Spot.FRAME).intValue();
        }
        @Override
        public double squareDistanceTo( final Spot s ) {
            if (s instanceof TrackingObject) {
                TrackingObject  next  = (TrackingObject)s;
                if (frame()==next.frame()+1) return next.squareDistanceTo(this);
                if (frame()!=next.frame()-1) return Double.POSITIVE_INFINITY;

                return Math.pow(getFeature(POSITION_X) - next.getFeature(POSITION_X),2) +
                        Math.pow(getFeature(POSITION_Y) - (next.getFeature(POSITION_Y) - next.getFeature("dy")) , 2) + // translate next y coord
                        Math.pow(getFeature(POSITION_Z)-next.getFeature(POSITION_Z),2);

                // other possible distance: overlap of translated region -> would be more efficient with a regression of distance to split parent center when division

            } else return Double.POSITIVE_INFINITY;
        }
    }

    @Override
    public Parameter[] getParameters() {
        return new Parameter[] {h5dataParameters};
    }


    // testable processing plugin
    Map<SegmentedObject, TestableProcessingPlugin.TestDataStore> stores;
    @Override public void setTestDataStore(Map<SegmentedObject, TestableProcessingPlugin.TestDataStore> stores) {
        this.stores=  stores;
    }

}
