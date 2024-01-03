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
package bacmman.utils;

import bacmman.image.ImageFloat;
import bacmman.processing.ImageFeatures;
import ij.measure.CurveFitter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.function.DoublePredicate;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.DoubleStream;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 *
 * @author Jean Ollion
 */
public class ArrayUtil {
    public static final Logger logger = LoggerFactory.getLogger(ArrayUtil.class);

    public static <T, K extends Comparable<K>> int binarySearchKey(T[] sortedArray, K key, Function<T, K> keyExtractor) {
        return binarySearchKey(sortedArray, key, keyExtractor, 0, sortedArray.length);
    }
    public static <T, K extends Comparable<K>> int binarySearchKey(T[] sortedArray, K key, Function<T, K> keyExtractor, int fromIndex, int toIndex) {
        int low = fromIndex;
        int high = toIndex - 1;
        while (low <= high) {
            int mid = low  + ((high - low) / 2);
            int comp = keyExtractor.apply(sortedArray[mid]).compareTo(key);
            if (comp < 0) {
                low = mid + 1;
            } else if (comp > 0) {
                high = mid - 1;
            } else {
                return mid; // key found
            }
        }
        return -(low + 1);  // key not found.;
    }

    @SuppressWarnings("unchecked")
    public static <I> I[] generateArray(Class<I> clazz, int size) {
        // Use Array native method to create array
        // of a type only known at run time
        return(I[]) Array.newInstance(clazz, size);
    }
    @SuppressWarnings("unchecked")
    public static <I> I[][] generateMatrix(Class<I> clazz, int... size) {
        if (size.length ==0 || size.length > 2) throw new IllegalArgumentException("Invalid size");
        int[] dims = size.length == 2 ? size : new int[]{size[0], size[0]};
        return(I[][]) Array.newInstance(clazz, dims);
    }

    @SuppressWarnings("unchecked")
    public static <I> I[][][] generateTensor3(Class<I> clazz, int... size) {
        if (size.length !=3 && size.length!=1) throw new IllegalArgumentException("Invalid size");
        int[] dims = size.length == 3 ? size : new int[]{size[0], size[0], size[0]};
        return(I[][][]) Array.newInstance(clazz, dims);
    }

    public static DoubleStream stream(double[] array) {
        return Arrays.stream(array);
    }
    public static DoubleStream stream(float[] array) {
        return IntStream.range(0, array.length).mapToDouble(i->array[i]);
    }
    public static DoubleStream stream(byte[] array) {
        return IntStream.range(0, array.length).mapToDouble(i->array[i]&0xff);
    }
    public static DoubleStream stream(short[] array) {
        return IntStream.range(0, array.length).mapToDouble(i->array[i]&0xffff);
    }
    public static DoubleStream stream(int[] array) {
        return IntStream.range(0, array.length).mapToDouble(i->array[i]);
    }
    public static IntStream streamInt(byte[] array) {
        return IntStream.range(0, array.length).map(i->array[i]&0xff);
    }
    public static IntStream streamInt(short[] array) {
        return IntStream.range(0, array.length).map(i->array[i]&0xffff);
    }
    public static IntStream streamInt(int[] array) {
        return IntStream.range(0, array.length).map(i->array[i]);
    }
    public static float selectKth(float[] arr, int k) { // from : http://blog.teamleadnet.com/2012/07/quick-select-algorithm-find-kth-element.html
        if (arr == null) throw new IllegalArgumentException("Select K: array null");
        if ( arr.length <= k) throw new IllegalArgumentException("Select K: k>=length of array");
        int from = 0, to = arr.length - 1;
        // if from == to we reached the kth element
        while (from < to) {
            int r = from, w = to;
            float mid = arr[(r + w) / 2];
            // stop if the reader and writer meets
            while (r < w) {
                if (arr[r] >= mid) { // put the large values at the end
                    float tmp = arr[w];
                    arr[w] = arr[r];
                    arr[r] = tmp;
                    w--;
                } else { // the value is smaller than the pivot, skip
                    r++;
                }
            }
            // if we stepped up (r++) we need to step one down
            if (arr[r] > mid) {
                r--;
            }
            // the r pointer is on the end of the first k elements
            if (k <= r) {
                to = r;
            } else {
                from = r + 1;
            }
        }
        return arr[k];
    }

    
    public static int max(float[] array) {
        return max(array, 0, array.length);
    }
    
