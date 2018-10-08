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

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;

/**
 * Class holding objects at a given frame linked to objects at the next frame
 * When there are no segmentation error, an assignment has one object at previous frame and either one object at the next frame, or two in case of a division
 * In the presence of errors (ie over- or under-segmentation) previous and next object number in an assignment will vary
 * @author Jean Ollion
 */
public class Assignment {
        final static boolean notSameLineIsError = false;
        List<Region> prevObjects;
        List<Region> nextObjects;
        int idxPrev, idxNext;
        double sizePrev, sizeNext;
        double previousSizeRatio = Double.NaN;
        double[] currentScore;
        Map<Double, Assignment> deltaSizeMapAssignmentCandidate = new HashMap<>(3);
        TrackAssigner ta;
        public Assignment(TrackAssigner ta, int idxPrev, int idxNext) {
            prevObjects = new ArrayList();
            nextObjects = new ArrayList();
            this.idxPrev = idxPrev;
            this.idxNext= idxNext;
            this.ta=ta;
        }
        public Assignment setTrackAssigner(TrackAssigner ta) {
            this.ta =ta;
            return this;
        }
        public Assignment duplicate(TrackAssigner ta) {
            Assignment a = new Assignment(new ArrayList(prevObjects), new ArrayList(nextObjects), sizePrev, sizeNext, idxPrev, idxNext).setTrackAssigner(ta);
            return a;
        }
        public void transferData(Assignment other) {
            this.prevObjects=other.prevObjects;
            this.nextObjects=other.nextObjects;
            this.idxNext=other.idxNext;
            this.idxPrev=other.idxPrev;
            this.sizePrev=other.sizePrev;
            this.sizeNext=other.sizeNext;
            this.previousSizeRatio=other.previousSizeRatio;
            this.currentScore=other.currentScore;
        }
        public Assignment(List<Region> prev, List<Region> next, double sizePrev, double sizeNext, int idxPrev, int idxNext) {
            this.sizeNext=sizeNext;
            this.sizePrev=sizePrev;
            this.prevObjects = prev;
            this.nextObjects = next;
            this.idxPrev= idxPrev;
            this.idxNext = idxNext;
        }
        public int objectCountPrev() {
            return prevObjects.size();
        }
        public int objectCountNext() {
            return nextObjects.size();
        }
        public int idxPrevEnd() {
            return idxPrev + prevObjects.size();
        }
        public int idxNextEnd() {
            return idxNext + nextObjects.size();
        }
        public boolean prevFromPrevObject() {
            if (prevObjects.size()<=1) return true;
            else {
                Iterator<Region> it = prevObjects.iterator();
                Region first = it.next();
                boolean prevFromPrev=true;
                while(it.hasNext() && prevFromPrev) prevFromPrev = ta.haveSamePreviousObjects.apply(first, it.next());
                return prevFromPrev;
            }
        }
        public boolean prevFromSameLine() {
            if (prevObjects.size()<=1) return true;
            else {
                Iterator<Region> it = prevObjects.iterator();
                Region first = it.next();
                boolean prevFromPrev=true;
                while(it.hasNext() && prevFromPrev) prevFromPrev = ta.areFromSameLine.apply(first, it.next());
                return prevFromPrev;
            }
        }
        public Collection<List<Region>> splitPrevObjectsByLine() {
            List<List<Region>> res = new ArrayList<>();
            Iterator<Region> it = prevObjects.iterator();
            List<Region> curList = new ArrayList<>(prevObjects.size());
            res.add(curList);
            Region curObject = it.next();
            curList.add(curObject);
            while(it.hasNext()) {
                Region otherObject = it.next();
                if (ta.areFromSameLine.apply(curObject,otherObject)) curList.add(otherObject);
                else {
                    curObject = otherObject;
                    curList = new ArrayList<>(prevObjects.size());
                    res.add(curList);
                    curList.add(curObject);
                }
            }
            return res;
        }
        public Region getLastObject(boolean prev) {
            if (prev) {
                if (prevObjects.isEmpty()) return null;
                else return prevObjects.get(prevObjects.size()-1);
            } else {
                if (nextObjects.isEmpty()) return null;
                else return nextObjects.get(nextObjects.size()-1);
            }
        }
        public boolean incrementPrev() {
            if (idxPrevEnd()<ta.prev.size()) {
                Region o = ta.prev.get(idxPrevEnd());
                prevObjects.add(o);
                sizePrev=ta.sizeFunction.apply(prevObjects);
                previousSizeRatio = Double.NaN;
                currentScore=null;
                return true;
            } else return false;
        }
        public boolean incrementNext() {
            if (idxNextEnd()<ta.next.size()) {
                Region o = ta.next.get(idxNextEnd());
                nextObjects.add(o);
                sizeNext=ta.sizeFunction.apply(nextObjects);
                currentScore=null;
                return true;
            } else return false;
        }
        public boolean remove(boolean prev, boolean removeFirst) {
            if (prev) {
                if (!prevObjects.isEmpty()) {
                    prevObjects.remove(removeFirst ? 0 : prevObjects.size()-1);
                    sizePrev=ta.sizeFunction.apply(prevObjects);
                    if (removeFirst) idxPrev++;
                    previousSizeRatio = Double.NaN;
                    currentScore=null;
                    return true;
                } else return false;
            } else {
                if (!nextObjects.isEmpty()) {
                    nextObjects.remove(removeFirst ? 0 : nextObjects.size()-1);
                    sizeNext=ta.sizeFunction.apply(nextObjects);
                    if (removeFirst) idxNext++;
                    return true;
                } else return false;
            }
        }
        public boolean removeUntil(boolean prev, boolean removeFirst, int n) {
            List<Region> l = prev ? prevObjects : nextObjects;
            if (l.size()<=n) return false;
            currentScore=null;
            if (prev) previousSizeRatio = Double.NaN;
            if (removeFirst) {
                Iterator<Region> it = l.iterator();
                while(l.size()>n) {
                    it.next();
                    if (prev) idxPrev++;
                    else idxNext++;
                    it.remove();
                }
            } else {
                ListIterator<Region> it = l.listIterator(l.size());
                while(l.size()>n) {
                    it.previous();
                    it.remove();
                }
            }
            if (prev) this.sizePrev = ta.sizeFunction.apply(prevObjects);
            else this.sizeNext = ta.sizeFunction.apply(nextObjects);
            return true;
        }
        
