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
package bacmman.processing.bacteria_spine;

import bacmman.data_structure.Region;
import bacmman.data_structure.Voxel;
import bacmman.image.*;
import bacmman.processing.ImageOperations;
import bacmman.processing.neighborhood.EllipsoidalNeighborhood;
import bacmman.utils.Pair;
import bacmman.utils.Utils;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.eclipse.collections.impl.factory.Sets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author Jean Ollion
 */
public class CleanVoxelLine {
    public static final Logger logger = LoggerFactory.getLogger(CleanVoxelLine.class);
    final Set<Voxel> lines;
    Map<Voxel, int[]> voxMapNeighAndLabels;
    final EllipsoidalNeighborhood neigh = new EllipsoidalNeighborhood(1.5, true);
    Map<Integer, Segment> segments=new HashMap<>();
    final TreeSet<Integer> availableLabels = new TreeSet<>(); // to avoid label overflow for large & complex contours
    int maxLabel = 0;
    public Consumer<Image> imageDisp;
    BoundingBox displayBounds;
    /**
     * Ensures {@param contour} is only composed of 2-connected voxels
     * @param contour set of contour voxel of an object, ie voxel from the object in contact with the background
     * @return cleaned contour, same instance as {@param contour} for convinience 
     */
    public static Set<Voxel> cleanContour(Set<Voxel> contour) {
        return new CleanVoxelLine(contour, null).cleanContour();
    }
    /**
     * See {@link #cleanContour(java.util.Set)}
     * @param contour
     * @param imageDisp to display intermediate states for debugging
     * @return 
     */
    public static Set<Voxel> cleanContour(Set<Voxel> contour, Consumer<Image> imageDisp) {
        return new CleanVoxelLine(contour, imageDisp).cleanContour();
    }
    public static List<Voxel> cleanSkeleton(Set<Voxel> skeleton) {
        return cleanSkeleton(skeleton, null, null);
    }
    public static List<Voxel> cleanSkeleton(Set<Voxel> skeleton, Consumer<Image> imageDisp, BoundingBox displayBounds) {
        Comparator<Voxel> comp = (v1,v2)->Integer.compare(v1.x+v1.y, v2.x+v2.y);
        if (skeleton.size()>2) {
            CleanVoxelLine cl = new CleanVoxelLine(skeleton, imageDisp);
            cl.displayBounds = displayBounds;
            skeleton = cl.cleanSkeleton();
            // order from upper-left end point
            Voxel endPoint = cl.voxMapNeighAndLabels.entrySet().stream().filter(e->e.getValue()[0]==1).map(e->e.getKey()).min(comp).orElseThrow(()->new RuntimeException("No end point in skeleton"));
            List<Voxel> res = cl.segments.values().stream().map(s->(Edge)s).max(Edge::compareTo).get().getOrderdVoxelList(endPoint);
            //res.sort((v1, v2)->Double.compare(endPoint.getDistanceSquareXY(v1), endPoint.getDistanceSquareXY(v2))); // distance to end point mayu not work for curved lines. 
            return res;
        } else {
            List<Voxel> res = new ArrayList<>(skeleton);
            res.sort(comp);
            return res;
        }
    }
    private CleanVoxelLine(Set<Voxel> contour,Consumer<Image> imageDisp) {
        this.imageDisp=imageDisp;
        this.lines=contour;
        voxMapNeighAndLabels = new HashMap<>(contour.size());
        lines.stream().forEach(v -> {
            int n = computeNeighbors(v);
            v.value = n;
            voxMapNeighAndLabels.put(v, new int[]{n, 0});
        });
        lines.stream().forEach(v->label(v));
    }
    
