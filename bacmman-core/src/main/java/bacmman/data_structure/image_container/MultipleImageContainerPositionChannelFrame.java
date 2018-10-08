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

import static bacmman.data_structure.Processor.logger;
import bacmman.image.MutableBoundingBox;
import bacmman.image.Image;
import bacmman.image.io.ImageIOCoordinates;
import bacmman.image.io.ImageReader;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import bacmman.utils.ArrayUtil;
import bacmman.utils.JSONUtils;
import bacmman.utils.Pair;

/**
 *
 * @author Jean Ollion
 */
public class MultipleImageContainerPositionChannelFrame extends MultipleImageContainer { // one file per channel & per frame
    byte[][] bufferStore = new byte[1][];
    String inputDir, extension, positionKey, timeKeyword;
    int frameNumber;
    String[] channelKeywords;
    int[] sizeZC;
    List<List<String>> fileCT;
    Map<String, Double> timePointCZT;
    @Override
    public boolean sameContent(MultipleImageContainer other) {
        if (other instanceof MultipleImageContainerPositionChannelFrame) {
            MultipleImageContainerPositionChannelFrame otherM = (MultipleImageContainerPositionChannelFrame)other;
            if (scaleXY!=otherM.scaleXY) return false;
            if (scaleZ!=otherM.scaleZ) return false;
            if (!inputDir.equals(otherM.inputDir)) return false;
            if (!extension.equals(otherM.extension)) return false;
            if (!positionKey.equals(otherM.positionKey)) return false;
            if (!timeKeyword.equals(otherM.timeKeyword)) return false;
            if (frameNumber!=otherM.frameNumber) return false;
            if (!Arrays.deepEquals(channelKeywords, otherM.channelKeywords)) return false;
            if (!Arrays.equals(sizeZC, otherM.sizeZC)) return false;
            if (timePointCZT!=null || otherM.timePointCZT!=null) {
                if (timePointCZT==null && otherM.timePointCZT!=null) return false;
                if (timePointCZT!=null && otherM.timePointCZT==null) return false;
                if (!timePointCZT.equals(otherM.timePointCZT)) return false;
            }
            return true;
        } else return false;
    }
    
    @Override
    public Object toJSONEntry() {
        JSONObject res = new JSONObject();
        res.put("scaleXY", scaleXY);
        res.put("scaleZ", scaleZ);
        res.put("inputDir", inputDir);
        res.put("extension", extension);
        res.put("positionKey", positionKey);
        res.put("timeKeyword", timeKeyword);
        res.put("frameNumber", frameNumber);
        res.put("channelKeywords", JSONUtils.toJSONArray(channelKeywords));
        res.put("sizeZC", JSONUtils.toJSONArray(sizeZC));
        if (timePointCZT!=null) res.put("timePointCZT", JSONUtils.toJSONObject(timePointCZT));
        return res;
    }

