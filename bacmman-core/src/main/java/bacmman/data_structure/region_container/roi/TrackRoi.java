package bacmman.data_structure.region_container.roi;

import bacmman.configuration.experiment.Structure;

import java.awt.*;

public interface TrackRoi {
    Structure.TRACK_DISPLAY getDisplayType();
    void setColor(Color color, double edgeOpacity, double fillOpacity, double arrowOpacity);

}