    private ImageInteger draw(boolean neigh) {
        ImageInteger map;
        if (displayBounds==null) {
            map = new Region(lines, 1, false, 1, 1).getMaskAsImageInteger();
            ImageOperations.fill(map, 0, null);
        } else map = new ImageByte("", new SimpleImageProperties(displayBounds, 1, 1));
        voxMapNeighAndLabels.entrySet().stream().forEach(e->map.setPixelWithOffset(e.getKey().x, e.getKey().y, e.getKey().z, e.getValue()[neigh?0:1]));
        return map;
    }
    private void keepOnlyLargestCluster() {
        if (segments.size()<=1) return;
        List<Set<Segment>> clusters = getAllSegmentClusters();
        if (clusters.size()>1) { 
            if (imageDisp!=null) logger.debug("clean contour: {} independent contours found! ", clusters.size());
            Function<Set<Segment>, Integer> clusterSize = s->s.stream().mapToInt(seg->seg.voxels.size()).sum();
            Set<Segment> max = clusters.stream().max((c1, c2)->Integer.compare(clusterSize.apply(c1), clusterSize.apply(c2))).get();
            clusters.remove(max);
            clusters.stream().forEach(s->s.stream().forEach(seg->seg.remove(true, false)));
            if (imageDisp!=null) imageDisp.accept(draw(true).setName("neighbors keep largest cluster"));
            if (imageDisp!=null) imageDisp.accept(draw(false).setName("labels keep largest cluster"));
        }
    }
    public Set<Voxel> cleanContour() {
        keepOnlyLargestCluster(); // if there are several distinct objects OR holes inside : keep only largest contour -> erase all others
        if (segments.values().stream().filter(s->s.isJunction()).findAny().orElse(null)==null) return lines;
        if (imageDisp!=null) logger.debug("clean contour: {} segments", segments.size());
        // erase all branch segments that are conected to only one junction
        while (segments.size()>1) {
            // start by removing end-branches ie connected to only one junction by only one pixel
            if (segments.size()>2) { // do not remove end-branch if there is only one branch & one junction
                Edge endBranch = segments.values().stream().filter(s->s.isEndBranch()).map(s->(Edge)s).min(Edge::compareTo).orElse(null);
                if (endBranch!=null) {
                    if (endBranch.voxels.size()==1 || segments.values().stream().filter(s->s.isEndBranch()).count()>1) { // if only one end-branch -> it could be the main cycle
                        endBranch.remove(true, true);
                        Vertex junction  = (endBranch).connectedSegments.stream().findAny().orElse(null); // end branch has only one junction
                        junction.relabel();
                        continue;
                    }
                }
            }
             // if no change -> it means the junction wasn't removed by removing end branches ->  there is a weird structure in the junction / or loop structure involving several junction -> try to clean it. start by solving simplest junctions
            Vertex junction = segments.values().stream().filter(s->s.isJunction()).map(s->(Vertex)s).min((j1, j2)->Integer.compare(j1.connectedSegments.size(), j2.connectedSegments.size())).orElse(null);
            if (junction==null) { // semgents were islated by previous process
                keepOnlyLargestCluster();
            } else {
                if (imageDisp!=null) {
                    imageDisp.accept(draw(true).setName("neighbors before clean junction:"+junction.label));
                    imageDisp.accept(draw(false).setName("labels after clean junction"+junction.label));
                }
                boolean cleanPerformed = cleanContourJunction(junction); // clean the junction : try to close the branch with minimal pixels of the junction
                if (imageDisp!=null) {
                    imageDisp.accept(draw(true).setName("neighbors after clean junction:"));
                    imageDisp.accept(draw(false).setName("labels after clean junction"));
                }
                if (!cleanPerformed) {
                    throw new RuntimeException("Unable to clean junction");
                    //break;
                }
            }
            
        }
        if (imageDisp!=null) {
            imageDisp.accept(draw(true).setName("neighbors after run"));
            imageDisp.accept(draw(false).setName("labels after run"));
        }
        return lines;
    }
    public Set<Voxel> cleanSkeleton() {
        if (imageDisp!=null) {
            imageDisp.accept(draw(true).setName("clean skeleton start"));
            imageDisp.accept(draw(false).setName("clean skeleton start"));
        }
        keepOnlyLargestCluster(); 
        // remove all end branch of size 1
        while (segments.size()>1) {
            Edge endBranch = segments.values().stream().filter(s->s.isEndBranch()).filter(s->s.voxels.size()==1).map(s->(Edge)s).findAny().orElse(null);
            if (endBranch!=null) {
                endBranch.remove(true, true);
                Vertex junction  = (endBranch).connectedSegments.stream().findAny().orElse(null); // end branch has only one junction
                junction.relabel();
            } else break;
        }
        // trim redondent junctions
        while (segments.size()>1) {
            Vertex junction = segments.values().stream().filter(v->v.isJunction()).map(v->(Vertex)v).filter(v->v.countNonEndEdges()>1).findAny().orElse(null);
            if (junction==null) break;
            Map<Edge, Vertex> connectedVertices = junction.connectedSegments.stream().filter(e->!e.isEndBranch()).collect(Collectors.toMap(e->e, e->e.getOtherJunction(junction)));
            Entry<Edge, Vertex> toRemove = connectedVertices.entrySet().stream().filter(e -> Collections.frequency(connectedVertices.values(), e.getValue())>1).min((e1, e2)->e1.getKey().compareTo(e2.getKey())).orElse(null);
            if (toRemove!=null) {
                toRemove.getKey().remove(true, true);
                toRemove.getValue().relabel();
                junction.relabel();
            } else break;
        }
        if (segments.size()==1) return lines;
        // compute largest shortest path & remove all the segments not included in the path
        // set end-points as vertices
        segments.values().stream().filter(v->v.isEndBranch()).map(e->(Edge)e).collect(Collectors.toList()).forEach(e ->e.setEndPointsAsVertex());
        if (imageDisp!=null) {
            imageDisp.accept(draw(true).setName("before largest shortest path"));
            imageDisp.accept(draw(false).setName("before largest shortest path"));
        }
        List<Vertex> lsPath = getLargestShortestPath();
        if (imageDisp!=null) {
            for (int i = 0; i<lsPath.size()-1; ++i) logger.debug("largest shortest path: {}->{} w={}", lsPath.get(i).label, lsPath.get(i+1).label, Sets.intersect(lsPath.get(i).connectedSegments, lsPath.get(i+1).connectedSegments).iterator().next().voxels.size());
        }
        Set<Vertex> path = new HashSet<>(lsPath);
        // remove all edges not included in path 
        Predicate<Segment> isEdgeOutsidePath = e -> {
            if (e.isJunction()) return false;
            return !((Edge)e).isContainedInPath(path);
        };
        Set<Vertex> toRelabel = new HashSet<>();
        Set<Vertex> toRemove = new HashSet<>();
        Consumer<Edge> cleanEdge = e -> {
            e.remove(true, true);
            e.connectedSegments.forEach(v->{
                if (path.contains(v)) toRelabel.add(v);
                else toRemove.add(v);
            });
        };
        segments.values().stream().filter(isEdgeOutsidePath).map(e->(Edge)e).collect(Collectors.toSet()).forEach(cleanEdge);
        toRemove.forEach((v) ->  v.remove(true, true));
        toRelabel.forEach((v) ->  v.relabel());
        
        if (segments.size()>1) { // put end points back to branch
            segments.values().stream().filter(s->s.isJunction() && s.connectedSegments.size()==1 && s.voxels.size()==1).map(s->(Vertex)s).collect(Collectors.toList()).forEach(v->{
                Edge connected = v.connectedSegments.iterator().next(); 
                v.remove(false, true);
                Voxel vox = v.voxels.iterator().next();
                connected.addVoxel(vox);
                voxMapNeighAndLabels.get(vox)[0] = 1;
            });
        }
        // last cleaning of right angles if necessary -> end branches with one voxel
        while (segments.size()>1) {
            Edge endBranch = segments.values().stream().filter(s->!s.isJunction() && s.connectedSegments.size()==1 && s.voxels.size()==1).map(s->(Edge)s).findAny().orElse(null);
            if (endBranch==null) break;
            endBranch.remove(true, true);
            Vertex junction  = endBranch.connectedSegments.stream().findAny().orElse(null); // end branch has only one junction
            junction.relabel();
        }
        
        if (imageDisp!=null) {
            imageDisp.accept(draw(true).setName("end of clean skeleton: neigh"));
            imageDisp.accept(draw(false).setName("end of clean skeleton: labels"));
        }
        return lines;
    }
    private void label(Voxel v) {
        Voxel temp = new Voxel(0, 0, 0);
        int currentLabel = 0;
        for (int i = 0; i<neigh.getSize(); ++i) {
            temp.x = v.x + neigh.dx[i];
            temp.y = v.y + neigh.dy[i];
            int[] temp_N_L = voxMapNeighAndLabels.get(temp);
            if (temp_N_L==null) continue; // out of contour
            if (temp_N_L[1]>0) { // neighbor already labelled
                if (v.value>2 == temp_N_L[0]>2) { // both are junction or both are branch
                    if (currentLabel == 0) { // current voxel not labelled -> add it to segment
                        segments.get(temp_N_L[1]).addVoxel(v);
                        currentLabel = temp_N_L[1];
                    } else { // current voxel already labelled merge its segments with the adjacent one
                        Segment res = segments.get(temp_N_L[1]).merge(segments.get(currentLabel));
                        currentLabel = res.label;
                    }
                } else { // current segment & other segment are of different type -> only add a link between them
                    if (currentLabel==0) { // label current
                        currentLabel = getNextLabel();
                        segments.put(currentLabel, createSegment(currentLabel, v));
                    }
                    Segment s1 = segments.get(temp_N_L[1]);
                    Segment s2 = segments.get(currentLabel);
                    s1.connectedSegments.add(s2);
                    s2.connectedSegments.add(s1);
                }
            }
        }
        if (currentLabel==0) {
            currentLabel = getNextLabel(); // label current
            segments.put(currentLabel, createSegment(currentLabel, v));
        }
    }
    private boolean cleanContourJunction(Vertex junction) {
        // get branch pixels that are connected to junction
        Voxel[] branchEnds;
        switch (junction.connectedSegments.size()) {
            case 1: {
                branchEnds = junction.connectedSegments.iterator().next().getTouchingVoxels(junction).toArray(s->new Voxel[s]);
                if (branchEnds.length==1) {
                    if (imageDisp!=null) {
                        imageDisp.accept(draw(true).setName("clean junction: only one branch voxel connected to junction #"+junction.label));
                        imageDisp.accept(draw(false).setName("clean junction: only one branch voxel connected to junction #"+junction.label));
                    }
                    junction.remove(true, true); // junction is weird structure at the end of branch -> erase it
                    return true;
                } else if (branchEnds.length>2) throw new RuntimeException("clean junction: more than 2 branch voxels connected to junction");
                break;
            }
            case 2: {
                Iterator<Edge> it = junction.connectedSegments.iterator();
                Edge b1 = it.next();
                Edge b2 = it.next();
                Voxel[] bEnds1 = b1.getTouchingVoxels(junction).toArray(s->new Voxel[s]);
                Voxel[] bEnds2 = b2.getTouchingVoxels(junction).toArray(s->new Voxel[s]);
                if (bEnds1.length==1 && bEnds2.length==1) {
                    branchEnds = new Voxel[]{bEnds1[0], bEnds2[0]};
                    break; // there are only 2 connected ends go to the proper clean branch section 
                } else { // EITHER one branche has 2 voxel on the junction the other only one -> erase the one with only one OR the 2 branches have 2 -> remove the smallest
                    Edge remove = bEnds1.length==1 || (bEnds2.length==2 && b1.voxels.size()<b2.voxels.size()) ? b1 : b2;
                    remove.remove(true, true);
                    if (remove.connectedSegments.size()==2) remove.connectedSegments.stream().filter(b->!b.equals(junction)).findAny().get().relabel();
                    junction.relabel();
                    return true;
                }
            }
            case 0: {
                junction.remove(true, true);
                return true;
                //throw new RuntimeException("cannot clean unconnected junction");
            }
            default: { 
                // remove duplicated redondent edges: remove smallest. 
                // At this stage end-branches have been removed, if one remains it is the only one in the cluster
                Map<Edge, Vertex> connectedVertices = junction.connectedSegments.stream().filter(e->!e.isEndBranch()).collect(Collectors.toMap(e->e, e->e.getOtherJunction(junction)));
                Entry<Edge, Vertex> toRemove = connectedVertices.entrySet().stream().filter(e -> Collections.frequency(connectedVertices.values(), e.getValue())>1).min((e1, e2)->e1.getKey().compareTo(e2.getKey())).orElse(null);
                if (toRemove!=null) {
                    toRemove.getKey().remove(true, true);
                    toRemove.getValue().relabel();
                    junction.relabel();
                    return true;
                }
                // more than 2 branches: erase all edges that are not implicated in the largest loop in which the vertex is implicated
                //String dbName = "MutH_140115";
                //int postition= 14, frame=886, mc=10, b=1;
                //imageDisp!=null = true;
                if (imageDisp!=null) {
                    logger.debug("will run shortest path algorithm for junction: {}", junction.label);
                    imageDisp.accept(draw(true).setName("neigh for clean junction: >2 branch connected to junction #"+junction.label));
                    imageDisp.accept(draw(false).setName("labels for clean junction: >2 branch connected to junction #"+junction.label));
                }
                List<Vertex> largestShortestLoop = getLargestShortestLoop(junction);
                if (largestShortestLoop.isEmpty()) throw new RuntimeException("Unable to clean junction : no loop");
                // keep only first edge & last edge of largest loop (redondent edges have been removed so path is larger that 2)
                Set<Edge> toKeep = new HashSet<>(2);
                toKeep.add(junction.connectedSegments.stream().filter(e->e.connectedSegments.contains(largestShortestLoop.get(1))).max(Edge::compareTo).get());
                toKeep.add(junction.connectedSegments.stream().filter(e->e.connectedSegments.contains(largestShortestLoop.get(largestShortestLoop.size()-1))).max(Edge::compareTo).get());
                junction.connectedSegments.stream().filter(e->!toKeep.contains(e)).collect(Collectors.toList()).forEach(s->{
                    s.remove(true, true);
                    if (s.connectedSegments.size()>1) s.getOtherJunction(junction).relabel();
                });
                junction.relabel();
                if (imageDisp!=null) {
                    imageDisp.accept(draw(true).setName("neigh after clean junction: >2 branch connected to junction #"+junction.label));
                    imageDisp.accept(draw(false).setName("labels after clean junction: >2 branch connected to junction #"+junction.label));
                }
                //if (imageDisp!=null) throw new RuntimeException("LARGEST SHORTEST LOOP");
                return true;
            }
        }
        // case junction with only 2 connected ends (from 1 or 2 different branches)
        // selection of minimal voxels allowing to link the two ends
        Voxel[] endsPropagation = new Voxel[] {branchEnds[0], branchEnds[1]};
        Set<Voxel> pool = new HashSet<>(junction.voxels); //remaining junction voxels
        propagate(branchEnds, endsPropagation, pool);
        while(!endsPropagation[0].equals(endsPropagation[1]) && !isTouching(endsPropagation[0], endsPropagation[1])) {
            if (pool.isEmpty()) throw new IllegalArgumentException("could not clean junction");
            propagate(branchEnds, endsPropagation, pool);
        }
        // remove all other voxels
        if (pool.isEmpty()) return false; // no cleaning was performed
        pool.stream().forEach(v->voxMapNeighAndLabels.remove(v));
        lines.removeAll(pool);
        junction.voxels.removeAll(pool);
        junction.relabel();
        return true;
    }
    private void propagate(Voxel[] branchEnds, Voxel[] ends, Set<Voxel> pool) {
        Voxel c1 = pool.stream().filter(v->isTouching(v, ends[0])).min((vox1, vox2)->Double.compare(branchEnds[1].getDistanceSquareXY(vox1), branchEnds[1].getDistanceSquareXY(vox2))).get(); // closest voxel to v2 in contact with v1
        pool.remove(c1);
        ends[0] = c1;
        if (c1.equals(ends[1])|| isTouching(c1, ends[1])) return;
        Voxel c2 = pool.stream().filter(v->isTouching(v, ends[1])).min((vox1, vox2)->Double.compare(branchEnds[0].getDistanceSquareXY(vox1), branchEnds[0].getDistanceSquareXY(vox2))).get(); // closest voxel to v1 in contact with v2
        pool.remove(c2);
        ends[1] = c2;
    }
    private boolean isTouching(Voxel v, Set<Voxel> other) {
        Voxel temp = new Voxel(0, 0, v.z);
        for (int i = 0; i<neigh.getSize(); ++i) {
            temp.x = v.x + neigh.dx[i];
            temp.y = v.y + neigh.dy[i];
            if (other.contains(temp)) return true;
        }
        return false;
    }
    private boolean isTouching(Voxel v, Voxel other) {
        Voxel temp = new Voxel(0, 0, v.z);
        for (int i = 0; i<neigh.getSize(); ++i) {
            temp.x = v.x + neigh.dx[i];
            temp.y = v.y + neigh.dy[i];
            if (other.equals(temp)) return true;
        }
        return false;
    }
    private int getNextLabel() {
        if (this.availableLabels.isEmpty()) {
            maxLabel++;
            return maxLabel;
        }
        return availableLabels.pollFirst();
    }
    private int computeNeighbors(Voxel v) {
        int count = 0;
        Voxel temp = new Voxel(0, 0, v.z);
        for (int i = 0; i<neigh.getSize(); ++i) {
            temp.x = v.x + neigh.dx[i];
            temp.y = v.y + neigh.dy[i];
            if (lines.contains(temp)) ++count;
        }
        return count;
    }
    
