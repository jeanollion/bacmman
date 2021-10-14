/* 
 * Copyright (C) 2018 Jean Ollion
 *
 * This File is part of BACMMAN
 *
 * BACMMAN is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * BACMMAN is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with BACMMAN.  If not, see <http://www.gnu.org/licenses/>.
 */
package bacmman.configuration.experiment;

import bacmman.configuration.parameters.*;
import bacmman.data_structure.DLengineProvider;
import bacmman.data_structure.ExperimentStructure;
import bacmman.measurement.MeasurementKey;
import bacmman.measurement.MeasurementKeyObject;
import bacmman.configuration.parameters.FileChooser.FileChooserOption;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import bacmman.plugins.plugins.transformations.SelectBestFocusPlane;
import bacmman.utils.ArrayUtil;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import bacmman.plugins.Autofocus;
import bacmman.plugins.Measurement;
import bacmman.utils.HashMapGetCreate;
import bacmman.utils.Pair;
import com.google.common.collect.Sets;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 *
 * @author Jean Ollion
 * 
 */

public class Experiment extends ContainerParameterImpl<Experiment> {
    SimpleListParameter<ChannelImage> channelImages= new SimpleListParameter<>("Detection Channels", 0 , ChannelImage.class).setNewInstanceNameFunction((l, i)->"channel"+i).setHint("Define here the different channels of input images");
    SimpleListParameter<ChannelImageDuplicated> channelImagesDuplicated= new SimpleListParameter<>("Duplicated Detection Channels", -1 , ChannelImageDuplicated.class).setNewInstanceNameFunction((l, i)->"duplicated channel"+i).setHint("Define here duplicated detection channels. Duplicated detection channels allow to perform different transformations pipeline on the same detection channel");

    SimpleListParameter<Structure> structures= new SimpleListParameter<>("Object Classes", -1 , Structure.class).setNewInstanceNameFunction((l, i)->"object class"+i).setHint("Types of objects to be analysed in this dataset. The processing pipeline (segmentation, tracking…) is defined in this part of the configuration tree, and can be configured from the <em>Configuration Test</em> tab (by selecting the <em>Processing</em> step)");
    SimpleListParameter<PluginParameter<Measurement>> measurements = new SimpleListParameter<>("Measurements", -1 , new PluginParameter<>("", Measurement.class, false))
            .addValidationFunctionToChildren(ppm -> {
                if (!ppm.isActivated()) return true;
                if (!ppm.isOnePluginSet()) return false;
                Measurement m = ppm.instantiatePlugin();
                if (m==null) return false;
                Map<Integer, List<String>> currentmkByStructure= m.getMeasurementKeys().stream().collect(Collectors.groupingBy(mk->mk.getStoreStructureIdx(), Collectors.mapping(mk->mk.getKey(), Collectors.toList())));               
                if (currentmkByStructure.values().stream().anyMatch((l) -> ((new HashSet<>(l).size()!=l.size())))) return false; // first check if duplicated keys for the measurement
                SimpleListParameter<PluginParameter<Measurement>> ml= (SimpleListParameter) ppm.getParent();
                Map<Integer, Set<String>> allmkByStructure= ml.getActivatedChildren().stream().filter(pp->pp!=ppm).map(pp -> pp.instantiatePlugin()).filter(mes->mes!=null).flatMap(mes -> mes.getMeasurementKeys().stream()).collect(Collectors.groupingBy(mk->mk.getStoreStructureIdx(), Collectors.mapping(mk->mk.getKey(), Collectors.toSet())));
                return !currentmkByStructure.entrySet().stream().anyMatch( e -> {
                    Set<String> otherKeys = allmkByStructure.get(e.getKey());
                    if (otherKeys==null) return false;
                    return !Sets.intersection(otherKeys, new HashSet<>(e.getValue())).isEmpty();
                });
            })
            .setHint("Measurements to be performed after processing. Measurements will be extracted in several data tables, each one corresponding to a single object class (e.g. microchannels or bacteria or spots). For each measurement, the table in which it will be written and the name of the corresponding column are indicated in the Help window. If the user defines two measurements with the same name in the same data table, the measurements will not be performed and invalid measurements are displayed in red.");
    SimpleListParameter<Position> positions= new SimpleListParameter<>("Pre-Processing for all Positions", -1 , Position.class).setAllowMoveChildren(false).setHint("Positions of the dataset. Pre-processing is defined for each position. Right-click menu allows to overwrite pre-processing to other position.<br />Element that appear in blue differ from the template");
    PreProcessingChain template = new PreProcessingChain("Pre-Processing template", true).setHint("List of pre-processing operations that will be set by default to positions at import. <br />For each position those operations can be edited (either from the <em>Positions</em> branch in the <em>Configuration tab</em> or from the <em>Configuration Test</em> tab)");
    