        protected void incrementIfNecessary() {
            if (prevObjects.isEmpty()) if (!incrementPrev()) return;
            if (nextObjects.isEmpty()) if (!incrementNext()) return;
            incrementUntilVerifyInequality();
            if (BacteriaClosedMicrochannelTrackerLocalCorrections.debug && ta.verboseLevel< BacteriaClosedMicrochannelTrackerLocalCorrections.verboseLevelLimit) Plugin.logger.debug("L:{} start: {}", ta.verboseLevel, this);
            adjustAssignmentToFitPreviousSizeRatio();
            if (BacteriaClosedMicrochannelTrackerLocalCorrections.debug && ta.verboseLevel< BacteriaClosedMicrochannelTrackerLocalCorrections.verboseLevelLimit) Plugin.logger.debug("L:{} after fit to SI: {}", ta.verboseLevel, this);
        }
                
        protected void incrementUntilVerifyInequality() {
            while(!verifyInequality()) {
                if (sizePrev * ta.baseSizeRatio[1] < sizeNext) {
                    if (!incrementPrev()) return;
                } else if (sizePrev * ta.baseSizeRatio[0] > sizeNext) {
                    if (!incrementNext()) return;
                } 
            }
        }
        protected void adjustAssignmentToFitPreviousSizeRatio() {
            if (Double.isNaN(getPreviousSizeRatio())) return;
            double currentDelta = previousSizeRatio * sizePrev - sizeNext;
            deltaSizeMapAssignmentCandidate.clear();
            while(true) {
                //logger.debug("Fit previous size increment: prevSI: {}, curSI: {} deltaSI: {} error: {} ass: {}", previousSizeRatio, sizeNext/sizePrev, previousSizeRatio-sizeNext/sizePrev, currentDelta, this);
                if (currentDelta>0) { // missing @ next OR too much at prev
                    if (this.prevObjects.size()>1) { // remove prev
                        Assignment other = this.duplicate(ta);
                        other.remove(true, false);
                        double otherDelta = other.getPreviousSizeRatio() * other.sizePrev - other.sizeNext;
                        if (Math.abs(otherDelta)<Math.abs(currentDelta)) deltaSizeMapAssignmentCandidate.put(otherDelta, other);
                    }
                    if (this.nextObjects.size()>1 && this.prevObjects.size()>1) { // remove prev & next
                        Assignment other = this.duplicate(ta);
                        other.remove(true, false);
                        other.remove(false, false);
                        double otherDelta = other.getPreviousSizeRatio() * other.sizePrev - other.sizeNext;
                        if (Math.abs(otherDelta)<Math.abs(currentDelta)) deltaSizeMapAssignmentCandidate.put(otherDelta, other);
                    }
                    if (Math.abs(currentDelta/sizePrev) > BacteriaClosedMicrochannelTrackerLocalCorrections.significantSRErrorThld /2.0) { // add to next. This limit is used to avoid the cases where
                        Assignment other = this.duplicate(ta);
                        if (other.incrementNext()) {
                            double otherDelta = other.getPreviousSizeRatio() * other.sizePrev - other.sizeNext;
                            if (Math.abs(otherDelta)<Math.abs(currentDelta)) deltaSizeMapAssignmentCandidate.put(otherDelta, other);
                            else if (other.incrementPrev() && other.prevFromPrevObject()){ // also try to add prev & next. This scenario need to be limited, if not it can grow easily because 
                                otherDelta = other.getPreviousSizeRatio() * other.sizePrev - other.sizeNext;
                                //if (Math.abs(otherDelta)<Math.abs(currentDelta)) deltaSizeMapAssignmentCandidate.put(otherDelta, other);
                                Assignment other2 = new Assignment(ta, idxPrevEnd(), idxNextEnd());
                                other2.incrementNext();
                                other2.incrementPrev();
                                double otherDelta2 = other2.getPreviousSizeRatio() * other2.sizePrev - other2.sizeNext;
                                //if (Math.abs(otherDelta)<(Math.abs(currentDelta)+Math.abs(otherDelta2))/2d) deltaSizeMapAssignmentCandidate.put(otherDelta, other);
                                if (BacteriaClosedMicrochannelTrackerLocalCorrections.debug) Plugin.logger.debug("d<0: assignment: add p&n: current {}({}), other: {}({}) other2: {}({})", this, currentDelta, other, otherDelta, other2, otherDelta2);
                                if (Math.abs(otherDelta)<Math.abs(currentDelta) && Math.abs(otherDelta)<Math.abs(otherDelta2)) {
                                    deltaSizeMapAssignmentCandidate.put(otherDelta, other);
                                }
                            }
                        }
                    }
                } else if (currentDelta<0) {
                    if (this.nextObjects.size()>1) { // remove next
                        Assignment other = this.duplicate(ta);
                        other.remove(false, false);
                        double otherDelta = other.getPreviousSizeRatio() * other.sizePrev - other.sizeNext;
                        if (Math.abs(otherDelta)<Math.abs(currentDelta)) deltaSizeMapAssignmentCandidate.put(otherDelta, other);
                    }
                    if (this.nextObjects.size()>1 && this.prevObjects.size()>1) { // remove prev & next
                        Assignment other = this.duplicate(ta);
                        other.remove(true, false);
                        other.remove(false, false);
                        double otherDelta = other.getPreviousSizeRatio() * other.sizePrev - other.sizeNext;
                        if (Math.abs(otherDelta)<Math.abs(currentDelta)) deltaSizeMapAssignmentCandidate.put(otherDelta, other);
                    }
                    if (Math.abs(currentDelta/sizePrev) > BacteriaClosedMicrochannelTrackerLocalCorrections.significantSRErrorThld /2.0 ) { // add to prev
                        Assignment other = this.duplicate(ta);
                        if (other.incrementPrev()) {
                            double otherDelta = other.getPreviousSizeRatio() * other.sizePrev - other.sizeNext;
                            if (Math.abs(otherDelta)<Math.abs(currentDelta)) deltaSizeMapAssignmentCandidate.put(otherDelta, other);
                            else if (other.incrementNext() && other.prevFromPrevObject()) { // also try to add next && prev
                                otherDelta = other.getPreviousSizeRatio() * other.sizePrev - other.sizeNext;
                                //if (Math.abs(otherDelta)<Math.abs(currentDelta)) deltaSizeMapAssignmentCandidate.put(otherDelta, other);
                                Assignment other2 = new Assignment(ta, idxPrevEnd(), idxNextEnd());
                                other2.incrementNext();
                                other2.incrementPrev();
                                double otherDelta2 = other2.getPreviousSizeRatio() * other2.sizePrev - other2.sizeNext;
                                if (BacteriaClosedMicrochannelTrackerLocalCorrections.debug) Plugin.logger.debug("d>0 assignment: add p&n: current {}({}), other: {}({}) other2: {}({})", this, currentDelta, other, otherDelta, other2, otherDelta2);
                                if (Math.abs(otherDelta)<Math.abs(currentDelta) && Math.abs(otherDelta)<Math.abs(otherDelta2)) {
                                    deltaSizeMapAssignmentCandidate.put(otherDelta, other);
                                }
                            }
                        }
                    }
                }
                if (deltaSizeMapAssignmentCandidate.isEmpty()) return;
                Entry<Double, Assignment> next = Collections.min(deltaSizeMapAssignmentCandidate.entrySet(), (e1, e2)->Double.compare(Math.abs(e1.getKey()),Math.abs(e2.getKey())));
                this.transferData(next.getValue());
                currentDelta = next.getKey();
                deltaSizeMapAssignmentCandidate.clear();
            }
        }
        
