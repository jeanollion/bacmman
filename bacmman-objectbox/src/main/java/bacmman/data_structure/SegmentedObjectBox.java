package bacmman.data_structure;

import bacmman.data_structure.dao.ObjectBoxDAO;
import bacmman.data_structure.dao.ObjectDAO;
import bacmman.utils.CompressionUtils;
import bacmman.utils.JSONUtils;
import io.objectbox.annotation.*;
import org.json.simple.JSONObject;
import org.json.simple.parser.ParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Map;

@Entity
@Uid(10)
public class SegmentedObjectBox {
    static final Logger logger = LoggerFactory.getLogger(SegmentedObjectBox.class);
    @Id(assignable = true)
    @Uid(11)
    long id;
    @Index
    @Uid(12)
    int frame;
    @Uid(13)
    int idx;
    @Index
    @Uid(14)
    long parentId;

    @Index
    @Uid(15)
    long trackHeadId;
    @Uid(16)
    long previousId;
    @Uid(17)
    long nextId;
    @Uid(18)
    byte[] jsonRegion;
    @Uid(19)
    String jsonAttributes;
    @Transient
    SegmentedObject object;

    public SegmentedObjectBox() {}
    public SegmentedObjectBox(long id, int frame, int idx, long parentId, long trackHeadId, long previousId, long nextId, byte[] jsonRegion, String jsonAttributes) {
        this.id = id;
        this.frame = frame;
        this.idx = idx;
        this.parentId = parentId;
        this.trackHeadId = trackHeadId;
        this.previousId = previousId;
        this.nextId = nextId;
        this.jsonRegion = jsonRegion;
        this.jsonAttributes = jsonAttributes;
    }

    public SegmentedObjectBox duplicate() {
        return new SegmentedObjectBox(id, frame, idx, parentId, trackHeadId, previousId, nextId, jsonRegion, jsonAttributes);
    }

    public SegmentedObjectBox(SegmentedObject object) {
        updateSegmentedObject(object);
    }

    public SegmentedObjectBox updateSegmentedObject(SegmentedObject object) {
        this.object = object;
        this.id = (Long)object.getId();
        this.frame = object.getFrame();
        this.idx = object.getIdx();
        this.parentId = object.parentId==null? 0L : (Long)object.parentId;
        this.trackHeadId = object.trackHeadId==null ? (Long)object.getId() : (Long)object.trackHeadId;
        this.previousId = object.previousId==null? 0L : (Long)object.previousId;
        this.nextId= object.nextId==null? 0L : (Long)object.nextId;
        boolean modified = object.updateRegionContainer();
        if (modified || jsonRegion == null) {
            try {
                jsonRegion = CompressionUtils.compress(object.getRegionJSONEntry().toJSONString(), true);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        //jsonRegion = object.getRegionJSONEntry().toJSONString();
        JSONObject attributes = object.getAttributesJSONEntry();
        if (attributes!=null) jsonAttributes = attributes.toJSONString();
        return this;
    }
    // TODO : test compressed array for region (benchmark storing time / retrieve) / idem for measurements


    public long getId() {
        return id;
    }

    public int getIdx() {
        return idx;
    }

    public long getParentId() {
        return parentId;
    }

    public long getTrackHeadId() {
        return trackHeadId;
    }


    public long getPreviousId() {
        return previousId;
    }

    public long getNextId() {
        return nextId;
    }

    public boolean isTrackHead() {
        return trackHeadId == id;
    }

    public boolean hasSegmentedObject() {
        return object!=null;
    }
    public SegmentedObject getSegmentedObject(int objectClassIdx, ObjectBoxDAO dao) {
        if (object == null) {
            synchronized (this) {
                if (object == null) {
                    object = new SegmentedObject(frame, objectClassIdx, idx, null, null);
                    object.id = id;
                    object.parentId = parentId==0 ? null : parentId;
                    //logger.debug("create SO from SOB: frame {} oc{} idx {} region {}", frame, objectClassIdx, idx, jsonRegion);
                    try {
                        String jsonRegionS = CompressionUtils.decompressToString(jsonRegion, true);
                        object.initRegionFromJSONEntry(JSONUtils.parse(jsonRegionS));
                    } catch (IOException | ParseException e) {
                        throw new RuntimeException(e);
                    }
                    //object.initRegionFromJSONEntry(JSONUtils.parse(jsonRegion));
                    if (jsonAttributes != null) {
                        try {
                            object.attributes = (Map<String, Object>)JSONUtils.parse(jsonAttributes);
                        } catch (ParseException e) {
                            throw new RuntimeException(e);
                        }
                    }
                    object.trackHeadId = trackHeadId == 0 ? id : trackHeadId;
                    object.previousId = previousId==0 ? null : previousId;
                    object.nextId = nextId == 0 ? null : nextId;
                    object.dao = dao;
                }
            }
        }
        return object;
    }

    public void setIdx(int idx) {
        this.idx = idx;
        if (object != null) object.setIdx(idx);
    }
}