    @Override
    public void initFromJSONEntry(Object jsonEntry) {
        JSONObject jsonO = (JSONObject)jsonEntry;
        scaleXY = ((Number)jsonO.get("scaleXY")).doubleValue();
        scaleZ = ((Number)jsonO.get("scaleZ")).doubleValue();
        inputDir = (String)jsonO.get("inputDir");
        extension = (String)jsonO.get("extension");
        positionKey = (String)jsonO.get("positionKey");
        timeKeyword = (String)jsonO.get("timeKeyword");
        frameNumber = ((Number)jsonO.get("frameNumber")).intValue();
        channelKeywords = JSONUtils.fromStringArray((JSONArray)jsonO.get("channelKeywords"));
        sizeZC = JSONUtils.fromIntArray((JSONArray)jsonO.get("sizeZC"));
        if (jsonO.containsKey("timePointCZT")) timePointCZT = (Map<String, Double>)jsonO.get("timePointCZT");
        //else initTimePointMap();         
    }
    protected MultipleImageContainerPositionChannelFrame() {super(1, 1);} // JSON init
    public MultipleImageContainerPositionChannelFrame(String inputDir, String extension, String positionKey, String timeKeyword, String[] channelKeywords, int[] sizeZC, int frameNumber, double scaleXY, double scaleZ) {
        super(scaleXY, scaleZ);
        this.inputDir = inputDir;
        this.extension = extension;
        this.positionKey = positionKey;
        this.channelKeywords = channelKeywords;
        this.timeKeyword = timeKeyword;
        this.frameNumber = frameNumber;
        createFileMap();
        if (sizeZC==null) {
            int maxZ = -1;
            this.sizeZC = new int[channelKeywords.length]; 
            for (int channelNumber=0; channelNumber<this.sizeZC.length; ++channelNumber) {
                Pair<int[][], double[]> info = ImageReader.getImageInfo(fileCT.get(channelNumber).get(0));
                this.sizeZC[channelNumber] = info.key[0][4];
                if (scaleXY<=0 && this.sizeZC[channelNumber]>maxZ) {
                    maxZ =  this.sizeZC[channelNumber];
                    this.scaleXY = info.value[0];
                    this.scaleZ = info.value[2];
                    logger.debug("pos: {}, scale: xy:{}; z:{}", this.positionKey, this.scaleXY,this.scaleZ );
                }
            }
        } else this.sizeZC=sizeZC;
        logger.debug("sizeZC: {}", this.sizeZC);
        if (this.scaleXY<=0) {
            ImageReader reader = new ImageReader(fileCT.get( ArrayUtil.max(this.sizeZC)).get(0));
            double[] sXYZ = reader.getScaleXYZ(1);
            this.scaleXY = sXYZ[0];
            this.scaleZ = sXYZ[2];
            reader.closeReader();
            Pair<int[][], double[]> info = ImageReader.getImageInfo(fileCT.get(ArrayUtil.max(this.sizeZC)).get(0));
            this.scaleXY = info.value[0];
            this.scaleZ = info.value[2];
        }
        initTimePointMap();
        
    }
    public MultipleImageContainerPositionChannelFrame(String inputDir, String extension, String positionKey, String timeKeyword, String[] channelKeywords, int frameNumber) {
        this(inputDir, extension, positionKey, timeKeyword, channelKeywords, null, frameNumber, -1, -1);
    }
    
    @Override public double getCalibratedTimePoint(int t, int c, int z) {
        if (timePointCZT==null) {
            synchronized(this) {
                if (timePointCZT==null) initTimePointMap();
            }
        }
        if (timePointCZT==null) return Double.NaN;
        if (timePointCZT.isEmpty()) return Double.NaN;
        String key = getKey(c, z, t);
        Double d = timePointCZT.get(key);
        if (d!=null) return d;
        else return Double.NaN;
    }
    
    private void initTimePointMap() {
        if (fileCT==null) {
            synchronized(this) {
                if (fileCT==null) createFileMap();
            }
        }
        timePointCZT = new HashMap<>();
        synchronized(this) {
            for (int c = 0; c<this.channelKeywords.length; ++c) {
                for (int f = 0; f<fileCT.get(c).size(); ++f) {
                    try {
                        ImageReader r = new ImageReader(fileCT.get(c).get(f));
                        if (r==null) return;
                        for (int z = 0; z<sizeZC[c]; ++z) {
                            double res= r.getTimePoint(0, 0, z);
                            if (!Double.isNaN(res)) timePointCZT.put(getKey(c, z, f), res);
                            else {
                                timePointCZT.clear();
                                logger.error("time point information not found in file: {}", f);
                                r.closeReader();
                                return;
                            }
                        }
                        r.closeReader();
                    } catch(Exception|Error e) {
                        /*if (fileCT.get(c).get(f).endsWith(".tif")) { // use frame interval
                            timePointCZT.clear();
                            double frameInterval = ImageReader.getTIFTimeFrameInterval(fileCT.get(0).get(0));
                            if (!Double.isNaN(frameInterval)) {
                                for (int cc = 0; cc<this.channelKeywords.length; ++cc) {
                                    double time=0;
                                    for (int ff = 0; ff<fileCT.get(cc).size(); ++ff) {
                                        for (int z = 0; z<sizeZC[c]; ++z) timePointCZT.put(getKey(c, z, f), time);
                                        time+=frameInterval;
                                    }
                                }
                            }
                        }*/
                        return;
                    }
                }
            }
        }
    }
    
