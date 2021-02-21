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
package bacmman.data_structure;

import bacmman.configuration.experiment.Experiment;
import bacmman.configuration.experiment.Position;
import bacmman.data_structure.dao.ObjectDAO;
import bacmman.data_structure.dao.BasicObjectDAO;
import bacmman.data_structure.region_container.RegionContainer;
import bacmman.image.*;

import java.util.*;
import java.util.Map.Entry;

import bacmman.image.io.KymographFactory;
import bacmman.utils.*;
import org.json.simple.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import bacmman.plugins.ObjectSplitter;
import bacmman.utils.geom.Point;

import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static bacmman.data_structure.SegmentedObjectUtils.getSiblings;


public class SegmentedObject implements Comparable<SegmentedObject>, JSONSerializable {
    public final static Logger logger = LoggerFactory.getLogger(SegmentedObject.class);
    public static final String TRACK_ERROR_PREV = "TrackErrorPrev";
    public static final String TRACK_ERROR_NEXT = "TrackErrorNext";
    public static final String EDITED_LINK_PREV = "EditedLinkPrev";
    public static final String EDITED_LINK_NEXT = "EditedLinkNext";
    public static final String EDITED_SEGMENTATION = "EditedSegmentation";
    //structure-related attributes
    protected String id;
    protected String parentId;
    protected transient SegmentedObject parent;
    protected int structureIdx;
    protected int idx;
    protected transient final SmallArray<List<SegmentedObject>> childrenSM=new SmallArray<List<SegmentedObject>>(); //maps structureIdx to Children (equivalent to hashMap)
    transient ObjectDAO dao;
    
    // track-related attributes
    protected int timePoint;
    protected transient SegmentedObject previous, next;
    String nextId, previousId;
    String parentTrackHeadId, trackHeadId; // TODO remove parentTrackHeadId ? useful for getTrackHeads
    protected transient SegmentedObject trackHead;
    protected boolean isTrackHead=true;
    protected Map<String, Object> attributes;
    // object- and images-related attributes
    private transient Region region;
    protected RegionContainer regionContainer;
    protected transient SmallArray<Image> rawImagesC=new SmallArray<>();
    protected transient SmallArray<Image> preFilteredImagesS=new SmallArray<>();
    protected transient SmallArray<Image> trackImagesC=new SmallArray<>();
    protected transient BoundingBox offsetInTrackImage;
    
    // measurement-related attributes
    Measurements measurements;

    SegmentedObject(Map json) {
        this.initFromJSONEntry(json);
    }

    public SegmentedObject(int timePoint, int structureIdx, int idx, Region region, SegmentedObject parent) {
        this.id= Id.get().toHexString();
        this.timePoint = timePoint;
        this.region = region;
        if (region !=null) this.region.label=idx+1;
        this.structureIdx = structureIdx;
        this.idx = idx;
        this.parent=parent;
        this.parentId=parent.getId();
        if (this.parent!=null) this.dao=parent.dao;
        setRegionAttributesToAttributes();
    }
    
    
    /**
     * Constructor for root objects only.
     * @param timePoint
     * @param mask
     */
    SegmentedObject(int timePoint, BlankMask mask, ObjectDAO dao) {
        this.id= Id.get().toHexString();
        this.timePoint=timePoint;
        if (mask!=null) this.region =new Region(mask, 1, true);
        this.structureIdx = -1;
        this.idx = 0;
        this.dao=dao;
    }
    SegmentedObject duplicate() {
        return duplicate(false, false, false);
    }

