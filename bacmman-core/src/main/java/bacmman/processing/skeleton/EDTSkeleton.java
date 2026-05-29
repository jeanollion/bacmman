/*
 * Copyright (C) 2026 Jean Ollion
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
package bacmman.processing.skeleton;

import bacmman.image.ImageByte;
import bacmman.image.ImageFloat;
import bacmman.image.ImageMask;
import bacmman.processing.EDT;
import bacmman.processing.Filters;
import bacmman.processing.neighborhood.EllipsoidalNeighborhood;
import bacmman.utils.geom.Point;

import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;

/**
 * Reusable, anisotropy-aware medial-axis centerline extraction from a binary mask. The centerline is the
 * Euclidean-distance-transform (EDT) minimal-cost path between the two geodesically-farthest EDT regional maxima
 * (the poles), optionally re-centered onto the EDT ridge. This is a generic replacement for topological
 * skeletonization (e.g. Skeletonize3D), not specific to any shape.
 *
 * @author Jean Ollion
 */
public class EDTSkeleton {
    /**
     * Ordered medial-axis centerline of {@param mask}, computing the EDT internally.
     * @param mask binary mask (not modified)
     * @param az anisotropy ratio scaleZ/scaleXY (1 for 2D / isotropic data); all distances are in XY-pixel units
     * @return centerline points in absolute (offset) voxel coords, oriented from the upper-left end (min x+y),
     *         or null if no centerline could be traced
     */
    public static List<Point> getCenterline(ImageByte mask, double az) {
        return getCenterline(mask, EDT.transform(mask, true, 1, az, false), az);
    }

    /**
     * Ordered medial-axis centerline of {@param mask} using a precomputed anisotropy-aware EDT (scaleXY=1, scaleZ=az).
     * Allows callers that already have the EDT to avoid recomputing it.
     */
    public static List<Point> getCenterline(ImageByte mask, ImageFloat edt, double az) {
        return edtMinimalPathCenterline(mask, edt, az);
    }

    /**
     * Gradient-ascent of a centerline onto the EDT ridge (true medial axis), constrained to the plane perpendicular
     * to the local tangent so points re-center on the axis without sliding along it. Corrects centerlines that
     * short-cut bends. Coordinates are voxel coords; the tangent and gradient are computed in physical XY-pixel space
     * (z weighted by {@param az}). Endpoints are kept fixed.
     */
    public static void refineToEDTRidge(List<Point> path, ImageFloat edt, ImageMask mask, double az, int iterations) {
        int n = path.size();
        if (n<3 || iterations<=0) return;
        for (int it = 0; it<iterations; ++it) {
            Point[] np = new Point[n];
            for (int i = 1; i<n-1; ++i) {
                Point p = path.get(i);
                double px = p.get(0), py = p.get(1), pz = p.get(2);
                double tx = path.get(i+1).get(0)-path.get(i-1).get(0);
                double ty = path.get(i+1).get(1)-path.get(i-1).get(1);
                double tz = (path.get(i+1).get(2)-path.get(i-1).get(2))*az;
                double tn = Math.sqrt(tx*tx + ty*ty + tz*tz);
                if (tn>0) { tx /= tn; ty /= tn; tz /= tn; }
                // EDT gradient in physical space (z step = az)
                double gx = (edtInterp(edt, px+1, py, pz) - edtInterp(edt, px-1, py, pz)) / 2;
                double gy = (edtInterp(edt, px, py+1, pz) - edtInterp(edt, px, py-1, pz)) / 2;
                double gz = (edtInterp(edt, px, py, pz+1) - edtInterp(edt, px, py, pz-1)) / (2*az);
                double dot = gx*tx + gy*ty + gz*tz; // remove tangential component
                gx -= dot*tx; gy -= dot*ty; gz -= dot*tz;
                double gn = Math.sqrt(gx*gx + gy*gy + gz*gz);
                if (gn<1e-6) { np[i] = p; continue; }
                double step = Math.min(1.0, gn); // physical px
                double nx = px + step*gx/gn, ny = py + step*gy/gn, nz = pz + (step*gz/gn)/az; // back to voxel coords
                if (mask.containsWithOffset((int)Math.round(nx), (int)Math.round(ny), (int)Math.round(nz))
                        && mask.insideMaskWithOffset((int)Math.round(nx), (int)Math.round(ny), (int)Math.round(nz))
                        && edtInterp(edt, nx, ny, nz) >= edtInterp(edt, px, py, pz)-1e-6)
                    np[i] = new Point((float)nx, (float)ny, (float)nz);
                else np[i] = p;
            }
            for (int i = 1; i<n-1; ++i) path.set(i, np[i]);
        }
    }

