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
package bacmman.plugins.plugins.trackers.nested_spot_tracker.post_processing;

import bacmman.plugins.plugins.trackers.nested_spot_tracker.SpotWithQuality;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.apache.commons.math3.distribution.RealDistribution;

/**
 *
 * @author Jean Ollion
 */
public class TrackLikelyhoodEstimator {
    double minLength, maxLength; // minimal length for a track
    double theoricalLength;
    final ScoreFunction scoreFunction;
    final int maximalSplitNumber;
    public TrackLikelyhoodEstimator(RealDistribution lengthDistribution, RealDistribution distanceDistribution, int minimalTrackLength, int maximalSplitNumber) {
        this.scoreFunction = new HarmonicScoreFunction(new DistributionFunction(lengthDistribution), new DistributionFunction(distanceDistribution));
        this.minLength = minimalTrackLength;
        this.maximalSplitNumber=maximalSplitNumber;
        init();
        //logger.debug("minLength: {}/D:{}, theorical length: {}/D:{}, maximalLength: {}/D:{}", minLength, lengthDistribution.density(minLength), theoricalLength, lengthDistribution.density(theoricalLength), maxLength, lengthDistribution.density(maxLength));
    }
    
    public TrackLikelyhoodEstimator(ScoreFunction scoreFunction, double minimalTrackLength, int maximalSplitNumber) {
        this.scoreFunction = scoreFunction;
        this.minLength=minimalTrackLength;
        this.maximalSplitNumber=maximalSplitNumber;
        init();
    }
    
    private void init() {
        this.theoricalLength = getModalXValue(scoreFunction.getLengthFunction(), minLength, 0.5d, 0.01d);
        maxLength = getMaxXValue(scoreFunction.getLengthFunction(), theoricalLength, scoreFunction.getLengthFunction().y(minLength), 0.5d, 0.01d);
        //logger.debug("th length: {}/D={}, max length: {}", theoricalLength, scoreFunction.getLengthFunction().y(theoricalLength), maxLength);
    }
    
    private static double getMaxXValue(Function f, double minX, double y, double precision1, double precision2) {
        double tempY = f.y(minX);
        while(tempY>y) {
            minX+=precision1;
            tempY=f.y(minX);
        }
        minX-=precision1;
        tempY=f.y(minX);
        while(tempY>y) {
            minX+=precision2;
            tempY=f.y(minX);
        }
        return minX;
    }
    
    private static double getModalXValue(Function f, double minX, double precision1, double precision2) { 
        double minProbability = f.y(minX);
        double maxX = minX;
        double maxP = minProbability;
        double tempP = maxP;
        double tempX = minX;
        while(tempP>=minProbability) {
            tempX+=precision1;
            tempP = f.y(tempX);
            if (tempP>maxP) {
                maxP = tempP;
                maxX = tempX;
            }
        }
        if (precision2 * 10 < precision1) {
            maxX = getModalXValue_(f, maxX-precision1, maxX+precision1, precision2*10);
            return getModalXValue_(f, maxX-precision2*10, maxX+precision2*10, precision2);
        } else return getModalXValue_(f, maxX-precision1, maxX+precision1, precision2);
    }
    
    private static double getModalXValue_(Function f, double lowerXBound, double upperXBound, double precision) { 
        double maxX = upperXBound;
        double maxP = f.y(maxX);
        double tempP;
        for (double x = lowerXBound; x<upperXBound; x+=precision) {
            tempP = f.y(x);
            if (tempP>maxP) {
                maxP = tempP;
                maxX = x;
            }
        }
        //logger.debug("modal value search: lb: {}, hb: {}, precision: {}, X: {}, p(X): {}", lowerXBound, upperXBound, precision, maxX, maxP);
        return maxX;
    }
    
