package bacmman.processing;

import bacmman.data_structure.SortedCoordSet;
import bacmman.image.ImageFloat;
import bacmman.image.ImageMask;
import bacmman.processing.neighborhood.EllipsoidalNeighborhood;
import bacmman.utils.geom.Point;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GCDM {
    public final static Logger logger = LoggerFactory.getLogger(GCDM.class);
    private static final double LARGE_VALUE = Double.MAX_VALUE;
    private static final double TOLERANCE = 1e-10;
    private static final ThreadLocal<double[][]> VALUE_BUCKET =  ThreadLocal.withInitial(() -> new double[3][2]); // [index][value,spacing]

    public static ImageFloat run(Point center, ImageMask mask, double scaleXY, double scaleZ) {
        ImageFloat gcdm = new ImageFloat("GCDM", mask);
        SortedCoordSet heap = SortedCoordSet.create(gcdm, false);

        // Initialize all pixels to large value
        ImageMask.loop(mask, (x, y, z) -> gcdm.setPixel(x, y, z, LARGE_VALUE));
        // Center must be relative to mask
        center = center.duplicate().translateRev(mask);
        int x = (int)center.get(0);
        int y = (int)center.get(1);
        int z = (int)center.getWithDimCheck(2);

        Point centerScaled = center.numDimensions() == 2 ?
                new Point(center.get(0) * scaleXY, center.get(1) * scaleXY) :
                new Point(center.get(0) * scaleXY, center.get(1) * scaleXY, center.get(2) * scaleZ);

        // Initialize seed points around center
        initializeSeedPoints(heap, gcdm, mask, x, y, z, center, centerScaled, scaleXY, scaleZ);

        // Fast Marching Method main loop
        fastMarchingPropagation(heap, gcdm, mask, scaleXY, scaleZ, true);

        return gcdm;
    }

    private static void initializeSeedPoints(SortedCoordSet heap, ImageFloat gcdm, ImageMask mask,  int x, int y, int z, Point center, Point centerScaled, double scaleXY, double scaleZ) {
        // Initialize pixels around center with exact distances
        addSeedPoint(heap, gcdm, mask, x, y, z, centerScaled, scaleXY, scaleZ);
        if (x != center.get(0)) addSeedPoint(heap, gcdm, mask, x+1, y, z, centerScaled, scaleXY, scaleZ);
        if (y != center.get(1)) addSeedPoint(heap, gcdm, mask, x, y+1, z, centerScaled, scaleXY, scaleZ);
        if (x != center.get(0) && y != center.get(1)) addSeedPoint(heap, gcdm, mask, x+1, y+1, z, centerScaled, scaleXY, scaleZ);

        if (center.numDimensions() == 3 && gcdm.sizeZ() > 1 && z != center.get(2)) {
            addSeedPoint(heap, gcdm, mask, x, y, z+1, centerScaled, scaleXY, scaleZ);
            if (x != center.get(0)) addSeedPoint(heap, gcdm, mask, x+1, y, z+1, centerScaled, scaleXY, scaleZ);
            if (y != center.get(1)) addSeedPoint(heap, gcdm, mask, x, y+1, z+1, centerScaled, scaleXY, scaleZ);
            if (x != center.get(0) && y != center.get(1)) addSeedPoint(heap, gcdm, mask, x+1, y+1, z+1, centerScaled, scaleXY, scaleZ);
        }
    }

    private static void addSeedPoint(SortedCoordSet heap, ImageFloat gcdm, ImageMask mask, int x, int y, int z, Point centerScaled, double scaleXY, double scaleZ) {
        if (mask.contains(x, y, z) && mask.insideMask(x, y, z)) {
            double dist = centerScaled.dist(new Point(x * scaleXY, y * scaleXY, z * scaleZ));
            gcdm.setPixel(x, y, z, dist);
            heap.add(heap.toCoord(x, y, z));
        }
    }

    private static void fastMarchingPropagation(SortedCoordSet heap, ImageFloat gcdm, ImageMask mask, double scaleXY, double scaleZ, boolean highConnectivity) {
        // Define neighborhood for Fast Marching. rad 1.5 = 8 / 26 connectivity whereas rad 1 is 4 / 6 connectivity
        double rad = highConnectivity ? 1.5 : 1;
        EllipsoidalNeighborhood neigh = gcdm.sizeZ()>1?new EllipsoidalNeighborhood(rad, rad, true) : new EllipsoidalNeighborhood(rad, true);

        while (!heap.isEmpty()) {
            long coord = heap.pollFirst();
            // Update all 6-connected neighbors
            for (int i = 0; i<neigh.getSize(); ++i) {
                if (!heap.insideBounds(coord, neigh.dx[i], neigh.dy[i], neigh.dz[i])) continue;
                long neighborCoord = heap.translate(coord, neigh.dx[i], neigh.dy[i], neigh.dz[i]);
                if (!heap.insideMask(mask, neighborCoord)) continue;
                double neighborValue = heap.getPixel(gcdm, neighborCoord);
                // Only update if neighbor hasn't been processed (still has large value)
                if (neighborValue >= LARGE_VALUE - TOLERANCE) {
                    double newValue = solveEikonalEquation(heap, gcdm, mask, neigh, neighborCoord, scaleXY, scaleZ, highConnectivity);
                    if (newValue < neighborValue) {
                        heap.setPixel(gcdm, neighborCoord, newValue);
                        heap.add(neighborCoord);
                    }
                }
            }
        }
    }


    private static double solveEikonalEquation(SortedCoordSet heap, ImageFloat gcdm, ImageMask mask, EllipsoidalNeighborhood neigh, long coord,  double scaleXY, double scaleZ, boolean highConnectivity) {
        // First, try the simple cardinal direction approach
        double ux = getUpwindValue(heap, gcdm, mask, coord, -1, 0, 0, 1, 0, 0);
        double uy = getUpwindValue(heap, gcdm, mask, coord, 0, -1, 0, 0, 1, 0);
        double uz = getUpwindValue(heap, gcdm, mask, coord, 0, 0, -1, 0, 0, 1);

        double minDist = solveQuadraticEikonal(ux, uy, uz, scaleXY, scaleXY, scaleZ);
        if (!highConnectivity) return minDist;
        // For 26-connectivity, also check if any diagonal neighbor gives a better result
        // Check all 26-connected neighbors and find minimum viable update
        for (int i = 0; i < neigh.getSize(); i++) {
            if (heap.insideBounds(coord, neigh.dx[i], neigh.dy[i], neigh.dz[i])) {
                long neighborCoord = heap.translate(coord, neigh.dx[i], neigh.dy[i], neigh.dz[i]);
                if (heap.insideMask(mask, neighborCoord)) {
                    double neighborValue = heap.getPixel(gcdm, neighborCoord);
                    if (neighborValue < LARGE_VALUE - TOLERANCE) {
                        // Calculate distance to this neighbor
                        double dx = neigh.dx[i] * scaleXY;
                        double dy = neigh.dy[i] * scaleXY;
                        double dz = neigh.dz[i] * scaleZ;
                        double distToNeighbor = Math.sqrt(dx*dx + dy*dy + dz*dz);
                        double candidateValue = neighborValue + distToNeighbor;
                        minDist = Math.min(minDist, candidateValue);
                    }
                }
            }
        }
        return minDist;
    }

    private static double getUpwindValue(SortedCoordSet heap, ImageFloat gcdm, ImageMask mask, long coord, int dx1, int dy1, int dz1, int dx2, int dy2, int dz2) {
        double val1 = LARGE_VALUE;
        double val2 = LARGE_VALUE;

        // Check negative direction
        if (heap.insideBounds(coord, dx1, dy1, dz1)) {
            long coord1 = heap.translate(coord, dx1, dy1, dz1);
            if (heap.insideMask(mask, coord1)) val1 = heap.getPixel(gcdm, coord1);
        }

        // Check positive direction
        if (heap.insideBounds(coord, dx2, dy2, dz2)) {
            long coord2 = heap.translate(coord, dx2, dy2, dz2);
            if (heap.insideMask(mask, coord2)) val2 = heap.getPixel(gcdm, coord2);
        }

        // Return the minimum (upwind) value
        return Math.min(val1, val2);
    }

    private static double solveQuadraticEikonal(double ux, double uy, double uz, double hx, double hy, double hz) {
        // Get the reusable bucket for this thread
        double[][] bucket = VALUE_BUCKET.get();

        // Reset and populate the bucket - this is much faster than creating new objects
        int count = 0;

        if (ux < LARGE_VALUE - TOLERANCE) {
            bucket[count][0] = ux;      // value
            bucket[count][1] = hx;      // spacing
            count++;
        }
        if (uy < LARGE_VALUE - TOLERANCE) {
            bucket[count][0] = uy;
            bucket[count][1] = hy;
            count++;
        }
        if (uz < LARGE_VALUE - TOLERANCE) {
            bucket[count][0] = uz;
            bucket[count][1] = hz;
            count++;
        }

        if (count == 0) return LARGE_VALUE;
        if (count == 1) return bucket[0][0] + bucket[0][1];

        // Sort the bucket contents in-place (no new arrays created)
        sortBucketInPlace(bucket, count);

        // Try solutions with increasing dimensions using the sorted bucket
        for (int dims = 1; dims <= count; dims++) {
            double result = solveForDimension(bucket, dims);

            // Validate solution against all used upwind values
            boolean valid = true;
            for (int i = 0; i < dims; i++) {
                if (result <= bucket[i][0] + TOLERANCE) {
                    valid = false;
                    break;
                }
            }
            if (valid) return result;
        }
        // Fallback case
        return bucket[0][0] + bucket[0][1];
    }

    // Efficient in-place sorting for our small bucket (max 3 elements)
    private static void sortBucketInPlace(double[][] bucket, int count) {
        // We know count is at most 3, so we can use an optimal sorting network
        if (count == 2) {
            if (bucket[0][0] > bucket[1][0]) {
                swapBucketElements(bucket, 0, 1);
            }
        } else if (count == 3) {
            // Three-element sorting network (optimal number of comparisons)
            if (bucket[0][0] > bucket[1][0]) swapBucketElements(bucket, 0, 1);
            if (bucket[1][0] > bucket[2][0]) swapBucketElements(bucket, 1, 2);
            if (bucket[0][0] > bucket[1][0]) swapBucketElements(bucket, 0, 1);
        }
    }

    private static void swapBucketElements(double[][] bucket, int i, int j) {
        // Swap both value and spacing
        double tempValue = bucket[i][0];
        double tempSpacing = bucket[i][1];
        bucket[i][0] = bucket[j][0];
        bucket[i][1] = bucket[j][1];
        bucket[j][0] = tempValue;
        bucket[j][1] = tempSpacing;
    }

    private static double solveForDimension(double[][] bucket, int dims) {
        if (dims == 1) {
            return bucket[0][0] + bucket[0][1];
        }

        // Build quadratic equation coefficients from bucket data
        double a = 0, b = 0, c = -1.0;

        for (int i = 0; i < dims; i++) {
            double hi = bucket[i][1];  // spacing
            double ui = bucket[i][0];  // value

            a += 1.0 / (hi * hi);
            b -= 2.0 * ui / (hi * hi);
            c += ui * ui / (hi * hi);
        }

        // Solve quadratic equation
        double discriminant = b * b - 4 * a * c;
        if (discriminant < 0) return LARGE_VALUE;
        double sqrtDisc = Math.sqrt(discriminant);
        double sol1 = (-b + sqrtDisc) / (2 * a);
        double sol2 = (-b - sqrtDisc) / (2 * a);
        return Math.max(sol1, sol2);
    }

}
