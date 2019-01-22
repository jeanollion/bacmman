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
package bacmman.core;

import bacmman.configuration.experiment.Experiment;
import bacmman.data_structure.Processor;
import bacmman.data_structure.image_container.MultipleImageContainer;
import bacmman.data_structure.image_container.MultipleImageContainerChannelSerie;
import bacmman.data_structure.image_container.MultipleImageContainerPositionChannelFrame;
import bacmman.data_structure.image_container.MultipleImageContainerSingleFile;
import bacmman.image.io.ImageReader;
import java.io.File;
import java.io.FileFilter;
import java.util.*;
import java.util.Map.Entry;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import bacmman.utils.Utils;

/**
 *
 * @author Jean Ollion
 */

public class ImageFieldFactory {
    private final static List<String> ignoredExtensions = Arrays.asList(new String[]{".log"});
    public static List<MultipleImageContainer> importImages(String[] path, Experiment xp, ProgressCallback pcb) {
        ArrayList<MultipleImageContainer> res = new ArrayList<>();
        switch (xp.getImportImageMethod()) {
            case SINGLE_FILE:
                for (String p : path) ImageFieldFactory.importImagesSingleFile(new File(p), xp, res, pcb);
                break;
            case ONE_FILE_PER_CHANNEL_POSITION:
                {
                    // get keywords
                    String[] keyWords = xp.getChannelImages().getChildren().stream().map(c -> c.getImportImageChannelKeyword()).toArray(s->new String[s]);
                    Processor.logger.debug("import image channel: keywords: {}", (Object)keyWords);
                    long countBlank = Arrays.stream(keyWords).filter(s->"".equals(s)).count();
                    if (countBlank>1) {
                        if (pcb!=null) pcb.log("When Experiement has several channels, one must specify channel keyword for this import method");
                        Processor.logger.error("When Experiement has several channels, one must specify channel keyword for this import method");
                        return res;
                    }
                    for (String p : path) ImageFieldFactory.importImagesChannel(new File(p), xp, keyWords, res, pcb);
                    break;
                }
            case ONE_FILE_PER_CHANNEL_FRAME_POSITION:
                {
                    String[] keyWords = xp.getChannelImages().getChildren().stream().map(c -> c.getImportImageChannelKeyword()).toArray(s->new String[s]);
                    long countBlank = Arrays.stream(keyWords).filter(s->"".equals(s)).count();
                    if (countBlank>1) {
                        if (pcb!=null) pcb.log("When Experiement has several channels, one must specify channel keyword for this import method");
                        Processor.logger.error("When Experiement has several channels, one must specify channel keyword for this import method");
                        return res;
                    }       
                    for (String p : path) ImageFieldFactory.importImagesCTP(new File(p), xp, keyWords, res, pcb);
                    break;
                }
            default:
                break;
        }
        Collections.sort(res, (MultipleImageContainer arg0, MultipleImageContainer arg1) -> arg0.getName().compareToIgnoreCase(arg1.getName()));
        return res;
    }
    
    
    protected static void importImagesSingleFile(File f, Experiment xp, ArrayList<MultipleImageContainer> containersTC, ProgressCallback pcb) {
        if (f.isDirectory()) {
            for (File ff : f.listFiles()) {
                ImageFieldFactory.importImagesSingleFile(ff, xp, containersTC, pcb);
            }
        } else if (!isIgnoredFile(f.getName())) {
            addContainerSingleFile(f, xp, containersTC, pcb);
        }
    }
    
