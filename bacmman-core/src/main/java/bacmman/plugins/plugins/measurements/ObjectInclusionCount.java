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

import bacmman.data_structure.Region;
import bacmman.data_structure.SegmentedObject;
import bacmman.measurement.MeasurementKey;
import bacmman.measurement.MeasurementKeyObject;
import bacmman.plugins.Hint;
import bacmman.plugins.Measurement;
import bacmman.configuration.parameters.BooleanParameter;
import bacmman.configuration.parameters.BoundedNumberParameter;
import bacmman.configuration.parameters.Parameter;
import bacmman.configuration.parameters.ObjectClassParameter;
import bacmman.configuration.parameters.TextParameter;

import java.util.ArrayList;
import java.util.stream.Stream;

/**
 *
 * @author Jean Ollion
 */
public class ObjectInclusionCount implements Measurement, Hint {
    protected ObjectClassParameter structureContainer = new ObjectClassParameter("Containing Objects", -1, false, false).setHint("Objects to perform measurement on");
    protected ObjectClassParameter structureToCount = new ObjectClassParameter("Objects to count", -1, false, false).setHint("Objects to count when included in <em>Containing Objects</em>");
    protected BooleanParameter onlyTrackHeads = new BooleanParameter("Count Only TrackHeads", false).setHint("Whether only head of tracks or all objects should be counted");
    protected BoundedNumberParameter percentageInclusion = new BoundedNumberParameter("Minimum percentage of inclusion", 0, 100, 0, 100).setHint("Inclusion criterion: minimal percentage of volume of objects to count that should be included in the container to consider it is included");
    protected TextParameter inclusionText = new TextParameter("Inclusion Key Name", "ObjectCount", false);
    protected Parameter[] parameters = new Parameter[]{structureContainer, structureToCount, percentageInclusion, onlyTrackHeads, inclusionText};
    
    
    @Override
    public String getHintText() {
        return "Counts the number of included objects";
    }
    
    public ObjectInclusionCount() {}
    
    public ObjectInclusionCount(int containingStructure, int structureToCount, double minPercentageInclusion) {
        this.structureContainer.setSelectedIndex(containingStructure);
        this.structureToCount.setSelectedIndex(structureToCount);
        this.percentageInclusion.setValue(minPercentageInclusion);
    }
    public ObjectInclusionCount setMeasurementName(String name) {
        inclusionText.setValue(name);
        return this;
    }
    public ObjectInclusionCount setOnlyTrackHeads(boolean onlyTh) {
        onlyTrackHeads.setSelected(onlyTh);
        return this;
    }
    @Override
    public int getCallObjectClassIdx() {
        return structureContainer.getFirstCommonParentObjectClassIdx(structureToCount.getSelectedIndex());
    }
    @Override
    public void performMeasurement(SegmentedObject object) {
        double p = percentageInclusion.getValue().doubleValue()/100d;
        if (object.getStructureIdx()==structureContainer.getSelectedIndex()) {
            object.getMeasurements().setValue(inclusionText.getValue(), count(object, structureToCount.getSelectedIndex(), p, onlyTrackHeads.getSelected()));
        } else {
            object.getChildren(structureContainer.getSelectedIndex()).forEach(c-> {
                c.getMeasurements().setValue(inclusionText.getValue(), count(c, object.getChildren(structureToCount.getSelectedIndex()), p, onlyTrackHeads.getSelected()));
            });
        }
    }
    
    @Override 
    public ArrayList<MeasurementKey> getMeasurementKeys() {
        ArrayList<MeasurementKey> res = new ArrayList<>(1);
        res.add(new MeasurementKeyObject(inclusionText.getValue(), structureContainer.getSelectedIndex()));
        return res;
    }
    
    public static int count(SegmentedObject container, Stream<SegmentedObject> toCount, double proportionInclusion, boolean onlyTrackHeads) {
        if (toCount==null) return 0;
        Region containerObject = container.getRegion();
        return (int)toCount.filter(o->!onlyTrackHeads || o.isTrackHead())
                .filter( o-> o.getRegion().intersect(containerObject)).filter(o-> {
            if (proportionInclusion==0) return true;
            else {
                if (o.getRegion().getVoxels().isEmpty()) return false;
                double incl = (double)o.getRegion().getOverlapMaskMask(containerObject, null, null) / (double)o.getRegion().getVoxels().size();
                //logger.debug("inclusion: {}, threshold: {}, container: {}, parent:{}", incl, percentageInclusion, container, o.getParent());
                return (incl>=proportionInclusion);
            }
        }).count();
        //logger.debug("inclusion count: commont parent: {} container: {}, toTest: {}, result: {}", commonParent, container, toCount.size(), count);
    }

    public static int count(SegmentedObject container, int structureToCount, double proportionInclusion, boolean onlyTrackHeads) {
        if (structureToCount==container.getStructureIdx()) return 1;
        int common = container.getHierarchy().getFirstCommonParentObjectClassIdx(container.getStructureIdx(), structureToCount);
        SegmentedObject commonParent = container.getParent(common);

        Stream<SegmentedObject> toCount = commonParent.getChildren(structureToCount);
        return count(container, toCount, proportionInclusion, onlyTrackHeads);
        
    }
    @Override
    public Parameter[] getParameters() {
        return parameters;
    }

    public boolean does3D() {
        return true;
    }
    @Override
    public boolean callOnlyOnTrackHeads() {
        return false;
    }

    
}
