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
import bacmman.utils.HashMapGetCreate;
import bacmman.utils.Utils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;
import static bacmman.plugins.plugins.trackers.bacteria_in_microchannel_tracker.BacteriaClosedMicrochannelTrackerLocalCorrections.debug;
import static bacmman.plugins.plugins.trackers.bacteria_in_microchannel_tracker.BacteriaClosedMicrochannelTrackerLocalCorrections.verboseLevelLimit;

import java.util.Collection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static bacmman.plugins.plugins.trackers.bacteria_in_microchannel_tracker.BacteriaClosedMicrochannelTrackerLocalCorrections.SRIncreaseThld;

/**
 *
 * @author Jean Ollion
 */
public class TrackAssigner {
    public final static Logger logger = LoggerFactory.getLogger(TrackAssigner.class);
    public static enum AssignerMode {ADAPTATIVE, RANGE};
    
    double[] currentScore = null;
    protected int verboseLevel = 0;
    AssignerMode mode = AssignerMode.ADAPTATIVE;
    final Function<Collection<Region>, Double> sizeFunction;
    private Function<Region, Double> sizeRatioFunction;
    final BiFunction<Region, Region, Boolean> areFromSameLine, haveSamePreviousObjects;
    final List<Region> prev, next;
    final protected List<Assignment> assignments = new ArrayList();
    protected Assignment currentAssignment;
    double[] baseSizeRatio;
    protected boolean truncatedChannel;
    int nextIncrementCheckRecursiveLevel = -1; 
    HashMapGetCreate<Region, Double> sizeRatios = new HashMapGetCreate<>(o -> sizeRatioFunction.apply(o));
    protected TrackAssigner(List<Region> prev, List<Region> next, double[] baseGrowthRate, boolean truncatedChannel, Function<Collection<Region>, Double> sizeFunction, Function<Region, Double> sizeRatioFunction, BiFunction<Region, Region, Boolean> areFromSameLine, BiFunction<Region, Region, Boolean> haveSamePreviousObjects) {
        this.prev= prev!=null ? prev : Collections.EMPTY_LIST;
        this.next= next!=null ? next : Collections.EMPTY_LIST;
        this.sizeFunction = sizeFunction;
        this.sizeRatioFunction=sizeRatioFunction;
        if (sizeRatioFunction==null) mode = AssignerMode.RANGE;
        this.areFromSameLine=areFromSameLine;
        this.haveSamePreviousObjects=haveSamePreviousObjects;
        this.baseSizeRatio=baseGrowthRate;
        this.truncatedChannel=truncatedChannel;
    }
    public TrackAssigner setVerboseLevel(int verboseLevel) {
        this.verboseLevel=verboseLevel;
        return this;
    }
    public TrackAssigner setMode(AssignerMode mode) {
        this.mode = mode;
        return this;
    }
    public TrackAssigner setAllowTruncatedChannel(boolean allow) {
        this.truncatedChannel=allow;
        return this;
    }

    public TrackAssigner setNextIncrementCheckRecursiveLevel(int recursiveDepth) {
        this.nextIncrementCheckRecursiveLevel = recursiveDepth;
        return this;
    }
    public boolean allowRecurstiveNextIncrementCheck() {
        return nextIncrementCheckRecursiveLevel!=0;
    }
    public boolean allowCellDeathScenario() {
        return nextIncrementCheckRecursiveLevel<0 || nextIncrementCheckRecursiveLevel>1;
    }
    
    protected TrackAssigner duplicate(boolean duplicateCurrentAssignment) {
        TrackAssigner res = new TrackAssigner(prev, next, baseSizeRatio, truncatedChannel, sizeFunction, sizeRatioFunction, areFromSameLine, haveSamePreviousObjects);
        res.sizeRatios=sizeRatios;
        res.assignments.addAll(assignments);
        if (duplicateCurrentAssignment && currentAssignment!=null) {
            res.currentAssignment = currentAssignment.duplicate(res);
            res.assignments.remove(currentAssignment);
            res.assignments.add(res.currentAssignment);
        }
        res.mode=mode;
        res.verboseLevel=verboseLevel;
        //res.currentScore=currentScore; // do not duplicate scores & previous SI because duplicated objects are subect to modifications..
        //res.previousSizeRatio=previousSizeRatio;
        return res;
    }
    