    protected static void addContainerSingleFile(File image, Experiment xp, ArrayList<MultipleImageContainer> containersTC, ProgressCallback pcb) {
        String sep = xp.getImportImagePositionSeparator();
        ImageReader reader=null;
        long t0 = System.currentTimeMillis();
        try {
            reader = new ImageReader(image.getAbsolutePath());
            if (xp.isImportImageInvertTZ()) reader.setInvertTZ(true);
        } catch(Exception e) {
            if (pcb!=null) pcb.log("WARNING: Image: "+image.getAbsolutePath()+" could not be read");
            Processor.logger.warn("Image : {} could not be read", image.getAbsolutePath());
            return;
        }
        long t1 = System.currentTimeMillis();
        int[][] stc = reader.getSTCXYZNumbers();
        long t2 = System.currentTimeMillis();
        int s = 0;
        String end = "";
        int digits=(int)(Math.log10(stc.length)+1);
        for (int[] tc:stc) {
            if (stc.length>1) end = sep+Utils.formatInteger(digits, s);
            else end = Utils.removeExtension(image.getName());
            if (tc[1]==xp.getChannelImageCount()) {
                double[] scaleXYZ = reader.getScaleXYZ(1);
                MultipleImageContainerSingleFile c = new MultipleImageContainerSingleFile(end, image.getAbsolutePath(),s, tc[0], tc[1], tc[4], scaleXYZ[0], scaleXYZ[2], xp.isImportImageInvertTZ());
                containersTC.add(c); //Utils.removeExtension(image.getName())+"_"+
                Processor.logger.info("image {} imported successfully", image.getAbsolutePath());
            } else {
                if (pcb!=null) pcb.log("WARNING: Invalid Image: "+image.getAbsolutePath()+" has: "+tc[1]+" channels instead of: "+xp.getChannelImageCount());
                Processor.logger.warn("Invalid Image: {} has: {} channels instead of: {}", image.getAbsolutePath(), tc[1], xp.getChannelImageCount());
            }
            ++s;
        }
        reader.closeReader();
        long t3 = System.currentTimeMillis();
        Processor.logger.debug("import image: {}, open reader: {}, getSTC: {}, create image containers: {}", t1-t0, t2-t1, t3-t2);
    }
    
    protected static void importImagesChannel(File input, Experiment xp, String[] channelKeywords, ArrayList<MultipleImageContainer> containersTC, ProgressCallback pcb) {
        if (channelKeywords.length==0) return;
        if (!input.isDirectory()) return;
        File[] subDirs = input.listFiles(getDirectoryFilter()); // recursivity
        for (File dir : subDirs) importImagesChannel(dir, xp, channelKeywords, containersTC, pcb);// recursivity
        
        File[] file0 = input.listFiles((File dir, String name) -> name.contains(channelKeywords[0]) && !isIgnoredFile(name));
        Processor.logger.debug("import images in dir: {} number of candidates: {}", input.getAbsolutePath(), file0.length);
        for (File f : file0) {
            String[] allChannels = new String[channelKeywords.length];
            allChannels[0] = f.getAbsolutePath();
            boolean allFiles = true;
            for (int c = 1; c < channelKeywords.length; ++c) {
                String name = input + File.separator + f.getName().replace(channelKeywords[0], channelKeywords[c]);
                File channel = new File(name);
                if (!channel.exists()) {
                    Processor.logger.warn("missing file: {}", name);
                    allFiles=false;
                    break;
                } else allChannels[c] = name;
            }
            if (allFiles) {
                String name = Utils.removeExtension(f.getName().replace(channelKeywords[0], ""));
                addContainerChannel(allChannels, name, xp, containersTC);
            }
            
        }
    }
    
