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
import bacmman.image.io.ImageIOCoordinates;
import bacmman.image.io.ImageReader;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import bacmman.utils.JSONUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author Jean Ollion
 */

public class MultipleImageContainerChannelSerie extends MultipleImageContainer { //one file per channel and serie
    public static final Logger logger = LoggerFactory.getLogger(MultipleImageContainerChannelSerie.class);
    String[] filePathC;
    String name;
    int timePointNumber;
    int[] sizeZC;
    MutableBoundingBox bounds;
    private ImageReader reader[];
    private Image[] singleFrameImages;
    boolean[] singleFrameC;
    Map<String, Double> timePointCZT;
    private boolean invertTZ;
    @Override
    public boolean sameContent(MultipleImageContainer other) {
        if (other instanceof MultipleImageContainerChannelSerie) {
            MultipleImageContainerChannelSerie otherM = (MultipleImageContainerChannelSerie)other;
            if (scaleXY!=otherM.scaleXY) return false;
            if (scaleZ!=otherM.scaleZ) return false;
            if (!name.equals(otherM.name)) return false;
            if (!Arrays.deepEquals(filePathC, otherM.filePathC)) return false;
            if (timePointNumber!=otherM.timePointNumber) return false;
            if (!Arrays.equals(sizeZC, otherM.sizeZC)) return false;
            if (bounds!=null && !bounds.equals(otherM.bounds)) return false;
            else if (bounds==null && otherM.bounds!=null) return false;
            if (!Arrays.equals(singleFrameC, otherM.singleFrameC)) return false;
            if (!timePointCZT.equals(otherM.timePointCZT)) return false;
            if (invertTZ!=otherM.invertTZ) return false;
            return true;
        } else return false;
    }
    
    @Override
    public Object toJSONEntry() {
        JSONObject res = new JSONObject();
        res.put("scaleXY", scaleXY);
        res.put("scaleZ", scaleZ);
        res.put("filePathC", JSONUtils.toJSONArray(filePathC));
        res.put("name", name);
        res.put("frameNumber", timePointNumber);
        res.put("sizeZC", JSONUtils.toJSONArray(sizeZC));
        res.put("invertTZ", invertTZ);
        if (bounds!=null) res.put("bounds", bounds.toJSONEntry());
        res.put("singleFrameC", JSONUtils.toJSONArray(singleFrameC));
        if (timePointCZT!=null) res.put("timePointCZT", JSONUtils.toJSONObject(timePointCZT));
        return res;
    }

    @Override
    public void initFromJSONEntry(Object jsonEntry) {
        JSONObject jsonO = (JSONObject)jsonEntry;
        scaleXY = ((Number)jsonO.get("scaleXY")).doubleValue();
        scaleZ = ((Number)jsonO.get("scaleZ")).doubleValue();
        filePathC = JSONUtils.fromStringArray((JSONArray)jsonO.get("filePathC"));
        name = (String)jsonO.get("name");
        timePointNumber = ((Number)jsonO.get("frameNumber")).intValue();
        sizeZC = JSONUtils.fromIntArray((JSONArray)jsonO.get("sizeZC"));
        invertTZ = (Boolean)jsonO.getOrDefault("invertTZ", false);
        if (jsonO.containsKey("bounds")) {
            bounds = new MutableBoundingBox();
            bounds.initFromJSONEntry(jsonO.get(("bounds")));
        }
        singleFrameC = JSONUtils.fromBooleanArray((JSONArray)jsonO.get("singleFrameC"));
        timePointCZT = (Map<String, Double>)jsonO.get("timePointCZT");
    }
    protected MultipleImageContainerChannelSerie() {super(1, 1);} // only for JSON initialization
    
    public MultipleImageContainerChannelSerie(String name, String[] imagePathC, int frameNumber, boolean[] singleFrameC, int[] sizeZC, double scaleXY, double scaleZ, boolean invertTZ) {
        this(name, imagePathC, frameNumber, singleFrameC, sizeZC, scaleXY, scaleZ,invertTZ, null);
    }
    public MultipleImageContainerChannelSerie(String name, String[] imagePathC, int frameNumber, boolean[] singleFrameC, int[] sizeZC, double scaleXY, double scaleZ, boolean invertTZ, Map<String, Double> timePointCZT) {
        super(scaleXY, scaleZ);
        this.name = name;
        filePathC = imagePathC;
        this.singleFrameC = singleFrameC;
        this.timePointNumber=frameNumber;
        this.reader=new ImageReader[imagePathC.length];
        this.singleFrameImages = new Image[imagePathC.length];
        this.invertTZ=invertTZ;
        this.sizeZC= sizeZC;
        if (timePointCZT!=null && !timePointCZT.isEmpty()) this.timePointCZT = new HashMap<>(timePointCZT);
        else initTimePointMap();
    }

