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
import bacmman.data_structure.region_container.RegionContainer;
import bacmman.image.*;

import java.io.IOException;
import java.util.*;

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


public class SegmentedObject implements Comparable<SegmentedObject>, GraphObject<SegmentedObject>, JSONSerializable {
    public final static Logger logger = LoggerFactory.getLogger(SegmentedObject.class);
    public static final String TRACK_ERROR_PREV = "TrackErrorPrev";
    public static final String TRACK_ERROR_NEXT = "TrackErrorNext";
    public static final String EDITED_LINK_PREV = "EditedLinkPrev";
    public static final String EDITED_LINK_NEXT = "EditedLinkNext";
    public static final String EDITED_SEGMENTATION = "EditedSegmentation";
    //structure-related attributes
    protected Object id;
    protected Object parentId;
    protected transient SegmentedObject parent;
    protected int structureIdx;
    protected int idx;
    protected transient final SmallArray<List<SegmentedObject>> childrenSM= new SmallArray<>(); //maps structureIdx to Children (equivalent to hashMap)
    public transient ObjectDAO dao;
    
    // track-related attributes
    protected int timePoint;
    protected transient SegmentedObject previous, next;
    Object nextId, previousId;
    Object trackHeadId;
    protected transient SegmentedObject trackHead;
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

    SegmentedObject(Map json, ObjectDAO dao) {
        this.dao = dao;
        this.initFromJSONEntry(json);
    }

    public SegmentedObject(int frame, int structureIdx, int idx, Region region, SegmentedObject parent) {
        this.timePoint = frame;
        this.region = region;
        if (region !=null) this.region.label=idx+1;
        this.structureIdx = structureIdx;
        this.idx = idx;
        this.parent=parent;
        if (this.parent!=null) {
            this.parentId=parent.getId();
            this.dao=parent.dao;
            this.id=parent.getDAO().generateID(structureIdx, frame);
        }
        setRegionAttributesToAttributes();
    }
    
    
    /**
     * Constructor for root objects only.
     * @param frame
     * @param mask
     */
    SegmentedObject(int frame, BlankMask mask, ObjectDAO dao) {
        this.id=dao.generateID(-1, frame);
        this.timePoint=frame;
        if (mask!=null) this.region =new Region(mask, 1, true);
        this.structureIdx = -1;
        this.idx = 0;
        this.dao=dao;
    }
    SegmentedObject duplicate() {
        return duplicate(timePoint, structureIdx, false, false, false);
    }

   SegmentedObject duplicate(int targetFrame, int targetObjectClass, boolean generateNewID, boolean duplicateRegion, boolean duplicateImages) {
        SegmentedObject res;
        if (targetObjectClass==-1 && !isRoot()) throw new IllegalArgumentException("Only root objects can be duplicated to root objects");
        if (targetObjectClass==-1) res = new SegmentedObject(targetFrame, (BlankMask)(duplicateRegion?getMask().duplicateMask():getMask()), dao);
        else res=new SegmentedObject(targetFrame, targetObjectClass, idx, duplicateRegion?getRegion().duplicate():getRegion(), getParent());
        if (!generateNewID) res.id=id;
        res.previousId=previousId;
        res.nextId=nextId;
        res.parentId=parentId;
        res.previous=previous;
        res.next=next;
        if (isTrackHead()) {
            res.trackHead = res;
            res.trackHeadId=res.id;
        } else {
            res.trackHead=trackHead;
            res.trackHeadId=trackHeadId;
        }
        if (duplicateImages) {
            res.rawImagesC=rawImagesC.duplicate();
            res.trackImagesC=trackImagesC.duplicate();
            res.preFilteredImagesS=preFilteredImagesS.duplicate();
            res.offsetInTrackImage=offsetInTrackImage==null ? null : new SimpleBoundingBox(offsetInTrackImage);
        }
        if (attributes!=null && !attributes.isEmpty()) { // deep copy of attributes
            res.attributes=new HashMap<>(attributes.size());
            SegmentedObjectUtils.deepCopyAttributes(attributes, res.attributes);
        }        
        return res;
    }