    private static String[] IMAGE_EXTENSION_CTP = new String[]{"tif", "tiff", "nd2", "png"};
    protected static void importImagesCTP(File input, Experiment xp, String[] channelKeywords, ArrayList<MultipleImageContainer> containersTC, ProgressCallback pcb) {
        String posSep = xp.getImportImagePositionSeparator();
        String frameSep = xp.getImportImageFrameSeparator();
        if (channelKeywords.length==0) return;
        if (!input.isDirectory()) return;
        File[] subDirs = input.listFiles(getDirectoryFilter()); // recursivity
        for (File dir : subDirs) importImagesCTP(dir, xp, channelKeywords, containersTC, pcb);// recursivity
        // 1 : filter by extension
        Pattern allchanPattern = getAllChannelPattern(channelKeywords);
        Map<String, List<File>> filesByExtension = Arrays.stream(input.listFiles((File dir, String name) -> allchanPattern.matcher(name).find() && !isIgnoredFile(name))).collect(Collectors.groupingBy(f -> Utils.getExtension(f.getName())));
        List<File> files=null;
        String extension = null;
        if (filesByExtension.size()>1) { // keep most common extension
            int maxLength = Collections.max(filesByExtension.entrySet(), Comparator.comparingInt(e1 -> e1.getValue().size())).getValue().size();
            filesByExtension.entrySet().removeIf(e -> e.getValue().size()<maxLength);
            if (filesByExtension.size()>1) { // keep extension in list
                Set<String> contained = new HashSet<>(Arrays.asList(IMAGE_EXTENSION_CTP));
                filesByExtension.entrySet().removeIf(e -> !contained.contains(e.getKey()));
                if (filesByExtension.size()>1) {
                    Processor.logger.error("Folder: {} contains several image extension: {}", input.getAbsolutePath(), filesByExtension.keySet());
                    return;
                } else if (filesByExtension.isEmpty()) return;
            }
            //List<Entry<String, List<File>>> l = filesByExtension.entrySet().stream().filter(e -> e.getValue().size()==maxLength).collect(Collectors.toList());
        } 
        if (filesByExtension.size()==1) {
            files = filesByExtension.entrySet().iterator().next().getValue();
            extension = filesByExtension.keySet().iterator().next();
        } else {
            Processor.logger.error("Folder: {} contains several image extension: {}", input.getAbsolutePath(), filesByExtension.keySet());
            return;
        }
        Processor.logger.debug("extension: {}, #files: {}", extension, files.size());
        // get other channels
        
        // 2/ get maximum common part at start
        
        /*List<String> fileNames = new ArrayList<String>(files.size());
        for (File f : files) fileNames.add(Utils.removeExtension(f.getName()));
        int startIndex = MultipleImageContainerPositionChannelFrame.getCommomStartIndex(fileNames);
        String startName = fileNames.get(0).substring(0, startIndex+1);
        logger.debug("common image name: {}", startName);
        Map<String, File> filesMap= new HashMap<>(fileNames.size());
        for (File f : files) filesMap.put(f.getName().substring(startIndex+1, f.getName().length()), f);*/
        
        // 3 split by position / channel (check number) / frames (check same number between channels & continity)
        
        Pattern timePattern = Pattern.compile(".*"+frameSep+"(\\d+).*");
        Map<String, List<File>> filesByPosition=null;
        Pattern posPattern = Pattern.compile(".*("+posSep+"\\d+).*");
        try {
            filesByPosition = files.stream().collect(Collectors.groupingBy(f -> MultipleImageContainerPositionChannelFrame.getAsString(f.getName(), posPattern)));
        } catch (Exception e) {
            if (pcb!=null) pcb.log("No position with keyword: "+posSep+" could be find in dir: "+input);
            Processor.logger.error("no position could be identified for dir: {}", input);
            return;
        }
        Processor.logger.debug("Dir: {} # positions: {}", input.getAbsolutePath(), filesByPosition.size());
        PosLoop : for (Entry<String, List<File>> positionFiles : filesByPosition.entrySet()) {
            Map<String, List<File>> filesByChannel = positionFiles.getValue().stream().collect(Collectors.groupingBy(f -> MultipleImageContainerPositionChannelFrame.getKeyword(f.getName(), channelKeywords, "")));
            Processor.logger.debug("Pos: {}, channel found: {}", positionFiles.getKey(),filesByChannel.keySet() );
            
            if (filesByChannel.size()==channelKeywords.length) {
                Integer frameNumber = null;
                boolean ok = true;
                for (Entry<String, List<File>> channelFiles : filesByChannel.entrySet()) {
                    Map<Integer, File> filesByTimePoint = channelFiles.getValue().stream().collect(Collectors.toMap(f -> MultipleImageContainerPositionChannelFrame.get(f.getName(), timePattern), Function.identity()));
                    List<Integer> tpList = new ArrayList<>(new TreeMap<>(filesByTimePoint).keySet());
                    int minTimePoint = tpList.get(0);
                    int maxFrameNumberSuccessive=1;
                    while(maxFrameNumberSuccessive<tpList.size() && tpList.get(maxFrameNumberSuccessive-1)+1==tpList.get(maxFrameNumberSuccessive)) {++maxFrameNumberSuccessive;}
                    int maxTimePoint = tpList.get(tpList.size()-1);
                    //int maxTimePoint = Collections.max(filesByTimePoint.entrySet(), (e1, e2) -> e1.getKey() - e2.getKey()).getKey();
                    //int minTimePoint = Collections.min(filesByTimePoint.entrySet(), (e1, e2) -> e1.getKey() - e2.getKey()).getKey();
                    int theoframeNumberCurrentChannel = maxTimePoint-minTimePoint+1;
                    
                    if (theoframeNumberCurrentChannel != maxFrameNumberSuccessive) {
                        Processor.logger.warn("Dir: {} Position: {}, missing time points for channel: {}, 1st: {}, last: {}, count: {}, max successive: {}", input.getAbsolutePath(), positionFiles.getKey(), channelFiles.getKey(), minTimePoint, maxTimePoint, filesByTimePoint.size(), maxFrameNumberSuccessive);
                        //ok = false;
                        //break;
                    } 
                    // check if all channels have same number of Frames
                    if (frameNumber == null) frameNumber = maxFrameNumberSuccessive;
                    else {
                        if (frameNumber!=maxFrameNumberSuccessive) {
                            Processor.logger.warn("Dir: {} Position: {}, Channel: {}, {} tp found instead of {}", input.getAbsolutePath(), positionFiles.getKey(), channelFiles.getKey(), maxFrameNumberSuccessive, frameNumber);
                            ok = false;
                            break;
                        }
                    }
                }
                if (ok) {
                    containersTC.add(
                        new MultipleImageContainerPositionChannelFrame(
                            input.getAbsolutePath(), 
                            extension, 
                            positionFiles.getKey(), 
                            frameSep, 
                            channelKeywords, 
                            frameNumber
                        ));
                }
                
            } else Processor.logger.warn("Dir: {} Position: {}, {} channels instead of {}", input.getAbsolutePath(), positionFiles.getKey(), filesByChannel.size(), channelKeywords.length);
        }
    }
    
