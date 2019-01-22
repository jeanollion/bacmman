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
package bacmman.data_structure.image_container;

import bacmman.image.MutableBoundingBox;
import bacmman.image.Image;
import static bacmman.image.Image.logger;
import bacmman.image.io.ImageIOCoordinates;
import bacmman.image.io.ImageReader;

import java.util.HashMap;
import java.util.Map;

import org.json.simple.JSONObject;
import bacmman.utils.JSONUtils;

/**
 *
 * @author Jean Ollion
 */

public class MultipleImageContainerSingleFile extends MultipleImageContainer {
    String filePath;
    String name;
    int timePointNumber, channelNumber;
    int seriesIdx;
    int sizeZ;
    MutableBoundingBox bounds;
    private ImageReader reader;
    private Map<String, Double> timePointCZT;
    private boolean invertTZ;
    @Override
    public boolean sameContent(MultipleImageContainer other) {
        if (other instanceof MultipleImageContainerSingleFile) {
            MultipleImageContainerSingleFile otherM = (MultipleImageContainerSingleFile)other;
            if (scaleXY!=otherM.scaleXY) return false;
            if (scaleZ!=otherM.scaleZ) return false;
            if (!name.equals(otherM.name)) return false;
            if (!filePath.equals(otherM.filePath)) return false;
            if (timePointNumber!=otherM.timePointNumber) return false;
            if (channelNumber!=otherM.channelNumber) return false;
            if (seriesIdx!=otherM.seriesIdx) return false;
            if (sizeZ!=otherM.sizeZ) return false;
            if (bounds!=null && !bounds.equals(otherM.bounds)) return false;
            else if (bounds==null && otherM.bounds!=null) return false;
            if (invertTZ!=otherM.invertTZ) return false;
            return true;
        } else return false;
    }
    
    @Override
    public Object toJSONEntry() {
        JSONObject res = new JSONObject();
        res.put("scaleXY", scaleXY);
        res.put("scaleZ", scaleZ);
        res.put("filePath", filePath);
        res.put("name", name);
        res.put("framePointNumber", timePointNumber);
        res.put("channelNumber", channelNumber);
        res.put("seriesIdx", seriesIdx);
        res.put("sizeZ", sizeZ);
        res.put("invertTZ", invertTZ);
        if (bounds!=null) res.put("bounds", bounds.toJSONEntry());
        if (timePointCZT!=null) res.put("timePointCZT", JSONUtils.toJSONObject(timePointCZT));
        return res;
    }

    @Override
    public void initFromJSONEntry(Object jsonEntry) {
        JSONObject jsonO = (JSONObject)jsonEntry;
        scaleXY = ((Number)jsonO.get("scaleXY")).doubleValue();
        scaleZ = ((Number)jsonO.get("scaleZ")).doubleValue();
        filePath = (String)jsonO.get("filePath");
        name = (String)jsonO.get("name");
        timePointNumber = ((Number)jsonO.get("framePointNumber")).intValue();
        channelNumber = ((Number)jsonO.get("channelNumber")).intValue();
        seriesIdx = ((Number)jsonO.get("seriesIdx")).intValue();
        sizeZ = ((Number)jsonO.get("sizeZ")).intValue();
        invertTZ = (Boolean)jsonO.getOrDefault("invertTZ", false);
        if (jsonO.containsKey("bounds")) {
            bounds = new MutableBoundingBox();
            bounds.initFromJSONEntry(jsonO.get(("bounds")));
        }
        if (jsonO.containsKey("timePointCZT")) {
            timePointCZT = (Map<String, Double>)jsonO.get("timePointCZT");
            logger.debug("load tpMap: {}", timePointCZT);
        }
    }
    protected MultipleImageContainerSingleFile() {super(1, 1);}
    public MultipleImageContainerSingleFile(String name, String imagePath, int series, int timePointNumber, int channelNumber, int sizeZ, double scaleXY, double scaleZ, boolean invertTZ) {
        super(scaleXY, scaleZ);
        this.name = name;
        this.seriesIdx=series;
        filePath = imagePath;
        this.timePointNumber = timePointNumber;
        this.channelNumber=channelNumber;
        this.sizeZ=sizeZ;
        this.invertTZ=invertTZ;
    }