    /**
     * 
     * @param array 
     * @param start start of search index, inclusive
     * @param stopExclusive end of search index, exclusive
     * @return index of maximum value
     */
    public static int max(float[] array, int start, int stopExclusive) {
        if (start<0) start=0;
        if (stopExclusive>array.length) stopExclusive=array.length;
        if (stopExclusive<start) throw new IllegalArgumentException("Stop before start");
        int idxMax = start;
        for (int i = start+1; i<stopExclusive; ++i) if (array[i]>array[idxMax]) idxMax=i;
        return idxMax;
    }
    public static int max(double[] array) {
        return max(array, 0, array.length);
    }
    public static int max(double[] array, int start, int stop) {
        if (start<0) start=0;
        if (stop>array.length) stop=array.length;
        if (stop<start) throw new IllegalArgumentException("Stop before start");
        int idxMax = start;
        for (int i = start+1; i<stop; ++i) if (array[i]>array[idxMax]) idxMax=i;
        return idxMax;
    }
    public static int max(int[] array) {
        return max(array, 0, array.length);
    }
    public static int max(int[] array, int start, int stopExcluded) {
        if (start<0) start=0;
        if (stopExcluded>array.length) stopExcluded=array.length;
        if (stopExcluded<start) throw new IllegalArgumentException("Stop before start");
        int idxMax = start;
        for (int i = start+1; i<stopExcluded; ++i) if (array[i]>array[idxMax]) idxMax=i;
        return idxMax;
    }
    public static int max(long[] array) {
        return max(array, 0, array.length);
    }
    public static int max(long[] array, int start, int stopExcluded) {
        if (start<0) start=0;
        if (stopExcluded>array.length) stopExcluded=array.length;
        if (stopExcluded<start) throw new IllegalArgumentException("Stop before start");
        int idxMax = start;
        for (int i = start+1; i<stopExcluded; ++i) if (array[i]>array[idxMax]) idxMax=i;
        return idxMax;
    }
    public static int min(float[] array) {
        return min(array, 0, array.length);
    }
    public static int min(float[] array, int start, int stop) {
        if (start<0) start=0;
        if (stop>array.length) stop=array.length;
        if (stop<start) throw new IllegalArgumentException("Stop before start");
        int idxMin = start;
        for (int i = start+1; i<stop; ++i) if (array[i]<array[idxMin]) idxMin=i;
        return idxMin;
    }
    public static int min(double[] array) {
        return min(array, 0, array.length);
    }
    public static int min(double[] array, int start, int stop) {
        if (start<0) start=0;
        if (stop>array.length) stop=array.length;
        if (stop<start) throw new IllegalArgumentException("Stop before start");//{int temp = start; start=stop; stop=temp;}
        int idxMin = start;
        for (int i = start+1; i<stop; ++i) if (array[i]<array[idxMin]) idxMin=i;
        return idxMin;
    }
    public static int min(int[] array) {
        return min(array, 0, array.length);
    }
    public static int min(int[] array, int start, int stop) {
        if (start<0) start=0;
        if (stop>array.length) stop=array.length;
        if (stop<start) throw new IllegalArgumentException("Stop before start");
        int idxMin = start;
        for (int i = start+1; i<stop; ++i) if (array[i]<array[idxMin]) idxMin=i;
        return idxMin;
    }
    