    <ID2> SegmentedObject duplicate(ObjectDAO<ID2> dao, SegmentedObject parent, boolean duplicateRegion, boolean duplicateImages, boolean duplicateAttributes, boolean duplicateMeasurements) {
        SegmentedObject res;
        if (isRoot()) {
            if (dao == null) throw new IllegalArgumentException("DAO must be provided for root objects");
            res = new SegmentedObject(timePoint, (BlankMask)(duplicateRegion?getMask().duplicateMask():getMask()), dao);
        } else {
            if (parent == null && dao == null) throw new IllegalArgumentException("Either Parent or DAO must be provided for non-root objects");
            res = new SegmentedObject(timePoint, structureIdx, idx, duplicateRegion?getRegion().duplicate():getRegion(), parent);
            if (parent == null) { // new ID was not generated
                res.setDAO(dao);
                res.id = dao.generateID(res.getStructureIdx(), res.getFrame());
            }
        }
        if (duplicateImages) {
            res.rawImagesC=rawImagesC.duplicate();
            res.trackImagesC=trackImagesC.duplicate();
            res.preFilteredImagesS=preFilteredImagesS.duplicate();
            res.offsetInTrackImage=offsetInTrackImage==null ? null : new SimpleBoundingBox(offsetInTrackImage);
        } else {
            res.rawImagesC=rawImagesC;
            res.trackImagesC=trackImagesC;
            res.preFilteredImagesS=preFilteredImagesS;
            res.offsetInTrackImage=offsetInTrackImage;
        }
        if (attributes!=null && !attributes.isEmpty()) { // deep copy of attributes
            if (duplicateAttributes) {
                res.attributes = new HashMap<>(attributes.size());
                SegmentedObjectUtils.deepCopyAttributes(attributes, res.attributes);
            } else res.attributes = attributes;
        }
        if (duplicateMeasurements) {
            getMeasurements(true);
            if (measurements!=null) {
                res.measurements = new Measurements(res);
                SegmentedObjectUtils.deepCopyAttributes(measurements.values, res.measurements.values);
            }
        }
        return res;
    }
    void setLinks(Map<Object, Object> oldMapNewID, SegmentedObject ref) {
        if (parentId == null && ref.parentId!=null) parentId=oldMapNewID.get(ref.parentId);
        if (ref.previousId!=null) previousId=oldMapNewID.get(ref.previousId);
        if (ref.nextId!=null) nextId=oldMapNewID.get(ref.nextId);
        if (ref.trackHeadId!=null) trackHeadId=oldMapNewID.get(ref.trackHeadId);
        else trackHeadId=id;
    }


    public static Comparator<SegmentedObject> frameComparator() {
        return Comparator.comparingInt(SegmentedObject::getFrame);
    }
    