    protected FileChooser imagePath = new FileChooser("Output Image Path", FileChooserOption.DIRECTORIES_ONLY, false).setHint("Directory where preprocessed images will be stored");
    protected FileChooser outputPath = new FileChooser("Output Path", FileChooserOption.DIRECTORIES_ONLY, false).setHint("Directory where segmentation & lineage results will be stored");
    ChoiceParameter importMethod = new ChoiceParameter("Import Method", IMPORT_METHOD.getChoices(), null, false);
    TextParameter positionSeparator = new TextParameter("Position Separator", "xy", true).setHint("character sequence located just before the position index.  It should be shared by all image files of the dataset, and unique in the file name");
    TextParameter frameSeparator = new TextParameter("Frame Separator", "t", true).setHint("character sequence located just before the frame number. It should be shared by all image files of the dataset, and unique in the file name");
    BooleanParameter invertTZ = new BooleanParameter("Swap T & Z dimension", false).setHint("BACMMAN can analyze time series of Z-stacks. For some image formats, the Z and time dimensions may be swapped. In this case, set SWAP time and Z to TRUE. <br />The correct interpretation of time and Z dimensions can be checked after import by opening the images of a position through the <em>Open Input Images</em> command and checking the properties of the image (CTRL + SHIFT + P under imageJ/FIJI)<br />After changing this parameter, images should be re-imported (re-run the import / re-link command)");
    public enum AXIS_INTERPRETATION {AUTOMATIC, TIME, Z}
    EnumChoiceParameter<AXIS_INTERPRETATION> axesInterpretation = new EnumChoiceParameter<>("Force axis", AXIS_INTERPRETATION.values(), AXIS_INTERPRETATION.AUTOMATIC).setHint("Defines how to interpret the third axis (after X, Y). Automatic: axis as defined in the image file, Z: axis is interpreted as Z if several frames and only one z-slice are detected, Time: axis is interpreted as time, if several z-slices and only one frame are detected. <br /> when Frame or Z are selected, the option invertTZ is not taken into account");

    ConditionalParameter<String> importCond = new ConditionalParameter<>(importMethod)
            .setActionParameters(IMPORT_METHOD.ONE_FILE_PER_CHANNEL_FRAME_POSITION.getMethod(), positionSeparator, frameSeparator)
            .setActionParameters(IMPORT_METHOD.ONE_FILE_PER_CHANNEL_POSITION.getMethod(), invertTZ, axesInterpretation)
            .setActionParameters(IMPORT_METHOD.SINGLE_FILE.getMethod(), invertTZ)
            .setHint("<b>Define here the organization of input images</b><ol>"
                    + "<li>"+IMPORT_METHOD.SINGLE_FILE.getMethod()+": A single file contains all frames, detection channels and positions</li>"
                    + "<li>"+IMPORT_METHOD.ONE_FILE_PER_CHANNEL_POSITION.getMethod()+": For each position, there is one file per detection channel, which contains all frames<br /> File names must contain the user-defined channel keywords (defined in <em>Detection Channel</em>). For a given position, the file names should differ only by their channel keyword. In case one file contains several channels, several <em>Detection Channels</em> with the same channel keyword can be set. They will point to each channel in the corresponding file (in the same order)</li>"
                    + "<li>"+IMPORT_METHOD.ONE_FILE_PER_CHANNEL_FRAME_POSITION.getMethod()+": A single file corresponds to a single position, a single detection channel and a single frame. <br />All files must have the same extension. <br /> File names must contain the user-defined channel keywords (defined in <em>Detection Channel</em>), the user-defined <em>position separator</em>, and contain the user-defined <em>frame separator</em> followed by the frame index. For a given position, the file names should only differ by their channel keyword and their frame index.<br />Supported extensions: tif, tiff, nd2, png<br />If no position keyword is specified, this method expects one position per subfolder and position name will be the folder names (at first subfolder level).</li></ol>");
    
