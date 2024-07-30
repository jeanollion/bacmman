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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import bacmman.utils.*;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

/**
 *
 * @author Jean Ollion
 */
public class MultipleImageContainerPositionChannelFrame extends MultipleImageContainer { // one file per channel & per frame
    final SynchronizedPool<byte[][]> bufferPool = new SynchronizedPool<>(() -> new byte[1][], null);
    String inputDir, extension, positionKey, positionName, timeKeyword;
    int frameNumber;
    int nChannels;
    String[] channelKeywords;
    boolean[] singleFrameC;
    int[] sizeZC;
    Map<String, Boolean> invertTZ_CT;
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
            if (singleFrameC!=null && otherM.singleFrameC!=null) {
                if (!Arrays.equals(singleFrameC, otherM.singleFrameC)) return false;
            }
            return true;
        } else return false;
    }

    @Override
    public boolean isEmpty() {
        try {
            if (fromOmero()) {
                if (!omeroGateway.isConnected()) return false; // do not try to connect
                return !getReader(0, 0).imageExists(); // if omero gateway is connected check if file exists
            } else {
                List<List<String>> fm = getFileMap();
                return fm.isEmpty() || fm.get(0).isEmpty() || !Files.exists(Paths.get(fm.get(0).get(0))); // just check if file exists, do not open reader as this can take time
            }
        } catch (IOException e) {
            return true;
        }
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
        if (invertTZ_CT!=null) res.put("invertTZ_CT", JSONUtils.toJSONObject(invertTZ_CT));
        if (singleFrameC!=null) res.put("singleFrameC", JSONUtils.toJSONArray(singleFrameC));
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
        if (jsonO.containsKey("invertTZ_CT")) invertTZ_CT = (Map<String, Boolean>)jsonO.get("invertTZ_CT");
        positionName =  jsonO.containsKey("positionName") ?  (String)jsonO.get("positionName") : positionKey;
        if (jsonO.containsKey("singleFrameC")) singleFrameC = JSONUtils.fromBooleanArray((JSONArray)jsonO.get("singleFrameC"));
    }
    protected MultipleImageContainerPositionChannelFrame() {super(1, 1);} // JSON init
    public MultipleImageContainerPositionChannelFrame(String inputDir, String extension, String positionKey, String timeKeyword, String[] channelKeywords, int[] sizeZC, int frameNumber, double scaleXY, double scaleZ, String positionName) throws IOException {
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
        singleFrameC = new boolean[nChannels];
        for (int i = 0; i<nChannels; ++i) singleFrameC[i] = isSingleFrame(i);

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
    public MultipleImageContainerPositionChannelFrame(String inputDir, String extension, String positionKey, String timeKeyword, String[] channelKeywords, int frameNumber, String positionName) throws IOException {
        this(inputDir, extension, positionKey, timeKeyword, channelKeywords, null, frameNumber, -1, -1, positionName);
    }

    // omero constructor
    public MultipleImageContainerPositionChannelFrame(List<List<String>> fileIDsCT, int frameNumber, Map<String, Boolean> invertTZ_CT, int[] sizeZC, double scaleXY, double scaleZ, String positionName) {
        super(scaleXY, scaleZ);
        this.fileCT = fileIDsCT;
        this.nChannels = fileCT.size();
        this.positionName = positionName;
        this.frameNumber = frameNumber;
        this.scaleXY = scaleXY;
        this.scaleZ=scaleZ;
        this.fromOmero = true;
        this.sizeZC = sizeZC;
        this.invertTZ_CT = invertTZ_CT;
        singleFrameC = new boolean[nChannels];
        for (int i = 0; i<nChannels; ++i) {
            try {
                singleFrameC[i] = isSingleFrame(i);
            } catch (IOException ignored) {}
        }
    }
    public boolean fromOmero() {
        return fromOmero;
    }
    @Override public double getCalibratedTimePoint(int t, int c, int z) {
        if (timePointCZT==null) {
            synchronized(this) {
                if (timePointCZT==null) {
                    if (fromOmero()) initTimePointMapOmero();
                    else {
                        try {
                            initTimePointMap();
                        } catch (IOException ignored) {}
                    }
                }
            }
        }
        if (timePointCZT==null) return Double.NaN;
        if (timePointCZT.isEmpty()) return Double.NaN;
        String key = getKey(c, z, t);
        Double d = timePointCZT.get(key);
        if (d!=null) return d;
        else return Double.NaN;
    }

    private void initTimePointMapOmero() {
        if (!fromOmero()) return;
        timePointCZT = new HashMap<>();
        synchronized(this) {
            for (int c = 0; c<this.nChannels; ++c) {
                logger.debug("init timepoint map for channel: {} (omero)", c);
                long ref = 0;
                for (int f = 0; f < fileCT.get(c).size(); ++f) {
                    try {
                        String filename = positionName + "_c"+c+"_t"+f+".txt";
                        File file = Paths.get(path.toAbsolutePath().toString(), "SourceImageMetadata", filename).toFile();
                        if (!file.exists()) { // TODO: otherwise fetch from server
                            continue;
                        }
                        List<UnaryPair<String>> entries = FileIO.readFromFile(file.getPath(), line -> {
                            if (!line.contains("=")) return null;
                            String[] p = line.split("=");
                            if (p.length!=2) return null;
                            return new UnaryPair<>(p[0], p[1]);
                        }, null);
                        List<Long> timestamps = entries.stream().filter(Objects::nonNull).filter(p -> p.key.startsWith("timestamp")).sorted(Comparator.comparing(p->p.key)).map(p -> p.value).map(Long::parseLong).collect(Collectors.toList());
                        if (timestamps.size() == sizeZC[c]) {
                            for (int z = 0; z < sizeZC[c]; ++z) {
                                long t = timestamps.get(0);
                                if (this.frameNumber>1 && !singleFrame(c)) {
                                    if (f==0) {
                                        ref = t;
                                        t=0;
                                    } else t-= ref; // relative time
                                }
                                timePointCZT.put(getKey(c, z, f), (double)t);
                            }
                        } else logger.debug("invalid timestamp number for channel: {} -> {} number of z: {}", c, timestamps.size(), sizeZC[c]);
                    } catch (Exception | Error e) {
                        logger.debug("error init timepoint", e);
                        return;
                    }
                }
            }
        }
    }

    private void initTimePointMap() throws IOException {
        if (fromOmero()) return;
        timePointCZT = new HashMap<>();
        synchronized(this) {
            for (int c = 0; c<this.nChannels; ++c) {
                logger.debug("init timepoint map for channel: {}", c);
                for (int f = 0; f<getFileMap().get(c).size(); ++f) {
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
    public long getImageID(int c, int f) {
        if (singleFrame(c)) f = 0;
        return Long.parseLong(fileCT.get(c).get(f));
    }
    protected ImageReader getReader(int c, int f) throws IOException {
        if (singleFrame(c)) f = 0;
        if (fromOmero()) {
            ImageReader r = omeroGateway==null ? null : omeroGateway.createReader(getImageID(c, f));
            if (r==null) throw new IOException("Could not connect to Omero server");
            return r;
        } else return new ImageReaderFile(getFileMap().get(c).get(f));
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
    public Image getImage(int frame, int channel) throws IOException {
        if (singleFrame(channel)) frame = 0;
        boolean invertTZ = false;
        if (invertTZ_CT!=null) {
            Boolean inv = invertTZ_CT.get(getKeyCT(channel, frame));
            if (inv!=null) invertTZ = inv;
        }
        if (fromOmero()) {
            try {
                ImageReader r = getReader(channel, frame).setInvertTZ(invertTZ);
                Image i = r.openImage(new ImageIOCoordinates());
                r.closeReader();
                return i;
            } catch (Exception e) {
                throw new IOException(e);
            }
        } else {
            if (fileCT == null) {
                try {
                    getFileMap();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
            int f = frame;
            boolean inv = invertTZ;
            return bufferPool.apply( b -> ImageReaderFile.openImage(fileCT.get(channel).get(f), new ImageIOCoordinates(), inv, b) );
        }
    }
    
    
    
    @Override
    public Image getImage(int frame, int channel, BoundingBox bounds) throws IOException {
        if (singleFrame(channel)) frame = 0;
        ImageIOCoordinates coords = new ImageIOCoordinates(0, 0, 0, bounds);
        boolean invertTZ = false;
        if (invertTZ_CT!=null) {
            Boolean inv = invertTZ_CT.get(getKeyCT(channel, frame));
            if (inv!=null) invertTZ = inv;
        }
        if (fromOmero()) {
            ImageReader r = null;
            try {
                r = getReader(channel, frame).setInvertTZ(invertTZ);
                Image i = r.openImage(coords);
                return i;
            } finally {
                if (r!=null) r.closeReader();
            }
        } else {
            if (fileCT == null) getFileMap();
            int f = frame;
            boolean inv = invertTZ;
            return bufferPool.apply( b -> ImageReaderFile.openImage(fileCT.get(channel).get(f), coords, inv, b));
        }
    }

    @Override
    public Image getPlane(int z, int timePoint, int channel) throws IOException {
        return getImage(timePoint, channel, new SimpleBoundingBox(0, -1, 0, -1, z, z));
    }

    @Override
    public void flush() {
        if (!fromOmero()) {
            fileCT=null;
            bufferPool.flush();
        }
    }

    @Override
    public String getName() {
        return positionName;
    }

    @Override
    public MultipleImageContainer duplicate() {
        if (fromOmero()) {
            MultipleImageContainer res = new MultipleImageContainerPositionChannelFrame(fileCT, frameNumber, new HashMap<>(invertTZ_CT), ArrayUtil.duplicate(sizeZC), scaleXY, scaleZ, positionName);
            res.setOmeroGateway(omeroGateway);
            return res;
        } else { // avoid throwing exception when images are not present
            MultipleImageContainerPositionChannelFrame res = new MultipleImageContainerPositionChannelFrame();
            res.setPath(path);
            res.initFromJSONEntry(toJSONEntry());
            return res;
        }
    }
    private List<List<String>> getFileMap() throws IOException {
        if (!fromOmero()) {
            if (fileCT == null) {
                synchronized (this) {
                    if (fileCT == null) createFileMap();
                }
            }
        }
        return fileCT;
    }
    private void createFileMap() throws IOException {
        if (fromOmero()) return;
        File in = new File(inputDir);
        Pattern positionPattern = positionKey==null || positionKey.length()==0 ? Pattern.compile("[\\."+extension+"]$") : Pattern.compile(".*"+positionKey+".*[\\."+extension+"]$");
        File[] allImages = in.listFiles((f, name) -> name.charAt(0)!='.' ); //(f, name) -> !isIgnoredFile(name)
        if (allImages==null) throw new IOException("No Images found in directory:"+in.getAbsolutePath());
        List<File> files = Arrays.stream(allImages).filter( f -> positionPattern.matcher(f.getName()).find()).collect(Collectors.toList());
        Pattern timePattern = timeKeyword!=null && timeKeyword.length()>0 ? Pattern.compile(".*"+timeKeyword+"(\\d+).*") : null;
        Map<Integer, List<File>> filesByChannel = files.stream().collect(Collectors.groupingBy(f -> getKeywordIdx(f.getName(), channelKeywords)));
        fileCT = new ArrayList<>(filesByChannel.size());
        for (Map.Entry<Integer, List<File>> channelFiles : filesByChannel.entrySet().stream().sorted(Comparator.comparingInt(Map.Entry::getKey)).collect(Collectors.toList())) {
            Map<Integer, String> filesByTimePoint;
            if (timePattern!=null) filesByTimePoint = channelFiles.getValue().stream().collect(Collectors.toMap(f -> get(f.getName(), timePattern), File::getAbsolutePath));
            else {
                Comparator<File> fileComp = Comparator.comparing(File::getName);
                List<File> sortedFiles = channelFiles.getValue().stream().sorted(fileComp).collect(Collectors.toList());
                filesByTimePoint = IntStream.range(0, sortedFiles.size()).boxed().collect(Collectors.toMap(i->i, i->sortedFiles.get(i).getAbsolutePath()));
            }
            List<String> filenames = new ArrayList<>(new TreeMap<>(filesByTimePoint).values());
            if (filenames.size()>1) {
                if (filenames.size()<frameNumber) throw new IOException("Invalid file number for channel "+fileCT.size());
                filenames = filenames.subList(0, frameNumber);
            }
            fileCT.add(filenames);
        }
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
        if (singleFrameC==null) {
            try {
                return isSingleFrame(channel);
            } catch (IOException e) {
                return false; // default value when no raw input images are found. in new version, single frame information is stored in configuration so no need to access to raw images.
                //throw new RuntimeException(e);
            }
        } else return singleFrameC[channel];
    }

    protected boolean isSingleFrame(int channel) throws IOException {
        return getFileMap().get(channel).size()==1;
    }
}
