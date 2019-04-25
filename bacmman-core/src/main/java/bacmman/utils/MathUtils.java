package bacmman.utils;

public class MathUtils {
    /**
     * Solves the equation {@param a} * X^2 + {@param b}  * X + {@param c} = 0
     * @param a
     * @param b
     * @param c
     * @return array with roots or array of length 0 if no roots
     */
    public static double[] solveQuadratic(double a, double b, double c) {
        if (a == 0) return new double[]{-c/b};
        double d = b*b-4*a*c;
        if (d<0) return new double[0];
        if (d==0) return new double[]{-b/(2*a)};
        d = Math.sqrt(d);
        return new double[]{(-b+d)/(2*a), (-b-d)/(2*a)};
    }
}