    SegmentedObject duplicate(boolean generateNewID, boolean duplicateObject, boolean duplicateImages) {
        SegmentedObject res;
        if (isRoot()) res = new SegmentedObject(timePoint, (BlankMask)(duplicateObject?getMask().duplicateMask():getMask()), dao);
        else res= new SegmentedObject(timePoint, structureIdx, idx, duplicateObject?getRegion().duplicate():getRegion(), getParent());
        if (!generateNewID) res.id=id;
        res.previousId=previousId;
        res.nextId=nextId;
        res.parentTrackHeadId=parentTrackHeadId;
        res.parentId=parentId;
        res.trackHeadId=trackHeadId;
        res.isTrackHead=isTrackHead;
        res.previous=previous;
        res.next=next;
        res.trackHead=trackHead;
        if (duplicateImages) {
            res.rawImagesC=rawImagesC.duplicate();
            res.trackImagesC=trackImagesC.duplicate();
            res.preFilteredImagesS=preFilteredImagesS.duplicate();
            res.offsetInTrackImage=offsetInTrackImage==null ? null : new SimpleBoundingBox(offsetInTrackImage);
        }
        if (attributes!=null && !attributes.isEmpty()) { // deep copy of attributes
            res.attributes=new HashMap<>(attributes.size());
            for (Entry<String, Object> e : attributes.entrySet()) {
                if (e.getValue() instanceof double[]) res.attributes.put(e.getKey(), Arrays.copyOf((double[])e.getValue(), ((double[])e.getValue()).length));
                else if (e.getValue() instanceof float[]) res.attributes.put(e.getKey(), Arrays.copyOf((float[])e.getValue(), ((float[])e.getValue()).length));
                else if (e.getValue() instanceof long[]) res.attributes.put(e.getKey(), Arrays.copyOf((long[])e.getValue(), ((long[])e.getValue()).length));
                else if (e.getValue() instanceof int[]) res.attributes.put(e.getKey(), Arrays.copyOf((int[])e.getValue(), ((int[])e.getValue()).length));
                else if (e.getValue() instanceof Point) res.attributes.put(e.getKey(), ((Point)e.getValue()).duplicate());
                else res.attributes.put(e.getKey(), e.getValue());
            }
        }        
        return res;
    }
    
    
    // structure-related methods
    ObjectDAO getDAO() {return dao;}
    void setDAO(ObjectDAO dao) {this.dao=dao;}
    /**
     *
     * @return unique identifier of this object
     */
    public String getId() {return id;}
    /**
     *
     * @return Name of the position of this object
     */
    public String getPositionName() {return dao==null? "?":dao.getPositionName();}
    public int getPositionIdx() {return dao==null?-1 : getExperiment().getPosition(getPositionName()).getIndex();}
    /**
     *
     * @return index of the object class of this object
     */
    public int getStructureIdx() {return structureIdx;}
    /**
     *
     * @return frame (0-based) of this object
     */
    public int getFrame() {return timePoint;}
    /**
     *
     * @return time point in time units if available, if not {@link #getFrame()} * frame interval
     */
    public double getCalibratedTimePoint() {
        if (getExperiment()==null) return Double.NaN;
        Position f = getExperiment().getPosition(getPositionName());
        int z = (int)Math.round((getBounds().zMin()+getBounds().zMax())/2);
        double res  = f.getInputImages()==null || isRoot() ? Double.NaN : f.getInputImages().getCalibratedTimePoint(getExperiment().getChannelImageIdx(structureIdx), timePoint, z);
        //double res = Double.NaN; // for old xp TODO change
        if (Double.isNaN(res)) res = timePoint * f.getFrameDuration();
        return res;
    }
    /**
     *
     * @return index (0-based) among parent's children
     */
    public int getIdx() {return idx;}

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof SegmentedObject) {
            return id.equals(((SegmentedObject)obj).id);
        }
        return false;
    }

    Experiment getExperiment() {
        if (dao==null) {
            if (parent!=null) return parent.getExperiment();
            return null;
        }
        return dao.getExperiment();
    }
    public ExperimentStructure getExperimentStructure() {
        return getExperiment().experimentStructure;
    }
    private Position getPosition() {
        return getExperiment()!=null?getExperiment().getPosition(getPositionName()):null;
    }
    /**
     *
     * @return size of a pixel in micrometers in XY direction
     */
    public float getScaleXY() {return getPosition()!=null? getPosition().getScaleXY():1;}
    /**
     *
     * @return size of a pixel in micrometers in Z direction
     */
    public float getScaleZ() {return getPosition()!=null? getPosition().getScaleZ():1;}
    /**
     *
     * @return parent object (object of same frame in which this element is contained) of this object, or null if this object is root
     */
    public SegmentedObject getParent() {
        if (parent==null && parentId!=null) parent = dao.getById(null, getExperiment().getStructure(structureIdx).getParentStructure(), timePoint, parentId);
        return parent;
    }
    public boolean hasParent() {
        return parent!=null;
    }
    public String getParentId() {return parentId;}
    /**
     *
     * @param parentObjectClassIdx index of class of the parent object
     * @return parent object (direct or indirect) of the same frame, of class {@param objectClassIdx} if existing, null if not
     */
    public SegmentedObject getParent(int parentObjectClassIdx) {
        if (structureIdx==parentObjectClassIdx) return this;
        if (parentObjectClassIdx<0) return getRoot();
        if (parentObjectClassIdx == this.getParent().getStructureIdx()) return getParent();
        int common = getExperiment().experimentStructure.getFirstCommonParentObjectClassIdx(structureIdx, parentObjectClassIdx);
        //logger.debug("common idx: {}", common);
        if (common == parentObjectClassIdx) {
            SegmentedObject p = this;
            while (p!=null && p.getStructureIdx()!=parentObjectClassIdx) p = p.getParent();
            return p;
        } else {
            SegmentedObject p = this;
            while (p.getStructureIdx()!=common) p = p.getParent();
            //logger.debug("{} (2D?:{} so2D?: {}) common parent: {}, candidates: {}", this, getRegion().is2D(), is2D(), p, candidates);
            return SegmentedObjectUtils.getContainer(getRegion(), p.getChildren(parentObjectClassIdx), null);
        }
    }
    public void setParent(SegmentedObject parent) {
        this.parent=parent;
        this.parentId=parent.getId();
        this.parentTrackHeadId=parent.getTrackHeadId();
    }
    /**
     *
     * @return the root object associated to this object. See {@link #isRoot()}
     */
    public SegmentedObject getRoot() {
        if (isRoot()) return this;
        if (getParent()!=null) {
            if (parent.isRoot()) return parent;
            else return parent.getRoot();
        } else return null;
    }

    /**
     *
     * @return whether this object is root or not (i.e. has no parent object, and has the extension of the whole viewfield)
     */
    public boolean isRoot() {return structureIdx==-1;}


    void loadAllChildren(boolean indirect) {
        for (int i : getExperimentStructure().getAllDirectChildStructures(structureIdx)) {
            Stream<SegmentedObject> c = getChildren(i);
            if (indirect) c.forEach(o->loadAllChildren(true));
        }
    }
    /**
     *
     * @param structureIdx
     * @return return all contained objects of {@param objectClassIdx}
     */
    public Stream<SegmentedObject> getChildren(int structureIdx) {
        return getChildren(structureIdx, false);
    }

    public Stream<SegmentedObject> getChildren(int structureIdx, boolean strictIntersection) {
        if (structureIdx == this.structureIdx) return Stream.of(this);
        if (structureIdx<0) return Stream.of(getRoot());
        List<SegmentedObject> res= this.childrenSM.get(structureIdx);
        if (res==null) {
            if (getExperiment().experimentStructure.isDirectChildOf(this.structureIdx, structureIdx)) { // direct child
                res = getDirectChildren(structureIdx);
                if (res!=null) return res.stream();
                else return null; // Stream.empty(); ?
            } else { // indirect child
                int[] path = getExperimentStructure().getPathToStructure(this.getStructureIdx(), structureIdx);
                if (path.length == 0) { // structure is not (indirect) child of current structure -> get included objects from first common parent
                    int commonParentIdx = getExperiment().experimentStructure.getFirstCommonParentObjectClassIdx(this.structureIdx, structureIdx);
                    SegmentedObject commonParent = this.getParent(commonParentIdx);
                    Stream<SegmentedObject> candidates = commonParent.getChildren(structureIdx, strictIntersection);
                    if (candidates==null) return null;
                    if (strictIntersection) {
                        return candidates.filter(c -> is2D() ? BoundingBox.isIncluded2D(c.getBounds(), this.getBounds()) : BoundingBox.isIncluded(c.getBounds(), this.getBounds()));
                    } else return candidates.filter(c -> is2D() ? BoundingBox.intersect2D(c.getBounds(), this.getBounds()) : BoundingBox.intersect(c.getBounds(), this.getBounds()));
                } else { // direct children
                    Stream<SegmentedObject> currentChildren = getChildren(path[0], strictIntersection);
                    if (currentChildren==null) return null;
                    for (int i = 1; i<path.length; ++i) {
                        int ii = i;
                        currentChildren = currentChildren.flatMap(p->p.getChildren(path[ii], strictIntersection));
                        //logger.debug("getAllObjects: current structure {} current number of objects: {}", pathToStructure[i], currentChildren.size());
                    }
                    return currentChildren;
                }
            }
        } else return res.stream();
    }

    List<SegmentedObject> getDirectChildren(int structureIdx) {
        synchronized(childrenSM) {
            List<SegmentedObject> res= this.childrenSM.get(structureIdx);
            if (res==null) {
                if (dao!=null) {
                    res = dao.getChildren(this, structureIdx);
                    setChildren(res, structureIdx);
                } else logger.debug("getChildren called on {} but DAO null, cannot retrieve objects", this);
            }
            return res;
        }
    }

    boolean hasChildren(int structureIdx) {
        return childrenSM.has(structureIdx);
    }

    void setChildren(List<SegmentedObject> children, int structureIdx) {
        this.childrenSM.set(children, structureIdx);
        if (children!=null) children.forEach(o -> o.setParent(this));
    }
    
    List<SegmentedObject> setChildrenObjects(RegionPopulation population, int structureIdx) {
        if (!getExperiment().experimentStructure.isDirectChildOf(this.structureIdx, structureIdx)) throw new IllegalArgumentException("Set children object call with non-direct child object class");
        if (population==null) {
            ArrayList<SegmentedObject> res = new ArrayList<>();
            childrenSM.set(res, structureIdx);
            return res;
        }
        population.relabel();
        if (!population.isAbsoluteLandmark()) {
            population.translate(getBounds(), true); // from parent-relative coordinates to absolute coordinates
        }
        ArrayList<SegmentedObject> res = new ArrayList<>(population.getRegions().size());
        childrenSM.set(res, structureIdx);
        int i = 0;
        for (Region o : population.getRegions()) res.add(new SegmentedObject(timePoint, structureIdx, i++, o, this));
        return res;
    }


    void relabelChildren(int structureIdx) {relabelChildren(structureIdx, null);}
    void relabelChildren(int structureIdx, Collection<SegmentedObject> modifiedObjects) {
        synchronized (childrenSM) {
            List<SegmentedObject> children = getDirectChildren(structureIdx);
            if (children!=null) {
                int i = 0;
                for (SegmentedObject c : children) {
                    if (c.idx!=i) {
                        c.setIdx(i);
                        if (modifiedObjects!=null) modifiedObjects.add(c);
                    }
                    ++i;
                }
            }
        }
    }
    void setIdx(int idx) {
        if (this.region !=null) region.setLabel(idx+1);
        this.idx=idx;
    }

    
    // track-related methods
    void setTrackLinks(SegmentedObject next, boolean setPrev, boolean setNext) {
        setTrackLinks(next, setPrev, setNext, false, null);
    }
    void setTrackLinks(SegmentedObject next, boolean setPrev, boolean setNext, boolean propagate, Collection<SegmentedObject> modifiedObjects) {
        if (next==null) resetTrackLinks(setPrev, setNext, propagate, modifiedObjects);
        else {
            if (next.getFrame()<=this.getFrame()) throw new RuntimeException("setLink previous after next!");
            if (setPrev && setNext) { // double link: set trackHead
                setNext(next);
                next.setPrevious(this);
                next.setTrackHead(getTrackHead(), false, propagate, modifiedObjects);
                if (modifiedObjects!=null) {
                    modifiedObjects.add(this);
                    modifiedObjects.add(next);
                }
            } else if (setPrev) {
                next.setPrevious(this);
                if (!next.equals(this.getNext())) next.setTrackHead(next, false, propagate, modifiedObjects);
                else if (next.getTrackHead()!=getTrackHead()) next.setTrackHead(getTrackHead(), false, propagate, modifiedObjects);
                if (modifiedObjects!=null) modifiedObjects.add(next);
            } else if (setNext) {
                setNext(next);
                if (!this.equals(next.getPrevious())) next.setTrackHead(next, false, propagate, modifiedObjects);
                else next.setTrackHead(getTrackHead(), false, propagate, modifiedObjects);
                if (modifiedObjects!=null) modifiedObjects.add(this);
            }
        }
        if (next!=null && setPrev) next.setAttribute(TRACK_ERROR_PREV, null);
    }

    SegmentedObject resetTrackLinks(boolean prev, boolean next, boolean propagate, Collection<SegmentedObject> modifiedObjects) {
        if (prev && this.previous!=null && this.previous.next==this) previous.unSetTrackLinksOneWay(false, true, propagate, modifiedObjects); // remove next of prev
        if (next && this.next!=null && this.next.previous==this) this.next.unSetTrackLinksOneWay(true, false, propagate, modifiedObjects); // remove prev of next & propagate new trackHead in track
        unSetTrackLinksOneWay(prev, next, propagate, modifiedObjects);
        return this;
    }
    private void unSetTrackLinksOneWay(boolean prev, boolean next, boolean propagate, Collection<SegmentedObject> modifiedObjects) {
        if (prev) {
            //if (this.previous!=null && this.equals(this.previous.next))
            setPrevious(null);
            setTrackHead(this, false, propagate, modifiedObjects);
            setAttribute(TRACK_ERROR_PREV, null);
        }
        if (next) {
            setNext(null);
            setAttribute(TRACK_ERROR_NEXT, null);
        }
        if (modifiedObjects!=null && (prev||next)) modifiedObjects.add(this);
    }

    /**
     *
     * @return previous object of the track in which this object is contained if existing, else null. In case there are several previous objects it means they belong to different tracks so null is returned
     */
    public SegmentedObject getPrevious() {
        if (previous==null && previousId!=null) previous = dao.getById(parentTrackHeadId, structureIdx, -1, previousId);
        return previous;
    }

    /**
     *
     * @return first element of the track in which this object is contained.
     */
    public SegmentedObject getNext() {
        if (next==null && nextId!=null) next = dao.getById(parentTrackHeadId, structureIdx, -1, nextId);
        return next;
    }
    public String getNextId() {
        return nextId;
    }
    public String getPreviousId() {
        return this.previousId;
    }
    public void setNext(SegmentedObject next) {
        this.next=next;
        if (next!=null) this.nextId=next.getId();
        else this.nextId=null;
    }
    
    public void setPrevious(SegmentedObject previous) {
        this.previous=previous;
        if (previous!=null) this.previousId=previous.getId();
        else this.previousId=null;
    }
    
    public SegmentedObject getInTrack(int timePoint) {
        SegmentedObject current;
        if (this.getFrame()==timePoint) return this;
        else if (timePoint>this.getFrame()) {
            current = this;
            while(current!=null && current.getFrame()<timePoint) current=current.getNext();
        } else {
            current = this.getPrevious();
            while(current!=null && current.getFrame()>timePoint) current=current.getPrevious();
        }
        if (current!=null && current.getFrame()==timePoint) return current;
        return null;
    }
    
    public SegmentedObject getTrackHead() {
        if (trackHead==null) {
            if (isTrackHead) {
                this.trackHead=this;
                this.trackHeadId=this.id;
            } else if (trackHeadId!=null ) {
                trackHead = dao.getById(parentTrackHeadId, structureIdx, -1, trackHeadId);
            } else if (getPrevious()!=null) {
                if (previous.isTrackHead) this.trackHead=previous;
                else if (previous.trackHead!=null) this.trackHead=previous.trackHead;
                else {
                    ArrayList<SegmentedObject> prevList = new ArrayList<>();
                    prevList.add(this);
                    prevList.add(previous);
                    SegmentedObject prev = previous;
                    while (prev.getPrevious()!=null && (prev.getPrevious().trackHead==null || !prev.getPrevious().isTrackHead)) {
                        prev=prev.previous;
                        prevList.add(prev);
                    }
                    if (prev.isTrackHead) for (SegmentedObject o : prevList) o.trackHead=prev;
                    else if (prev.trackHead!=null) for (SegmentedObject o : prevList) o.trackHead=prev.trackHead;
                }
            }
            if (trackHead==null) { // set trackHead if no trackHead found
                this.isTrackHead=true;
                this.trackHead=this;
            } 
            this.trackHeadId=trackHead.id;
        }
        return trackHead;
    }
    
    public String getTrackHeadId() {
        if (trackHeadId==null) {
            getTrackHead();
            if (trackHead!=null) trackHeadId = trackHead.id;
        }
        return trackHeadId;
    }
    public String getParentTrackHeadIdIfPresent() {
        return parentTrackHeadId;
    }

    public String getParentTrackHeadId() {
        if (parentTrackHeadId==null) {
            if (getParent()!=null) {
                parentTrackHeadId = parent.getTrackHeadId();
            }
        }
        
        return parentTrackHeadId;
    }

    /**
     *
     * @param prev
     * @param next
     * @return if there is an error in the link with previous object if {@param prev} == true OR if there is an error in the link with next object if {@param next} == true
     */
    public boolean hasTrackLinkError(boolean prev, boolean next) {
        if (attributes==null) return false;
        if (prev && Boolean.TRUE.equals(getAttribute(TRACK_ERROR_PREV))) return true;
        else if (next && Boolean.TRUE.equals(getAttribute(TRACK_ERROR_NEXT))) return true;
        else return false;
    }
    /**
     * Whether this element is the first of the track it is contain in
     * @return
     */
    public boolean isTrackHead() {return this.isTrackHead;}
    
    public SegmentedObject resetTrackHead() {
        trackHeadId=null;
        trackHead=null;
        getTrackHead();
        SegmentedObject n = this;
        while (n.getNext()!=null && n.getNext().getPrevious()==n) { // only on main track
            n=n.getNext();
            n.trackHeadId=null;
            n.trackHead=trackHead;
        }
        return this;
    }
    SegmentedObject setTrackHead(SegmentedObject trackHead, boolean resetPreviousIfTrackHead) {
        return setTrackHead(trackHead, resetPreviousIfTrackHead, false, null);
    }
    
    SegmentedObject setTrackHead(SegmentedObject trackHead, boolean resetPreviousIfTrackHead, boolean propagateToNextObjects, Collection<SegmentedObject> modifiedObjects) {
        if (trackHead==null) trackHead=this;
        if (resetPreviousIfTrackHead && this.equals(trackHead) && previous!=null && previous.next==this) {
            previous.setNext(null);
            if (modifiedObjects!=null) modifiedObjects.add(previous);
        }
        if (this.trackHead!=null && trackHead.equals(this.trackHead) && trackHeadId==trackHead.id)  return this; // do nothing
        this.isTrackHead=this.equals(trackHead);
        this.trackHead=trackHead;
        this.trackHeadId=trackHead.id;
        if (modifiedObjects!=null) modifiedObjects.add(this);
        if (propagateToNextObjects) {
            SegmentedObject n = getNext();
            while(n!=null) {
                n.setTrackHead(trackHead, false, false, null);
                if (modifiedObjects!=null) modifiedObjects.add(n);
                n = n.getNext();
            }
        }
        return this;
    }
    
    // track correction-related methods 

    
    /**
     * 
     * @return the next element of the track that contains a track link error, as defined by the tracker; null is there are no next track error;
     */
    public SegmentedObject getNextTrackError() {
        SegmentedObject error = this.getNext();
        while(error!=null && !error.hasTrackLinkError(true, true)) error=error.getNext();
        return error;
    }

    void merge(SegmentedObject other) {
        // update object
        if (other==null) logger.debug("merge: {}, other==null", this);
        if (getRegion()==null) logger.debug("merge: {}+{}, object==null", this, other);
        if (other.getRegion()==null) logger.debug("merge: {}+{}, other object==null", this, other);
        getRegion().merge(other.getRegion());
        flushImages();
        regionContainer = null;
        // update links
        SegmentedObject prev = other.getPrevious();
        if (prev !=null && prev.getNext()!=null && prev.next==other) prev.setNext(this);
        SegmentedObject next = other.getNext();
        if (next==null) next = getNext();
        if (next!=null) getSiblings(next).filter(o->o.getPrevious()==other).forEachOrdered(o->o.setPrevious(this));

        this.getParent().getDirectChildren(structureIdx).remove(other); // concurent modification..
        // set flags
        setAttribute(EDITED_SEGMENTATION, true);
        other.isTrackHead=false; // so that it won't be detected in the correction
        // update children
        int[] chilIndicies = getExperiment().experimentStructure.getAllDirectChildStructuresAsArray(structureIdx);
        for (int cIdx : chilIndicies) {
            List<SegmentedObject> otherChildren = other.getDirectChildren(cIdx);
            if (otherChildren!=null) {
                for (SegmentedObject o : otherChildren) o.setParent(this);
                //xp.getObjectDAO().updateParent(otherChildren);
                List<SegmentedObject> ch = this.getDirectChildren(cIdx);
                if (ch!=null) ch.addAll(otherChildren);
            }
        }
    }

    SegmentedObject split(Image input, ObjectSplitter splitter) { // in 2 objects
        // get cropped image
        if (input==null) input = getParent().getPreFilteredImage(structureIdx);
        RegionPopulation pop = splitter.splitObject(input, getParent(), structureIdx, getRegion());
        if (pop==null || pop.getRegions().size()==1) {
            logger.warn("split error: {}", this);
            return null;
        }
        // set landmark
        if (!pop.isAbsoluteLandmark()) {
            pop.translate(getParent().getBounds(), true);
            logger.debug("offsets: {}", Utils.toStringList(pop.getRegions(), r -> new SimpleOffset(r.getBounds())));
        }
        // first object returned by splitter is updated to current structureObject
        this.region =pop.getRegions().get(0).setLabel(idx+1);
        this.regionContainer = null;
        flushImages();
        // second object is added to parent and returned
        if (pop.getRegions().size()>2) pop.mergeWithConnected(pop.getRegions().subList(2, pop.getRegions().size()));
        SegmentedObject res = new SegmentedObject(timePoint, structureIdx, idx+1, pop.getRegions().get(1).setLabel(idx+2), getParent());
        getParent().getDirectChildren(structureIdx).add(getParent().getDirectChildren(structureIdx).indexOf(this)+1, res);
        setAttribute(EDITED_SEGMENTATION, true);
        res.setAttribute(EDITED_SEGMENTATION, true);
        return res;
    }
    public boolean hasRegion() {return region !=null;}
    // object- and image-related methods

    /**
     *
     * @return an object representing the segmented physical area
     */
    public Region getRegion() {
        if (region ==null) {
            if (regionContainer==null) return null;
            synchronized(this) {
                if (region ==null) {
                    region =regionContainer.getRegion().setIsAbsoluteLandmark(true);
                    //logger.debug("Region: {} attributes: {}", this, attributes);
                    if (attributes!=null) {
                        if (attributes.containsKey("Quality")) region.setQuality((Double)attributes.get("Quality"));
                        if (!(region instanceof Spot) && attributes.containsKey("Center")) region.setCenter(new Point(JSONUtils.fromFloatArray((List)attributes.get("Center"))));
                    }
                }
            }
        }
        return region;
    }
    void setRegion(Region o) {
        synchronized(this) {
            regionContainer=null;
            region =o;
            region.label=idx+1;
            flushImages();
            setRegionAttributesToAttributes();
        }
    }
    private void setRegionAttributesToAttributes() {
        if (attributes!=null) {
            attributes.remove("Quality");
            attributes.remove("Center");
            attributes.remove("Intensity");
            attributes.remove("Radius");
        }
        if (!Double.isNaN(region.getQuality())) setAttribute("Quality", region.getQuality());
        if (region instanceof Spot) {
            setAttribute("Radius", ((Spot) region).getRadius());
            setAttribute("Intensity", ((Spot) region).getIntensity());
        }
        if (region.getCenter()!=null) {
            Point c = region.getCenter();
            setAttributeList("Center", IntStream.range(0, c.numDimensions()).mapToObj(i->(double)c.get(i)).collect(Collectors.toList()));
        }
    }
    
    public ImageProperties getMaskProperties() {
        if (region == null) return new SimpleImageProperties(getBounds(), getScaleXY(), getScaleZ());
        return getRegion().getImageProperties();
    }
    public ImageMask getMask() {return getRegion().getMask();}
    public BoundingBox getBounds() {
        if (region==null && regionContainer!=null) return regionContainer.getBounds();
        return getRegion().getBounds();
    }
    protected void createRegionContainer() {
        this.regionContainer= region.createRegionContainer(this);
        region.regionModified=false;
    }
    boolean hasRegionContainer() {
        return regionContainer!=null;
    }
    RegionContainer getRegionContainer() {
        updateRegionContainer();
        return regionContainer;
    }
    void updateRegionContainer(){
        if (regionContainer==null) {
            if (region!=null && region.regionModified) setRegionAttributesToAttributes();
            createRegionContainer();
        } else {
            if (region!=null && region.regionModified) {
                setRegionAttributesToAttributes();
                regionContainer.update();
                region.regionModified=false;
            }
        }
    }
    void setRawImage(int structureIdx, Image image) {
        int channelIdx = getExperiment().getChannelImageIdx(structureIdx);
        rawImagesC.set(image, channelIdx);
    }
    /**
     * @param structureIdx
     * @return raw image of the channel associated to {@param objectClassIdx} cropped to the bounds of this object
     */
    public Image getRawImage(int structureIdx) {
        int channelIdx = getExperiment().getChannelImageIdx(structureIdx);
        if (rawImagesC.get(channelIdx)==null) {
            synchronized(rawImagesC) {
                if (rawImagesC.get(channelIdx)==null) {
                    if (isRoot()) {
                        if (rawImagesC.getAndExtend(channelIdx)==null) {
                            if (getPosition().singleFrame(structureIdx) && timePoint>0 && trackHead!=null) { // getImage from trackHead
                                rawImagesC.set(trackHead.getRawImage(structureIdx), channelIdx);
                            } else {
                                Image im = getExperiment().getImageDAO().openPreProcessedImage(channelIdx, getPosition().singleFrame(structureIdx) ? 0 : timePoint, getPositionName());
                                rawImagesC.set(im, channelIdx);
                                if (im==null) logger.error("Could not find preProcessed Image for: {}", this);
                            }
                        }
                    } else { // look in parent
                        SegmentedObject parentWithImage=getFirstParentWithOpenedRawImage(structureIdx);
                        if (parentWithImage!=null) {
                            //logger.debug("object: {}, channel: {}, open from parent with open image: {}", this, channelIdx, parentWithImage);
                            BoundingBox bb=getRelativeBoundingBox(parentWithImage);
                            bb=extendBoundsInZIfNecessary(channelIdx, bb);
                            rawImagesC.set(parentWithImage.getRawImage(structureIdx).crop(bb), channelIdx);    
                        } else { // check track image
                            Image trackImage = getTrackImage(structureIdx);
                            if (trackImage!=null) {
                                //logger.debug("object: {}, channel: {}, open from trackImage: offset:{}", this, channelIdx, offsetInTrackImage);
                                BoundingBox bb = new SimpleBoundingBox(getBounds()).resetOffset().translate(offsetInTrackImage);
                                bb=extendBoundsInZIfNecessary(channelIdx, bb);
                                Image image = trackImage.crop(bb);
                                image.resetOffset().translate(getBounds());
                                rawImagesC.set(image, channelIdx);
                            } else { // open root and crop
                                Image rootImage = getRoot().getRawImage(structureIdx);
                                //logger.debug("object: {}, channel: {}, no trackImage try to open root and crop... null ? {}", this, channelIdx, rootImage==null);
                                if (rootImage!=null) {
                                    BoundingBox bb = getRelativeBoundingBox(getRoot());
                                    bb=extendBoundsInZIfNecessary(channelIdx, bb);
                                    Image image = rootImage.crop(bb);
                                    rawImagesC.set(image, channelIdx);
                                } else if (!this.equals(getRoot())) {
                                    // try to open parent image (if trackImage present...)
                                    Image pImage = this.getParent().getRawImage(structureIdx);
                                    //logger.debug("try to open parent image: null?{}", pImage==null);
                                    if (pImage!=null) {
                                        BoundingBox bb = getRelativeBoundingBox(getParent());
                                        bb=extendBoundsInZIfNecessary(channelIdx, bb);
                                        Image image = pImage.crop(bb);
                                        rawImagesC.set(image, channelIdx);
                                    }
                                }                                
                            }
                            // no speed gain in opening only tiles
                            /*StructureObject root = getRoot();
                            BoundingBox bb=getRelativeBoundingBox(root);
                            bb=extendBoundsInZIfNecessary(channelIdx, bb);
                            rawImagesC.set(root.openRawImage(structureIdx, bb), channelIdx);*/
                        }
                    }
                    if (rawImagesC.has(channelIdx)) rawImagesC.get(channelIdx).setCalibration(getScaleXY(), getScaleZ());
                    
                    //logger.debug("{} open channel: {}, use scale? {}, scale: {}", this, channelIdx, this.getPosition().getPreProcessingChain().useCustomScale(), getScaleXY());
                }
            }
        }
        return rawImagesC.get(channelIdx);
    }
    /**
     *
     * @param structureIdx
     * @return Pre-filtered image of the channel associated to {@param objectClassIdx} cropped to the bounds of this object. Pre-filtered image is ensured to be set only to the segmentation parent of {@param ObjectClassIdx} at segmentation step of {@param ObjectClassIdx}, at any other step will return null.
     */
    public Image getPreFilteredImage(int structureIdx) {
        return this.preFilteredImagesS.get(structureIdx);
    }
    void setPreFilteredImage(Image image, int structureIdx) {
        if (image!=null) {
            // test same dimension. allow different z for 2D objects only
            if (is2D() && (getBounds().sizeX()!=image.sizeX() || getBounds().sizeY()!=image.sizeY()) || !is2D() && !image.sameDimensions(getBounds())) throw new IllegalArgumentException("PreFiltered Image should have same dimensions as object: image: "+image.getBoundingBox()+ " object: "+new SimpleBoundingBox(getMask()).toString());
            image.setCalibration(getScaleXY(), getScaleZ());
            image.resetOffset().translate(getBounds()); // ensure same offset
        }
        this.preFilteredImagesS.set(image, structureIdx);
    }
    Image getTrackImage(int structureIdx) {
        //logger.debug("get Track image for : {}, id: {}, thId: {}, isTH?: {}, th: {}", this, id, this.trackHeadId, isTrackHead, this.trackHead);
        //logger.debug("get Track Image for: {} th {}", this, getTrackHead());
        // feature temporarily not supported anymore TODO restore
        if (this.isTrackHead) {
            int channelIdx = getExperiment().getChannelImageIdx(structureIdx);
            if (this.trackImagesC.get(channelIdx)==null) {
                synchronized(trackImagesC) {
                    if (trackImagesC.getAndExtend(channelIdx)==null) {
                        Image im = getExperiment().getImageDAO().openTrackImage(this, channelIdx);
                        if (im!=null) { // set image && set offsets for all track
                            im.setCalibration(getScaleXY(), getScaleZ());
                            List<SegmentedObject> track = SegmentedObjectUtils.getTrack(this, false);
                            KymographFactory.KymographData kymo = KymographFactory.generateKymographData(track, false, 0);
                            IntStream.range(0, track.size()).forEach(i->track.get(i).offsetInTrackImage=kymo.trackOffset[i]);
                            //logger.debug("get track image: track:{}(id: {}/trackImageCId: {}) length: {}, chId: {}", this, this.hashCode(), trackImagesC.hashCode(), track.size(), channelIdx);
                            //logger.debug("offsets: {}", Utils.toStringList(track, o->o+"->"+o.offsetInTrackImage));
                            trackImagesC.setQuick(im, channelIdx); // set after offset is set if not offset could be null
                        }
                    }
                }
            }
            return trackImagesC.get(channelIdx);
        } else {
            return getTrackHead().getTrackImage(structureIdx);
        }
    }
    
    private BoundingBox getOffsetInTrackImage() {
        return this.offsetInTrackImage;
    }
    
    private BoundingBox extendBoundsInZIfNecessary(int channelIdx, BoundingBox bounds) { //when the current structure is 2D but channel is 3D 
        //logger.debug("extends bounds Z if necessary: is2D: {}, bounds: {}, sizeZ of image to open: {}", is2D(), bounds, getExperiment().getPosition(getPositionName()).getSizeZ(channelIdx));
        if (bounds.sizeZ()==1 && is2D() && channelIdx!=this.getExperiment().getChannelImageIdx(structureIdx)) { 
            int sizeZ = getExperiment().getPosition(getPositionName()).getSizeZ(channelIdx); //TODO no reliable if a transformation removes planes -> need to record the dimensions of the preProcessed Images
            if (sizeZ>1) {
                //logger.debug("extends bounds Z: is2D: {}, bounds: {}, sizeZ of image to open: {}, new bounds: {}", is2D(), bounds, getExperiment().getPosition(getPositionName()).getSizeZ(channelIdx), new MutableBoundingBox(bounds).unionZ(sizeZ-1));
                if (bounds instanceof MutableBoundingBox) ((MutableBoundingBox)bounds).unionZ(sizeZ-1);
                else return new MutableBoundingBox(bounds).unionZ(sizeZ-1);
            }
        }
        return bounds;
    }
    
    public boolean is2D() {
        if (getRegion()!=null) return getRegion().is2D();
        if (isRoot()) return true;
        return getExperiment().getPosition(getPositionName()).getSizeZ(getExperiment().getChannelImageIdx(structureIdx))==1;
    }

    
    private SegmentedObject getFirstParentWithOpenedRawImage(int structureIdx) {
        if (isRoot()) {
            if (rawImagesC.get(getExperiment().getChannelImageIdx(structureIdx))!=null) return this;
            else return null;
        }
        if (getParent().rawImagesC.get(getExperiment().getChannelImageIdx(structureIdx))!=null) return parent;
        else return parent.getFirstParentWithOpenedRawImage(structureIdx);
    }
    
    public <T extends BoundingBox<T>> BoundingBox<T> getRelativeBoundingBox(SegmentedObject stop) throws RuntimeException {
        SimpleBoundingBox res = new SimpleBoundingBox(getBounds());
        if (stop==null || stop == getRoot()) return res;
        else return res.translate(new SimpleOffset(stop.getBounds()).reverseOffset());
    }
    public SegmentedObject getFirstCommonParent(SegmentedObject other) {
        if (other==null) return null;
        SegmentedObject object1 = this;
        
        while (object1.getStructureIdx()>=0 && other.getStructureIdx()>=0) {
            if (object1.getStructureIdx()>other.getStructureIdx()) object1 = object1.getParent();
            else if (object1.getStructureIdx()<other.getStructureIdx()) other = other.getParent();
            else if (object1==other) return object1;
            else return null;
        }
        return null;
    } 
    
    void flushImages() {
        for (int i = 0; i<rawImagesC.getBucketSize(); ++i) rawImagesC.setQuick(null, i);
        for (int i = 0; i<trackImagesC.getBucketSize(); ++i) trackImagesC.setQuick(null, i);
        for (int i = 0; i<preFilteredImagesS.getBucketSize(); ++i) preFilteredImagesS.setQuick(null, i);
        this.offsetInTrackImage=null;
    }
    public RegionPopulation getChildRegionPopulation(int structureIdx, boolean strictInclusion) {
        Stream<SegmentedObject> children = this.getChildren(structureIdx, strictInclusion);
        if (children==null) children = Stream.empty();
        return new RegionPopulation(children.map(SegmentedObject::getRegion).collect(Collectors.toList()), this.getMaskProperties());
    }
    public RegionPopulation getChildRegionPopulation(int structureIdx) {
        return getChildRegionPopulation(structureIdx, true);
    }
    public void setAttributeList(String key, List<Double> value) {
        if (this.attributes==null) attributes = new HashMap<>();
        attributes.put(key, value);
    }
    public void setAttributeArray(String key, double[] value) {
        if (this.attributes==null) attributes = new HashMap<>();
        attributes.put(key, Utils.toList(value));
    }
    public void setAttributeArray(String key, float[] value) {
        if (this.attributes==null) attributes = new HashMap<>();
        attributes.put(key, Utils.toList(value));
    }
    public void setAttribute(String key, boolean value) {
        if (this.attributes==null) attributes = new HashMap<>();
        attributes.put(key, value);
    }
    public void setAttribute(String key, Object value) {
        if (this.attributes==null) attributes = new HashMap<>();
        attributes.put(key, value);
    }
    public void setAttribute(String key, double value) {
        if (this.attributes==null) attributes = new HashMap<>();
        attributes.put(key, value);
    }
    public void setAttribute(String key, String value) {
        if (value==null) {
            if (attributes==null) return;
            attributes.remove(key);
            if (attributes.isEmpty()) attributes=null;
        } else {
            if (this.attributes==null) attributes = new HashMap<>();
            attributes.put(key, value);
        }
    }
    public Object getAttribute(String key) {
        if (attributes==null) return null;
        Object v = attributes.get(key);
        if (v == null) return null;
        if (v instanceof Number || v instanceof String || v instanceof Boolean) return v;
        if ("center".equals(key)) return new Point(JSONUtils.fromFloatArray((List)v));
        return v;
    }
    public <T> T getAttribute(String key, T defaultValue) {
        if (attributes==null) return defaultValue;
        Object v = getAttribute(key);
        if (v==null) return defaultValue;
        if (!(defaultValue instanceof String) && v instanceof String && ("NA".equals(v) || "NaN".equals(v))) v = Double.NaN;
        if (!defaultValue.getClass().isAssignableFrom(v.getClass())) return defaultValue;
        return (T)v;
    }
    public Set<String> getAttributeKeys() {
        if (this.attributes==null) {
            synchronized(this) {
                if (attributes==null) {
                    attributes = new HashMap<>();
                }
            }
        }
        return attributes.keySet();
    }
    void setMeasurements(Measurements m) {
        this.measurements=m;
    }
    public Measurements getMeasurements() {
        if (measurements==null) {
            if (dao!=null) {
                synchronized(dao) {
                    if (measurements==null) {
                        if (!(dao instanceof BasicObjectDAO)) measurements = dao.getMeasurements(this);
                        if (measurements==null) measurements = new Measurements(this);
                    }
                }
            } else {
                synchronized (this) {
                    if (measurements == null) measurements = new Measurements(this);
                }
            }
        }
        return measurements;
    }
    
    public boolean hasMeasurements() {
        return measurements!=null;
    }
    public boolean hasMeasurementModifications() {
        return measurements!=null && measurements.modifications;
    }
    boolean updateMeasurementsIfNecessary() {
        if (measurements!=null) {
            if (measurements.modifications) dao.upsertMeasurement(this);
            else measurements.updateObjectProperties(this); // upsert always update objectProperties
            return measurements.modifications;
        }
        return false;
    }
    
    @Override
    public String toString() {
        return "P:"+getPositionIdx()+"/S:"+structureIdx+"/I:"+Selection.indicesToString(SegmentedObjectUtils.getIndexTree(this));//+"/id:"+id;
        //if (isRoot()) return "F:"+getPositionIdx() + ",T:"+frame;
        //else return "F:"+getPositionIdx()+ ",T:"+frame+ ",S:"+structureIdx+ ",Idx:"+idx+ ",P:["+getParent().toStringShort()+"]" + (flag==null?"":"{"+flag+"}") ;
    }
    
    public String toStringShort() {
        if (isRoot()) return "";
        else return "S:"+structureIdx+ ",Idx:"+idx+ ",P:["+(getParent()==null?"f"+getFrame()+"-null":getParent().toStringShort())+"]" ;
    }
    
    @Override
    public int compareTo(SegmentedObject other) {
        int comp = Integer.compare(getFrame(), other.getFrame());
        if (comp!=0) return comp;
        comp = Integer.compare(getStructureIdx(), other.getStructureIdx());
        if (comp!=0) return comp;
        if (getParent() != null && other.getParent() != null && !getParent().equals(other.getParent())) {
            comp = getParent().compareTo(other.getParent());
            if (comp!=0) return comp;
        }
        return Integer.compare(getIdx(), other.getIdx());
    }
    
    @Override
    public JSONObject toJSONEntry() {
        JSONObject obj1=new JSONObject();
        obj1.put("id", id);
        if (parentId!=null) obj1.put("pId", parentId);
        obj1.put("sIdx", structureIdx);
        obj1.put("idx", idx);
        obj1.put("frame", timePoint);
        if (nextId!=null) obj1.put("nextId", nextId);
        if (previousId!=null) obj1.put("prevId", previousId);
        if (parentTrackHeadId!=null) obj1.put("parentThId", parentTrackHeadId);
        if (trackHeadId!=null) obj1.put("thId", trackHeadId);
        obj1.put("isTh", isTrackHead);
        if (attributes!=null && !attributes.isEmpty()) obj1.put("attributes", JSONUtils.toJSONObject(attributes));
        if (regionContainer!=null) obj1.put("object", regionContainer.toJSON());
        return obj1;
    }
    @Override
    public void initFromJSONEntry(Object jsonEntry) {
        Map json = (JSONObject)jsonEntry;
        id = (String)json.get("id");
        Object pId = json.get("pId");
        if (pId!=null) parentId = (String)pId;
        structureIdx = ((Number)json.get("sIdx")).intValue();
        idx = ((Number)json.get("idx")).intValue();
        timePoint = ((Number)json.get("frame")).intValue();
        Object nId = json.get("nextId");
        if (nId!=null) nextId = (String)nId;
        Object prevId = json.get("prevId");
        if (prevId!=null) previousId = (String)prevId;
        Object parentThId = json.get("parentThId");
        if (parentThId!=null) parentTrackHeadId = (String)parentThId;
        Object thId = json.get("thId");
        if (thId!=null) trackHeadId = (String)thId;
        isTrackHead = (Boolean)json.get("isTh");
        
        if (json.containsKey("attributes")) {
            attributes = (Map<String, Object>)json.get("attributes");
            //attributes = JSONUtils.toValueMap((Map)json.get("attributes")); // leave list for better efficiency ?
        } 
        if (json.containsKey("object")) {
            Map objectJ = (Map)json.get("object");
            regionContainer = RegionContainer.createFromJSON(this, objectJ);
        }
    }


}