    public static double[] meanSigma(float[] array, int start, int stop, double[] res) {
        if (res==null) res= new double[2];
        res[0]=0; // sum
        res[1]=0; // sum2
        for (int i = start; i<stop; ++i) {
            res[0]+=array[i];
            res[1]+=array[i]*array[i];
        }
        res[0] /= (double)(stop-start);
        res[1] = Math.sqrt(res[1] / (float)(stop-start) - res[0] * res[0]);
        return res;
    }
    public static double[] meanSigma(double[] array, int start, int stop, double[] res) {
        if (res==null) res= new double[2];
        res[0]=0; // sum
        res[1]=0; // sum2
        for (int i = start; i<stop; ++i) {
            res[0]+=array[i];
            res[1]+=array[i]*array[i];
        }
        res[0] /= (double)(stop-start);
        res[1] = Math.sqrt(res[1] / (float)(stop-start) - res[0] * res[0]);
        return res;
    }
    public static double mean(double[] array, int start, int stop) {
        double sum=0;
        for (int i = start; i<stop; ++i) sum+=array[i];
        sum /= (stop-start);
        return sum;
    }
    public static int getFirstOccurence(double[] array, int start, int stop, DoublePredicate verify) {
        if (start<0) start=0;
        if (stop<0) stop = 0;
        if (stop>array.length) stop=array.length;
        if (start>array.length) start = array.length;
        int i = start;
        if (start<=stop) {
            while(i<stop-1 && !verify.test(array[i])) ++i;
            if (verify.test(array[i])) return i;
            else return -1;
        } else {
            while (i>stop && !verify.test(array[i])) --i;
            if (verify.test(array[i])) return i;
            else return -1;
        }
    }
    public static int getFirstOccurence(float[] array, int start, int stop, DoublePredicate verify) {
        if (start<0) start=0;
        if (stop<0) stop = 0;
        if (stop>array.length) stop=array.length;
        if (start>array.length) start = array.length;
        int i = start;
        if (start<=stop) {
            while(i<stop-1 && !verify.test(array[i])) ++i;
            if (verify.test(array[i])) return i;
            else return -1;
        } else {
            while (i>stop && !verify.test(array[i])) --i;
            if (verify.test(array[i])) return i;
            else return -1;
        }
    }
    public static int getFirstOccurence(int[] array, int start, int stop, java.util.function.IntPredicate verify) {
        if (start<0) start=0;
        if (stop<0) stop = 0;
        if (stop>array.length) stop=array.length;
        if (start>array.length) start = array.length;
        int i = start;
        if (start<=stop) {
            while(i<stop-1 && !verify.test(array[i])) ++i;
            if (verify.test(array[i])) return i;
            else return -1;
        } else {
            while (i>stop && !verify.test(array[i])) --i;
            if (verify.test(array[i])) return i;
            else return -1;
        }
    }
    public static int getFirstOccurence(long[] array, int start, int stop, java.util.function.LongPredicate verify) {
        if (start<0) start=0;
        if (stop<0) stop = 0;
        if (stop>array.length) stop=array.length;
        if (start>array.length) start = array.length;
        int i = start;
        if (start<=stop) {
            while(i<stop-1 && !verify.test(array[i])) ++i;
            if (verify.test(array[i])) return i;
            else return -1;
        } else {
            while (i>stop && !verify.test(array[i])) --i;
            if (verify.test(array[i])) return i;
            else return -1;
        }
    }
    
    public static List<Integer> getRegionalExtrema(float[] array, int scale, boolean max) {
        if (scale<1) scale = 1;
        ArrayList<Integer> localExtrema = new ArrayList<Integer>();
        // get local extrema
        if (max) for (int i = 0; i<array.length; ++i) {if (isLocalMax(array, scale, i)) localExtrema.add(i);}
        else for (int i = 0; i<array.length; ++i) {if (isLocalMin(array, scale, i)) localExtrema.add(i);}
        if (localExtrema.size()<=1) return localExtrema;
        
        // suppress plateau
        ArrayList<Integer> regionalExtrema = new ArrayList<Integer>(localExtrema.size());
        for (int i = 1; i<localExtrema.size(); ++i) {
            if (localExtrema.get(i)==localExtrema.get(i-1)+1) {
                int j = i+1;
                while (j<localExtrema.size() && localExtrema.get(j)==localExtrema.get(j-1)+1){j++;}
                //logger.debug("i: {}, j:{}, loc i-1: {}, loc j-1: {}",i, j, localExtrema.get(i-1), localExtrema.get(j-1));
                regionalExtrema.add((localExtrema.get(i-1)+localExtrema.get(j-1))/2); //mid-value of plateau (i-1 = borne inf, j-1 = borne sup)
                i=j;
            } else regionalExtrema.add(localExtrema.get(i-1));
        }
        // add last element if not plateau:
        if (localExtrema.get(localExtrema.size()-1)!=localExtrema.get(localExtrema.size()-2)+1) regionalExtrema.add(localExtrema.get(localExtrema.size()-1));
        return regionalExtrema;
    }
    
