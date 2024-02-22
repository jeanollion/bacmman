package bacmman.plugins.plugins.track_pre_filters;

import bacmman.configuration.parameters.BooleanParameter;
import bacmman.configuration.parameters.FileChooser;
import bacmman.configuration.parameters.Parameter;
import bacmman.configuration.parameters.TextParameter;
import bacmman.data_structure.SegmentedObject;
import bacmman.data_structure.SegmentedObjectImageMap;
import bacmman.image.Image;
import bacmman.plugins.DevPlugin;
import bacmman.plugins.ProcessingPipeline;
import bacmman.plugins.TrackPreFilter;
import bacmman.py_dataset.PyDatasetReader;
import bacmman.utils.Pair;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

public class ImportH5File implements TrackPreFilter, DevPlugin {
    public final FileChooser h5File = new FileChooser("Input images", FileChooser.FileChooserOption.FILE_ONLY).setEmphasized(true);// todo add relative path option in FileChooser parameter
    public final  TextParameter datasetName = new TextParameter("Dataset name", "edm", true, false).setEmphasized(true);
    public final  TextParameter groupName = new TextParameter("Group name", "", true, true).setEmphasized(true);
    public final BooleanParameter binaryImage = new BooleanParameter("Binary", false).setEmphasized(true);
    public Pair<String, String> getFileAndDatasetName() {
        return new Pair<String, String>(h5File.getFirstSelectedFilePath(), datasetName.getValue());
    }
    public ImportH5File setDatasetName(String name) {
        this.datasetName.setName(name);
        return this;
    }
    public ImportH5File setBinary(boolean binary) {
        this.binaryImage.setSelected(binary);
        return this;
    }
    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{h5File, groupName, datasetName, binaryImage};
    }

    @Override
    public ProcessingPipeline.PARENT_TRACK_MODE parentTrackMode() {
        return ProcessingPipeline.PARENT_TRACK_MODE.MULTIPLE_INTERVALS;
    }

    @Override
    public void filter(int structureIdx, SegmentedObjectImageMap preFilteredImages) {
        if (preFilteredImages.isEmpty()) return;
        List<SegmentedObject> track = preFilteredImages.streamKeys().collect(Collectors.toList());
        getImages(track).forEach(preFilteredImages::set); // TODO : proceed by segments if needed
    }
    public Map<SegmentedObject, Image> getImages(List<SegmentedObject> track) {
        File file = new File(h5File.getFirstSelectedFilePath());
        if (!file.exists() || !file.isFile()) throw new RuntimeException("ImportH5 file not found @ "+h5File.getFirstSelectedFilePath());
        return getImages(track, file, groupName.getValue(), datasetName.getValue(), binaryImage.getSelected());
    }
    public static Map<SegmentedObject, Image> getImages(List<SegmentedObject> track, File file, String groupName, String datasetName, boolean binary) {
        PyDatasetReader reader = new PyDatasetReader(file);
        SegmentedObject ref = track.get(0);
        String dbName = ref.getExperimentStructure().getDatasetName();
        String posName = ref.getPositionName();
        PyDatasetReader.DatasetAccess datasetAccess = reader.getDatasetAccess(groupName, dbName, 1, 0);
        if (datasetAccess==null) throw new RuntimeException("Dataset not found: file:"+file+" group:"+groupName+" db:"+dbName);
        return datasetAccess.extractImagesForTrack(datasetName, posName, track, binary);
    }
}