        public double getPreviousSizeRatio() {
            if (Double.isNaN(previousSizeRatio) && !prevObjects.isEmpty()) {
                previousSizeRatio = ta.sizeRatios.getAndCreateIfNecessary(prevObjects.get(0));
                if (!prevFromPrevObject()) {  // compute size-weighted barycenter of size increment from lineage
                    double totalSize= prevObjects.get(0).size();
                    previousSizeRatio *= totalSize;
                    for (int i = 1; i<prevObjects.size(); ++i) { 
                        double curSI = ta.sizeRatios.getAndCreateIfNecessary(prevObjects.get(i));
                        if (!Double.isNaN(curSI)) {
                            double size = prevObjects.get(i).size();
                            previousSizeRatio+= curSI * size;
                            totalSize += size;
                        }
                    }
                    previousSizeRatio/=totalSize;
                }
            }
            return previousSizeRatio;
        }
        
        public boolean verifyInequality() {
            return ta.verifyInequality(sizePrev, sizeNext);
        }
        public boolean truncatedEndOfChannel() {
            return (ta.truncatedChannel && idxNextEnd()==ta.next.size()   && 
                    (ta.mode==TrackAssigner.AssignerMode.ADAPTATIVE && !Double.isNaN(getPreviousSizeRatio()) ? getPreviousSizeRatio()-sizeNext/sizePrev> BacteriaClosedMicrochannelTrackerLocalCorrections.significantSRErrorThld : sizePrev * ta.baseSizeRatio[0] > sizeNext) ); //&& idxEnd-idx==1 // && idxPrevEnd-idxPrev==1
        }
        public boolean needCorrection() {
            return prevObjects.size()>1 || nextObjects.size()>2; 
        }
        public boolean canBeCorrected() {
            return needCorrection();// && (idxEnd-idx==1) ;
        }
        /**
         * 
         * @return a length 2 array holing the error count (see {@link #getErrorCount() } and the difference between size increment and expected size increment from previous frames when available, NaN when not available
         */
        protected double[] getScore() {
            if (this.nextObjects.isEmpty() && idxNextEnd()<ta.next.size()) return new double[]{getErrorCount(), 0}; // cell death scenario
            double prevSizeRatio = ta.mode==TrackAssigner.AssignerMode.ADAPTATIVE ? getPreviousSizeRatio() : Double.NaN;
            if (Double.isNaN(prevSizeRatio)) return new double[]{getErrorCount(), Double.NaN};
            if (BacteriaClosedMicrochannelTrackerLocalCorrections.debug && ta.verboseLevel< BacteriaClosedMicrochannelTrackerLocalCorrections.verboseLevelLimit) Plugin.logger.debug("L:{}, assignment score: prevSI: {}, SI: {}", ta.verboseLevel, prevSizeRatio, sizeNext/sizePrev);
            return new double[]{getErrorCount(), Math.abs(prevSizeRatio - sizeNext/sizePrev)};
        }
        