    public static List<Integer> getRegionalExtrema(double[] array, int scale, boolean max) {
        if (scale<1) scale = 1;
        ArrayList<Integer> localExtrema = new ArrayList<>();
        // get local extrema
        if (max) for (int i = 0; i<array.length; ++i) {if (isLocalMax(array, scale, i)) localExtrema.add(i);}
        else for (int i = 0; i<array.length; ++i) {if (isLocalMin(array, scale, i)) localExtrema.add(i);}
        if (localExtrema.size()<=1) return localExtrema;
        
        // suppress plateau
        ArrayList<Integer> regionalExtrema = new ArrayList<>(localExtrema.size());
        for (int i = 1; i<localExtrema.size(); ++i) {
            if (localExtrema.get(i)==localExtrema.get(i-1)+1) {
                int j = i+1;
                while (j<localExtrema.size() && localExtrema.get(j)==localExtrema.get(j-1)+1){j++;}
                //logger.debug("i: {}, j:{}, loc i-1: {}, loc j-1: {}",i, j, localExtrema.get(i-1), localExtrema.get(j-1));
                regionalExtrema.add((localExtrema.get(i-1)+localExtrema.get(j-1))/2); //mid-value of plateau (i-1 = borne inf, j-1 = borne sup)
                i=j;
            } else regionalExtrema.add(localExtrema.get(i-1));
        }
        // add last element if not plateau:
        if (localExtrema.get(localExtrema.size()-1)!=localExtrema.get(localExtrema.size()-2)+1) regionalExtrema.add(localExtrema.get(localExtrema.size()-1));
        return regionalExtrema;
    }
    
    protected static boolean isLocalMax(float[] array, int scale, int idx) {
        for (int i = 1; i<=scale; ++i) {
            if (idx-i>=0 && array[idx-i]>array[idx]) return false;
            if (idx+i<array.length && array[idx+i]>array[idx]) return false; 
        }
        return true;
    }
    protected static boolean isLocalMin(float[] array, int scale, int idx) {
        for (int i = 1; i<=scale; ++i) {
            if (idx-i>=0 && array[idx-i]<array[idx]) return false;
            if (idx+i<array.length && array[idx+i]<array[idx]) return false; 
        }
        return true;
    }
    protected static boolean isLocalMax(double[] array, int scale, int idx) {
        for (int i = 1; i<=scale; ++i) {
            if (idx-i>=0 && array[idx-i]>array[idx]) return false;
            if (idx+i<array.length && array[idx+i]>array[idx]) return false; 
        }
        return true;
    }
    protected static boolean isLocalMin(double[] array, int scale, int idx) {
        for (int i = 1; i<=scale; ++i) {
            if (idx-i>=0 && array[idx-i]<array[idx]) return false;
            if (idx+i<array.length && array[idx+i]<array[idx]) return false; 
        }
        return true;
    }
   
    public static float[] getDerivative(float[] array, double scale, int order, boolean override) {
        ImageFloat in = new ImageFloat("", array.length, new float[][]{array});
        ImageFloat out = ImageFeatures.getDerivative(in, scale, order, 0, 0, override);
        return out.getPixelArray()[0];
    }
    
    public static double[] subset(double[] data, int idxStart, int idxStopExcl) {
        double[] res = new double[idxStopExcl-idxStart];
        System.arraycopy(data, idxStart, res, 0, res.length);
        return res;
    }
    
