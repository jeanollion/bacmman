package bacmman.data_structure;

import bacmman.data_structure.region_container.RegionContainerIjRoi;
import bacmman.data_structure.region_container.roi.Roi3D;
import bacmman.image.*;
import bacmman.image.wrappers.ImgLib2ImageWrapper;
import bacmman.utils.Pair;
import bacmman.utils.geom.Point;
import fiji.plugin.trackmate.Model;
import fiji.plugin.trackmate.SpotCollection;
import fiji.plugin.trackmate.SpotRoi;
import fiji.plugin.trackmate.TrackModel;
import ij.gui.Roi;
import net.imagej.ImgPlus;
import org.jgrapht.graph.DefaultWeightedEdge;
import org.jgrapht.graph.SimpleWeightedGraph;

import java.awt.*;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class BacmmanToTrackMate {
    public static Model getSpotsAndTracks(List<SegmentedObject> parentTrack, int objectClassIdx) {
        return getSpotsAndTracks(parentTrack, TrackMateToBacmman.getDefaultOffset(parentTrack), objectClassIdx);
    }
    public static double getFrameDuration(SegmentedObject object) {
        return object.getExperiment()==null? 1d: object.getExperiment().getPosition(object.getPositionName()).getFrameDuration();
    }
    public static Model getSpotsAndTracks(List<SegmentedObject> parentTrack, List<BoundingBox> parentTrackOffset, int objectClassIdx) {
        assert parentTrack.size()==parentTrackOffset.size() : "parent track  & offset lists should match" ;
        double timeScale = getFrameDuration(parentTrack.get(0));
        IntFunction<Offset> getOffset = i -> parentTrackOffset.get(i).duplicate().translate(parentTrack.get(i).getBounds().duplicate().reverseOffset());
        SpotCollection spots = new SpotCollection();
        SimpleWeightedGraph<fiji.plugin.trackmate.Spot, DefaultWeightedEdge> graph = new SimpleWeightedGraph<>(DefaultWeightedEdge.class);
        Map<SegmentedObject, fiji.plugin.trackmate.Spot> map = new HashMap<>();
        IntStream.range(0, parentTrack.size()).forEach(i -> parentTrack.get(i).getDirectChildren(objectClassIdx).stream().forEach(c -> {
            fiji.plugin.trackmate.Spot spot = regionToSpot(c.getRegion(), getOffset.apply(i), c.getFrame(), timeScale);
            map.put(c, spot);
            spots.add(spot, c.getFrame());
            graph.addVertex(spot);
        }));
        int minFrame = parentTrack.get(0).getFrame();
        int maxFrame = parentTrack.get(parentTrack.size()-1).getFrame();
        SegmentedObjectUtils.getAllChildrenAsStream(parentTrack.stream(), objectClassIdx).forEach( c -> {
            fiji.plugin.trackmate.Spot s=map.get(c);
            SegmentedObject prev = c.getPrevious();
            if (prev!=null && prev.getFrame()>=minFrame) graph.addEdge(map.get(prev), s);
            SegmentedObject next = c.getNext();
            if (next!=null && next.getFrame()<=maxFrame) graph.addEdge(s, map.get(next));
        } );
        Model model = new Model();
        model.setSpots(spots, false);
        model.setTracks(graph, false);
        return model;
    }
    public static SimpleWeightedGraph<SegmentedObject, DefaultWeightedEdge> getGraph(List<SegmentedObject> objects) {
        SimpleWeightedGraph<SegmentedObject, DefaultWeightedEdge> graph = new SimpleWeightedGraph<>(DefaultWeightedEdge.class);
        objects.forEach( c -> {
            graph.addVertex(c);
            SegmentedObject prev = c.getPrevious();
            if (prev!=null) {
                graph.addVertex(prev);
                graph.addEdge(prev, c);
            }
            SegmentedObject next = c.getNext();
            if (next!=null) {
                graph.addVertex(next);
                graph.addEdge(c, next);
            }
        } );
        return graph;
    }
    public static fiji.plugin.trackmate.Spot regionToSpot(Region region, Offset offset, int frame, double timescale) {
        double scale = region.getScaleXY();
        Point centerTemp = region.getCenter()==null ? region.getGeomCenter(false) : region.getCenter();
        Point center = centerTemp.numDimensions()==2 ? new Point(centerTemp.get(0), centerTemp.get(1), offset.zMin()) : centerTemp;
        double radius;
        SpotRoi roi = null;
        if (region instanceof Spot) radius = ((Spot)region).getRadius() * region.getScaleXY();
        else if (region instanceof Ellipse2D) radius = region.getScaleXY() * (((Ellipse2D)region).getMajor() + ((Ellipse2D)region).getMinor())/4 ;
        else {
            // create ROI.
            ImageMask mask = region.getMask();
            if (mask.sizeZ()>1) { // in case object is 3D -> get only plane that corresponds to the center
                int z = (int)(center.get(2) + 0.5) - region.getBounds().zMin();
                mask = region.getMaskAsImageInteger().getZPlane(z);
            }
            Roi3D objRoi = RegionContainerIjRoi.createRoi(mask, region.getBounds(), false);
            Roi r = objRoi.get(region.getBounds().zMin());
            Polygon p = r.getPolygon();
            double[] x = Arrays.stream(p.xpoints).mapToDouble(i -> (i - center.get(0)) * scale).toArray();
            double[] y = Arrays.stream(p.ypoints).mapToDouble(i -> (i - center.get(1)) * scale).toArray();
            roi = new SpotRoi(x, y);
            radius = roi.radius();
        }
        center.translate(offset);
        // scale center
        center.multiplyDim(region.getScaleXY(), 0);
        center.multiplyDim(region.getScaleXY(), 1);
        center.multiplyDim(region.getScaleZ(), 2);
        fiji.plugin.trackmate.Spot res = new fiji.plugin.trackmate.Spot(center, radius, region.getQuality());
        res.setRoi(roi);
        if (region instanceof Ellipse2D) {
            Ellipse2D e = (Ellipse2D) region;
            res.getFeatures().put("ELLIPSE_MAJOR", e.getMajor() * scale);
            res.getFeatures().put("ELLIPSE_MINOR", e.getMinor() * scale);
            res.getFeatures().put("ELLIPSE_THETA", e.getTheta());
            // TODO other ellipse feature need to be set ?
        }
        res.getFeatures().put(fiji.plugin.trackmate.Spot.POSITION_T, frame * timescale);
        res.getFeatures().put(fiji.plugin.trackmate.Spot.FRAME, (double)frame);
        return res;
    }
}
