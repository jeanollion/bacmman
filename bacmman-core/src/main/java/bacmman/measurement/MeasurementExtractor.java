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
package bacmman.measurement;

import static bacmman.data_structure.Processor.logger;
import bacmman.configuration.experiment.Experiment;
import bacmman.data_structure.dao.MasterDAO;
import bacmman.data_structure.Measurements;
import bacmman.data_structure.dao.ObjectDAO;
import bacmman.data_structure.Selection;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TreeMap;
import java.util.function.Function;
import bacmman.utils.Utils;

/**
 *
 * @author Jean Ollion
 */
public class MeasurementExtractor {
    
    final static String separator =";";
    int structureIdx;
    MasterDAO db;
    public static Function<Number, String> numberFormater = (Number n) -> { // precision
        return Utils.format4(n);
    };
    public MeasurementExtractor(MasterDAO db, int structureIdx) {
        this.db=db;
        this.structureIdx=structureIdx;
    }
    protected StringBuilder getBaseHeader() {
        int[] path = db.getExperiment().hierarchy.getPathToRoot(structureIdx);
        String[] structureNames = db.getExperiment().getStructureNames(path);
        StringBuilder sb = new StringBuilder(50+20*structureNames.length);
        sb.append("Position");
        sb.append(separator);
        sb.append("PositionIdx");
        sb.append(separator);
        sb.append("Indices");
        sb.append(separator);
        sb.append("Frame");
        sb.append(separator);
        // index of this structure
        //sb.append(db.getExperiment().getStructureNames(structureIdx)[0]);
        sb.append("Idx");
        sb.append(separator);
        sb.append("Time");
        return sb;
    }
    protected StringBuilder getBaseLine(Measurements m, int pIdx) { // if add one key -> also add in the retrieved keys in DAO
        StringBuilder sb = new StringBuilder();
        sb.append(m.getPosition());
        sb.append(separator);
        sb.append(pIdx);
        int[] idx = m.getIndices();
        sb.append(separator);
        Utils.appendArray(idx, Selection.indexSeparator, sb); 
        sb.append(separator);
        sb.append(m.getFrame());
        sb.append(separator);
        sb.append(m.getIndices()[m.getIndices().length-1]);
        sb.append(separator);
        sb.append(numberFormater.apply(m.getCalibratedTimePoint()));
        return sb;
    }
    
    protected String getHeader(ArrayList<String> measurements) {
        StringBuilder header = getBaseHeader();
        for (String m : measurements) {
            header.append(separator);
            header.append(m);
        }
        //logger.debug("extract data: header: {}", header);
        return header.toString();
    }
    protected static ArrayList<String> getAllMeasurements(Map<Integer, String[]> measurements) {
        ArrayList<String> l = new ArrayList<>();
        for (String[] s : measurements.values()) l.addAll(Arrays.asList(s));
        return l;
    }
    public static void extractMeasurementObjects(MasterDAO db, String outputFile, int structureIdx,  List<String> positions, String... measurements) {
        Map<Integer, String[]> map = new HashMap<>(1);
        map.put(structureIdx, measurements);
        MeasurementExtractor de= new MeasurementExtractor(db, structureIdx);
        de.extractMeasurementObjects(outputFile, positions, map);
    }
    public static void extractMeasurementObjects(MasterDAO db, String outputFile, List<String> positions, Map<Integer, String[]> allMeasurements) {
        TreeMap<Integer, String[]> allMeasurementsSort = new TreeMap<>(allMeasurements);
        //if (allMeasurementsSort.isEmpty()) return;
        MeasurementExtractor de= new MeasurementExtractor(db, allMeasurementsSort.lastKey());
        de.extractMeasurementObjects(outputFile, positions, allMeasurementsSort);
    }
    protected void extractMeasurementObjects(String outputFile, List<String> positions, Map<Integer, String[]> allMeasurements) {
        Experiment xp = db.getExperiment();
        if (positions==null) positions = Arrays.asList(db.getExperiment().getPositionsAsString());
        long t0 = System.currentTimeMillis();
        FileWriter fstream;
        BufferedWriter out;
        int count = 0;
        try {
            File output = new File(outputFile);
            output.delete();
            fstream = new FileWriter(output);
            out = new BufferedWriter(fstream);
            TreeMap<Integer, String[]> allMeasurementsSort = new TreeMap<>(allMeasurements); // sort by structureIndex value
            out.write(getHeader(getAllMeasurements(allMeasurements))); 
            
            int currentStructureIdx = allMeasurementsSort.lastKey();
            int[] parentOrder = new int[currentStructureIdx]; // maps structureIdx to parent order
            for (int s : allMeasurementsSort.keySet()) {
                if (s!=currentStructureIdx) {
                    parentOrder[s] = xp.hierarchy.getPathToStructure(s, currentStructureIdx).length;
                }
            }
            String[] currentMeasurementNames = allMeasurementsSort.pollLastEntry().getValue();
            for (String fieldName : positions) {
                int posIdx = db.getExperiment().getPositionIdx(fieldName);
                ObjectDAO dao = db.getDao(fieldName);
                TreeMap<Integer, List<Measurements>> parentMeasurements = new TreeMap<Integer, List<Measurements>>();
                for (Entry<Integer, String[]> e : allMeasurementsSort.entrySet()) parentMeasurements.put(e.getKey(), dao.getMeasurements(e.getKey(), e.getValue()));
                List<Measurements> currentMeasurements = dao.getMeasurements(currentStructureIdx, currentMeasurementNames);
                Collections.sort(currentMeasurements);
                for (Measurements m : currentMeasurements) {
                    StringBuilder line = getBaseLine(m, posIdx);
                    // add measurements from parents of the the current structure
                    for (Entry<Integer, List<Measurements>> e : parentMeasurements.entrySet()) {
                        Measurements key = m.getParentMeasurementKey(parentOrder[e.getKey()]);
                        int pIdx = e.getValue().indexOf(key);
                        if (pIdx==-1) {
                            for (String pMeasName : allMeasurementsSort.get(e.getKey())) {
                                line.append(separator);
                                line.append(Measurements.NA_STRING);
                            } 
                        }
                        else {
                            key = e.getValue().get(pIdx);
                            for (String pMeasName : allMeasurementsSort.get(e.getKey())) {
                                line.append(separator);
                                line.append(key.getValueAsString(pMeasName, numberFormater));
                                
                            }
                        }
                    }
                    //add measurements from the current structure
                    for (String mName : currentMeasurementNames) {
                        line.append(separator);
                        line.append(m.getValueAsString(mName, numberFormater));
                    }
                    out.newLine();
                    out.write(line.toString());
                    ++count;
                }
            }
            out.close();
            long t1 = System.currentTimeMillis();
            logger.debug("data extractions: {} line in: {} ms", count, t1-t0);
        } catch (IOException ex) {
            logger.debug("init extract data error: {}", ex);
        }
    }
    
}