    public SplitScenario splitTrack(Track track) {
        // get number of divisions
        int[] splitNumbers = getSplitNumber(track);
        //if (splitNumbers.length>0) logger.debug("split track: number of divisions: {}", splitNumbers);
        //logger.debug("frames: {}", track.frames);
        //logger.debug("distances: {}", track.distances);
        SplitScenario best=null;
        for (int i =0; i<splitNumbers.length; ++i) {
            SplitScenario si = splitTrack(track, splitNumbers[i]);
            if (best==null || (si!=null && si.scoreSum>best.scoreSum)) best = si;
        }
        //logger.debug("split track: best scenario {}", s1);
        return best;
    }
    
    public int[] getSplitNumber(Track track) {
        if (track.getLength()<=maxLength) return new int[]{0};
        double meanGap = track.getMeanGapLength();
        double rTh = (double) (track.getLength()-theoricalLength) / (theoricalLength+meanGap);
        double rMax = (double) (track.getLength()-minLength) / (minLength+meanGap);
        double rMin = (double) (track.getLength()-maxLength) / (maxLength+meanGap);
        //logger.debug("split track: length: {}, rMin {}, rTh: {}, rMax: {}, meanGap: {}",track.getLength(), rMin, rTh, rMax, meanGap);
        int nMin = (int)Math.ceil(rMin);
        int nMax = (int)rMax;
        if (nMin+1>=maximalSplitNumber) return new int[]{0};
        if (nMin==nMax) return new int[]{nMin};
        else if (nMax-nMin==1) return new int[]{nMin, nMax};
        else {
            int nThSup = (int) (rTh+0.5);
            int nThInf = (int) rTh;
            if (nThSup>=maximalSplitNumber) return new int[]{0};
            if (nThSup==nThInf) {
                if (nThSup==nMin) return new int[]{nThSup};
                else return new int[]{nThSup-1, nThSup};
            } else {
                if (nThSup<=nMax) return new int[]{nThInf, nThSup};
                else return new int[]{nThInf};
            }
        }
    }
    
    public SplitScenario splitTrack(Track track, int divisionNumber) {
        if (divisionNumber==0) {
            SplitScenario cur =  new SplitScenario(track);
            return cur;
        }
        SplitScenario current = new SplitScenario(track, divisionNumber);
        SplitScenario best = new SplitScenario(track, divisionNumber);
        //logger.debug("split in: {}, best: {}, current: {}", divisionNumber, best, current);
        if (!current.increment(track, 0)) return null;
        current.testLastDivisions(track, best);
        if (divisionNumber == 1) {
            //logger.debug("split in: {}, new best: {}, scoreSum: {}", divisionNumber, best, scoreSum);
            return best;
        }
        final int lastSplitIdx = divisionNumber-2;
        int splitIdx = lastSplitIdx; // index of the split site currently incremented
        boolean inc;
        while(splitIdx>=0) {
            inc = current.increment(track, splitIdx);
            if (inc) {
                splitIdx = lastSplitIdx;
                current.testLastDivisions(track, best);
            } else --splitIdx;
        }
        //logger.debug("split in: {}, new best: {}, scoreSum: {}", divisionNumber, best, scoreSum);
        return best;
    }
    
    public class SplitScenario implements Comparable<SplitScenario> {
        int[] splitIndices;
        double score, scoreSum=0;
        private SplitScenario(Track track) { // no division case
            this.splitIndices=new int[0];
            this.score=scoreFunction.getScore(track, splitIndices);
            this.score+=scoreSum;
        }
        public SplitScenario(Track track, int divisionNumber) {
            this.score=Double.NEGATIVE_INFINITY;
            this.splitIndices= new int[divisionNumber];
        }
        