    private List<Set<Segment>> getAllSegmentClusters() {
        if (segments.isEmpty()) return Collections.EMPTY_LIST;
        List<Set<Segment>> res = new ArrayList<>();
        Set<Segment> remaniningSegments = new HashSet<>(segments.values());
        while(!remaniningSegments.isEmpty()) {
            Segment seed = remaniningSegments.stream().findAny().get();
            Set<Segment> cluster = getAllConnectedSegments(seed);
            remaniningSegments.removeAll(cluster);
            res.add(cluster);
            if (imageDisp!=null) logger.debug("get all clusters: remaining segments {}/{}, cluster nb : {}", remaniningSegments.size(), segments.size(), res.size());
        }
        return res;
    }
    private static Set<Segment> getAllConnectedSegments(Segment start) {
        Set<Segment> res = new HashSet<>();
        res.add(start);
        LinkedList<Segment> queue = new LinkedList<>();
        queue.addAll(start.connectedSegments);
        //logger.debug("get cluster start: visited: {}, queue: {}", res.size(), queue.size());
        while(!queue.isEmpty()) {
            //logger.debug("get cluster: visited: {}, queue: {}", res.size(), queue.size());
            Segment<? extends Segment> s = queue.pollFirst();
            if (res.contains(s)) continue;
            res.add(s);
            s.connectedSegments.stream().filter((Segment ss) -> !(res.contains(ss))).forEach((ss) -> queue.add(ss));
        }
        return res;
    }
    private Segment createSegment(int label, Voxel v) {
        if (v.value>2) return new Vertex(label, v);
        else return new Edge(label, v);
    }
    private abstract class Segment<T extends Segment> {
        Set<T> connectedSegments=new HashSet<>(); // junctions if !isJunction, branch else
        Set<Voxel> voxels = new HashSet<>();
        final int label;
        