    public static double[] getMeanAndSigma(double[] data) {
        double mean = 0;
        double values2 = 0;
        for (int i = 0; i < data.length; ++i) {
            mean += data[i];
            values2 += data[i] * data[i]; 
        }
        mean /= data.length;
        values2 /= data.length;
        return new double[]{mean, Math.sqrt(values2 - mean * mean)};
    }
    
    public static void gaussianSmooth(float[] array, double scale) {
        ImageFloat im = new ImageFloat("array", array.length, new float[][]{array});
        ImageFeatures.gaussianSmooth(im, scale, scale, true);
    }
    public static void gaussianSmooth(double[] array, double scale) {
        ImageFloat im = new ImageFloat("array", array.length, new float[][]{ArrayUtil.toFloat(array)});
        ImageFeatures.gaussianSmooth(im, scale, scale, true);
        float[] pixArray = im.getPixelArray()[0];
        for (int i = 0; i<array.length; ++i) array[i] = pixArray[i];
    }
    
    public static <T> T[] duplicate(T[] array) {
        if (array==null) return null;
        return Arrays.copyOf(array, array.length);
    }
    public static boolean[] duplicate(boolean[] array) {
        if (array==null) return null;
        return Arrays.copyOf(array, array.length);
    }
    public static int[] duplicate(int[] array) {
        if (array==null) return null;
        return Arrays.copyOf(array, array.length);
    }
    public static float[] duplicate(float[] array) {
        if (array==null) return null;
        return Arrays.copyOf(array, array.length);
    }
    public static double[] duplicate(double[] array) {
        if (array==null) return null;
        return Arrays.copyOf(array, array.length);
    }
    