        public void testLastDivisions(Track track, SplitScenario best) {
            int dIdx = splitIndices.length-1;
            for (int frameIdx = splitIndices[dIdx]; frameIdx<track.frames.length; ++frameIdx) {
                if (track.getLengthToEnd(frameIdx+1) < minLength) break;
                score=scoreFunction.getScore(track, splitIndices);
                scoreSum+=score;
                if (this.compareTo(best)>0) {
                    best.transferFrom(this);
                    //logger.debug("new best: {} (last test: {})", best, this);
                }
                ++splitIndices[dIdx];
            }
        }
        public boolean increment(Track track, int divisionIdx) { // sets the frameDivision index at divisionIdx and forward, return true if there is at leat one acceptable scenario
            ++splitIndices[divisionIdx];
            if (divisionIdx==0) {
                while(track.getLengthFromStart(splitIndices[0])<minLength) ++splitIndices[0];
                if (splitIndices[0]>=track.frames.length) {
                    //logger.debug("increment: no solution @ idx: {}, scenario: {}", 0, this);
                    return false;
                }
            }
            for (int dIdx = divisionIdx+1; dIdx<splitIndices.length; ++dIdx) {
                splitIndices[dIdx] = splitIndices[dIdx-1]+1;
                while(track.getLength(splitIndices[dIdx-1]+1, splitIndices[dIdx])<minLength) {
                    ++splitIndices[dIdx];
                    if (splitIndices[dIdx]>=track.frames.length-1) {
                        //logger.debug("increment: no solution @ idx: {}, scenario: {}", dIdx, this);
                        return false;
                    }
                }
            }
            if (track.getLengthToEnd(splitIndices[splitIndices.length-1])>=minLength) return true;
            else {
                //logger.debug("increment: no solution @ idx: {}, scenario: {}", splitIndices.length-1, this);
                return false;
            }
        }
        public void transferFrom(SplitScenario other) {
            if (other.splitIndices.length!=splitIndices.length) splitIndices = new int[other.splitIndices.length];
            System.arraycopy(other.splitIndices, 0, this.splitIndices, 0, splitIndices.length);
            this.score=other.score;
            this.scoreSum=other.scoreSum;
        }
        
        public <T> List<List<T>> splitTrack(List<T> track) {
            //if (track.size()!=frames.length) throw new IllegalArgumentException("Invalid track length");
            List<List<T>> res = new ArrayList<List<T>>(this.splitIndices.length+1);
            if (splitIndices.length==0) {
                res.add(track);
                return res;
            }
            res.add(track.subList(0, splitIndices[0]+1));
            for (int i = 1; i<splitIndices.length; ++i) res.add(track.subList(splitIndices[i-1]+1, splitIndices[i]+1));
            res.add(track.subList(splitIndices[splitIndices.length-1]+1, track.size()));
            return res;
        }
        @Override public int compareTo(SplitScenario other) {
            return Double.compare(score, other.score);
        }
        @Override public String toString() {
            return "Split Indices: "+Arrays.toString(splitIndices)+ " score: "+score;
        }
    }
    public static class Track {
        int[] frames;
        double[] distances;
        /**
         * 
         * @param frames array of frames of the track length n
         * @param squareDistances array of square distances between spot of a frame and spot of following frame (length = n-1)
         */
        public Track( int[] frames, double[] squareDistances) {
            this.frames=frames;
            this.distances=squareDistances;
        }
        public Track(List<SpotWithQuality> track) {
            frames = new int[track.size()];
            if (track.size()>1) { 
                distances = new double[frames.length-1];
                int lim = track.size();
                frames[0] = track.get(0).frame();
                for (int i = 1; i<lim; ++i) {
                    frames[i] = track.get(i).frame();
                    distances[i-1] = Math.sqrt(track.get(i-1).squareDistanceTo(track.get(i)));
                }
            } else distances = new double[0];
        }
        public int getLength(int startIdx, int stopIdx) {
            return frames[stopIdx] - frames[startIdx];
        }
        public int getLengthFromStart(int stopIdx) {
            return frames[stopIdx] - frames[0];
        }
        public int getLengthToEnd(int startIdx) {
            return frames[frames.length-1] - frames[startIdx];
        }
        public int getLength() {
            return frames[frames.length-1] - frames[0];
        }
        public double getMeanGapLength() {
            double mean = 0;
            for (int i = 1; i<frames.length; ++i) mean+= (frames[i]-frames[i-1]);
            return mean/(frames.length-1);
        }
        @Override public String toString() {
            return "Track: "+Arrays.toString(frames);
        }
    }
    public static interface ScoreFunction {
        public Function getLengthFunction();
        public Function getDistanceFunction();
        public double getScore(Track track, int[] splitIndices);
    }
    public static abstract class AbstractScoreFunction implements ScoreFunction {
        protected Function lengthFunction, distanceFunction;
        public AbstractScoreFunction(Function lengthFunction, Function distanceFunction) {
            this.lengthFunction=lengthFunction;
            this.distanceFunction=distanceFunction;
        }
        public Function getLengthFunction() {return lengthFunction;}
        public Function getDistanceFunction() {return distanceFunction;}
    }
    
