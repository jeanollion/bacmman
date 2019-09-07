package bacmman.plugins;

import bacmman.data_structure.SegmentedObject;
import bacmman.data_structure.SegmentedObjectUtils;
import bacmman.data_structure.dao.ObjectDAO;
import bacmman.image.Histogram;
import bacmman.image.HistogramFactory;
import bacmman.image.Image;
import bacmman.plugins.Plugin;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public interface HistogramScaler extends Plugin {
    void setHistogram(Histogram histogram);
    Image scale(Image image);
    boolean isConfigured();


}
