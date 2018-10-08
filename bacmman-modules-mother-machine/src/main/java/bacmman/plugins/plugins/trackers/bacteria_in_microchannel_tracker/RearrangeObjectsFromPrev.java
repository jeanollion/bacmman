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
package bacmman.plugins.plugins.trackers.bacteria_in_microchannel_tracker;

import bacmman.data_structure.Region;
import bacmman.plugins.Plugin;
import bacmman.plugins.plugins.trackers.ObjectIdxTracker;
import bacmman.utils.HashMapGetCreate;
import bacmman.utils.Pair;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.TreeSet;
import java.util.function.BiFunction;
import java.util.function.Function;

import static bacmman.plugins.plugins.trackers.bacteria_in_microchannel_tracker.BacteriaClosedMicrochannelTrackerLocalCorrections.debugCorr;
import static bacmman.plugins.plugins.trackers.bacteria_in_microchannel_tracker.BacteriaClosedMicrochannelTrackerLocalCorrections.verboseLevelLimit;

import static bacmman.plugins.plugins.trackers.bacteria_in_microchannel_tracker.BacteriaClosedMicrochannelTrackerLocalCorrections.significantSRErrorThld;

/**
 *
 * @author Jean Ollion
 */
public class RearrangeObjectsFromPrev extends ObjectModifier {
    protected List<RearrangeAssignment> assignements;
    protected final Assignment assignment;
    protected HashMapGetCreate<Region, Double> sizeMap = new HashMapGetCreate<>(o -> tracker.getObjectSize(o));
    public RearrangeObjectsFromPrev(BacteriaClosedMicrochannelTrackerLocalCorrections tracker, int frame, Assignment assignment) { // idxMax included
        super(frame, frame, tracker);
        objects.put(frame, new ArrayList(assignment.nextObjects)); // new arraylist -> can be modified
        objects.put(frame-1, assignment.prevObjects);
        assignements = new ArrayList<>(assignment.objectCountPrev());
        this.assignment=assignment;
        for (Region o : assignment.prevObjects) {
            double[] sizeRange = new double[2];
            double si = tracker.sizeRatioFunction.apply(o);
            double size = o.size();
            if (Double.isNaN(si)) {
                sizeRange[0] = tracker.minGR * size;
                sizeRange[1] = tracker.maxGR * size;
            } else {
                sizeRange[0] = (si- significantSRErrorThld /2) * size;
                sizeRange[1] = (si+ significantSRErrorThld /2) * size;
            }
            assignements.add(new RearrangeAssignment(o, sizeRange));
        }
        // TODO: take into acount endo-of-channel
        // split phase
        RearrangeAssignment a = needToSplit();
        while(a!=null) {
            if (debugCorr) Plugin.logger.debug("RO: assignments: {}", assignements);
            if (!a.split()) break;
            a = needToSplit();
        }
        // merge phase: merge until 2 objects per assignment & remove each merge cost to global cost
        if (a==null && needToMerge()) { 
            if (frame+1<tracker.maxFExcluded) { 
                TrackAssigner ta = tracker.getTrackAssigner(frame+1).setVerboseLevel(verboseLevelLimit);
                ta.assignUntil(assignment.getLastObject(false), true);
                // check that ta's has assignments included in current assignments
                if (debugCorr) Plugin.logger.debug("RO: merge from next: current assignment: [{}->{}] assignment with next: {}", assignment.idxNext, assignment.idxNextEnd()-1, ta.toString());
                Assignment ass1 = ta.getAssignmentContaining(assignment.nextObjects.get(0), true);
                if (ta.currentAssignment.getLastObject(true)==assignment.getLastObject(false) && ass1.prevObjects.get(0)==assignment.nextObjects.get(0)) {
                    // functions for track assigner -> use prev object assigned to next object
                    Function<Region, Double> sizeIncrementFunction = o -> { 
                        RearrangeAssignment ra = getAssignement(o, false, false);
                        if (ra==null) return Double.NaN;
                        else return tracker.sizeRatioFunction.apply(ra.prevObject);
                    };
                    BiFunction<Region, Region, Boolean> areFromSameLine = (o1, o2) -> {
                        RearrangeAssignment ra1 = getAssignement(o1, false, false);
                        if (ra1==null) return false;
                        RearrangeAssignment ra2 = getAssignement(o2, false, false);
                        if (ra2==null) return false;
                        return tracker.areFromSameLine.apply(ra1.prevObject, ra2.prevObject);
                    };
                    BiFunction<Region, Region, Boolean> haveSamePreviousObject = (o1, o2) -> {
                        RearrangeAssignment ra1 = getAssignement(o1, false, false);
                        if (ra1==null) return false;
                        RearrangeAssignment ra2 = getAssignement(o2, false, false);
                        if (ra2==null) return false;
                        return tracker.haveSamePreviousObject.apply(ra1.prevObject, ra2.prevObject);
                    };
                    ta = new TrackAssigner(getObjects(frame), tracker.getObjects(frame+1).subList(ass1.idxNext, ta.currentAssignment.idxNextEnd()), tracker.baseGrowthRate, assignment.truncatedEndOfChannel(), tracker.sizeFunction, sizeIncrementFunction, areFromSameLine, haveSamePreviousObject).setVerboseLevel(ta.verboseLevel);
                    ta.assignAll();
                    for (RearrangeAssignment ass : assignements) ass.mergeUsingNext(ta);
                }
            } 
            for (RearrangeAssignment ass : assignements) if (ass.objects.size()>2) ass.mergeUntil(2);
        }
        if (debugCorr) Plugin.logger.debug("Rearrange objects: tp: {}, {}, cost: {}", frameMax, assignment.toString(false), cost);
    }
    