    public static class HarmonicScoreFunction extends AbstractScoreFunction {

        public HarmonicScoreFunction(Function lengthFunction, Function distanceFunction) {
            super(lengthFunction, distanceFunction);
        }
        public double getScore(Track track, int[] divisionIndices) {
            if (divisionIndices.length==0) return lengthFunction.y(track.getLength());
            double lengthProduct = lengthFunction.y(track.getLengthFromStart(divisionIndices[0])) * lengthFunction.y(track.getLengthToEnd(divisionIndices[divisionIndices.length-1]+1));
            double distanceProduct = distanceFunction.y(track.distances[divisionIndices[0]]);
            for (int i = 1; i<divisionIndices.length; ++i) {
                lengthProduct *= lengthFunction.y(track.getLength(divisionIndices[i-1]+1, divisionIndices[i]));
                distanceProduct*=distanceFunction.y(track.distances[divisionIndices[i]]);
            }
            return Math.pow(lengthProduct, 1.0/(divisionIndices.length+1.0)) / Math.pow(distanceProduct, 1.0/(divisionIndices.length));
        }
    }
    
    public static interface Function {
        public double y(double x);
    }
    public static class DistributionFunction implements Function {
        RealDistribution distribution;
        boolean trimX=false, normalize=false;
        double maxX, maxY, normalizationValue=Double.NaN;

        public DistributionFunction(RealDistribution distribution) {
            this.distribution=distribution;
        }
        
        public DistributionFunction setTrimAndNormalize(boolean normalizeByModalValue, boolean trimX, double minSearch, double maxSearch, double precision1, double precision2) {
            this.maxX=getModalXValue_(this, minSearch, maxSearch, precision1);
            if (precision2 * 10 < precision1) {
                maxX = getModalXValue_(this, maxX-precision1, maxX+precision1, precision2*10);
                maxX =  getModalXValue_(this, maxX-precision2*10, maxX+precision2*10, precision2);
            } else maxX = getModalXValue_(this, maxX-precision1, maxX+precision1, precision2);
            maxY= y(maxX);
            if (normalizeByModalValue) this.normalizationValue = maxY;
            this.normalize=normalizeByModalValue;
            this.trimX=trimX;
            return this;
        }
        public DistributionFunction setNormalization(double nomalizationValue) {
            this.normalizationValue= nomalizationValue;
            this.normalize=true;
            return this;
        }
        public double y(double x) {
            double res = (trimX && x<=maxX) ? maxY : distribution.density(x);
            if (normalize) return res/normalizationValue;
            else return res;
        }
    }
    public static class LinearTrimmedFunction implements Function {
        final double minX, maxX, minY, maxY, m, a;
        final boolean trimOnMax;
        public LinearTrimmedFunction(double minX, double minY, double maxX, double maxY, boolean trimOnMax) {
            this.minX = minX;
            this.maxX = maxX;
            this.minY = minY;
            this.maxY = maxY;
            this.m = (maxY-minY) / (maxX-minX);
            this.a = minY - m * minX;
            this.trimOnMax=trimOnMax;
        }
        
        public double y(double x) {
            if (x<=minX) return minY;
            if (trimOnMax && x>=maxX) return maxY;
            return a + m * x;
        }

    }
}
