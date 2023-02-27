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

import bacmman.image.BoundingBox;
import bacmman.image.Image;
import bacmman.image.SimpleBoundingBox;
import bacmman.image.io.ImageIOCoordinates;
import bacmman.image.io.ImageReader;
import bacmman.image.io.ImageReaderFile;
import java.io.File;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

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
    String inputDir, extension, positionKey, positionName, timeKeyword;
    int frameNumber;
    int nChannels;
    String[] channelKeywords;
    int[] sizeZC;
    List<List<String>> fileCT;
    Map<String, Double> timePointCZT;
    boolean fromOmero;
    @Override
    public boolean sameContent(MultipleImageContainer other) {
        if (other instanceof MultipleImageContainerPositionChannelFrame) {
            MultipleImageContainerPositionChannelFrame otherM = (MultipleImageContainerPositionChannelFrame)other;
            if (otherM.fromOmero()!=fromOmero()) return false;
            if (fromOmero()) {
                if (nChannels!=otherM.nChannels) return false;
                if (!fileCT.equals(otherM.fileCT)) return false;
            } else {
                if (!inputDir.equals(otherM.inputDir)) return false;
                if (!extension.equals(otherM.extension)) return false;
                if (!positionKey.equals(otherM.positionKey)) return false;
                if (!timeKeyword.equals(otherM.timeKeyword)) return false;
                if (!Arrays.deepEquals(channelKeywords, otherM.channelKeywords)) return false;
            }
            if (scaleXY!=otherM.scaleXY) return false;
            if (scaleZ!=otherM.scaleZ) return false;
            if (!positionName.equals(otherM.positionName)) return false;
            if (frameNumber!=otherM.frameNumber) return false;
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
        if (fromOmero()) {
            res.put("fromOmero", true);
            res.put("channelNumber", fileCT.size());
            for (int c = 0; c<fileCT.size(); ++c) res.put("omeroID_c"+c, JSONUtils.toJSONArrayString(fileCT.get(c)));
        } else {
            res.put("inputDir", relativePath(inputDir));
            res.put("extension", extension);
            res.put("positionKey", positionKey);
            res.put("timeKeyword", timeKeyword);
            res.put("channelKeywords", JSONUtils.toJSONArray(channelKeywords));
        }

        res.put("positionName", positionName);
        res.put("frameNumber", frameNumber);
        res.put("sizeZC", JSONUtils.toJSONArray(sizeZC));
        if (timePointCZT!=null) res.put("timePointCZT", JSONUtils.toJSONObject(timePointCZT));
        return res;
    }

    @Override
    public void initFromJSONEntry(Object jsonEntry) {
        JSONObject jsonO = (JSONObject)jsonEntry;
        scaleXY = ((Number)jsonO.get("scaleXY")).doubleValue();
        scaleZ = ((Number)jsonO.get("scaleZ")).doubleValue();
        fromOmero = jsonO.containsKey("fromOmero") ? (Boolean)jsonO.get("fromOmero") : false;
        if (fromOmero) {
            nChannels = ((Number)jsonO.get("channelNumber")).intValue();
            fileCT = new ArrayList<>(nChannels);
            for (int c = 0; c < nChannels; ++c) fileCT.add(Arrays.asList(JSONUtils.fromStringArray((List) jsonO.get("omeroID_c" + c))));
        } else {
            inputDir = absolutePath((String)jsonO.get("inputDir"));
            extension = (String)jsonO.get("extension");
            positionKey = (String)jsonO.get("positionKey");
            timeKeyword = (String)jsonO.get("timeKeyword");
            channelKeywords = JSONUtils.fromStringArray((JSONArray)jsonO.get("channelKeywords"));
            nChannels = channelKeywords.length;
        }
        frameNumber = ((Number)jsonO.get("frameNumber")).intValue();
        sizeZC = JSONUtils.fromIntArray((JSONArray)jsonO.get("sizeZC"));
        if (jsonO.containsKey("timePointCZT")) timePointCZT = (Map<String, Double>)jsonO.get("timePointCZT");
        positionName =  jsonO.containsKey("positionName") ?  (String)jsonO.get("positionName") : positionKey;
        //else initTimePointMap();         
    }
    protected MultipleImageContainerPositionChannelFrame() {super(1, 1);} // JSON init
    public MultipleImageContainerPositionChannelFrame(String inputDir, String extension, String positionKey, String timeKeyword, String[] channelKeywords, int[] sizeZC, int frameNumber, double scaleXY, double scaleZ, String positionName) {
        super(scaleXY, scaleZ);
        this.inputDir = inputDir;
        this.extension = extension;
        this.positionKey = positionKey;
        this.positionName = positionName == null ? positionKey : positionName;
        this.channelKeywords = channelKeywords;
        this.nChannels = channelKeywords.length;
        this.timeKeyword = timeKeyword;
        this.frameNumber = frameNumber;
        createFileMap();
        if (sizeZC==null) {
            int maxZ = -1;
            this.sizeZC = new int[nChannels];
            for (int channelNumber=0; channelNumber<this.sizeZC.length; ++channelNumber) {
                Pair<int[][], double[]> info = ImageReaderFile.getImageInfo(fileCT.get(channelNumber).get(0));
                this.sizeZC[channelNumber] = info.key[0][4];
                if (scaleXY<=0 && this.sizeZC[channelNumber]>maxZ) {
                    maxZ =  this.sizeZC[channelNumber];
                    this.scaleXY = info.value[0];
                    this.scaleZ = info.value[2];
                    logger.debug("pos: {}, scale: xy:{}; z:{}", this.positionName, this.scaleXY,this.scaleZ );
                }
            }
        } else this.sizeZC=sizeZC;
        logger.debug("sizeZC: {}", this.sizeZC);
        if (this.scaleXY<=0) {
            logger.debug("fetching scale...");
            ImageReaderFile reader = new ImageReaderFile(fileCT.get( ArrayUtil.max(this.sizeZC)).get(0));
            double[] sXYZ = reader.getScaleXYZ(1);
            this.scaleXY = sXYZ[0];
            this.scaleZ = sXYZ[2];
            reader.closeReader();
            Pair<int[][], double[]> info = ImageReaderFile.getImageInfo(fileCT.get(ArrayUtil.max(this.sizeZC)).get(0));
            this.scaleXY = info.value[0];
            this.scaleZ = info.value[2];
            logger.debug("scale: xy={} z={}", this.scaleXY, this.scaleZ);
        }
        initTimePointMap();
        
    }
    public MultipleImageContainerPositionChannelFrame(String inputDir, String extension, String positionKey, String timeKeyword, String[] channelKeywords, int frameNumber, String positionName) {
        this(inputDir, extension, positionKey, timeKeyword, channelKeywords, null, frameNumber, -1, -1, positionName);
    }
    public MultipleImageContainerPositionChannelFrame(List<List<String>> fileIDsCT, int frameNumber, double scaleXY, double scaleZ, String positionName) {
        super(scaleXY, scaleZ);
        this.fileCT = fileIDsCT;
        this.nChannels = fileCT.size();
        this.positionName = positionName;
        this.frameNumber = frameNumber;
        this.scaleXY = scaleXY;
        this.scaleZ=scaleZ;
    }
    public boolean fromOmero() {
        return fromOmero;
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
            for (int c = 0; c<this.nChannels; ++c) {
                logger.debug("init timepoint map for channel: {}", c);
                for (int f = 0; f<fileCT.get(c).size(); ++f) {
                    try {
                        ImageReader r = getReader(c, f);
                        if (r == null) return;
                        for (int z = 0; z < sizeZC[c]; ++z) {
                            double res = r.getTimePoint(0, 0, z);
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
                        return;
                    }
                }
            }
        }
    }
    protected long getImageID(int c, int f) {
        return Long.parseLong(fileCT.get(c).get(f));
    }
    protected ImageReader getReader(int c, int f) {
        if (fromOmero()) return omeroGateway==null ? null : omeroGateway.createReader(getImageID(c, f));
        else return new ImageReaderFile(fileCT.get(c).get(f));
    }

    @Override
    public int getFrameNumber() {
        return frameNumber;
    }

    @Override
    public int getChannelNumber() {
        return nChannels;
    }

    @Override
    public int getSizeZ(int channelNumber) { 
        return sizeZC[channelNumber];
    }

    @Override
    public synchronized Image getImage(int frame, int channel) { // synchronized if use of buffer
        if (fromOmero()) {
            ImageReader r = getReader(channel, frame);
            Image i = r.openImage(new ImageIOCoordinates());
            r.closeReader();
            return i;
        } else {
            if (fileCT == null) {
                //synchronized(this) {
                if (fileCT == null) createFileMap();
                //}
            }
            return ImageReaderFile.openImage(fileCT.get(channel).get(frame), new ImageIOCoordinates(), bufferStore);
        }
    }
    
    
    
    @Override
    public Image getImage(int frame, int channel, BoundingBox bounds) {
        ImageIOCoordinates coords = new ImageIOCoordinates(0, 0, 0, bounds);
        if (fromOmero()) {
            ImageReader r = getReader(channel, frame);
            Image i = r.openImage(coords);
            r.closeReader();
            return i;
        }
        if (fileCT==null) {
            synchronized(this) {
                if (fileCT==null) createFileMap();
            }
        }
        return ImageReaderFile.openImage(fileCT.get(channel).get(frame), coords, bufferStore);
    }

    @Override
    public Image getPlane(int z, int timePoint, int channel) {
        return getImage(timePoint, channel, new SimpleBoundingBox(0, -1, 0, -1, z, z));
    }

    @Override
    public void flush() {
        fileCT=null;
        bufferStore[0] = null;
    }

    @Override
    public String getName() {
        return positionName;
    }

    @Override
    public MultipleImageContainer duplicate() {
        if (fromOmero()) {
            MultipleImageContainer res = new MultipleImageContainerPositionChannelFrame(fileCT, frameNumber, scaleXY, scaleZ, positionName);
            res.setOmeroGateway(omeroGateway);
            return res;
        } else return new MultipleImageContainerPositionChannelFrame(inputDir, extension, positionKey, timeKeyword, ArrayUtil.duplicate(channelKeywords), ArrayUtil.duplicate(sizeZC), frameNumber, scaleXY, scaleZ, positionName);
    }
    
    private void createFileMap() {
        File in = new File(inputDir);
        Pattern positionPattern = positionKey==null || positionKey.length()==0 ? Pattern.compile("[\\."+extension+"]$") : Pattern.compile(".*"+positionKey+".*[\\."+extension+"]$");
        File[] allImages = in.listFiles((f, name) -> name.charAt(0)!='.' ); //(f, name) -> !isIgnoredFile(name)
        if (allImages==null) throw new RuntimeException("No Images found in directory:"+in.getAbsolutePath());
        List<File> files = Arrays.stream(allImages).filter( f -> positionPattern.matcher(f.getName()).find()).collect(Collectors.toList());
        Pattern timePattern = timeKeyword!=null && timeKeyword.length()>0 ? Pattern.compile(".*"+timeKeyword+"(\\d+).*") : null;
        Map<Integer, List<File>> filesByChannel = files.stream().collect(Collectors.groupingBy(f -> getKeywordIdx(f.getName(), channelKeywords)));
        fileCT = new ArrayList<>(filesByChannel.size());
        filesByChannel.entrySet().stream().sorted(Comparator.comparingInt(Map.Entry::getKey)).forEach((channelFiles) -> {
            Map<Integer, String> filesByTimePoint;
            if (timePattern!=null) filesByTimePoint = channelFiles.getValue().stream().collect(Collectors.toMap(f -> get(f.getName(), timePattern), File::getAbsolutePath));
            else {
                Comparator<File> fileComp = Comparator.comparing(File::getName);
                List<File> sortedFiles = channelFiles.getValue().stream().sorted(fileComp).collect(Collectors.toList());
                filesByTimePoint = IntStream.range(0, sortedFiles.size()).boxed().collect(Collectors.toMap(i->i, i->sortedFiles.get(i).getAbsolutePath()));
            }
            fileCT.add(new ArrayList<>(new TreeMap(filesByTimePoint).values()).subList(0, frameNumber));
        });
        logger.debug("Position: {}, channels: {}, tp: {}", getName(), fileCT.size(), fileCT.get(0).size());
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
    public static String getAsStringBeforeMatch(String s, Pattern p) {
        Matcher m = p.matcher(s);
        if (m.find()) {
            int end = s.length();
            for (int i = 0; i<m.groupCount(); ++i) {
                if (m.start(i)>0 && m.start(i)<end) end = m.start(i);
            }
            return end>0 ? s.substring(0, end) : "";
        }
        else return "";
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