    ChannelImageParameter bestFocusPlaneChannel = new ChannelImageParameter("Channel", 0, true).setHint("Detection Channel for best focus plane computation");
    PluginParameter<Autofocus> autofocus = new PluginParameter<>("Algorithm", Autofocus.class, new SelectBestFocusPlane(), true);
    GroupParameter bestFocusPlane = new GroupParameter("Best Focus plane computation", new Parameter[]{bestFocusPlaneChannel, autofocus}).setHint("This algorithm can be used to transform 3-D images (Z-stacks) into 2-D images. For each Z-stack the algorithm will select the plane corresponding to the best focalized image.");
    NoteParameter note = new NoteParameter("Note");

    public final ExperimentStructure experimentStructure = new ExperimentStructure(this);
    
    @Override
    public JSONObject toJSONEntry() {
        JSONObject res= new JSONObject();
        if (path==null && getOutputDirectory()!=null) path = Paths.get(getOutputDirectory()).getParent();
        res.put("imagePath", imagePath.toJSONEntry());
        res.put("outputPath", outputPath.toJSONEntry());

        /*if (path!=null) {
            String iPath = getOutputImageDirectory();
            if (iPath != null) res.put("imagePath", path.relativize(Paths.get(iPath)).toString());
            String oPath = getOutputDirectory();
            if (oPath != null) res.put("outputPath", path.relativize(Paths.get(oPath)).toString());
        }*/
        res.put("channelImages", channelImages.toJSONEntry());
        res.put("channelImagesDuplicated", channelImagesDuplicated.toJSONEntry());
        res.put("structures", structures.toJSONEntry());
        res.put("measurements", measurements.toJSONEntry());
        res.put("positions", positions.toJSONEntry());
        res.put("template", template.toJSONEntry());
        res.put("importMethod", importCond.toJSONEntry());
        res.put("bestFocusPlane", bestFocusPlane.toJSONEntry());
        res.put("note", note.toJSONEntry());
        return res;
    }

    @Override
    public void initFromJSONEntry(Object jsonEntry) {
        if (jsonEntry==null) throw new IllegalArgumentException("Cannot init xp with null content!");
        JSONObject jsonO = (JSONObject)jsonEntry;
        /*if (jsonO.get("imagePath") instanceof JSONArray) {
            imagePath.initFromJSONEntry(jsonO.get("imagePath"));
        } else if (jsonO.containsKey("imagePath"))  imagePath.setSelectedFilePath(path.resolve(Paths.get(jsonO.get("imagePath").toString())).normalize().toFile().getAbsolutePath());
        if (jsonO.get("outputPath") instanceof JSONArray) {
            outputPath.initFromJSONEntry(jsonO.get("outputPath"));
        } else  if (jsonO.containsKey("outputPath")) outputPath.setSelectedFilePath(path.resolve(Paths.get(jsonO.get("outputPath").toString())).normalize().toFile().getAbsolutePath());
        */
        if (jsonO.get("imagePath") instanceof JSONArray) imagePath.initFromJSONEntry(jsonO.get("imagePath"));
        if (jsonO.get("outputPath") instanceof JSONArray) outputPath.initFromJSONEntry(jsonO.get("outputPath"));
        channelImages.initFromJSONEntry(jsonO.get("channelImages"));
        if (jsonO.containsKey("channelImagesDuplicated")) channelImagesDuplicated.initFromJSONEntry(jsonO.get("channelImagesDuplicated"));
        structures.initFromJSONEntry(jsonO.get("structures"));
        measurements.initFromJSONEntry(jsonO.get("measurements"));
        positions.setParent(this); // positions needs access to experiment in order to initialize
        if (jsonO.containsKey("positions")) positions.initFromJSONEntry(jsonO.get("positions"));
        template.initFromJSONEntry(jsonO.get("template"));
        if (jsonO.get("importMethod") instanceof JSONObject) importCond.initFromJSONEntry(jsonO.get("importMethod"));
        else importMethod.initFromJSONEntry(jsonO.get("importMethod")); // RETRO COMPATIBILITY
        bestFocusPlane.initFromJSONEntry(jsonO.get("bestFocusPlane"));
        if (jsonO.containsKey("note")) note.initFromJSONEntry(jsonO.get("note"));
    }
    public Experiment(){
        this("");
    }

    Path path;
    public Experiment setPath(Path path) {
        this.path=path;
        return this;
    }

    public Path getPath() {
        return path;
    }
    