    /** EDT value at a (sub-voxel) absolute position via the image's linear interpolation, clamped so border samples are in-bounds. */
    private static double edtInterp(ImageFloat edt, double x, double y, double z) {
        double xMax = edt.xMin()+edt.sizeX()-1, yMax = edt.yMin()+edt.sizeY()-1, zMax = edt.zMin()+edt.sizeZ()-1;
        double xc = Math.max(edt.xMin(), Math.min(xMax-1e-3, x));
        double yc = Math.max(edt.yMin(), Math.min(yMax-1e-3, y));
        double zc = edt.sizeZ()<=1 ? edt.zMin() : Math.max(edt.zMin(), Math.min(zMax-1e-3, z));
        return edt.getPixelWithOffset(xc, yc, zc);
    }

    /**
     * Traces the centerline as the EDT minimal-cost path between the two geodesically-farthest EDT regional maxima
     * (the poles). Edge length and EDT are anisotropy-aware (z weighted by {@param az}); the path cost favors high
     * EDT so the path follows the medial ridge and the bends instead of short-cutting. Returns points in absolute
     * (offset) voxel coords, oriented from the upper-left end.
     */
    private static List<Point> edtMinimalPathCenterline(ImageByte mask, ImageFloat edt, double az) {
        final int sizeX = mask.sizeX(), sizeY = mask.sizeY(), sizeZ = mask.sizeZ(), sizeXY = sizeX*sizeY;
        final int xMin = mask.xMin(), yMin = mask.yMin(), zMin = mask.zMin();
        // EDT regional maxima (medial-axis candidates): the poles are chosen among these, not among surface voxels
        ImageByte ridge = Filters.localExtrema(edt, null, true, mask, new EllipsoidalNeighborhood(1.5, 1.5, false), false);
        double edtMax = 0;
        int seed = -1;
        for (int z = 0; z<sizeZ; ++z) for (int xy = 0; xy<sizeXY; ++xy) {
            if (!mask.insideMask(xy, z)) continue;
            double e = edt.getPixel(xy, z);
            if (e>edtMax) edtMax = e;
            if (seed<0 && ridge.insideMask(xy, z)) seed = xy + z*sizeXY;
        }
        if (seed<0) { // no regional max found (degenerate): fall back to any foreground voxel, no ridge restriction
            for (int z = 0; z<sizeZ && seed<0; ++z) for (int xy = 0; xy<sizeXY; ++xy) if (mask.insideMask(xy, z)) { seed = xy + z*sizeXY; break; }
            ridge = null;
        }
        if (seed<0) return null;
        int[] ndx = new int[26], ndy = new int[26], ndz = new int[26];
        double[] len = new double[26];
        int c = 0;
        for (int dz = -1; dz<=1; ++dz) for (int dy = -1; dy<=1; ++dy) for (int dx = -1; dx<=1; ++dx) {
            if (dx==0 && dy==0 && dz==0) continue;
            ndx[c] = dx; ndy[c] = dy; ndz[c] = dz; len[c] = Math.sqrt(dx*dx + dy*dy + az*az*dz*dz); ++c;
        }
        // two furthest regional maxima = poles (medial-axis endpoints)
        int a = dijkstraGrid(seed, mask, edt, sizeX, sizeY, sizeZ, sizeXY, ndx, ndy, ndz, len, edtMax, false, ridge, null);
        int b = dijkstraGrid(a, mask, edt, sizeX, sizeY, sizeZ, sizeXY, ndx, ndy, ndz, len, edtMax, false, ridge, null);
        int[] prev = new int[sizeXY*sizeZ];
        dijkstraGrid(a, mask, edt, sizeX, sizeY, sizeZ, sizeXY, ndx, ndy, ndz, len, edtMax, true, null, prev);
        LinkedList<Point> path = new LinkedList<>();
        for (int cur = b; cur!=-1; cur = prev[cur]) {
            int z = cur/sizeXY, xy = cur-z*sizeXY, y = xy/sizeX, x = xy-y*sizeX;
            path.addFirst(new Point(x+xMin, y+yMin, z+zMin));
        }
        // deterministic orientation: start from the upper-left end (min x+y) so the curvilinear coordinate (and any
        // left/right convention derived from it) is stable across frames and methods.
        if (path.size()>1) {
            Point f = path.getFirst(), l = path.getLast();
            if (f.get(0)+f.get(1) > l.get(0)+l.get(1)) java.util.Collections.reverse(path);
        }
        return path;
    }