        private Segment(int label, Voxel v) {
            this.label=label;
            addVoxel(v);
        }
        
        public void addVoxel(Voxel v) {
            voxels.add(v);
            voxMapNeighAndLabels.get(v)[1] = label;
        }
        public Segment merge(Segment<T> other) {
            if (other.label==this.label) return this;
            if (other.label>this.label) return other.merge(this);
            other.voxels.forEach(v->voxMapNeighAndLabels.get(v)[1] = label);
            voxels.addAll(other.voxels);
            segments.remove(other.label);
            availableLabels.add(other.label);
            for (Segment s : other.connectedSegments) {
                s.connectedSegments.add(this);
                s.connectedSegments.remove(other);
            }
            connectedSegments.addAll(other.connectedSegments);
            return this;
        }
        
        public void remove(boolean fromContour, boolean fromConnected) {
            if (fromConnected) connectedSegments.forEach((connected) -> connected.connectedSegments.remove(this));
            segments.remove(label);
            availableLabels.add(label);
            if (fromContour) {
                lines.removeAll(voxels);
                voxels.forEach(v-> voxMapNeighAndLabels.remove(v));
            } else {
                voxels.forEach(v-> {
                    int[] N_L = voxMapNeighAndLabels.get(v);
                    N_L[1] = 0;
                    N_L[0] = 0;
                });
            }
        }
        public void relabel() {
            remove(false, true);
            voxels.forEach(v-> { // recompute neighbors
                int n = computeNeighbors(v);
                v.value = n;
                voxMapNeighAndLabels.get(v)[0] = n;
            });
            voxels.forEach(v->label(v));
        }
        /**
         * 
         * @param other
         * @return Stream of voxel from this segment that are in contact with {@param other}
         */
        public Stream<Voxel> getTouchingVoxels(Segment other) {
            return voxels.stream().filter(v->isTouching(v, other.voxels));
        }
        public boolean isEndBranch() {
            return !isJunction() && connectedSegments.size()<=1 ;//&& getTouchingVoxels(connectedSegments.iterator().next()).count()==1;
        }
        public boolean isJunction() {
            return this instanceof Vertex;
        }
        @Override
        public String toString() {
            return label+ " Size="+voxels.size()+"->"+Utils.toStringList(this.connectedSegments, s->s.label);
        }
    }
    private class Vertex extends Segment<Edge> {
        int idx; // for shortest path computation
        public Vertex(int label, Voxel v) {
            super(label, v);
        }
        public Set<Vertex> getFirstNeighbors() {
            if (this.connectedSegments.isEmpty()) return Collections.EMPTY_SET;
            Set<Vertex> res = new HashSet<>();
            for (Edge e : connectedSegments) {
                Vertex v = e.getOtherJunction(this);
                if (v!=null) res.add(v);
            }
            return res;
        }
        public Edge getEdge(Vertex v) {
            if (this.equals(v)) return null;
            for (Edge e : connectedSegments) {
                if (e.connectedSegments.contains(v)) return e;
            }
            return null;
        }
        public int countNonEndEdges() {
            return (int)connectedSegments.stream().filter(e->!e.isEndBranch()).count();
        }
        @Override
        public String toString() {
            return "V:"+super.toString();
        }
    }
    private class Edge extends Segment<Vertex> implements Comparable<Edge> {
        public Edge(int label, Voxel v) {
            super(label, v);
        }
        public Vertex getOtherJunction(Vertex junction1) {
            if (connectedSegments.size()>2) throw new RuntimeException("invalid edge");
            if (connectedSegments.size()==1) return null;
            Iterator<Vertex> vIt = connectedSegments.iterator();
            Vertex n1 = vIt.next();
            Vertex n2 = vIt.next();
            if (n1.equals(junction1)) return n2;
            else if (n2.equals(junction1)) return n1;
            throw new IllegalArgumentException("junction not linked to edge");
        }
        public void setEndPointsAsVertex() {
            voxels.stream().filter(v->voxMapNeighAndLabels.get(v)[0]==1).collect(Collectors.toList()).forEach(v->{
                voxels.remove(v);
                Vertex end = new Vertex(getNextLabel(), v);
                end.connectedSegments.add(this);
                this.connectedSegments.add(end);
                segments.put(end.label, end);
            });
        }
        public boolean isContainedInPath(Collection<Vertex> path) {
            if (connectedSegments.size()==1) return false;
            Iterator<Vertex> vIt = connectedSegments.iterator();
            Vertex n1 = vIt.next();
            Vertex n2 = vIt.next();
            return path.contains(n1) && path.contains(n2);
        }
        public List<Voxel> getOrderdVoxelList(Voxel start) {
            List<Voxel> res = new ArrayList<>(voxels.size());
            res.add(start);
            Voxel prev=null;
            Voxel temp = new Voxel(0, 0, start.z);
            boolean change = true;
            while(change) {
                change = false;
                for (int i = 0; i<neigh.getSize(); ++i) {
                    temp.x = start.x + neigh.dx[i];
                    temp.y = start.y + neigh.dy[i];
                    if (prev!=null && prev.equals(temp)) continue;
                    if (voxels.contains(temp)) {
                        prev = start;
                        start = temp.duplicate();
                        res.add(start);
                        change = true;
                        break; // maximum 2 neighbors in edge
                    }
                }
            }
            return res;
        }
        @Override
        public int compareTo(Edge o) {
            return Integer.compare(voxels.size(), o.voxels.size());
        }
        @Override
        public String toString() {
            return "E:"+super.toString()+ (isEndBranch() ? "(end)":"");
        }
    }
    