    public static double[] gaussianFit(int[] data, int replicate) {
        return gaussianFit(ArrayUtil.toDouble(data), replicate);
    }
    public static double[] gaussianFit(float[] data, int replicate) {
        double[] data2 = new double[data.length];
        for (int i = 0; i<data.length; ++i) data2[i] = data[i];
        return gaussianFit(data2, replicate);
    }
    /**
     * Gaussian Fit using ImageJ's curveFitter
     * @param data
     * @param replicate : 0 no replicate, 1 replicate left, 2 replicate right
     * @return fit parameters: 0=MEAN / 1=sigma
     */
    public static double[] gaussianFit(double[] data, int replicate) {
        //int maxIdx = max(data);
        //double maxValue = data[maxIdx];
        if (replicate>0) { // replicate data
            double[] data2 = new double[data.length * 2 -1];
            if (replicate==1) { //replicate left
                for (int i = 0; i<data.length; ++i) {
                    data2[i] = data[data.length-i-1];
                    data2[i+data.length-1]=data[i];
                }
            } else { // replicate right
                for (int i = 0; i<data.length; ++i) {
                    data2[i] = data[i];
                    data2[i+data.length-1]=data[data.length-i-1];
                }
            }
            data=data2;
        }
        double[] xData = new double[data.length];
        for (int i = 0; i<data.length; ++i) xData[i] = i;
        
        CurveFitter fit = new CurveFitter(xData, data);
        fit.setMaxIterations(10000);
        fit.setRestarts(1000);
        fit.doFit(CurveFitter.GAUSSIAN);
        double[] params = fit.getParams();
        //Utils.plotProfile("gaussian fit: X-center: "+params[2]+ " sigma: "+params[3], data);
        //Utils.plotProfile("residuals", fit.getResiduals());
        return new double[]{params[2], params[3]};
        
        /*WeightedObservedPoints obs = new WeightedObservedPoints();
        for (int i = 0; i<data.length; ++i) obs.add(xData[i], data[i]);
        GaussianCurveFitter fitter = GaussianCurveFitter.create().withStartPoint(new double[]{maxValue, halfData?0:maxIdx, getMeanAndSigma(data)[1]});
        double[] params = fitter.fit(obs.toList());
        
        Utils.plotProfile("gaussian fit: X-center: "+(params[1]+" XOffset: "+(data.length-1)/2)+ " sigma: "+params[2]+" initialGuess: "+getMeanAndSigma(data)[1], data);
        return new double[]{params[2], params[1]};
        <dependency>
            <groupId>org.apache.commons</groupId>
            <artifactId>commons-math3</artifactId>
            <version>3.5</version>
        </dependency>
        */
    }
    public static int[] generateIntegerArray(int start, int stopExcl) {
        if (stopExcl<start) return new int[0];
        return IntStream.range(start, stopExcl).toArray();
    }
    public static float[] generateFloatArray(int start, int stopExcl) {
        if (stopExcl<start) return new float[0];
        float[] res = new float[stopExcl-start];
        for (int i = 0; i<res.length; ++i) res[i] = start+i;
        return res;
    }
    public static int[] generateIntegerArray(int size) {
        return IntStream.range(0, size).toArray();
    }
    public static double mean(Collection<Double> col) {
        if (col.isEmpty()) return Double.NaN;
        double res = 0;
        for (Double d : col) res+=d;
        return res/col.size();
    }
    public static double[] meanSigma(Collection<Double> col) {
        if (col.isEmpty()) return new double[]{Double.NaN, Double.NaN};
        double sum = 0, sum2=0;
        for (Double d : col) {
            sum+=d;
            sum2+=d*d;
        }
        sum/=col.size();
        sum2 = Math.sqrt(sum2 / col.size() - sum * sum);
        return new double[]{sum, sum2};
    }
    public static double median(int[] array) {
        if (array.length==0) return Double.NaN;
        Arrays.sort(array);
        if (array.length%2==1) return array[array.length/2];
        else return (array[array.length/2]+array[array.length/2-1])/2.0d;
    }
    public static double median(float[] array) {
        if (array.length==0) return Double.NaN;
        Arrays.sort(array);
        if (array.length%2==1) return array[array.length/2];
        else return (array[array.length/2]+array[array.length/2-1])/2.0d;
    }
    public static double median(double[] array) {
        if (array.length==0) return Double.NaN;
        Arrays.sort(array);
        if (array.length%2==1) return array[array.length/2];
        else return (array[array.length/2]+array[array.length/2-1])/2.0d;
    }
    public static double median(Collection<Double> col) {
        if (col.isEmpty()) return Double.NaN;
        List<Double> list = new ArrayList<>(col);
        Collections.sort(list);
        if (list.size()%2==1) return list.get(list.size()/2);
        else return (list.get(list.size()/2)+list.get(list.size()/2-1))/2.0;
    }
    public static double medianInt(Collection<Integer> col) {
        if (col.isEmpty()) return Double.NaN;
        List<Integer> list = new ArrayList<>(col);
        Collections.sort(list);
        if (list.size()%2==1) return list.get(list.size()/2);
        else return (list.get(list.size()/2)+list.get(list.size()/2-1))/2.0;
    }
    public static double quantileInt(List<Integer> list, double q) {
        if (list.isEmpty()) return Double.NaN;
        if (q<0) q=0;
        if (q>1) q=1;
        Collections.sort(list);
        double idx = (list.size()-1)*q;
        int idxInf = (int)idx;
        double delta = idx - idxInf;
        if (delta==0 || idx==list.size()-1) return list.get(idxInf);
        else return list.get(idxInf) * (1-delta) + list.get(idxInf+1) * delta;
    }
    public static double quantile(List<Double> list, double q) {
        if (list.isEmpty()) return Double.NaN;
        if (q<0) q=0;
        if (q>1) q=1;
        Collections.sort(list);
        double idx = (list.size()-1)*q;
        int idxInf = (int)idx;
        double delta = idx - idxInf;
        if (delta==0 || idx==list.size()-1) return list.get(idxInf);
        else return list.get(idxInf) * (1-delta) + list.get(idxInf+1) * delta;
    }
    public static double quantile(float[] array, double q) {
        if (array.length==0) return Double.NaN;
        Arrays.sort(array);
        double idx = (array.length-1)*q;
        int idxInf = (int)idx;
        double delta = idx - idxInf;
        if (delta==0 || idx==array.length-1) return array[idxInf];
        else return array[idxInf] * (1-delta) + array[idxInf+1] * delta;
    }
    public static double quantile(double[] array, double q) {
        if (array.length==0) return Double.NaN;
        Arrays.sort(array);
        double idx = (array.length-1)*q;
        int idxInf = (int)idx;
        double delta = idx - idxInf;
        if (delta==0 || idx==array.length-1) return array[idxInf];
        else return array[idxInf] * (1-delta) + array[idxInf+1] * delta;
    }
    public static double[] quantiles(float[] values, double... quantile) {
        if (quantile.length==0) return new double[0];
        Arrays.sort(values);
        double[] res = new double[quantile.length];
        for (int qIdx = 0; qIdx<quantile.length; ++qIdx) {
            double idxD = quantile[qIdx] * (values.length-1);
            if (idxD<0) idxD=0;
            else if (idxD>values.length-1) idxD = values.length-1;
            int idx = (int) idxD;
            double delta = idxD - idx;
            if (delta==0) res[qIdx] = values[idx];
            else res[qIdx] = (1 - delta) * values[idx]  + (delta) * values[idx+1];
        }
        return res;
    }
    public static double quantile(DoubleStream sortedStream, int size, double quantile) {
        if (quantile<=0) return sortedStream.min().orElse(Double.NaN);
        if (quantile>=1) return sortedStream.max().orElse(Double.NaN);
        if (size<0) { // count is terminating stream
            double[] values = sortedStream.toArray();
            return quantile(values, quantile);
        }
        double idxD = quantile * (size-1);
        int idx = (int) idxD;
        double delta = idxD - idx;
        if (delta==0) return sortedStream.skip(idx).limit(1).findFirst().orElse(Double.NaN);
        if (delta==0.5) return sortedStream.skip(idx).limit(2).average().orElse(Double.NaN);
        double[] values = sortedStream.skip(idx).limit(2).toArray();
        if (values.length==2) return (1 - delta) * values[0]  + (delta) * values[1];
        else return Double.NaN;
    }
    public static double[] quantiles(DoubleStream sortedStream, int size, double... quantiles) {
        if (size<0 || quantiles.length>1) { // need to convert to array as 
            double[] values = sortedStream.toArray();
            return quantiles(values, quantiles);
        }
        if (quantiles.length==0) return new double[0];
        return new double[]{quantile(sortedStream, size, quantiles[0])};
    }
    public static double[] quantiles(int[] values, double... quantile) {
        if (quantile.length==0) return new double[0];
        Arrays.sort(values);
        double[] res = new double[quantile.length];
        for (int qIdx = 0; qIdx<quantile.length; ++qIdx) {
            double idxD = quantile[qIdx] * (values.length-1);
            if (idxD<0) idxD=0;
            else if (idxD>values.length-1) idxD = values.length-1;
            int idx = (int) idxD;
            double delta = idxD - idx;
            if (delta==0) res[qIdx] = values[idx];
            else res[qIdx] = (1 - delta) * values[idx]  + (delta) * values[idx+1];
        }
        return res;
    }
    public static double[] quantiles(double[] values, double... quantile) {
        if (quantile.length==0) return new double[0];
        Arrays.sort(values);
        double[] res = new double[quantile.length];
        for (int qIdx = 0; qIdx<quantile.length; ++qIdx) {
            double idxD = quantile[qIdx] * (values.length-1);
            if (idxD<0) idxD=0;
            else if (idxD>values.length-1) idxD = values.length-1;
            int idx = (int) idxD;
            double delta = idxD - idx;
            if (delta==0) res[qIdx] = values[idx];
            else res[qIdx] = (1 - delta) * values[idx]  + (delta) * values[idx+1];
        }
        return res;
    }
    public static int[] toInt(float[] array) {
        int[] res= new int[array.length];
        for (int i = 0; i<array.length; ++i) res[i] = Math.round(array[i]);
        return res;
    }
    public static int[] toInt(long[] array) {
        int[] res= new int[array.length];
        for (int i = 0; i<array.length; ++i) res[i] = (int)(array[i]);
        return res;
    }
    public static long[] toLong(int[] array) {
        long[] res= new long[array.length];
        for (int i = 0; i<array.length; ++i) res[i] = array[i];
        return res;
    }
    public static double[] toDouble(int[] array) {
        double[] res= new double[array.length];
        for (int i = 0; i<array.length; ++i) res[i] = array[i];
        return res;
    }
    public static double[] toDouble(long[] array) {
        double[] res= new double[array.length];
        for (int i = 0; i<array.length; ++i) res[i] = array[i];
        return res;
    }
    public static float[] toFloat(long[] array) {
        float[] res= new float[array.length];
        for (int i = 0; i<array.length; ++i) res[i] = (float)array[i];
        return res;
    }
    public static float[] toFloat(double[] array) {
        float[] res= new float[array.length];
        for (int i = 0; i<array.length; ++i) res[i] = (float)array[i];
        return res;
    }
    public static double[] toPrimitiveDouble(List<Double> coll) {
        double[] res = new double[coll.size()];
        for (int i = 0; i<res.length; ++i) res[i] = coll.get(i);
        return res;
    }
    public static long[] toPrimitiveLong(List<Long> coll) {
        long[] res = new long[coll.size()];
        for (int i = 0; i<res.length; ++i) res[i] = coll.get(i);
        return res;
    }
    public static boolean contains(int[] array, int value) {
        for (int i : array) if (i==value) return true;
        return false;
    }
    public static boolean contains(long[] array, long value) {
        for (long i : array) if (i==value) return true;
        return false;
    }
    public static boolean contains(float[] array, float value) {
        for (float i : array) if (i==value) return true;
        return false;
    }
    public static boolean contains(double[] array, double value) {
        for (double i : array) if (i==value) return true;
        return false;
    }
    /**
     * Apply function {@param func} to each element of array {@param array}
     * @param <T> type 
     * @param array
     * @param func
     * @return {@param array} for convenience
     */
    public static <T> T[] apply(T[] array, Function<T, T> func) {
        for (int i = 0; i<array.length; ++i) array[i] = func.apply(array[i]);
        return array;
    }
    public static String toString(Object array) {
        if (!array.getClass().isArray()) throw new IllegalArgumentException("object is not an array");
        if (array instanceof int[]) return Arrays.toString((int[])array);
        if (array instanceof long[]) return Arrays.toString((long[])array);
        if (array instanceof float[]) return Arrays.toString((float[])array);
        if (array instanceof double[]) return Arrays.toString((double[])array);
        if (array instanceof String[]) return Arrays.toString((String[])array);
        if (array instanceof boolean[]) return Arrays.toString((boolean[])array);
        if (array instanceof byte[]) return Arrays.toString((byte[])array);
        if (array instanceof short[]) return Arrays.toString((short[])array);
        if (array instanceof char[]) return Arrays.toString((char[])array);
        if (array instanceof Object[]) return Arrays.toString((Object[])array);
        else throw new IllegalArgumentException("array type not supported");
    }
    public static <T> Stream<T> flatmap(T[][] array) { // TODO optimize for matrices: use Stream.concate
        return Arrays.stream(array).flatMap(Arrays::stream);
    }
    public static <T> List<T> concatenateLists(List<T>... collections) {
        return Arrays.stream(collections).flatMap(Collection::stream).collect(Collectors.toList());
    }
    public static int[] reverse(int[] array, boolean inplace) {
        int l = array.length - 1;
        if (inplace) {
            for (int i = 0; i < array.length/2; ++i) {
                int value = array[i];
                array[i] = array[l - i];
                array[l-i] = value;
            }
            return array;
        } else {
            int[] rev = new int[array.length];
            for (int i = 0; i<array.length; ++i) rev[i] = array[l-i];
            return rev;
        }
    }

    public static <T> T[][] transpose(T[][] source, T[][] target) {
        for (int i = 0; i<source.length; ++i) {
            for (int j = 0; j<source[i].length; ++j) {
                target[j][i] = source[i][j];
            }
        }
        return target;
    }
}
