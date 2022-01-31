package bacmman.image.io;

import bacmman.utils.JSONSerializable;
import org.json.simple.JSONObject;

import java.util.List;

public class OmeroImageMetadata implements JSONSerializable {

    /** Identifies the type used to store pixel values. */
    public static final String INT_8 ="int8";
    /** Identifies the type used to store pixel values. */
    public static final String UINT_8 = "uint8";

    /** Identifies the type used to store pixel values. */
    public static final String INT_16 = "int16";

    /** Identifies the type used to store pixel values. */
    public static final String UINT_16 ="uint16";

    /** Identifies the type used to store pixel values. */
    public static final String INT_32 = "int32";

    /** Identifies the type used to store pixel values. */
    public static final String UINT_32 =  "uint32";

    /** Identifies the type used to store pixel values. */
    public static final String FLOAT = "float";

    /** Identifies the type used to store pixel values. */
    public static final String DOUBLE = "double";

    protected String fileName, datasetName, pixelType;
    protected long fileId;
    protected int sizeX, sizeY, sizeZ, sizeT, sizeC;
    protected double scaleXY, scaleZ;
    protected List<Long> timepoints;
    public OmeroImageMetadata(String fileName, String datasetName, long fileId, int sizeX, int sizeY, int sizeZ, int sizeT, int sizeC, double scaleXY, double scaleZ, String pixelType) {
        this.fileName = fileName;
        this.datasetName = datasetName;
        this.fileId = fileId;
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.sizeT = sizeT;
        this.sizeC = sizeC;
        this.scaleXY = scaleXY;
        this.scaleZ = scaleZ;
        this.pixelType = pixelType;
    }
    public OmeroImageMetadata setTimePoint(List<Long> timePoint) {
        assert timePoint.size() == sizeT;
        this.timepoints = timePoint;
        return this;
    }
    public List<Long> getTimepoints() {
        return timepoints;
    }
    public int getBitDepth() {
        if (UINT_8.equals(pixelType) || INT_8.equals(pixelType)) return 8;
        if (UINT_16.equals(pixelType) || INT_16.equals(pixelType)) return 16;
        if (FLOAT.equals(pixelType) || UINT_32.equals(pixelType) || INT_32.equals(pixelType))
            return 32;
        return -1;
    }

    public boolean floatType() {
        return FLOAT.equals(pixelType) || DOUBLE.equals(pixelType);
    }


    @Override
    public Object toJSONEntry() {
        JSONObject res = new JSONObject();
        res.put("sizeX", sizeX);
        res.put("sizeY", sizeY);
        res.put("sizeZ", sizeZ);
        res.put("sizeC", sizeC);
        res.put("sizeT", sizeT);
        res.put("fileName", fileName);
        res.put("datasetName", datasetName);
        res.put("id", fileId);
        res.put("scaleXY", scaleXY);
        res.put("scaleZ", scaleZ);
        res.put("pixelType", pixelType);
        return res;
    }

    @Override
    public void initFromJSONEntry(Object jsonEntry) {
        JSONObject jsonObject = (JSONObject)jsonEntry;
        this.sizeX=((Number)jsonObject.get("sizeX")).intValue();
        this.sizeY=((Number)jsonObject.get("sizeY")).intValue();
        this.sizeZ=((Number)jsonObject.get("sizeZ")).intValue();
        this.sizeC=((Number)jsonObject.get("sizeC")).intValue();
        this.sizeT=((Number)jsonObject.get("sizeT")).intValue();
        this.fileId=((Number)jsonObject.get("id")).longValue();
        this.scaleXY=((Number)jsonObject.get("scaleXY")).doubleValue();
        this.scaleZ=((Number)jsonObject.get("scaleZ")).doubleValue();
        this.pixelType = (String)jsonObject.get("pixelType");
        this.fileName = (String)jsonObject.get("fileName");
        this.datasetName = (String)jsonObject.get("datasetName");
    }

    public String getFileName() {
        return fileName;
    }
    public String getFileNameAndId() {
        return fileName+ " (id:"+fileId+")";
    }
    public String getDatasetName() {
        return datasetName;
    }

    public String getPixelType() {
        return pixelType;
    }

    public long getFileId() {
        return fileId;
    }

    public int getSizeX() {
        return sizeX;
    }

    public int getSizeY() {
        return sizeY;
    }

    public int getSizeZ() {
        return sizeZ;
    }

    public int getSizeT() {
        return sizeT;
    }

    public int getSizeC() {
        return sizeC;
    }

    public double getScaleXY() {
        return scaleXY;
    }

    public double getScaleZ() {
        return scaleZ;
    }
}