    private Vertex[] initVertices() {
        Vertex[] vertices = this.segments.values().stream().filter(v->v.isJunction()).toArray(s->new Vertex[s]);
        for (int i = 0; i<vertices.length; ++i) vertices[i].idx = i;
        return vertices;
    }
    private List<Vertex> getLargestShortestLoop(Vertex v) {
        Set<Vertex> firstNeigh = v.getFirstNeighbors();
        if (firstNeigh.isEmpty()) return Collections.EMPTY_LIST;
        Vertex[] vertices = initVertices();
        Vertex[][] next = new Vertex[vertices.length][vertices.length];
        float[][] dist = new float[vertices.length][vertices.length];
        List<Pair<List<Vertex>, Integer>> shortestPaths = new ArrayList<>();
        while(!firstNeigh.isEmpty()) {
            Vertex u = firstNeigh.iterator().next();
            Edge e = v.getEdge(u);
            v.connectedSegments.remove(e); // temporarily remove the edge between u & v to get the other path
            u.connectedSegments.remove(e);
            floydWarshall(vertices, next, dist);
            List<Vertex> path = path(v, u, next); // compute shortest path between u & v without e 
            int length = (int)dist[v.idx][u.idx] + e.voxels.size();
            shortestPaths.add(new Pair(path, length));
            v.connectedSegments.add(e);
            u.connectedSegments.add(e);
            firstNeigh.removeAll(path); // remove visited first neighbors from candidates to avoid compute 2 times the same path
        }
        return shortestPaths.stream().max((p1, p2)->Integer.compare(p1.value, p2.value)).orElse(new Pair<List<Vertex>, Integer>(Collections.EMPTY_LIST, 0)).key;
    }
    private List<Vertex> getLargestShortestPath() {
        Vertex[] vertices = initVertices();
        Vertex[][] next = new Vertex[vertices.length][vertices.length];
        float[][] dist = new float[vertices.length][vertices.length];
        floydWarshall(vertices, next, dist);
        float max = Float.NEGATIVE_INFINITY;
        int u = -1;
        int v = -1;
        for (int i = 0; i<vertices.length; ++i) {
            for (int j = 0; j<vertices.length; ++j) {
                if (Float.isFinite(dist[i][j]) && dist[i][j]>max) {
                    max = dist[i][j];
                    u = i;
                    v = j;
                } 
            }
        }
        if (imageDisp!=null) logger.debug("largest shortest path: dist: {}, {} -> {}", max, vertices[u].label, vertices[v].label);
        if (u<0) return Collections.EMPTY_LIST;
        return path(vertices[u], vertices[v], next);
    }
    private static List<Vertex> path(Vertex u, Vertex v, Vertex[][] next) {
        if (next[u.idx][v.idx]==null) return Collections.EMPTY_LIST;
        List<Vertex> path = new ArrayList<>();
        path.add(u);
        while(u!=v) {
            u = next[u.idx][v.idx];
            if (u==null) return Collections.EMPTY_LIST;
            path.add(u);
        }
        return path;
    }
    
    private static void floydWarshall(Vertex[] allVertices, Vertex[][] next, float[][] dist) {
        init(allVertices, next, dist);
        for (int k = 0; k<allVertices.length; ++k) {
            for (int i = 0; i<allVertices.length; ++i) {
                for (int j = 0; j<allVertices.length; ++j) {
                    if (dist[i][j]>dist[i][k] + dist[k][j]) {
                        dist[i][j] = dist[i][k] + dist[k][j];
                        next[i][j] = next[i][k];
                    }
                }
            }
        }
    }
    private static void init(Vertex[] allVertices, Vertex[][] next, float[][] dist) {
        for (int i = 0; i<next.length; ++i) {
            for (int j = 0; j<next.length; ++j) {
                next[i][j]=null;
                dist[i][j] = Float.POSITIVE_INFINITY;
            }
        }
        for (Vertex u : allVertices) {
            for (Edge e : u.connectedSegments) {
                Vertex v = e.getOtherJunction(u);
                if (v==null) continue;
                next[u.idx][v.idx] = v;
                dist[u.idx][v.idx] = e.voxels.size();
            }
            dist[u.idx][u.idx] = 0;
        }
    }
}