    @Override public MultipleImageContainerSingleFile duplicate() {
        MultipleImageContainerSingleFile res = new MultipleImageContainerSingleFile(name, filePath, seriesIdx, timePointNumber, channelNumber, sizeZ, scaleXY, scaleZ, invertTZ);
        return res;
    }
    
    @Override public double getCalibratedTimePoint(int t, int c, int z) {
        return Double.NaN; // not supported
        /*if (timePointCZT==null) initTimePointMap();
        if (timePointCZT.isEmpty()) return Double.NaN;
        String key = getKey(c, z, t);
        Double d = timePointCZT.get(key);
        if (d!=null) return d;
        else return Double.NaN;*/
    }
    
    private void initTimePointMap() {
        ImageReader r = this.getReader();
        timePointCZT = new HashMap<>();
        if (r==null) return;
        for (int c = 0; c<this.getChannelNumber(); ++c) {
            for (int z = 0; z<getSizeZ(c); ++z) {
                for (int t = 0; t<getFrameNumber(); ++t) {
                    double tp = r.getTimePoint(c, t, z);
                    if (!Double.isNaN(tp)) timePointCZT.put(getKey(c, z, t), tp);
                }
            }
        }
        logger.debug("tpMap: {}", timePointCZT);
    }
    
    public void setImagePath(String path) {
        this.filePath=path;
    }
    
    public String getFilePath(){return filePath;}
    
    @Override 
    public String getName(){return name;}

    @Override 
    public int getFrameNumber() {
        return timePointNumber;
    }

    @Override 
    public int getChannelNumber() {
        return channelNumber;
    }
    
    @Override
    public boolean singleFrame(int channel) {
        return false;
    }
    
    /**
     * 
     * @param channelNumber ignored for this time of image container
     * @return the number of z-slices for each image
     */
    @Override
    public int getSizeZ(int channelNumber) {
        return sizeZ;
    }
    
    protected ImageIOCoordinates getImageIOCoordinates(int timePoint, int channel) {
        return new ImageIOCoordinates(seriesIdx, channel, timePoint);
    }
    
    protected ImageReader getReader() {
        if (reader==null) reader = new ImageReader(filePath);
        if (invertTZ) reader.setInvertTZ(true);
        return reader;
    }
    
    @Override
    public synchronized Image getImage(int timePoint, int channel) {
        if (this.timePointNumber==1) timePoint=0;
        ImageIOCoordinates ioCoordinates = getImageIOCoordinates(timePoint, channel);
        if (bounds!=null) ioCoordinates.setBounds(bounds);
        Image image = getReader().openImage(ioCoordinates);
        /*if (scaleXY!=0 && scaleZ!=0) image.setCalibration((float)scaleXY, (float)scaleZ);
        else {
            scaleXY = image.getScaleXY();
            scaleZ = image.getScaleZ();
        }*/
        return image;
    }
    
    @Override
    public synchronized Image getImage(int timePoint, int channel, MutableBoundingBox bounds) {
        if (this.timePointNumber==1) timePoint=0;
        ImageIOCoordinates ioCoordinates = getImageIOCoordinates(timePoint, channel);
        ImageIOCoordinates ioCoords = ioCoordinates.duplicate();
        ioCoords.setBounds(bounds);
        Image image = getReader().openImage(ioCoordinates);
        /*if (scaleXY!=0 && scaleZ!=0) image.setCalibration((float)scaleXY, (float)scaleZ);
        else {
            scaleXY = image.getScaleXY();
            scaleZ = image.getScaleZ();
        }*/
        return image;
    }
    @Override 
    public void flush() {
        if (reader!=null) reader.closeReader();
        reader = null;
    }
}