    private boolean needToMerge() {
        for (RearrangeAssignment ass : assignements) if (ass.objects.size()>2) return true;
        return false;
    }

    private int getNextVoidAssignementIndex() {
        for (int i = 0; i<assignements.size(); ++i) if (assignements.get(i).isEmpty()) return i;
        return -1;
    }
    
    protected RearrangeAssignment getAssignement(Region o, boolean prev, boolean reset) {
        if (prev) return assignUntil(reset, (a, i) -> a.prevObject==o ? a : null);
        else return assignUntil(reset, (a, i) -> a.contains(o) ? a : null);
    }
          
    protected RearrangeAssignment needToSplit() { // assigns from start and check range size
        return assignUntil(true, (a, i) -> a.overSize() ? a : ((a.underSize() && i>0) ? assignements.get(i-1) : null)); // if oversize: return current, if undersize return previous
    }
        
    protected RearrangeAssignment assignUntil(boolean reset, BiFunction<RearrangeAssignment, Integer, RearrangeAssignment> exitFunction) { // assigns from start with custom exit function -> if return non null value -> exit assignment loop with value
        List<Region> allObjects = getObjects(frameMax);
        if (reset) for (int rangeIdx = 0; rangeIdx<assignements.size(); ++rangeIdx) assignements.get(rangeIdx).clear();
        int currentOIdx = 0;
        if (!reset) {
            int idx = getNextVoidAssignementIndex();
            if (idx>0) currentOIdx = allObjects.indexOf(assignements.get(idx-1).getLastObject());
        }
        for (int rangeIdx = 0; rangeIdx<assignements.size(); ++rangeIdx) {
            RearrangeAssignment cur = assignements.get(rangeIdx);
            if (cur.isEmpty()) {
                while(currentOIdx<allObjects.size() && cur.underSize()) assignements.get(rangeIdx).add(allObjects.get(currentOIdx++));
            }
            RearrangeAssignment a = exitFunction.apply(cur, rangeIdx);
            if (a!=null) return a;
        }
        return null;
    }

    @Override protected RearrangeObjectsFromPrev getNextScenario() { 
        return null;
    }

    @Override
    protected void applyScenario() {
        for (int i = this.assignment.idxNextEnd()-1; i>=assignment.idxNext; --i) tracker.objectAttributeMap.remove(tracker.populations.get(frameMin).remove(i));
        List<Region> allObjects = getObjects(frameMax);
        sortAndRelabel();
        int idx = assignment.idxNext;
        for (Region o : allObjects) {
            tracker.populations.get(frameMin).add(idx, o);
            tracker.objectAttributeMap.put(o, tracker.new TrackAttribute(o, idx, frameMax));
            idx++;
        }
        tracker.resetIndices(frameMax);
    }
    
    public void sortAndRelabel() {
        List<Region> allObjects = getObjects(frameMax);
        Collections.sort(allObjects, ObjectIdxTracker.getComparatorRegion(ObjectIdxTracker.IndexingOrder.YXZ));
        for (int i = 0; i<allObjects.size(); ++i) allObjects.get(i).setLabel(i+1);
    }
    
    @Override 
    public String toString() {
        return "Rearrange@"+frameMax+"["+this.assignment.idxNext+";"+(assignment.idxNextEnd()-1)+"]/c="+cost;
    }
    
