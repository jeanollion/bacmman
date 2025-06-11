package bacmman.utils;

import bacmman.image.Image;
import ij.gui.Plot;

import java.util.Arrays;
import java.util.function.Function;

public class IJUtils {

    public static void plot(double[] x, double[] y, String title, String xLabel, String yLabel) {
        if (y.length<=1) return;
        new Plot(title, xLabel, yLabel, x, y).show();
    }

    public static void plot(float[] x, float[] y, String title, String xLabel, String yLabel) {
        if (y.length<=1) return;
        new Plot(title, xLabel, yLabel, x, y).show();
    }

    public static void plotProfile(Image image, int z, int coord, boolean alongX, String... axisLabels) {
        double[] x;
        double[] y;
        if (alongX) {
            x=new double[image.sizeX()];
            y=new double[image.sizeX()];
            for (int i = 0; i<x.length; ++i) {
                x[i]=i;
                y[i]=image.getPixel(i, coord, z);
            }
        } else {
            x=new double[image.sizeY()];
            y=new double[image.sizeY()];
            for (int i = 0; i<x.length; ++i) {
                x[i]=i;
                y[i]=image.getPixel(coord, i, z);
            }
        }
        new Plot(image.getName(), axisLabels.length>0 ? axisLabels[0] : (alongX?"x":"y"), axisLabels.length>1 ? axisLabels[1] : "value", x, y).show();
    }

    public static void plotProfile(String title, int[] values) {
        if (values.length<=1) return;
        double[] doubleValues = ArrayUtil.toDouble(values);
        double v = doubleValues[0];
        int idx = 0;
        while (idx<doubleValues.length && doubleValues[idx]==v) ++idx;
        if (idx==doubleValues.length) return;
        double[] x=new double[doubleValues.length];
        for (int i = 0; i<x.length; ++i) x[i]=i;
        new Plot(title, "coord", "value", x, doubleValues).show();
    }

    public static void plotProfile(String title, float[] values, String... axisLabels) {
        plotProfile(title, values, 0, axisLabels);
    }

    public static void plotProfile(String title, float[] values, int xOffset, String... axisLabels) {
        if (values.length<=1) return;
        float v = values[0];
        int idx = 0;
        while (idx<values.length && values[idx]==v) ++idx;
        if (idx==values.length) return;
        float[] x=new float[values.length];
        for (int i = 0; i<x.length; ++i) x[i]=i+xOffset;
        new Plot(title, axisLabels.length>0 ? axisLabels[0] : "coord", axisLabels.length>1 ? axisLabels[1] : "value", x, values).show();
    }

    public static Plot plotProfile(String title, double[] values, String... axisLabels) {
        if (values.length<=1) return null;
        double v = values[0];
        int idx = 0;
        while (idx<values.length && values[idx]==v) ++idx;
        if (idx==values.length) return null; // cannot be ploted if one single value
        double[] x=new double[values.length];
        for (int i = 0; i<x.length; ++i) x[i]=i;
        Plot p  = new Plot(title, axisLabels.length>0 ? axisLabels[0] : "coord", axisLabels.length>1 ? axisLabels[1] : "value", x, values);
        p.show();
        return p;
    }

    public static void plotProfile(String title, double[] values1, double[] values2, boolean sort) {
        if (values1.length<=1) return;
        double v = values1[0];
        int idx = 0;
        while (idx<values1.length && values1[idx]==v) ++idx;
        if (idx==values1.length) return; // cannot be ploted if one single value
        double[] x1=new double[values1.length];
        for (int i = 0; i<x1.length; ++i) x1[i]=i;
        double[] x2=new double[values2.length];
        for (int i = 0; i<x2.length; ++i) x2[i]=i;
        if (sort) {
            Arrays.sort(values1);
            Arrays.sort(values2);
        }
        Plot p = new Plot(title, "coord", "value1", x1, values1);
        p.addPoints(x2, values2, 5);
        p.show();
    }

    public static void plotHistogram(String title, double[] x, double[] count) {
        if (count.length<=1) return;
        double v = count[0];
        int idx = 0;
        while (idx<count.length && count[idx]==v) ++idx;
        if (idx==count.length) return; // cannot be ploted if one single value
        Plot p = new Plot(title, "Value", "Count");
        p.add("bar", x, count);
        p.show();
    }

    public static void plotProfile(String title, double[] values1, double[] x1, double[] values2, double[] x2) {
        if (values1.length<=1) return;
        double v = values1[0];
        int idx = 0;
        while (idx<values1.length && values1[idx]==v) ++idx;
        if (idx==values1.length) return; // cannot be ploted if one single value
        Plot p = new Plot(title, "coord", "value1", x1, values1);
        if (values2!=null) {
            p.addPoints(x2, values2, 5);
            Function<double[] , Double> min = a -> Arrays.stream(a).min().getAsDouble();
            Function<double[] , Double> max = a -> Arrays.stream(a).max().getAsDouble();
            p.setLimits(Math.min(min.apply(x1), min.apply(x1)), Math.max(max.apply(x1), max.apply(x1)), Math.min(min.apply(values1), min.apply(values2)), Math.max(max.apply(values1), max.apply(values2)));
        }
        p.show();
    }
}
