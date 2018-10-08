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
package bacmman.plugins.plugins.measurements;

import bacmman.configuration.parameters.ObjectClassParameter;
import bacmman.configuration.parameters.Parameter;
import bacmman.configuration.parameters.TextParameter;
import bacmman.data_structure.SegmentedObject;
import bacmman.data_structure.SegmentedObjectUtils;
import bacmman.measurement.MeasurementKey;
import bacmman.measurement.MeasurementKeyObject;
import bacmman.plugins.Hint;
import bacmman.plugins.Measurement;
import bacmman.utils.HashMapGetCreate;
import bacmman.utils.MultipleException;
import bacmman.utils.Pair;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 *
 * @author Jean Ollion
 */
public class BacteriaLineageMeasurements implements Measurement, Hint {
    protected ObjectClassParameter structure = new ObjectClassParameter("Bacteria Structure", 1, false, false);
    protected TextParameter keyName = new TextParameter("Lineage Index Name", "BacteriaLineage", false);
    protected Parameter[] parameters = new Parameter[]{structure, keyName};
    public static char[] lineageName = new char[]{'H', 'T'};
    public static char[] lineageError = new char[]{'I', 'J', 'K', 'L', 'M', 'N', 'O', 'P', 'Q', 'R', 'S'};
    public static char lineageErrorSymbol = '-';
    public BacteriaLineageMeasurements() {}
    
    public BacteriaLineageMeasurements(int structureIdx) {
        structure.setSelectedIndex(structureIdx);
    }
    
    public BacteriaLineageMeasurements(int structureIdx, String keyName) {
        structure.setSelectedIndex(structureIdx);
        this.keyName.setValue(keyName);
    }
    
    @Override
    public String getHintText() {
        return "Lineage Index for Bacteria.<br />Index starts with any letter except H and T. At each division, H is added to the upper daughter cell track and T to the lower daugther cell track.<br />In case of division with more than 2 cells, letter I to S are added track of other daughter cell<br />This measurement also computes the frame of previous and next divisions";
    }
    
    @Override
    public int getCallObjectClassIdx() {
        return structure.getParentObjectClassIdx();
    }
    