    /**
     * Dijkstra over the foreground 26-neighborhood grid. Edge weight is the az-aware step length, optionally scaled
     * by (edtMax-EDT+1) so the path prefers the medial ridge. Returns the farthest reachable voxel (linear index);
     * if {@param prevOut} is given it is filled with predecessors for path reconstruction.
     */
    private static int dijkstraGrid(int src, ImageByte mask, ImageFloat edt, int sizeX, int sizeY, int sizeZ, int sizeXY, int[] ndx, int[] ndy, int[] ndz, double[] len, double edtMax, boolean weightByEDT, ImageByte ridge, int[] prevOut) {
        int nTot = sizeXY*sizeZ;
        double[] dist = new double[nTot];
        Arrays.fill(dist, Double.POSITIVE_INFINITY);
        if (prevOut!=null) Arrays.fill(prevOut, -1);
        dist[src] = 0;
        IntIndirectHeap heap = new IntIndirectHeap(dist, nTot);
        heap.add(src);
        while (!heap.isEmpty()) {
            int u = heap.poll(); // settled: minimal dist
            int uz = u/sizeXY, uxy = u-uz*sizeXY, uy = uxy/sizeX, ux = uxy-uy*sizeX;
            double du = dist[u];
            for (int k = 0; k<26; ++k) {
                int vx = ux+ndx[k], vy = uy+ndy[k], vz = uz+ndz[k];
                if (vx<0 || vx>=sizeX || vy<0 || vy>=sizeY || vz<0 || vz>=sizeZ) continue;
                int vxy = vx + vy*sizeX;
                if (!mask.insideMask(vxy, vz)) continue;
                int v = vxy + vz*sizeXY;
                double w = weightByEDT ? len[k] * (edtMax - edt.getPixel(vxy, vz) + 1.0) : len[k];
                double nd = du + w;
                if (nd<dist[v]) {
                    dist[v] = nd;
                    if (prevOut!=null) prevOut[v] = u;
                    if (heap.contains(v)) heap.decrease(v); else heap.add(v);
                }
            }
        }
        int best = src;
        double bd = -1;
        for (int i = 0; i<nTot; ++i) {
            if (dist[i]>=Double.POSITIVE_INFINITY || dist[i]<=bd) continue;
            if (ridge!=null) { int z = i/sizeXY, xy = i-z*sizeXY; if (!ridge.insideMask(xy, z)) continue; } // poles must be regional maxima
            bd = dist[i]; best = i;
        }
        return best;
    }

    /** Allocation-free indirect binary min-heap of node indices ordered by a live double key array (for Dijkstra). */
    private static final class IntIndirectHeap {
        final double[] key;
        int[] heap;
        final int[] pos;
        int size;
        IntIndirectHeap(double[] key, int n) {
            this.key = key;
            this.heap = new int[Math.min(n, 64) > 0 ? Math.min(n, 64) : 1];
            this.pos = new int[n];
            Arrays.fill(pos, -1);
        }
        boolean isEmpty() { return size==0; }
        boolean contains(int v) { return pos[v]>=0; }
        void add(int v) {
            if (size==heap.length) heap = Arrays.copyOf(heap, size*2);
            heap[size] = v; pos[v] = size; siftUp(size++);
        }
        void decrease(int v) { siftUp(pos[v]); } // key[v] has decreased
        int poll() {
            int r = heap[0];
            pos[r] = -1;
            if (--size>0) { heap[0] = heap[size]; pos[heap[0]] = 0; siftDown(0); }
            return r;
        }
        private void siftUp(int i) {
            int v = heap[i]; double kv = key[v];
            while (i>0) { int p = (i-1)>>1, pv = heap[p]; if (key[pv]<=kv) break; heap[i] = pv; pos[pv] = i; i = p; }
            heap[i] = v; pos[v] = i;
        }
        private void siftDown(int i) {
            int v = heap[i]; double kv = key[v];
            while (true) {
                int l = 2*i+1; if (l>=size) break;
                int c = l, r = l+1; if (r<size && key[heap[r]]<key[heap[l]]) c = r;
                if (key[heap[c]]>=kv) break;
                heap[i] = heap[c]; pos[heap[c]] = i; i = c;
            }
            heap[i] = v; pos[v] = i;
        }
    }
}