    protected class RearrangeAssignment {
        final List<Region> objects;
        final Region prevObject;
        final double[] sizeRange;
        double size;
        public RearrangeAssignment(Region prevObject, double[] sizeRange) {
            this.prevObject=prevObject;
            this.sizeRange=sizeRange;
            this.objects = new ArrayList<>(3);
        }
        public void add(Region o) {
            this.objects.add(o);
            this.size+=sizeMap.getAndCreateIfNecessary(o);
        }
        public boolean isEmpty() {
            return objects.isEmpty();
        }
        public boolean contains(Region o) {
            return objects.contains(o);
        }
        public void clear() {
            size=0;
            objects.clear();
        }
        public boolean overSize() {
            return size>sizeRange[1];
        }
        public boolean underSize() {
            return size<sizeRange[0];
        }
        public Region getLastObject() {
            if (objects.isEmpty()) return null;
            return objects.get(objects.size()-1);
        }
        public boolean split() { 
            TreeSet<Split> res = new TreeSet<>();
            for (Region o : objects) {
                Split s = getSplit(frameMax, o);
                if (Double.isFinite(s.cost)) res.add(s);
            }
            if (res.isEmpty()) return false;
            Split s = res.first(); // lowest cost
            List<Region> allObjects = getObjects(frameMax);
            if (debugCorr) Plugin.logger.debug("RO: split: {}, cost: {}", allObjects.indexOf(s.source)+assignment.idxNext, s.cost);
            s.apply(objects);
            s.apply(getObjects(s.frame));
            cost+=s.cost;
            sortAndRelabel();
            return true;
        }
        
        public void mergeUsingNext(TrackAssigner assignments) {
            if (debugCorr) Plugin.logger.debug("RO: merge using next: current: {} all assignments: {}", this, assignments);
            if (objects.size()<=1) return;
            Iterator<Region> it = objects.iterator();
            Region lastO = it.next();
            Assignment lastAss = assignments.getAssignmentContaining(lastO, true);
            boolean reset = false;
            while(it.hasNext()) {
                Region currentO = it.next();
                Assignment ass = assignments.getAssignmentContaining(currentO, true);
                if (ass!=null && ass == lastAss && (ass.objectCountNext()<ass.objectCountPrev())) {
                    Merge m = getMerge(frameMax, new Pair(lastO, currentO));
                    if (debugCorr) Plugin.logger.debug("RO: merge using next: cost: {} assignement containing objects {}", m.cost, ass);
                    if (true || Double.isFinite(m.cost)) {
                        m.apply(objects);
                        m.apply(getObjects(m.frame));
                        m.apply(ass.prevObjects); 
                        ass.ta.resetIndices(true); // merging modifies following prev indices
                        currentO = m.value;
                        reset = true;
                    }
                }
                if (reset) {
                    if (objects.size()<=1) return;
                    it = objects.iterator();
                    lastO = it.next();
                    lastAss = assignments.getAssignmentContaining(lastO, true);
                    reset = false;
                } else {
                    lastO = currentO;
                    lastAss = ass;
                }
                
            }
        }
        public void mergeUntil(int limit) {
            double additionalCost = Double.NEGATIVE_INFINITY;
            while(objects.size()>limit) { // critère merge = cout le plus bas. // TODO: inclure les objets du temps suivants dans les contraintes
                Merge m = getBestMerge();
                if (m!=null) {
                    m.apply(objects);
                    m.apply(getObjects(m.frame));
                    if (m.cost>additionalCost) additionalCost=m.cost;
                } else break;
            }
            if (Double.isFinite(additionalCost)) cost+=additionalCost;
        }
        private Merge getBestMerge() {
            sortAndRelabel();
            TreeSet<Merge> res = new TreeSet();
            Iterator<Region> it = objects.iterator();
            Region lastO = it.next();
            while (it.hasNext()) {
                Region currentO = it.next();
                Merge m = getMerge(frameMax, new Pair(lastO, currentO));
                if (Double.isFinite(m.cost)) res.add(m);
                lastO = currentO;
            }
            if (res.isEmpty()) return null;
            else return res.first();
        }
        
        @Override public String toString() {
            return "RO:["+tracker.populations.get(frameMax-1).indexOf(this.prevObject)+"]->["+(objects.isEmpty()? "" : getObjects(frameMax).indexOf(objects.get(0))+";"+getObjects(frameMax).indexOf(getLastObject()))+"]/size: "+size+"/cost: "+cost+ "/sizeRange: ["+this.sizeRange[0]+";"+this.sizeRange[1]+"]";
        }
        
        
    }

}
