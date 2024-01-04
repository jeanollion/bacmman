package bacmman.plugins.plugins.segmenters;

import bacmman.configuration.parameters.FileChooser;
import bacmman.configuration.parameters.Parameter;
import bacmman.data_structure.Region;
import bacmman.data_structure.RegionPopulation;
import bacmman.data_structure.SegmentedObject;
import bacmman.data_structure.region_container.roi.IJRoi3D;
import bacmman.image.Image;
import bacmman.plugins.Segmenter;
import ij.gui.Roi;
import ij.io.RoiDecoder;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;

public class ImportROI implements Segmenter {
    FileChooser roiFile = new FileChooser("ROI File", FileChooser.FileChooserOption.FILE_ONLY, false).setEmphasized(true).setHint("Choose file containing exported ROI");

    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{roiFile};
    }

    @Override
    public RegionPopulation runSegmenter(Image input, int objectClassIdx, SegmentedObject parent) {
        String path = roiFile.getFirstSelectedFilePath();
        File f = new File(path);
        if (!f.exists()) throw new RuntimeException("File do not exist");
        Roi r = RoiDecoder.open(path);
        RoiDecoder rd = new RoiDecoder(path);
        try {
            Roi ijRoi = rd.getRoi();
            IJRoi3D roi = new IJRoi3D(1);
            roi.setIs2D(true);
            roi.put(0, ijRoi);
            Region region = new Region(roi, 1, roi.getBounds(), parent.getScaleXY(), parent.getScaleZ());
            return new RegionPopulation(new ArrayList<Region>(){{add(region);}}, input);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