    @Override
    public boolean callOnlyOnTrackHeads() {
        return true;
    }
    private static List<SegmentedObject> getAllNextSortedY(SegmentedObject o, List<SegmentedObject> bucket) {
        bucket = SegmentedObjectUtils.getDaugtherObjectsAtNextFrame(o, bucket);
        Collections.sort(bucket, Comparator.comparingDouble(o2 -> o2.getBounds().yMin()));
        return bucket;
    }
    @Override
    public void performMeasurement(SegmentedObject parentTrackHead) {
        int bIdx = structure.getSelectedIndex();
        String key = this.keyName.getValue();
        MultipleException ex = new MultipleException();
        HashMapGetCreate<SegmentedObject, List<SegmentedObject>> siblings = new HashMapGetCreate<>(o -> getAllNextSortedY(o, null));
        SegmentedObject currentParent = parentTrackHead;
        List<SegmentedObject> bacteria = currentParent.getChildren(bIdx).collect(Collectors.toList());
        int trackHeadIdx = 0;
        for (SegmentedObject o : bacteria) {
            o.getMeasurements().setStringValue(key, getTrackHeadName(trackHeadIdx++));
            int nextTP = getNextDivisionTimePoint(o);
            o.getMeasurements().setValue("NextDivisionFrame", nextTP>=0?nextTP:null );
        }
        while(currentParent.getNext()!=null) {
            currentParent = currentParent.getNext();
            bacteria = currentParent.getChildren(bIdx).collect(Collectors.toList());
            for (SegmentedObject o : bacteria) {
                if (o.getPrevious()==null) o.getMeasurements().setStringValue(key, getTrackHeadName(trackHeadIdx++));
                else {
                    if (o.getTrackHeadId().equals(o.getPrevious().getTrackHeadId())) o.getMeasurements().setStringValue(key, o.getPrevious().getMeasurements().getValueAsString(key));
                    else {
                        List<SegmentedObject> sib = siblings.getAndCreateIfNecessary(o.getPrevious());
                        if (sib.isEmpty()) ex.addExceptions(new Pair<>(o.toString(), new RuntimeException("Invalid bacteria lineage")));
                        else if (sib.get(0).equals(o)) o.getMeasurements().setStringValue(key, o.getPrevious().getMeasurements().getValueAsString(key)+lineageName[0]);
                        else if (sib.size()==2) o.getMeasurements().setStringValue(key, o.getPrevious().getMeasurements().getValueAsString(key)+lineageName[1]);
                        else { // MORE THAN 2 CELLS
                            int idx = sib.indexOf(o);
                            if (idx==sib.size()-1) o.getMeasurements().setStringValue(key, o.getPrevious().getMeasurements().getValueAsString(key)+lineageName[1]); // tail
                            else {
                                if (idx>lineageError.length) o.getMeasurements().setStringValue(key, o.getPrevious().getMeasurements().getValueAsString(key)+lineageErrorSymbol); // IF TOO MANY ERRORS: WILL GENERATE DUPLICATE LINEAGE
                                else o.getMeasurements().setStringValue(key, o.getPrevious().getMeasurements().getValueAsString(key)+lineageError[idx-1]);
                            }
                            
                        }
                    }
                }
                //if (currentParent.getFrame()<=10 && currentParent.getIdx()==0) logger.debug("o: {}, prev: {}, next: {}, lin: {}", o, o.getPrevious(), siblings.getAndCreateIfNecessary(o.getPrevious()), o.getMeasurements().getValueAsString(key));
                int prevTP = getPreviousDivisionTimePoint(o);
                o.getMeasurements().setValue("PreviousDivisionFrame", prevTP>0 ? prevTP : null);
                int nextTP = getNextDivisionTimePoint(o);
                o.getMeasurements().setValue("NextDivisionFrame", nextTP>=0?nextTP:null );
            }
            siblings.clear();
        }
        if (!ex.isEmpty()) throw ex;
    }
    
    @Override 
    public ArrayList<MeasurementKey> getMeasurementKeys() {
        ArrayList<MeasurementKey> res = new ArrayList<>(4);
        res.add(new MeasurementKeyObject(keyName.getValue(), structure.getSelectedIndex()));
        res.add(new MeasurementKeyObject("NextDivisionFrame", structure.getSelectedIndex()));
        res.add(new MeasurementKeyObject("PreviousDivisionFrame", structure.getSelectedIndex()));
        return res;
    }
    @Override
    public Parameter[] getParameters() {
        return parameters;
    }

    public boolean does3D() {
        return true;
    }
    public static String getTrackHeadName(int trackHeadIdx) {
        //return String.valueOf(trackHeadIdx);
        int r = trackHeadIdx%24; // 24 : skip T & H 
        int mod = trackHeadIdx/24;
        if (r>=18) { // skip T
            trackHeadIdx+=2;
            if (r>=24) r = trackHeadIdx%24;
            else r+=2;
        } else if (r>=7) { // skip H
            trackHeadIdx+=1;
            r+=1;
        }
        
        char c = (char)(r + 65); //ASCII UPPER CASE +65
        
        if (mod>0) return String.valueOf(c)+mod;
        else return String.valueOf(c);
    }

    public static int getPreviousDivisionTimePoint(SegmentedObject o) {
        SegmentedObject p = o.getPrevious();
        while (p!=null) {
            if (SegmentedObjectUtils.newTrackAtNextTimePoint(p)) return p.getFrame()+1;
            p=p.getPrevious();
        }
        return -1;
    }
    public static int getNextDivisionTimePoint(SegmentedObject o) {
        SegmentedObject p = o;
        while (p!=null) {
            if (SegmentedObjectUtils.newTrackAtNextTimePoint(p)) return p.getFrame()+1;
            p=p.getNext();
        }
        return -1;
    }
}
