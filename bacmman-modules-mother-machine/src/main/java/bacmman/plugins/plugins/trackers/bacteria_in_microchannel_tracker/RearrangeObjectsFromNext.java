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



/**
 *
 * @author Jean Ollion
 */
public class RearrangeObjectsFromNext  { //extends ObjectModifier
    /*final int idxMin, idxMax, idxNextMin, idxNextMax;
    final boolean endOfChannel;
    final List<Assignement> assignements = new ArrayList<>();
    final Map<Object3D, double[]> sizeIncrementRangeMap = new HashMap<>();
    final HashMapGetCreate<Object3D, Double> sizeMap = new HashMapGetCreate<>(o-> getObjectSize(o));
    public RearrangeObjectsFromNext(BacteriaClosedMicrochannelTrackerLocalCorrections tracker, int frame, int idxMin, int idxMaxIncluded, int idxNextMin, int idxNextMaxIncluded) { 
        super(frame, frame, tracker);
        this.idxMin=idxMin;
        this.idxMax=idxMaxIncluded;
        this.idxNextMin = idxNextMin;
        this.idxNextMax=idxNextMaxIncluded;
        this.endOfChannel= idxNextMaxIncluded == tracker.populations[frame+1].size()-1;
        objects.put(frame, new ArrayList(tracker.populations[frame].subList(idxMin, idxMaxIncluded+1)));
        objects.put(frame+1, new ArrayList(tracker.populations[frame+1].subList(idxNextMin, idxNextMaxIncluded+1)));
        for (int i = idxNextMin; i<=idxNextMaxIncluded; ++i) sizeMap.put(tracker.populations[frame+1].get(i), tracker.trackAttributes[frame+1].get(i).getSize());
        for (int i = idxMin; i<=idxMaxIncluded; ++i) {
            Object3D o = tracker.populations[frame].get(i);
            double[] sizeRange = new double[2];
            double si = tracker.trackAttributes[frame].get(i).getLineageSizeIncrement();
            sizeMap.put(o, tracker.trackAttributes[frame].get(i).getSize());
            if (Double.isNaN(si)) {
                sizeRange[0] = tracker.minGR;
                sizeRange[1] = tracker.maxGR;
            } else {
                sizeRange[0] = (si-significativeSIErrorThld/2);
                sizeRange[1] = (si+significativeSIErrorThld/2);
            }
            sizeIncrementRangeMap.put(o, sizeRange);
        }
        
        Assignement a = getFirstError();
        
        while(a!=null) {
            if (debugCorr) logger.debug("RON: assignments: {}", assignements);
            if (a.underSize()) { // split prev & merge OU split next and merge + end of channel case
                if (a.isEndOfChannel()) break;
                if (!a.splitAndMerge(false, true)) break; // split current and merge with previous ???
            } else if (a.overSize()) { grandir prevObject -> soit merge, soit split&merge avec le suivant-> tester les deux cas
                if (a.objects.size()==1) { // split current object
                    a.splitAndMerge(true, false);
                } else { // split next and merge 
                    
                }
            }
            a = getFirstError();
        }
        if (debugCorr) logger.debug("Rearrange objects: tp: {}, idx: [{};{}], cost: {}", timePointMax, idxMin, idxMax, cost);
    }
    
    @Override protected Split getSplit(int frame, Object3D o) {
        Split s = super.getSplit(frame, o);
        if (frame == timePointMin) { // update sizeIncrement range for newly created objects;
            double[] si = sizeIncrementRangeMap.get(o);
            for (Object3D so : s.values) sizeIncrementRangeMap.put(so, si);
            
        }
        return s;
    }
    
    @Override protected Merge getMerge(int frame, Pair<Object3D, Object3D> o) {
        Merge m = super.getMerge(frame, o);
        if (frame==timePointMin) { // update sizeIncrement range for newly created objects;
            double[] si1 = sizeIncrementRangeMap.get(o.key);
            double[] si2 = sizeIncrementRangeMap.get(o.value);
            double s1 = sizeMap.getAndCreateIfNecessary(o.key);
            double s2 = sizeMap.getAndCreateIfNecessary(o.value);
            double[] si = new double[] { (si1[0] * s1 + si2[0] * s2) / (s1+s2), (si1[1] * s1 + si2[1] * s2) / (s1+s2) };
            sizeIncrementRangeMap.put(m.value, si);
        }
        return m;
    }
    
    private Assignement getFirstError() {
        return assignUntil(true, (a, i) -> a.overSize()||a.underSize() ? a : null);
    }
    
    protected Assignement assignUntil(boolean reset, BiFunction<Assignement, Integer, Assignement> exitFunction) { // assigns from start with custom exit function -> if return non null value -> exit assignment loop with value
        List<Object3D> allObjectsPrev = getObjects(timePointMin);
        List<Object3D> allObjectsNext = getObjects(timePointMin+1);
        if (reset) for (int rangeIdx = 0; rangeIdx<assignements.size(); ++rangeIdx) assignements.get(rangeIdx).clear();
        int currentOIdx = 0;
        if (!reset) {
            int idx = getNextVoidAssignementIndex();
            if (idx>0) currentOIdx = allObjectsNext.indexOf(assignements.get(idx-1).getLastObject());
        }
        for (int currentPrevOIdx = 0; currentPrevOIdx<allObjectsPrev.size(); ++currentPrevOIdx) {
            Assignement cur = assignements.get(currentPrevOIdx);
            if (cur.isEmpty()) {
                cur.setPrev(allObjectsPrev.get(currentPrevOIdx));
                while(currentOIdx<allObjectsNext.size() && cur.underSize()) assignements.get(currentPrevOIdx).add(allObjectsNext.get(currentOIdx++));
            }
            Assignement a = exitFunction.apply(cur, currentPrevOIdx);
            if (a!=null) return a;
        }
        return null;
    }
    private int getNextVoidAssignementIndex() {
        for (int i = 0; i<assignements.size(); ++i) if (assignements.get(i).isEmpty()) return i;
        return -1;
    }
    
    
    @Override
    protected CorrectionScenario getNextScenario() {
        return null;
    }

    @Override
    protected void applyScenario() {
        for (int i = idxMax; i>=idxMin; --i) {
            tracker.populations[timePointMin].remove(i);
            tracker.trackAttributes[timePointMin].remove(i);
        }
        List<Object3D> allObjects = getObjects(timePointMin);
        Collections.sort(allObjects, getComparatorObject3D(ObjectIdxTracker.IndexingOrder.YXZ)); // sort by increasing Y position
        int idx = idxMin;
        for (Object3D o : allObjects) {
            tracker.populations[timePointMin].add(idx, o);
            tracker.trackAttributes[timePointMin].add(idx, tracker.new TrackAttribute(o, idx, timePointMin));
            idx++;
        }
        tracker.resetIndices(timePointMin);
    }
    
    protected class Assignement {
        final List<Object3D> objects = new ArrayList<>(3);
        Object3D prevObject;
        double[] sizeRange;
        double size;

        public void setPrev(Object3D prevObject) {
            this.prevObject=prevObject;
            double[] sir = sizeIncrementRangeMap.get(prevObject);
            double s = sizeMap.getAndCreateIfNecessary(prevObject);
            this.sizeRange= new double[]{sir[0] * s, sir[1] * s};
        }
        public void add(Object3D o) {
            this.objects.add(o);
            this.size+=sizeMap.getAndCreateIfNecessary(o);
        }
        public boolean isEmpty() {
            return objects.isEmpty();
        }
        public boolean contains(Object3D o) {
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
        
        public Object3D getLastObject() {
            if (objects.isEmpty()) return null;
            return objects.get(objects.size()-1);
        }
        public boolean split() { 
            Split s = getSplit(timePointMin, prevObject);
            if (Double.isFinite(s.cost)) {
                s.apply();
                cost+=s.cost;
                if (debugCorr) logger.debug("RON: split: {}, cost: {}", getObjects(timePointMin).indexOf(s.source)+idxMin, s.cost);
                return true;
            } else return false;   
        }
        public int getIndex() {
            return getObjects(timePointMin).indexOf(prevObject);
        }
        public boolean isEndOfChannel() {
            if (objects.isEmpty()) return getIndex()==getObjects(timePointMin).size()-1;
            return getObjects(timePointMin+1).indexOf(getLastObject()) == getObjects(timePointMin+1).size()-1;
        }
        public boolean merge(boolean next) {
            List<Object3D> allObjects = getObjects(timePointMin);
            if (allObjects.size()==2) return false; // no merge all
            int i = allObjects.indexOf(prevObject);
            if ((next && i+1<allObjects.size())||(!next && i>0)) {
                Object3D other = allObjects.get(next ? i+1 : i-1);
                Merge m = getMerge(timePointMin, new Pair<>(prevObject, other));
                if (Double.isInfinite(m.cost)) return false;
                else {
                    m.apply();
                    cost+=m.cost;
                    if (debugCorr) logger.debug("RON: merge: {}, next: {}, cost merge: {}", i+idxMin, next, m.cost);
                    return true;
                }
            } else return false;
        }
        public boolean splitAndMerge(boolean next, boolean splitCurrent) { // merge with next object
            List<Object3D> allObjects = getObjects(timePointMin);
            int i = allObjects.indexOf(prevObject);
            if ((next && i+1<allObjects.size())||(!next && i>0)) {
                Object3D other = allObjects.get(next ? i+1 : i-1);
                Merge m = getMerge(timePointMin, new Pair(prevObject, other));
                if (Double.isInfinite(m.cost)) return false;
                Split s = getSplit(timePointMin, splitCurrent ? prevObject : other);
                if (Double.isFinite(s.cost)) {
                    s.apply();
                    cost+=s.cost;
                    if (splitCurrent) m = getMerge(timePointMin, new Pair(next ? s.values.get(1) : other, next ? other : s.values.get(0)));
                    else m = getMerge(timePointMin, new Pair(next ? prevObject : s.values.get(1), next ? s.values.get(0) : prevObject));
                    if (Double.isFinite(m.cost)) {
                        m.apply();
                        cost+=m.cost;
                        if (debugCorr) logger.debug("RON: merge+split: {}, next: {}, cost merge: {}, cost split: {}", getObjects(timePointMin).indexOf(s.source)+idxMin, next, m.cost, s.cost);
                    } else { // reverse split
                        m = getMerge(timePointMin, new Pair(s.values.get(0), s.values.get(1)));
                        m.apply();
                        cost-=s.cost;
                        return false;
                    }
                    return true;
                } else return false;
            } else return false;
        }
        
        @Override public String toString() {
            return "RO:["+tracker.populations[timePointMax-1].indexOf(this.prevObject)+"]->#"+objects.size()+"/size: "+size+"/cost: "+cost+ "/sizeRange: ["+this.sizeRange[0]+";"+this.sizeRange[1]+"]";
        }
    }
    */
}