    private void initTimePointMap() {
        timePointCZT = new HashMap<>();
        for (int c = 0; c<filePathC.length; ++c) {
            ImageReader r = this.getReader(c);
            if (r==null) continue;
            for (int z = 0; z<sizeZC[c]; ++z) {
                for (int t = 0; t<timePointNumber; ++t) {
                    double tp = r.getTimePoint(0, t, z);
                    if (!Double.isNaN(tp)) timePointCZT.put(getKey(c, z, t), tp);
                }
            }
        }
        //logger.debug("tpMap: {}", timePointCZT);
    }
    
    
    @Override public MultipleImageContainerChannelSerie duplicate() {
        return new MultipleImageContainerChannelSerie(name, filePathC, timePointNumber, singleFrameC, sizeZC, scaleXY, scaleZ, invertTZ, timePointCZT);
    }
    
    @Override public double getCalibratedTimePoint(int t, int c, int z) {
        if (timePointCZT==null) {
            synchronized(this) {
                if (timePointCZT==null) initTimePointMap();
            }
        }
        if (timePointCZT.isEmpty()) return Double.NaN;
        String key = getKey(c, z, t);
        Double d = timePointCZT.get(key);
        if (d!=null) return d;
        else return Double.NaN;
    }
    
    public void setImagePath(String[] path) {
        this.filePathC=path;
        this.reader=new ImageReader[filePathC.length];
    }
    
    public String[] getFilePath(){return filePathC;}
    
    @Override public String getName(){return name;}

    @Override 
    public int getFrameNumber() {
        return timePointNumber;
    }
    @Override 
    public int getChannelNumber() {
        return filePathC!=null?filePathC.length:0;
    }
    
    @Override
    public boolean singleFrame(int channel) {
        return singleFrameC!=null? this.singleFrameC[channel] : false;
    }
    
    @Override
    public int getSizeZ(int channelNumber) {
        //if (sizeZC==null) sizeZC = new int[filePathC.length]; // temporary, for retrocompatibility
        //if (sizeZC[channelNumber]==0) sizeZC[channelNumber] = getReader(channelNumber).getSTCXYZNumbers()[0][4]; // temporary, for retrocompatibility
        return sizeZC[channelNumber];
    }
    
    protected ImageIOCoordinates getImageIOCoordinates(int timePoint) {
        return new ImageIOCoordinates(0, 0, timePoint);
    }
    
    protected synchronized ImageReader getReader(int channelIdx) {
        if (getImageReaders()[channelIdx]==null) {
            synchronized(this) {
                if (getImageReaders()[channelIdx]==null) {
                    reader[channelIdx] = new ImageReader(filePathC[channelIdx]);
                    if (invertTZ) reader[channelIdx].setInvertTZ(true);
                    logger.debug("invert TZ: {}", invertTZ);
                }
            }
        }
        return reader[channelIdx];
    }
    
    protected ImageReader[] getImageReaders() {
        if (reader==null) {
            synchronized(this) {
                if (reader==null) reader=new ImageReader[filePathC.length];
            }
        }
        return reader;
    }
    
    @Override
    public Image getImage(int timePoint, int channel) {
        if (singleFrame(channel)) timePoint=0;
        ImageIOCoordinates ioCoordinates = getImageIOCoordinates(timePoint);
        if (bounds!=null) ioCoordinates.setBounds(bounds);
        if (singleFrame(channel)) {
            if (singleFrameImages==null) {
                synchronized(this) {
                    if (singleFrameImages==null) singleFrameImages = new Image[filePathC.length];
                }
            }
            if (singleFrameImages[channel]==null) {
                synchronized(singleFrameImages) {
                    if (singleFrameImages[channel]==null) singleFrameImages[channel] = getReader(channel).openImage(ioCoordinates);
                }
            }
            return singleFrameImages[channel];
        } else {
            ImageReader r= getReader(channel);
            synchronized(r) {
                Image image = getReader(channel).openImage(ioCoordinates);
                return image;
            }
        }
    }
    
    @Override
    public synchronized Image getImage(int timePoint, int channel, MutableBoundingBox bounds) {
        
        if (this.timePointNumber==1) timePoint=0;
        ImageIOCoordinates ioCoordinates = getImageIOCoordinates(timePoint);
        ImageIOCoordinates ioCoords = ioCoordinates.duplicate();
        ioCoords.setBounds(bounds);
        Image image = getReader(channel).openImage(ioCoordinates);
        /*if (scaleXY!=0 && scaleZ!=0) image.setCalibration((float)scaleXY, (float)scaleZ);
        else {
            scaleXY = image.getScaleXY();
            scaleZ = image.getScaleZ();
        }*/
        return image;
    }
    
    @Override
    public void flush() {
        for (int i = 0; i<this.getChannelNumber(); ++i) {
            if (getImageReaders()[i]!=null) reader[i].closeReader();
            reader [i] = null;
            if (singleFrameImages!=null) singleFrameImages[i]=null;
        }
    }

    

    
}