        public int getTrackingErrorCount() {
            return Math.max(0, nextObjects.size()-2) + prevObjects.size()-1; // division in more than 2 + merging
            //return Math.max(Math.max(0, nextObjects.size()-2), prevObjects.size()-1); // max error @ prev OU @ next
        }
        /**
         * Error number is the sum of (1) the number of sur-numerous objects in assignment: more than 2 objects at next frame or more than one object at previous frame, and the number of errors due to size increment (See {@link #getSizeRatioErrors() }
         * No errors is counted is the assignment involve object at the opened-end of the microchannel to take into account missing bacteria
         * @return number of errors in this assignment
         */
        public double getErrorCount() {
            int res = getTrackingErrorCount();
            //if ((!verifyInequality() || significantSizeRatioError()) && !truncatedEndOfChannel()) ++res; // bad size increment
            if (!truncatedEndOfChannel()) {
                double sig = getSizeRatioErrors();
                res+=sig* BacteriaClosedMicrochannelTrackerLocalCorrections.SRErrorValue;
            }
            if (notSameLineIsError && !prevFromSameLine()) ++res;
            if (BacteriaClosedMicrochannelTrackerLocalCorrections.debug && ta.verboseLevel< BacteriaClosedMicrochannelTrackerLocalCorrections.verboseLevelLimit) Plugin.logger.debug("L:{}, getError count: {}, errors: {}, truncated: {}", ta.verboseLevel, this, res, truncatedEndOfChannel());
            return res;        
        }
        /**
         * Converts a the difference between size increment and expected size increment from previous lines (when available) into error number
         * If the difference is close enough to 0 no errors are returned
         * @return number of errors due to size increment
         */
        public double getSizeRatioErrors() {
            if (ta.mode==TrackAssigner.AssignerMode.ADAPTATIVE) {
            double prevSizeRatio = getPreviousSizeRatio();
            if (Double.isNaN(prevSizeRatio)) {
                return verifyInequality() ? 0 : 1;
            } else {
                double sizeRatio = sizeNext/sizePrev;
                if (BacteriaClosedMicrochannelTrackerLocalCorrections.debug && ta.verboseLevel< BacteriaClosedMicrochannelTrackerLocalCorrections.verboseLevelLimit) Plugin.logger.debug("L:{}: {}, sizeRatioError check: SI:{} lineage SI: {}, error: {}", ta.verboseLevel, this, sizeRatio, prevSizeRatio, Math.abs(prevSizeRatio-sizeRatio));
                double err =  (Math.abs(prevSizeRatio-sizeRatio)/ BacteriaClosedMicrochannelTrackerLocalCorrections.significantSRErrorThld);
                return err>1 ? err:0;
            }
            } else return verifyInequality() ? 0:1;
        }
        public String toString(boolean size) {
            String res = "["+idxPrev+";"+(idxPrevEnd()-1)+"]->[" + idxNext+";"+(idxNextEnd()-1)+"]";
            if (size) res +="/Sizes:"+String.format("%.2f", sizePrev)+ "->"+String.format("%.2f", sizeNext)+ "/Ineq:"+verifyInequality()+"/SI:"+sizeNext/sizePrev+"/SIPrev:"+getPreviousSizeRatio();
            return res;
        }
        @Override public String toString() {
            return toString(true);
        }
    }