    public boolean assignUntil(int idx, boolean prev) {
        if (currentAssignment==null) nextTrack();
        if (prev) {
            while(currentAssignment.idxPrevEnd()<=idx && nextTrack()) {}
            return currentAssignment.idxPrevEnd()>idx;
        }
        else {
            while(currentAssignment.idxNextEnd()<=idx && nextTrack()) {}
            return currentAssignment.idxNextEnd()>idx;
        }
    }
    
    public boolean assignUntil(Region o, boolean prev) {
        if (currentAssignment==null) nextTrack();
        if (prev) {
            while(!currentAssignment.prevObjects.contains(o) && nextTrack()) {}
            return currentAssignment.prevObjects.contains(o);
        }
        else {
            while(!currentAssignment.nextObjects.contains(o) && nextTrack()) {}
            return currentAssignment.nextObjects.contains(o);
        }
    }
    public void resetIndices(boolean prev) {
        if (prev) {
            for (Assignment a : assignments) if (!a.prevObjects.isEmpty()) a.idxPrev = this.prev.indexOf(a.prevObjects.get(0));
        } else {
            for (Assignment a : assignments) if (!a.nextObjects.isEmpty()) a.idxNext = this.next.indexOf(a.nextObjects.get(0));
        }
    }
    public void assignAll() {
        while(nextTrack()) {}
    }
    public Assignment getAssignmentContaining(Region o, boolean inPrev) {
        for (Assignment a : this.assignments) if (inPrev && a.prevObjects.contains(o) || !inPrev && a.nextObjects.contains(o)) return a;
        return null;
    }
    public int getErrorCount() {
        int res = 0;
        for (Assignment ass : assignments) res+=ass.getErrorCount();
        if (truncatedChannel && !assignments.isEmpty()) {
            Assignment last = assignments.get(assignments.size()-1);
            if (last.idxPrevEnd()<prev.size()-1) res+=prev.size()-1 - last.idxPrevEnd(); // # of unlinked cells @F-1, except the last one
            if (last.idxNextEnd()<next.size()) res+=next.size() - last.idxNextEnd(); // #of unlinked cells @t
        }
        return res;
    }
    
    
    /**
     * 
     * @return true if there is at least 1 remaining object @ timePoint & timePoint -1
     */
    public boolean nextTrack() {
        if (currentAssignment!=null && (currentAssignment.idxPrevEnd()==prev.size() || currentAssignment.idxNextEnd()==next.size())) return false;
        currentAssignment = new Assignment(this, currentAssignment==null?0:currentAssignment.idxPrevEnd(), currentAssignment==null?0:currentAssignment.idxNextEnd());
        currentScore=null;
        assignments.add(currentAssignment);
        currentAssignment.incrementIfNecessary();
        return true;
    }
    
    protected boolean checkNextIncrement() {
        TrackAssigner nextSolution = duplicate(true).setVerboseLevel(verboseLevel+1).setNextIncrementCheckRecursiveLevel(this.nextIncrementCheckRecursiveLevel<0 ? 1: (nextIncrementCheckRecursiveLevel==0 ? 0:this.nextIncrementCheckRecursiveLevel-1));
        // get another solution that verifies inequality
        boolean incrementPrev;
        if (mode==AssignerMode.ADAPTATIVE && !Double.isNaN(currentAssignment.getPreviousSizeRatio())) incrementPrev = currentAssignment.sizeNext/currentAssignment.sizePrev>currentAssignment.getPreviousSizeRatio();
        else incrementPrev = currentAssignment.sizePrev<currentAssignment.sizeNext;
        if (incrementPrev) {
            if (!nextSolution.currentAssignment.incrementPrev()) return false;
        } else {
            if (!nextSolution.currentAssignment.incrementNext()) return false;
        }
        nextSolution.currentAssignment.incrementUntilVerifyInequality();
        if (!nextSolution.currentAssignment.verifyInequality()) return false;
        if (debug && verboseLevel<verboseLevelLimit) logger.debug("L:{}, {} next solution: {}, current score: {}, next score: {}, prev from sameLine: {}", verboseLevel, currentAssignment.toString(false), nextSolution.currentAssignment.toString(false), getCurrentScore(), nextSolution.getCurrentScore(), nextSolution.currentAssignment.prevFromSameLine());
        //if (debug && verboseLevel<verboseLevelLimit) logger.debug("current: {}, next: {}", this, nextSolution);
        // compare the current & new solution
        double[] newScore = nextSolution.getCurrentScore();
        double curSIIncreaseThld = SRIncreaseThld; // increment only if significative improvement
        if (nextSolution.currentAssignment.prevFromSameLine()) curSIIncreaseThld=0;  // if all prev object the same line, no penalty, only absolute improvement is requiered
        newScore[1]+=curSIIncreaseThld; 
        if (compareScores(getCurrentScore(), newScore, mode!=AssignerMode.RANGE)<=0) return false;
        newScore[1]-=curSIIncreaseThld;
        this.currentScore=nextSolution.currentScore;
        this.currentAssignment.transferData(nextSolution.currentAssignment);
        return true;
    }