    protected static void addContainerChannel(String[] imageC, String fieldName, Experiment xp, ArrayList<MultipleImageContainer> containersTC) {
        //checks timepoint number is equal for all channels
        int timePointNumber=0;
        int[] sizeZC = new int[imageC.length];
        int[] frameNumberC = new int[imageC.length];
        boolean[] singleFile = new boolean[imageC.length];
        double[] scaleXYZ=null;
        int scaleChannel=0;
        for (int c = 0; c< imageC.length; ++c) {
            ImageReader reader = null;
            try {
                reader = new ImageReader(imageC[c]);
                if (xp.isImportImageInvertTZ()) reader.setInvertTZ(true);
            } catch (Exception e) {
                Processor.logger.warn("Image {} could not be read: ", imageC[c], e);
            }
            if (reader != null) {
                int[][] stc = reader.getSTCXYZNumbers();
                if (stc.length>1) {
                    Processor.logger.warn("Import method selected = one file per channel and per microscopy field, but file: {} contains {} series", imageC[c], stc.length);
                    return;
                }
                if (stc.length==0) return;
                if (stc[0][1]>1) {
                    Processor.logger.warn("Import method selected = one file per channel and per microscopy field, but file: {} contains {} channels", imageC[c], stc[0][1]);
                }
                if (c==0) {
                    timePointNumber=stc[0][0];
                    frameNumberC[0] = stc[0][0];
                    singleFile[c] = stc[0][0] == 1;
                    sizeZC[c] = stc[0][4];
                    scaleXYZ = reader.getScaleXYZ(1);
                    Processor.logger.debug("Channel0: frames: {}, Z: {}", timePointNumber, sizeZC[0]);
                }
                else {
                    if (timePointNumber==1 && stc[0][0]>1) timePointNumber = stc[0][0];
                    if (stc[0][0]!=timePointNumber && stc[0][0]!=1) {
                        Processor.logger.warn("Warning: invalid file: {}. Contains {} time points whereas file: {} contains: {} time points", imageC[c], stc[0][0], imageC[0], timePointNumber);
                        return;
                    }
                    singleFile[c] = stc[0][0] == 1;
                    sizeZC[c] = stc[0][4];
                    if (sizeZC[c]>sizeZC[scaleChannel]) scaleXYZ = reader.getScaleXYZ(1);
                }
                
            }
        }
        if (timePointNumber>0) {
            MultipleImageContainerChannelSerie c = new MultipleImageContainerChannelSerie(fieldName, imageC, timePointNumber, singleFile, sizeZC, scaleXYZ[0], scaleXYZ[2], xp.isImportImageInvertTZ());
            containersTC.add(c);
        }
        
        
    }
    
    
    
    public static boolean isIgnoredFile(String s) {
        if (s==null || s.length()==0 || s.charAt(0)=='.') return true;
        for (int i =s.length()-1; i>=0; --i) {
            if (s.charAt(i)=='.') {
                String ext = s.substring(i);
                return ignoredExtensions.contains(ext);
            }
        }
        return false;
    }
    
    private static FileFilter getDirectoryFilter() {
        return new FileFilter() {
            public boolean accept(File file) {
                return file.isDirectory();
            }
        };
    }
    
    private static FileFilter getFileFilter() {
        return (File file) -> !file.isDirectory();
    }
    
    private static Pattern getAllChannelPattern(String[] channelKeywords) {
        String pat = ".*("+channelKeywords[0]+")";
        for (int i = 1; i<channelKeywords.length; ++i) pat+="|("+channelKeywords[i]+")";
        pat+=".*";
        return Pattern.compile(pat);
    }
    
}