    // structure-related methods
    ObjectDAO<?> getDAO() {return dao;}
    void setDAO(ObjectDAO dao) {this.dao=dao;}
    /**
     *
     * @return unique identifier of this object
     */
    public Object getId() {return id;}
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
        double res = f.getCalibratedTimePoint(getExperiment().getChannelImageIdx(structureIdx), timePoint, z);
        if (Double.isNaN(res)) res = timePoint * f.getFrameDuration();
        return res;
    }
    /**
     *
     * @return index (0-based) among parent's children
     */
    public int getIdx() {return idx;}

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
        if (parent==null && parentId!=null) {
            int parentOCidx = getExperiment().getStructure(structureIdx).getParentStructure();
            parent = dao.getById(parentOCidx, parentId, timePoint, null);
        }
        if (parent!=null && parent.getStructureIdx()==getStructureIdx()) {
            logger.error("parent {} has same object class idx as {}. oc idx: {}, position: {}", parent.toStringShort(), this.toStringShort(), structureIdx, getPositionName());
            throw new RuntimeException("parent has same object class idx");
        }
        return parent;
    }
    public boolean hasParent() {
        return parent!=null;
    }
    public Object getParentId() {return parentId;}
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
        //logger.debug("getParent -> common idx: {}", common);
        if (common == parentObjectClassIdx) {
            SegmentedObject p = this;
            while (p!=null && p.getStructureIdx()!=parentObjectClassIdx) p = p.getParent();
            return p;
        } else {
            SegmentedObject p = this;
            while (p.getStructureIdx()!=common) p = p.getParent();
            //logger.debug("{} (2D?:{} so2D?: {}) common parent: {}", this, getRegion().is2D(), is2D(), p);
            return SegmentedObjectUtils.getContainer(getRegion(), p.getChildren(parentObjectClassIdx), null);
        }
    }

    public SegmentedObject getSegmentationParent() {
        return getParent(getExperimentStructure().getSegmentationParentObjectClassIdx(this.structureIdx));
    }

    public void setParent(SegmentedObject parent) {
        this.parent=parent;
        this.parentId=parent.getId();
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
                    } else return candidates.filter(c -> c.getRegion().intersect(getRegion())); //candidates.filter(c -> is2D() ? BoundingBox.intersect2D(c.getBounds(), this.getBounds()) : BoundingBox.intersect(c.getBounds(), this.getBounds()));
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

    boolean childrenRetrieved(int structureIdx) {
        return childrenSM.has(structureIdx);
    }

    void setChildren(List<SegmentedObject> children, int structureIdx) {
        this.childrenSM.set(children, structureIdx);
        if (children!=null) children.forEach(o -> o.setParent(this));
    }

    void addChild(SegmentedObject child, int structureIdx) {
        List<SegmentedObject> children = this.childrenSM.getOrDefault(structureIdx, ArrayList::new);
        children.add(child);
        child.setParent(this);
        children.sort(SegmentedObject::compareTo);
    }

    void addChildren(Stream<SegmentedObject> children, int structureIdx) {
        List<SegmentedObject> existingChildren = this.childrenSM.getOrDefault(structureIdx, ArrayList::new);
        children.peek(c -> c.setParent(this)).forEach(existingChildren::add);
        existingChildren.sort(SegmentedObject::compareTo);
    }
    
    List<SegmentedObject> setChildrenObjects(RegionPopulation population, int structureIdx, boolean relabel) {
        if (!getExperiment().experimentStructure.isDirectChildOf(this.structureIdx, structureIdx)) throw new IllegalArgumentException("Set children object call with non-direct child object class");
        if (population==null) {
            ArrayList<SegmentedObject> res = new ArrayList<>();
            childrenSM.set(res, structureIdx);
            return res;
        }
        if (!population.isAbsoluteLandmark()) {
            population.translate(getBounds(), true); // from parent-relative coordinates to absolute coordinates
        }
        ArrayList<SegmentedObject> res = new ArrayList<>(population.getRegions().size());
        childrenSM.set(res, structureIdx);
        int i = 0;
        for (Region o : population.getRegions()) res.add(new SegmentedObject(timePoint, structureIdx, relabel ? i++ : o.getLabel()-1, o, this));
        if (!relabel) res.sort(Comparator.comparingInt(SegmentedObject::getIdx));
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
        setTrackLinks(next, setPrev, setNext, true, false, null);
    }
    void setTrackLinks(SegmentedObject next, boolean setPrev, boolean setNext, boolean setTrackHead, boolean propagateTrackHead, Collection<SegmentedObject> modifiedObjects) {
        if (next==null) resetTrackLinks(setPrev, setNext, propagateTrackHead, modifiedObjects);
        else {
            if (next.getFrame()<=this.getFrame()) throw new RuntimeException("setLink previous after next!");
            if (setPrev && setNext) { // double link: set trackHead
                if (!next.equals(this.next)) {
                    setNext(next);
                    if (modifiedObjects!=null) modifiedObjects.add(this);
                }
                if (!this.equals(next.getPrevious()) || !getTrackHead().equals(next.getTrackHead())) {
                    next.setPrevious(this);
                    next.setTrackHead(getTrackHead(), false, propagateTrackHead, modifiedObjects);
                    if (modifiedObjects!=null) modifiedObjects.add(next);
                }
            } else if (setPrev) {
                boolean nextModified = false;
                if (!this.equals(next.getPrevious())) {
                    next.setPrevious(this);
                    nextModified=true;
                }
                if (setTrackHead) {
                    if (!next.equals(this.getNext())) {
                        if (!next.equals(next.getTrackHead())) {
                            next.setTrackHead(next, false, propagateTrackHead, modifiedObjects);
                            nextModified = true;
                        }
                    } else if (next.getTrackHead() != getTrackHead()) {
                        next.setTrackHead(getTrackHead(), false, propagateTrackHead, modifiedObjects);
                        nextModified = true;
                    }
                }
                if (modifiedObjects!=null && nextModified) {
                    modifiedObjects.add(next);
                }
            } else if (setNext) {
                boolean modified = false;
                if (!next.equals(this.next)) {
                    setNext(next);
                    modified = true;
                }
                if (setTrackHead) {
                    if (!this.equals(next.getPrevious())) {
                        if (!next.equals(next.getTrackHead())) {
                            next.setTrackHead(next, false, propagateTrackHead, modifiedObjects);
                            modified = true;
                        }
                    } else if (next.getTrackHead() != getTrackHead()) {
                        next.setTrackHead(getTrackHead(), false, propagateTrackHead, modifiedObjects);
                        modified = true;
                    }
                }
                if (modified && modifiedObjects!=null) {
                    modifiedObjects.add(this);
                }
            }
        }
        if (next!=null && setPrev) next.setAttribute(TRACK_ERROR_PREV, null);
    }

    SegmentedObject resetTrackLinks(boolean prev, boolean next, boolean propagate, Collection<SegmentedObject> modifiedObjects) {
        if (prev && this.previousId!=null && getPrevious()!=null && this.previous.next==this) previous.unSetTrackLinksOneWay(false, true, propagate, modifiedObjects); // remove next of prev
        if (next && this.nextId!=null && getNext()!=null && this.next.previous==this) this.next.unSetTrackLinksOneWay(true, false, propagate, modifiedObjects); // remove prev of next & propagate new trackHead in track
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
        if (previous==null && previousId!=null) previous = dao.getById(structureIdx, previousId, -1, dao.getIdUsesParentTrackHead() ? getParentTrackHeadId() : null);
        return previous;
    }

    /**
     *
     * @return next element of the track in which this object is contained if existing else null.
     */
    public SegmentedObject getNext() {
        if (next==null && nextId!=null) next = dao.getById(structureIdx, nextId, -1, dao.getIdUsesParentTrackHead() ? getParentTrackHeadId() : null);
        return next;
    }

    public SegmentedObject getAtFrame(int frame, boolean returnClosest) {
        if (frame>this.getFrame()) return getNextAtFrame(frame, returnClosest);
        else return getPreviousAtFrame(frame, returnClosest);
    }
    public SegmentedObject getPreviousAtFrame(int frame, boolean returnClosest) {
        if (frame>this.getFrame()) throw new IllegalArgumentException("Looking for previous object after");
        SegmentedObject p = this;
        if (returnClosest) {
            while (p.getPrevious()!= null && p.getFrame()>frame) p = p.getPrevious();
        } else {
            while (p != null && p.getFrame()>frame) p = p.getPrevious();
            if (p==null || p.getFrame()!=frame) return null;
        }
        return p;
    }

    public SegmentedObject getNextAtFrame(int frame, boolean returnClosest) {
        if (frame<this.getFrame()) throw new IllegalArgumentException("Looking for next object before");
        SegmentedObject n = this;
        if (returnClosest) {
            while (n.getNext() != null && n.getFrame()<frame) n = n.getNext();
        } else {
            while (n != null && n.getFrame()<frame) n = n.getNext();
            if (n==null || n.getFrame()!=frame) return null;
        }

        return n;
    }

    public SegmentedObject getTrackTail() {
        SegmentedObject n = this;
        while (n.getNext() != null) n = n.getNext();
        return n;
    }

    /**
     *
     * @param frameLimitExcl
     * @return track tail if trackTail.getFrame()<frameLimit else null
     */
    public SegmentedObject getTrackTail(int frameLimitExcl) {
        if (this.getFrame()>=frameLimitExcl) return null;
        SegmentedObject n = this;
        while (n.getNext() != null) {
            n = n.getNext();
            if (n.getFrame()>=frameLimitExcl) return null;
        }
        return n;
    }

    public Object getNextId() {
        return nextId;
    }
    public Object getPreviousId() {
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
            if (isTrackHead()) {
                this.trackHead=this;
                this.trackHeadId=this.id;
            } else if (trackHeadId!=null ) {
                trackHead = dao.getById(structureIdx, trackHeadId, -1, dao.getIdUsesParentTrackHead() ? getParentTrackHeadId() : null);
            } else if (getPrevious()!=null) {
                if (previous.isTrackHead()) this.trackHead=previous;
                else if (previous.trackHead!=null) this.trackHead=previous.trackHead;
                else {
                    ArrayList<SegmentedObject> prevList = new ArrayList<>();
                    prevList.add(this);
                    prevList.add(previous);
                    SegmentedObject prev = previous;
                    while (prev.getPrevious()!=null && (prev.getPrevious().trackHead==null || !prev.getPrevious().isTrackHead())) {
                        prev=prev.previous;
                        prevList.add(prev);
                    }
                    if (prev.isTrackHead()) for (SegmentedObject o : prevList) o.trackHead=prev;
                    else if (prev.trackHead!=null) for (SegmentedObject o : prevList) o.trackHead=prev.trackHead;
                }
            }
            if (trackHead==null) { // set trackHead if no trackHead found
                this.trackHead=this;
            } 
            this.trackHeadId=trackHead.id;
        }
        return trackHead;
    }

    public void setTrackHead(SegmentedObject trackHead) {
        if (trackHead == null) {
            this.trackHead = this;
            this.trackHeadId = getId();
        } else {
            this.trackHead = trackHead;
            this.trackHeadId = trackHead.getId();
        }
    }
    
    public Object getTrackHeadId() {
        if (trackHeadId==null) {
            getTrackHead();
            if (trackHead!=null) trackHeadId = trackHead.id;
        }
        return trackHeadId;
    }

    public Object getParentTrackHeadId() {
        if (getParent()!=null) return parent.getTrackHeadId();
        return null;
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
     * Whether this element is the first of the track it is contained in
     * @return
     */
    public boolean isTrackHead() {
        if (trackHeadId == null) trackHeadId = this.id;
        return trackHeadId.equals(id);
    }
    
    public SegmentedObject resetTrackHead(boolean propagate) {
        trackHeadId=null;
        trackHead=null;
        boolean isTrackHead = previousId==null || !this.id.equals(getPrevious().nextId);
        if (isTrackHead) trackHeadId = id;
        getTrackHead();
        if (propagate) {
            SegmentedObject n = this;
            while (n.getNext() != null && n.equals(n.getNext().getPrevious())) { // only on main track
                n = n.getNext();
                n.trackHeadId = trackHeadId;
                n.trackHead = trackHead;
            }
        }
        return this;
    }
    
    SegmentedObject setTrackHead(SegmentedObject trackHead, boolean resetPreviousIfTrackHead, boolean propagateToNextObjects, Collection<SegmentedObject> modifiedObjects) {
        if (trackHead==null) trackHead=this;
        else if (!trackHead.equals(this) && !trackHead.isTrackHead()) {
            throw new IllegalArgumentException("Set TrackHead called with non-trackhead element");
        }
        if (resetPreviousIfTrackHead && this.equals(trackHead) && previous!=null && previous.next==this) {
            previous.setNext(null);
            if (modifiedObjects!=null) modifiedObjects.add(previous);
        }
        if (!trackHead.equals(this.trackHead) || trackHeadId==trackHead.id) {
            this.trackHead = trackHead;
            this.trackHeadId = trackHead.id;
            if (modifiedObjects!=null) modifiedObjects.add(this);
        }
        if (propagateToNextObjects) {
            SegmentedObject n = this;
            while(n.getNext()!=null && n.equals(n.getNext().getPrevious())) {
                n = n.getNext();
                n.setTrackHead(trackHead, false, false, modifiedObjects);
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

    void merge(SegmentedObject other, boolean attributes, boolean children, boolean region) {
        // update object
        if (other==null) {
            logger.debug("merge: {}, other==null", this);
            return;
        }
        if (getRegion()==null) logger.debug("merge: {}+{}, object==null", this, other);
        if (other.getRegion()==null) logger.debug("merge: {}+{}, other object==null", this, other);
        if (region) {
            getRegion().merge(other.getRegion());
            flushImages();
            regionContainer = null;
            setRegionAttributesToAttributes();
        }
        if (children) { // transfer children
            for (int cOCIdx : getExperimentStructure().getAllDirectChildStructuresAsArray(structureIdx)) {
                List<SegmentedObject> c = other.getDirectChildren(cOCIdx);
                if (c!=null) {
                    addChildren(c.stream(), cOCIdx);
                    other.setChildren(null, cOCIdx);
                }
            }
        }
        if (attributes) {
            // update links
            SegmentedObject prev = other.getPrevious();
            if (prev != null && prev.getNext() != null && prev.next == other) prev.setNext(this);
            SegmentedObject next = other.getNext();
            if (next == null) next = getNext();
            if (next != null)
                getSiblings(next).filter(o -> o.getPrevious() == other).forEachOrdered(o -> o.setPrevious(this));

            this.getParent().getDirectChildren(structureIdx).remove(other); // concurrent modification..
            // set flags
            setAttribute(EDITED_SEGMENTATION, true);
            other.trackHeadId = trackHeadId; // so that it won't be detected in the correction (was other.isTrackHead = false)
        }
    }

    SegmentedObject splitInTwo(Image input, ObjectSplitter splitter, Collection<SegmentedObject> modifiedObjects) { // in 2 objects
        // get cropped image
        if (input==null) input = getParent().getPreFilteredImage(structureIdx);
        RegionPopulation pop = splitter.splitObject(input, getParent(), structureIdx, getRegion());
        if (pop==null || pop.getRegions().size()<=1) {
            logger.debug("could not split: {} number of segments: {}", this, pop==null?"null" : pop.getRegions().size());
            return null;
        }
        if (pop.getRegions().size()>2) pop.mergeWithConnected(pop.getRegions().subList(2, pop.getRegions().size()), true);
        return split(pop, modifiedObjects).get(0);
    }

    /**
     *
     * @param pop
     * @param modifiedObjects
     * @return new objects excluding current object
     */
    List<SegmentedObject> split(RegionPopulation pop, Collection<SegmentedObject> modifiedObjects) {
        if (isRoot()) throw new RuntimeException("Cannot split root object");
        if (pop.getRegions().isEmpty()) throw new IllegalArgumentException("Split: no objects");
        pop.getRegions().forEach(r -> r.regionModified = true);
        if (!pop.isAbsoluteLandmark()) { // set landmark
            pop.translate(getParent().getBounds(), true);
            //logger.debug("offsets: {}", Utils.toStringList(pop.getRegions(), r -> new SimpleOffset(r.getBounds())));
        }
        // first object is updated to current structureObject
        this.region = pop.getRegions().get(0).setLabel(idx+1);
        regionContainer = null;
        setAttribute(EDITED_SEGMENTATION, true);
        flushImages();
        // other objects are added to parent and returned
        List<SegmentedObject> res = pop.getRegions().size()==1 ? Collections.emptyList() : IntStream.range(1, pop.getRegions().size()).mapToObj(i -> {
            int[] otherIdxAndIP = SegmentedObjectFactory.getUnusedIndexAndInsertionPoint(getParent().getDirectChildren(structureIdx));
            SegmentedObject o = new SegmentedObject(timePoint, structureIdx, otherIdxAndIP[0], pop.getRegions().get(i), getParent());
            if (otherIdxAndIP[1]>=0) getParent().getDirectChildren(structureIdx).add(otherIdxAndIP[1], o);
            else getParent().getDirectChildren(structureIdx).add(o);
            o.setAttribute(EDITED_SEGMENTATION, true);
            return o;
        }).collect(Collectors.toList());

        // re-assign childen
        int[] directChildrenOCIdx = getExperimentStructure().getAllDirectChildStructuresAsArray(structureIdx);
        if (directChildrenOCIdx.length>0) {
            List<Region> parentRegions = StreamConcatenation.concat(Stream.of(this.region), res.stream().map(SegmentedObject::getRegion)).collect(Collectors.toList());
            Map<Region, SegmentedObject> rMapSo = res.stream().collect(Collectors.toMap(SegmentedObject::getRegion, o->o));
            for (int cOCIdx : directChildrenOCIdx) {
                if (childrenSM.has(cOCIdx) && !childrenSM.get(cOCIdx).isEmpty()) {
                    List<SegmentedObject> children = childrenSM.get(cOCIdx);
                    List<SegmentedObject> toRemove = null;
                    Set<SegmentedObject> toRelabel = null;
                    for (SegmentedObject c : children) {
                        Region p = c.getRegion().getMostOverlappingRegion(parentRegions, null, null);
                        if (p!=null && !p.equals(region)) {
                            if (toRemove == null) toRemove = new ArrayList<>();
                            toRemove.add(c); // remove from current object
                            rMapSo.get(p).addChild(c, cOCIdx);
                            if (toRelabel == null) toRelabel = new HashSet<>();
                            toRelabel.add(rMapSo.get(p));
                            if (modifiedObjects != null) modifiedObjects.add(c);
                        }
                    }
                    if (toRemove != null) {
                        children.removeAll(toRemove);
                        relabelChildren(cOCIdx, modifiedObjects);
                    }
                    if (toRelabel != null) toRelabel.forEach(p -> p.relabelChildren(cOCIdx, modifiedObjects));
                }
            }
        }
        //logger.debug("split object: {}({}) -> {} c={} & {}({}) -> {} c={}", this, getId(), this.getBounds(), this.region.getCenterOrGeomCenter(), res, res.getId(), res.getBounds(), res.region.getCenterOrGeomCenter());
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
                    region = regionContainer.getRegion().setIsAbsoluteLandmark(true);
                    //logger.debug("Region: {} attributes: {}", this, attributes);
                    if (attributes!=null) {
                        if (attributes.containsKey("Quality")) region.setQuality((Double)attributes.get("Quality"));
                        if (!(region instanceof Analytical) && attributes.containsKey("Center")) region.setCenter(new Point(JSONUtils.fromFloatArray((List)attributes.get("Center"))));
                        if (attributes.containsKey("Category")) region.setCategory((Integer)attributes.get("Category"), (Double)attributes.getOrDefault("CategoryProbability", 1.));
                    }
                    region.regionModified = false; // setters modify region
                }
            }
        }
        return region;
    }
    void setRegion(Region o) {
        synchronized(this) {
            regionContainer=null;
            if (!o.isAbsoluteLandMark()) {
                if (!isRoot()) o.translate(getParent().getBounds());
                o.setIsAbsoluteLandmark(true);
            }
            region = o;
            region.regionModified = true;
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
            attributes.remove("MajorAxis");
            attributes.remove("MinorAxis");
            attributes.remove("Theta");
            attributes.remove("AspectRatio");
            attributes.remove("Category");
            attributes.remove("CategoryProbability");
        }
        if (region!= null) {
            if (!Double.isNaN(region.getQuality())) setAttribute("Quality", region.getQuality());
            if (region instanceof Spot) {
                setAttribute("Radius", ((Spot) region).getRadius());
                setAttribute("Intensity", ((Spot) region).getIntensity());
                setAttribute("AspectRatioZ", ((Spot) region).getAspectRatioZ());
            } else if (region instanceof Ellipse2D) {
                setAttribute("MajorAxis", ((Ellipse2D) region).getMajor());
                setAttribute("MinorAxis", ((Ellipse2D) region).getMinor());
                setAttribute("AspectRatio", ((Ellipse2D) region).getAspectRatio());
                setAttribute("Theta", ((Ellipse2D) region).getTheta());
                setAttribute("Intensity", ((Ellipse2D) region).getIntensity());
            }
            if (region.getCenter() != null) {
                Point c = region.getCenter();
                setAttributeList("Center", IntStream.range(0, c.numDimensions()).mapToObj(i -> (double) c.get(i)).collect(Collectors.toList()));
            }
            if (region.getCategory() >= 0) {
                setAttribute("Category", region.getCategory());
                if (!Double.isNaN(region.getCategoryProbability())) setAttribute("CategoryProbability", region.getCategoryProbability());
            }
        }
    }
    
    public ImageProperties getMaskProperties() {
        if (region == null) return new SimpleImageProperties(getBounds(), getScaleXY(), getScaleZ());
        return getRegion().getImageProperties();
    }
    public ImageMask getMask() {return getRegion().getMask();}
    public BoundingBox<? extends BoundingBox<?>> getBounds() {
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
    boolean updateRegionContainer() {
        if (regionContainer==null) {
            if (region!=null && region.regionModified) setRegionAttributesToAttributes();
            createRegionContainer();
            return true;
        } else {
            if (region!=null && region.regionModified) {
                setRegionAttributesToAttributes();
                regionContainer.update();
                region.regionModified = false;
                return true;
            } else return false;
        }
    }

    void setRawImage(int channelIdx, Image image) {
        rawImagesC.set(image, channelIdx);
    }

    public Image getRawImage(int structureIdx) {
        return getRawImageByChannel(getExperiment().getChannelImageIdx(structureIdx));
    }

    /**
     * @param channelIdx
     * @return raw image of the channel associated to {@param channelIdx} cropped to the bounds of this object
     */
    public Image getRawImageByChannel(int channelIdx) {
        if (rawImagesC.get(channelIdx)==null) {
            synchronized(rawImagesC) {
                if (rawImagesC.get(channelIdx)==null) {
                    if (isRoot()) {
                        if (rawImagesC.getAndExtend(channelIdx)==null) {
                            if (getPosition().singleFrameChannel(channelIdx) && !isTrackHead() && trackHead!=null) { // getImage from trackHead
                                trackHead.getRawImageByChannel(channelIdx);
                                rawImagesC.set(trackHead.rawImagesC.get(channelIdx), channelIdx); // in case of disk backed image
                            } else {
                                Image im = null;
                                try {
                                    im = getPosition().getImageDAO().openPreProcessedImage(channelIdx, getPosition().singleFrameChannel(channelIdx) ? 0 : timePoint);
                                } catch (IOException e) {

                                }
                                rawImagesC.set(im, channelIdx);
                                if (im==null) logger.error("Could not find preProcessed Image for: {}", this);
                            }
                        }
                    } else { // look in parent
                        SegmentedObject parentWithImage=getFirstParentWithOpenedRawImage(channelIdx);
                        if (parentWithImage!=null) {
                            //logger.debug("object: {}, channel: {}, open from parent with open image: {}", this, channelIdx, parentWithImage);
                            BoundingBox bb=getRelativeBoundingBox(parentWithImage);
                            bb=extendBoundsInZIfNecessary(channelIdx, bb);
                            rawImagesC.set(parentWithImage.getRawImageByChannel(channelIdx).crop(bb), channelIdx);
                        } else { // open root and crop
                            Image rootImage = getRoot().getRawImageByChannel(channelIdx);
                            //logger.debug("object: {}, channel: {}, no trackImage try to open root and crop... null ? {}", this, channelIdx, rootImage==null);
                            if (rootImage!=null) {
                                BoundingBox bb = getRelativeBoundingBox(getRoot());
                                bb=extendBoundsInZIfNecessary(channelIdx, bb);
                                Image image = rootImage.crop(bb);
                                rawImagesC.set(image, channelIdx);
                            } else if (!this.equals(getRoot())) {
                                // try to open parent image (if trackImage present...)
                                Image pImage = this.getParent().getRawImageByChannel(channelIdx);
                                //logger.debug("try to open parent image: null?{}", pImage==null);
                                if (pImage!=null) {
                                    BoundingBox bb = getRelativeBoundingBox(getParent());
                                    bb=extendBoundsInZIfNecessary(channelIdx, bb);
                                    Image image = pImage.crop(bb);
                                    rawImagesC.set(image, channelIdx);
                                }
                            }
                            // no speed gain in opening only tiles // TODO check how that depends on reader ...
                            /*StructureObject root = getRoot();
                            BoundingBox bb=getRelativeBoundingBox(root);
                            bb=extendBoundsInZIfNecessary(channelIdx, bb);
                            rawImagesC.set(root.openRawImageByChannel(channelIdx, bb), channelIdx);*/
                        }
                    }
                    if (rawImagesC.has(channelIdx)) rawImagesC.get(channelIdx).setCalibration(getScaleXY(), getScaleZ());
                    
                    //logger.debug("{} open channel: {}, use scale? {}, scale: {}", this, channelIdx, this.getPosition().getPreProcessingChain().useCustomScale(), getScaleXY());
                }
            }
        }
        Image im = rawImagesC.get(channelIdx);
        if (im instanceof DiskBackedImage) {
            if (((DiskBackedImage)im).detached()) {
                synchronized (rawImagesC) {
                    im = rawImagesC.get(channelIdx);
                    if (im instanceof DiskBackedImage) {
                        if (((DiskBackedImage)im).detached()) { // image has been erased -> set a null value and re-open image.
                            rawImagesC.set(null, channelIdx);
                            return getRawImageByChannel(channelIdx);
                        } else return ((SimpleDiskBackedImage)im).getImage();
                    } else return im;
                }
            } else return ((SimpleDiskBackedImage)im).getImage();
        } else return im;
    }
    /**
     *
     * @param structureIdx
     * @return Pre-filtered image of the channel associated to {@param objectClassIdx} cropped to the bounds of this object. Pre-filtered image is ensured to be set only to the segmentation parent of {@param ObjectClassIdx} at segmentation step of {@param ObjectClassIdx}, at any other step will return null.
     */
    public Image getPreFilteredImage(int structureIdx) {
        Image im = this.preFilteredImagesS.get(structureIdx);
        if (im instanceof DiskBackedImage) return ((SimpleDiskBackedImage)im).getImage();
        else return im;
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
    
    private BoundingBox getOffsetInTrackImage() {
        return this.offsetInTrackImage;
    }
    
    private BoundingBox extendBoundsInZIfNecessary(int channelIdx, BoundingBox bounds) { //when the current structure is 2D but channel is 3D 
        //logger.debug("extends bounds Z if necessary: is2D: {}, bounds: {}, sizeZ of image to open: {}", is2D(), bounds, getExperiment().getPosition(getPositionName()).getSizeZ(channelIdx));
        if (bounds.sizeZ()==1 && is2D()) {
            int sizeZ = getExperiment().getPosition(getPositionName()).getSizeZ(channelIdx);
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

    
    private SegmentedObject getFirstParentWithOpenedRawImage(int channelIdx) {
        if (isRoot()) {
            if (rawImagesC.get(channelIdx)!=null) return this;
            else return null;
        }
        if (getParent().rawImagesC.get(channelIdx)!=null) return parent;
        else return parent.getFirstParentWithOpenedRawImage(channelIdx);
    }
    
    public <T extends BoundingBox<T>> BoundingBox<T> getRelativeBoundingBox(SegmentedObject stop) throws RuntimeException {
        SimpleBoundingBox res = new SimpleBoundingBox(getBounds());
        if (stop==null || stop == getRoot()) return res;
        else return res.translate(new SimpleOffset(stop.getBounds()).reverseOffset());
    }
    public SegmentedObject getFirstCommonParent(SegmentedObject other) {
        if (other==null) return null;
        SegmentedObject object1 = this;
        while (object1.getStructureIdx()>=0 || other.getStructureIdx()>=0) {
            if (object1.getStructureIdx()>other.getStructureIdx()) object1 = object1.getParent();
            else if (object1.getStructureIdx()<other.getStructureIdx()) other = other.getParent();
            else if (object1==other) return object1;
            else return null;
        }
        if (other.equals(object1)) return object1;
        return null;
    }
    void flushImages() {
        flushImages(true, true);
    }
    public void flushImages(boolean raw, boolean prefiltered) {
        if (raw) {
            for (int i = 0; i<rawImagesC.getBucketSize(); ++i) rawImagesC.setQuick(null, i);
            for (int i = 0; i<trackImagesC.getBucketSize(); ++i) trackImagesC.setQuick(null, i);
            this.offsetInTrackImage=null;
        }
        if (prefiltered) {
            for (int i = 0; i<preFilteredImagesS.getBucketSize(); ++i) preFilteredImagesS.setQuick(null, i);
        }
    }
    public RegionPopulation getChildRegionPopulation(int structureIdx, boolean strictInclusion) {
        Stream<SegmentedObject> children = this.getChildren(structureIdx, strictInclusion);
        if (children==null) children = Stream.empty();
        List<Region> regions = children.map(SegmentedObject::getRegion).collect(Collectors.toList());
        ImageProperties ip = this.getMaskProperties();
        boolean is2D;
        if (!regions.isEmpty()) is2D = regions.get(0).is2D();
        else is2D = getExperimentStructure().is2D(structureIdx, getPositionName());
        if (is2D && ip.sizeZ()>1) ip = new SimpleImageProperties(ip.sizeX(), ip.sizeY(), 1, ip.getScaleXY(), ip.getScaleZ());
        else if (!is2D) { // ensure sizeZ corresponds
            int sizeZ = getExperimentStructure().sizeZ(getPositionName(), getExperimentStructure().getChannelIdx(structureIdx));
            if (ip.sizeZ() != sizeZ) ip = new SimpleImageProperties(ip.sizeX(), ip.sizeY(), sizeZ, ip.getScaleXY(), ip.getScaleZ());
        }
        return new RegionPopulation(regions, ip);
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
    protected Measurements getMeasurements(boolean onlyIfExists) {
        if (measurements==null) {
            if (dao!=null) {
                synchronized(dao) {
                    if (measurements==null) {
                        measurements = dao.getMeasurements(this);
                        if (measurements==null && !onlyIfExists) measurements = new Measurements(this);
                    }
                }
            } else if (!onlyIfExists) {
                synchronized (this) {
                    if (measurements == null) measurements = new Measurements(this);
                }
            }
        }
        return measurements;
    }
    public Measurements getMeasurements() {
        return getMeasurements(false);
    }

    public void resetMeasurements() {
        this.measurements = new Measurements(this);
        this.measurements.modifications = true;
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
    }
    
    public String toStringShort() {
        return getFrame()+"-"+getIdx();
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
        if (trackHeadId!=null) obj1.put("thId", trackHeadId);
        obj1.put("isTh", isTrackHead()); // used in mapdb
        if (attributes!=null && !attributes.isEmpty()) obj1.put("attributes", JSONUtils.toJSONObject(attributes));
        return obj1;
    }
    public JSONObject getRegionJSONEntry() {
        if (regionContainer!=null) return regionContainer.toJSON();
        else return null;
    }
    public JSONObject getAttributesJSONEntry() {
        if (attributes!=null) return JSONUtils.toJSONObject(attributes);
        else return null;
    }
    @Override
    public void initFromJSONEntry(Object jsonEntry) {
        Map json = (JSONObject)jsonEntry;
        id = json.get("id");
        Object pId = json.get("pId");
        if (pId!=null) parentId = pId;
        structureIdx = ((Number)json.get("sIdx")).intValue();
        idx = ((Number)json.get("idx")).intValue();
        timePoint = ((Number)json.get("frame")).intValue();
        Object nId = json.get("nextId");
        if (nId!=null) nextId = nId;
        Object prevId = json.get("prevId");
        if (prevId!=null) previousId = prevId;
        Object thId = json.get("thId");
        if (thId!=null) trackHeadId = thId;
        
        if (json.containsKey("attributes")) {
            attributes = (Map<String, Object>)json.get("attributes");
            //attributes = JSONUtils.toValueMap((Map)json.get("attributes")); // leave list for better efficiency ?
        } 
        if (json.containsKey("object")) {
            Map objectJ = (Map)json.get("object");
            regionContainer = RegionContainer.createFromJSON(this, objectJ);
        }
    }
    public void initRegionFromJSONEntry(Map jsonEntry) {
        regionContainer = RegionContainer.createFromJSON(this, jsonEntry);
    }
}