    @Override
    public int getFrameNumber() {
        return frameNumber;
    }

    @Override
    public int getChannelNumber() {
        return channelKeywords.length;
    }

    @Override
    public int getSizeZ(int channelNumber) { 
        return sizeZC[channelNumber];
    }

    @Override
    public synchronized Image getImage(int frame, int channel) { // synchronized if use of buffer
        if (fileCT==null) {
            //synchronized(this) {
                if (fileCT==null) createFileMap();
            //}
        }
        return ImageReader.openImage(fileCT.get(channel).get(frame), new ImageIOCoordinates(), bufferStore);
    }
    
    
    
    @Override
    public Image getImage(int frame, int channel, MutableBoundingBox bounds) {
        if (fileCT==null) {
            synchronized(this) {
                if (fileCT==null) createFileMap();
            }
        }
        return ImageReader.openImage(fileCT.get(channel).get(frame), new ImageIOCoordinates(0, 0, 0, bounds), bufferStore);
    }

    @Override
    public void flush() {
        fileCT=null;
        bufferStore[0] = null;
    }

    @Override
    public String getName() {
        return positionKey;
    }

    @Override
    public MultipleImageContainer duplicate() {
        return new MultipleImageContainerPositionChannelFrame(inputDir, extension, positionKey, timeKeyword, ArrayUtil.duplicate(channelKeywords), ArrayUtil.duplicate(sizeZC), frameNumber, scaleXY, scaleZ);
    }
    
    private void createFileMap() {
        File in = new File(inputDir);
        Pattern positionPattern = Pattern.compile(".*"+positionKey+".*"+extension);
        File[] allImages = in.listFiles();
        if (allImages==null) throw new RuntimeException("No Images found in directory:"+in.getAbsolutePath());
        List<File> files = Arrays.stream(allImages).filter( f -> positionPattern.matcher(f.getName()).find()).collect(Collectors.toList());
        Pattern timePattern = Pattern.compile(".*"+timeKeyword+"(\\d+).*");
        Map<Integer, List<File>> filesByChannel = files.stream().collect(Collectors.groupingBy(f -> getKeywordIdx(f.getName(), channelKeywords)));
        fileCT = new ArrayList<>(filesByChannel.size());
        filesByChannel.entrySet().stream().sorted((n1, n2)->Integer.compare(n1.getKey(), n2.getKey())).forEach((channelFiles) -> {
            Map<Integer, String> filesByTimePoint = channelFiles.getValue().stream().collect(Collectors.toMap(f -> get(f.getName(), timePattern), f -> f.getAbsolutePath()));
            fileCT.add(new ArrayList<>(new TreeMap(filesByTimePoint).values()).subList(0, frameNumber));
        });
    }
    
    public static int getCommomStartIndex(List<String> names) {
        int startIndex = 0;
        String baseName = names.get(0);
        WL : while(startIndex<baseName.length()) {
            char currentChar = baseName.charAt(startIndex);
            for (String f : names) {
                if (f.charAt(startIndex)!=currentChar)  break WL;
            }
            ++startIndex;
        }
        return startIndex;
    }
    public static Integer get(String s, Pattern p) {
        Matcher m = p.matcher(s);
        if (m.find()) return Integer.parseInt(m.group(1));
        else return null;
    }
    public static String getAsString(String s, Pattern p) {
        Matcher m = p.matcher(s);
        if (m.find()) return m.group(1);
        else return null;
    }
    public static String getKeyword(String s, String[] keywords, String defaultValue) {
        for (String k : keywords) if (s.contains(k)) return k;
        return defaultValue;
    }
    public static int getKeywordIdx(String s, String[] keywords) {
        for (int i = 0; i<keywords.length; ++i) if (s.contains(keywords[i])) return i;
        return -1;
    }

    @Override
    public boolean singleFrame(int channel) {
        return false;
    }
}