    public Experiment(String name) {
        super(name);
        structures.addListener(source -> source.getChildren().stream().forEachOrdered((s) -> s.setMaxStructureIdx()));
        initChildList();
    }
    @Override 
    public boolean isEmphasized() {
        return false;
    }
    public Experiment(String name, Structure... defaultStructures) {
        this(name);
        for (Structure s : defaultStructures) structures.insert(s);
        structures.setUnmutableIndex(defaultStructures.length-1);
        initChildList();
    }

    public Experiment setNote(String note) {
        this.note.setValue(note);
        return this;
    }

    public String getNote() {
        return note.getValue();
    }

    public boolean isImportImageInvertTZ() {
        return this.invertTZ.getSelected();
    }

    public AXIS_INTERPRETATION getAxisInterpretation() {return this.axesInterpretation.getSelectedEnum();}

    public void setImportImageMethod(IMPORT_METHOD method) {this.importMethod.setValue(method.getMethod());}

    public ChoiceParameter getImportMethodParameter() {
        return importMethod;
    }

    public GroupParameter getBestFocusPlaneParameter() {
        return bestFocusPlane;
    }

    protected void initChildList() {
        super.initChildren(importCond, channelImages, channelImagesDuplicated, template, positions, structures, measurements, outputPath, imagePath, bestFocusPlane, note);
    }
    
    public PreProcessingChain getPreProcessingTemplate() {
        return template;
    }

    DLengineProvider dLengineProvider = new DLengineProvider();
    public DLengineProvider getDLengineProvider() { //todo see if flush needed at beginng of processing
        return dLengineProvider;
    }


    /**
     * 
     * @param positionName name of the MicroscopyField
     * @return a new Position if no Position named {@param fieldName} are already existing, else null. 
     */
    public Position createPosition(String positionName) {
        if (getPosition(positionName)!=null) return null;
        Position res =positions.createChildInstance(positionName);
        positions.insert(res);
        res.setPreProcessingChains(template);
        return res;
    }
    
    public Position getPosition(String fieldName) {
        return positions.getChildByName(fieldName);
    }
    
    public Position getPosition(int fieldIdx) {
        return positions.getChildAt(fieldIdx);
    }
    
    public List<Position> getPositions() {
        return positions.getChildren();
    }

    public SimpleListParameter<Position> getPositionParameter() {return positions;}
    
    public Pair<Integer, Autofocus> getFocusChannelAndAlgorithm() {
        if (this.bestFocusPlaneChannel.getSelectedIndex()<0 || !autofocus.isOnePluginSet()) return null;
        return new Pair<>(this.bestFocusPlaneChannel.getSelectedIndex(), this.autofocus.instantiatePlugin());
    }
    
    public void flushImages(boolean raw, boolean preProcessed, String... excludePositions) {
        List<String> pos = new ArrayList<>(Arrays.asList(getPositionsAsString()));
        pos.removeAll(Arrays.asList(excludePositions));
        for (String p : pos)  getPosition(p).flushImages(raw, preProcessed);
    }
   
    public SimpleListParameter<ChannelImage> getChannelImages() {
        return channelImages;
    }
    public SimpleListParameter<ChannelImageDuplicated> getChannelImagesDuplicated() {
        return channelImagesDuplicated;
    }

    public int[] getDuplicatedChannelSources() {
        return channelImagesDuplicated.getChildren().stream().mapToInt(ChannelImageDuplicated::getSourceChannel).toArray();
    }
    public int getSourceChannel(int channelIdx) {
        if (channelIdx<getChannelImageCount(false)) return channelIdx;
        int dupChannelIdx = channelIdx - getChannelImageCount(false);
        return channelImagesDuplicated.getChildAt(dupChannelIdx).getSourceChannel();
    }
    public IMPORT_METHOD getImportImageMethod() {
        return IMPORT_METHOD.getValueOf(this.importMethod.getSelectedItem());
    }
    
    public String getImportImagePositionSeparator() {
        return positionSeparator.getValue();
    }
    
    public String getImportImageFrameSeparator() {
        return frameSeparator.getValue();
    }
    
    public String getOutputDirectory() {
        return outputPath.getFirstSelectedFilePath();
    }
    
