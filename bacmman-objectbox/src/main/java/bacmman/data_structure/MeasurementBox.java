package bacmman.data_structure;

import bacmman.data_structure.dao.ObjectBoxDAO;
import bacmman.utils.JSONUtils;
import io.objectbox.annotation.*;

@Entity
@Uid(100)
public class MeasurementBox {
    @Id(assignable = true)
    @Uid(101)
    long id;
    @Index()
    @Uid(102)
    int objectClassIdx;
    @Index
    @Uid(103)
    String positionName;
    @Uid(104)
    String jsonMap;
    @Transient
    Measurements measurement;
    public MeasurementBox() {}

    public MeasurementBox(long id, int objectClassIdx, String positionName, String jsonMap) {
        this.id = id;
        this.objectClassIdx = objectClassIdx;
        this.positionName = positionName;
        this.jsonMap = jsonMap;
    }
    public MeasurementBox(Measurements m) {
        update(m);
    }
    public MeasurementBox update(Measurements m) {
        this.id = (Long)m.getId();
        this.positionName = m.positionName;
        this.objectClassIdx = m.structureIdx;
        this.jsonMap = m.toJSONEntry().toJSONString();
        return this;
    }

    public long getId() {
        return id;
    }

    public int getObjectClassIdx() {
        return objectClassIdx;
    }

    public String getPositionName() {
        return positionName;
    }

    public boolean hasMeasurement() {
        return measurement != null;
    }
    public Measurements getMeasurement(ObjectBoxDAO dao) {
        if (measurement == null) {
            synchronized (this) {
                if (measurement == null) {
                    measurement = new Measurements(JSONUtils.parse(jsonMap), positionName);
                }
            }
        }
        return measurement;
    }
}