    public double[] getCurrentScore() {
        if (currentScore==null) {
            currentScore = getScoreForCurrentAndNextAssignments(prev.size(), next.size());
        } return currentScore;
    }
    
    private double[] getScoreForCurrentAndNextAssignments(int idxPrevLimit, int idxLimit) { 
        verboseLevel++;
        if (currentAssignment==null) nextTrack();
        Assignment current = currentAssignment;
        int recursiveLevel = this.nextIncrementCheckRecursiveLevel;
        if (recursiveLevel<0) this.nextIncrementCheckRecursiveLevel=recursiveLevel = 1;
        double[] oldScore=this.currentScore;
        int assignmentCount = assignments.size();
        if (currentAssignment.truncatedEndOfChannel()) return new double[]{currentAssignment.getErrorCount(), 0}; 
        double[] score = currentAssignment.getScore();
        if (debug && verboseLevel-1<verboseLevelLimit) logger.debug("L:{}, {} score start:{}", verboseLevel-1, currentAssignment, currentScore);
        //if (Double.isNaN(score[1])) return score;
        int count = 1;
        while(nextTrack() && !currentAssignment.truncatedEndOfChannel() && (currentAssignment.idxNextEnd()<=idxLimit || currentAssignment.idxPrevEnd()<=idxPrevLimit)) { // do not take into account score of incomplete division
            double[] newScore=currentAssignment.getScore();
            //score[1] = Math.max(score[1], newScore[1]); // maximum score = worst case scenario
            score[1] += newScore[1];
            ++count;
            score[0]+=newScore[0];
            if (debug && verboseLevel-1<verboseLevelLimit) logger.debug("L:{} score for whole scenario: new score for {}, currentScore: {}", verboseLevel-1, currentAssignment, newScore, score);
        }
        score[1] /=count;
        // # of unlinked cells, except the last one if truncatedChannel
        int trunc = truncatedChannel ? 1 : 0;
        if (idxPrevLimit==prev.size() && currentAssignment.idxPrevEnd()<prev.size()-trunc) score[0]+=prev.size()-trunc - currentAssignment.idxPrevEnd(); 
        if (idxLimit==prev.size() && currentAssignment.idxNextEnd()<next.size()-trunc) score[0]+=next.size()-trunc - currentAssignment.idxNextEnd();

        // revert to previous state
        verboseLevel--;
        this.currentAssignment=current;
        this.currentScore=oldScore;
        this.nextIncrementCheckRecursiveLevel=recursiveLevel;
        for (int i = this.assignments.size()-1; i>=assignmentCount; --i) assignments.remove(i);
        return score;
    }
    
    public static int compareScores(double[] s1, double[] s2, boolean useScore) { // 0 = nb errors / 1 = score value. return -1 if first is better
        if (!useScore) return Double.compare(s1[0], s2[0]);
        else {
            if (s1[0]<s2[0]) return -1; // minimize errors
            else if (s1[0]>s2[0]) return 1;
            else if (s1[1]<s2[1]) return -1; // minimize errors
            else if (s1[1]>s2[1]) return 1;
            else return 0;
        }
    }
    
    protected boolean verifyInequality(double sizePrev, double sizeNext) {
        return sizePrev * baseSizeRatio[0] <= sizeNext && sizeNext <= sizePrev * baseSizeRatio[1];
    }
    

    @Override public String toString() {
        return toString(false);
    }
    public String toString(boolean all) {
        return "L:"+verboseLevel+" "+ Utils.toStringList(this.assignments, a -> a.toString(all));
    }
    

}