    public void setOutputDirectory(String outputPath) {
        this.outputPath.setSelectedFilePath(outputPath);
        if (outputPath!=null) {
            Path p = Paths.get(this.outputPath.getFirstSelectedFilePath());
            if (!Files.exists(p)) {
                try {
                    Files.createDirectories(p);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
            if (path==null) path = p.getParent();
        }
    }
    
    public String getOutputImageDirectory() {
        if (imagePath.getFirstSelectedFilePath()==null && getOutputDirectory()!=null) setOutputImageDirectory(getOutputDirectory());
        return imagePath.getFirstSelectedFilePath();
    }
    
    public void setOutputImageDirectory(String outputPath) {
        imagePath.setSelectedFilePath(outputPath);
        if (outputPath!=null) {
            Path p = Paths.get(this.imagePath.getFirstSelectedFilePath());
            if (!Files.exists(p)) {
                try {
                    Files.createDirectories(p);
                } catch (IOException e) {
                    logger.error("Error while trying to create Output folder @ "+outputPath, e);
                    //throw new RuntimeException(e);
                }
            }
        }
    }
    
    
    
    public void clearPositions() {
        this.positions.removeAllElements();
    }
    public void clearMeasurements() {
        this.measurements.removeAllElements();
    }
    public PluginParameter<Measurement> addMeasurement(Measurement measurement) {
        PluginParameter<Measurement> m = new PluginParameter<>("Measurement", Measurement.class, measurement, false);
        this.measurements.insert(m);
        return m;
    }
    public void addMeasurements(Measurement... measurements) {
        for (Measurement m : measurements) addMeasurement(m);
    }
    public int[] getStructureToChannelCorrespondance() {
        int[] res = new int[structures.getChildCount()];
        for (int i = 0; i<res.length; i++) res[i] = getStructure(i).getChannelImage();
        return res;
    }
    public HashMap<Integer, List<Integer>> getChannelToStructureCorrespondance() {
        HashMapGetCreate<Integer, List<Integer>> res = new HashMapGetCreate<>(new HashMapGetCreate.ListFactory());
        for (int s = 0; s<getStructureCount(); s++) res.getAndCreateIfNecessary(getStructure(s).getChannelImage()).add(s);
        return res;
    }
    
    public int getChannelImageIdx(int structureIdx) {return structureIdx==-1 ? 0 : getStructure(structureIdx).getChannelImage();}
    
    public SimpleListParameter<Structure> getStructures() {return structures;}
    
    public Structure getStructure(int structureIdx) {
        return structures.getChildAt(structureIdx);
    }
    
    public int getStructureCount() {
        return structures.getChildCount();
    }
    
    public int getStructureIdx(String name) {
        int i = 0;
        for (Structure s: structures.getChildren()) {
            if (s.getName().equals(name)) return i;
            i++;
        }
        if ("Viewfield".equals(name)) return -1;
        else return -2;
    }
    
    public int getChannelImageCount(boolean includeDuplicated) {
        return channelImages.getChildCount() + (includeDuplicated ? channelImagesDuplicated.getChildCount() : 0);
    }
    
    public int getPositionCount() {
        return positions.getChildCount();
    }
    
    public int getPositionIdx(String positionName) {
        return positions.getIndex(positionName);
    }
    

    
    public String[] getChannelImagesAsString(boolean includeDuplicated) {
        if (!includeDuplicated) return channelImages.getChildrenString();
        else return Stream.concat(channelImages.getChildren().stream().map(c->c.getName()), channelImagesDuplicated.getChildren().stream().map(c->c.getName())).toArray(String[]::new);
    }
    
    public String[] getPositionsAsString() {return positions.getChildrenString();}
    

    

    
    // measurement-related methods
    public SimpleListParameter<PluginParameter<Measurement>> getMeasurements() { return measurements;}
    public List<MeasurementKey> getAllMeasurementKeys() {
        if (this.measurements.getChildCount()==0) return Collections.emptyList();
        else {
            ArrayList<MeasurementKey> res= new ArrayList<MeasurementKey>();
            for (PluginParameter<Measurement> p : measurements.getActivatedChildren()) {
                Measurement m = p.instantiatePlugin();
                if (m!=null) res.addAll(m.getMeasurementKeys());
            }
            return res;
        }
    }
    
    public List<MeasurementKeyObject> getAllMeasurementKeyObject() {
        if (this.measurements.getChildCount()==0) return Collections.emptyList();
        else {
            ArrayList<MeasurementKeyObject> res= new ArrayList<MeasurementKeyObject>();
            for (PluginParameter<Measurement> p : measurements.getActivatedChildren()) {
                Measurement m = p.instantiatePlugin();
                if (m!=null) for (MeasurementKey k : m.getMeasurementKeys()) if (k instanceof MeasurementKeyObject) res.add((MeasurementKeyObject)k);
            }
            return res;
        }
    }
    
    public Map<Integer, String[]> getAllMeasurementNamesByStructureIdx(Class<? extends MeasurementKey> classFilter, int... structures) {
        HashMapGetCreate<Integer, ArrayList<String>> map = new HashMapGetCreate<Integer, ArrayList<String>>(this.getStructureCount(), new HashMapGetCreate.ArrayListFactory<Integer, String>());
        List<MeasurementKey> allKeys = getAllMeasurementKeys();
        for (MeasurementKey k : allKeys) {
            if (classFilter==null || classFilter.equals(k.getClass())) {
                if (structures.length==0 || ArrayUtil.contains(structures, k.getStoreStructureIdx())) map.getAndCreateIfNecessary(k.getStoreStructureIdx()).add(k.getKey());
            }
        }
        Map<Integer, String[]> mapRes = new HashMap<Integer, String[]>(map.size());
        for (Entry<Integer, ArrayList<String>> e : map.entrySet()) mapRes.put(e.getKey(), e.getValue().toArray(new String[e.getValue().size()]));
        for (int s : structures) if (!mapRes.containsKey(s)) mapRes.put(s, new String[0]);
        return mapRes;
    }
    
    public Map<Integer, List<Measurement>> getMeasurementsByCallStructureIdx(int... structureIdx) {
        if (this.measurements.getChildCount()==0) return Collections.emptyMap();
        else {
            HashMapGetCreate<Integer, List<Measurement>> res = new HashMapGetCreate<>(structureIdx.length>0?structureIdx.length : this.getStructureCount(), new HashMapGetCreate.ListFactory<Integer, Measurement>());
            for (PluginParameter<Measurement> p : measurements.getActivatedChildren()) {
                Measurement m = p.instantiatePlugin();
                if (m!=null) {
                    if (structureIdx.length==0 || contains(structureIdx, m.getCallObjectClassIdx())) {
                        res.getAndCreateIfNecessary(m.getCallObjectClassIdx()).add(m);
                    }
                }
            }
            return res;
        }
    }
    public Stream<Measurement> getMeasurements(int structureIdx) {
        return measurements.getChildren().stream().filter(pp->pp.isActivated() && pp.isOnePluginSet()).map(pp->pp.instantiatePlugin()).filter(m->m.getCallObjectClassIdx()==structureIdx);
    }
    
    private static boolean contains(int[] structures, int structureIdx) {
        for (int s: structures) if (s==structureIdx) return true;
        return false;
    }
    public synchronized Experiment duplicateWithoutPositions() {
        SimpleListParameter<Position> positions_bck = this.positions;
        this.positions= new SimpleListParameter<>("Pre-Processing for all Positions", -1 , Position.class).setAllowMoveChildren(false).setHint("Positions of the dataset. Pre-processing is defined for each position. Right-click menu allows to overwrite pre-processing to other position.<br />Element that appear in blue differ from the template");
        initChildList();
        Experiment res = super.duplicate();
        this.positions = positions_bck;
        initChildList();
        return res;
    }
    public String toString() {
        return name;
    }

    public enum IMPORT_METHOD {
        SINGLE_FILE("Single-file"),
        ONE_FILE_PER_CHANNEL_POSITION("One File Per Channel And Position"),
        ONE_FILE_PER_CHANNEL_FRAME_POSITION("One File Per Position, Channel And Frame");
        private final String name;
        IMPORT_METHOD(String name) {
            this.name=name;
        }
        @Override
        public String toString() {return name;}
        public String getMethod(){return name;}
        public static String[] getChoices() {
            IMPORT_METHOD[] all = IMPORT_METHOD.values();
            String[] res = new String[all.length];
            int i = 0;
            for (IMPORT_METHOD m : all) res[i++]=m.name;
            return res;
        }
        public static IMPORT_METHOD getValueOf(String method) {
            for (IMPORT_METHOD m : IMPORT_METHOD.values()) if (m.getMethod().equals(method)) return m;
            return null;
        }
        /*public static ImportImageMethod getMethod(String name) {
            if (BioFormats.getMethod().equals(name)) return BioFormats;
            else return null;
        }*/
    }
    
    
}
