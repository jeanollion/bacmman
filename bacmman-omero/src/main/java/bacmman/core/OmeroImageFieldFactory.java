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

import bacmman.configuration.experiment.ChannelImage;
import bacmman.configuration.experiment.Experiment;
import bacmman.data_structure.image_container.MultipleImageContainer;
import bacmman.data_structure.image_container.MultipleImageContainerChannelSerie;
import bacmman.data_structure.image_container.MultipleImageContainerPositionChannelFrame;
import bacmman.data_structure.image_container.MultipleImageContainerSingleFile;
import bacmman.image.io.ImageIOCoordinates;
import bacmman.image.io.ImageReaderFile;
import bacmman.image.io.OmeroImageMetadata;
import bacmman.utils.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileFilter;
import java.nio.file.Paths;
import java.util.*;
import java.util.Map.Entry;
import java.util.function.Function;
import java.util.function.ToIntFunction;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 *
 * @author Jean Ollion
 */

public class OmeroImageFieldFactory {
    private static final Logger logger = LoggerFactory.getLogger(OmeroImageFieldFactory.class);
    public static List<MultipleImageContainer> importImages(List<OmeroImageMetadata> images, Experiment xp, ProgressCallback pcb) {
        ArrayList<MultipleImageContainer> res = new ArrayList<>();
        switch (xp.getImportImageMethod()) {
            case SINGLE_FILE:
                for (OmeroImageMetadata i : images) OmeroImageFieldFactory.addContainerSingleFile(i, xp, res, pcb);
                break;
            case ONE_FILE_PER_CHANNEL_POSITION:
                {
                    // get keywords
                    String[] keyWords = xp.getChannelImages().getChildren().stream().map(ChannelImage::getImportImageChannelKeyword).toArray(String[]::new);
                    logger.debug("import image channel: keywords: {}", (Object)keyWords);
                    // split images by dataset
                    Map<String, List<OmeroImageMetadata>> imageByDataset = images.stream().collect(Collectors.groupingBy(OmeroImageMetadata::getDatasetName));
                    for (List<OmeroImageMetadata> allImages : imageByDataset.values()) OmeroImageFieldFactory.importImagesChannel(allImages, xp, keyWords, res, pcb);
                    break;
                }
            case ONE_FILE_PER_CHANNEL_FRAME_POSITION:
                {
                    String[] keyWords = xp.getChannelImages().getChildren().stream().map(ChannelImage::getImportImageChannelKeyword).toArray(String[]::new);
                    long countBlank = Arrays.stream(keyWords).filter(""::equals).count();
                    if (countBlank>1) {
                        if (pcb!=null) pcb.log("When Experiment has several channels, one must specify channel keyword for this import method");
                        logger.error("When Experiment has several channels, one must specify channel keyword for this import method");
                        return res;
                    }       
                    OmeroImageFieldFactory.importImagesCTP(images, xp, keyWords, res, pcb);
                    break;
                }
            default:
                break;
        }
        Collections.sort(res, (MultipleImageContainer arg0, MultipleImageContainer arg1) -> arg0.getName().compareToIgnoreCase(arg1.getName()));
        return res;
    }

    protected static void addContainerSingleFile(OmeroImageMetadata image, Experiment xp, ArrayList<MultipleImageContainer> containersTC, ProgressCallback pcb) {
        if (image.getSizeC()==xp.getChannelImageCount(false)) {
            Experiment.AXIS_INTERPRETATION axisInterpretation = xp.getAxisInterpretation();
            boolean invertTZ = (axisInterpretation.equals(Experiment.AXIS_INTERPRETATION.TIME) && image.getSizeZ() > 1 && image.getSizeT() == 1) || (axisInterpretation.equals(Experiment.AXIS_INTERPRETATION.Z) && image.getSizeZ() == 1 && image.getSizeT() > 1) || (axisInterpretation.equals(Experiment.AXIS_INTERPRETATION.AUTOMATIC) && xp.isImportImageInvertTZ());
            MultipleImageContainerSingleFile c = new MultipleImageContainerSingleFile(Utils.removeExtension(image.getFileName()), image.getFileId(), invertTZ?image.getSizeT():image.getSizeZ(), image.getSizeC(), invertTZ?image.getSizeZ():image.getSizeT(), image.getScaleXY(), image.getScaleZ(), invertTZ);
            if (image.getTimepoints()!=null && image.getTimepoints().size()==c.getFrameNumber()) c.setTimePoints(image.getTimepoints());
            containersTC.add(c); //Utils.removeExtension(image.getName())+"_"+
            logger.info("image {} imported successfully", image.getFileNameAndId());
        } else {
            if (pcb!=null) pcb.log("WARNING: Invalid Image: "+image.getFileNameAndId() +" has: "+image.getSizeC()+" channels instead of: "+xp.getChannelImageCount(false));
            logger.warn("Invalid Image: {} has: {} channels instead of: {}", image.getFileNameAndId(), image.getSizeC(), xp.getChannelImageCount(false));
        }

    }
    
    protected static void importImagesChannel(List<OmeroImageMetadata> images, Experiment xp, String[] channelKeywords, ArrayList<MultipleImageContainer> containersTC, ProgressCallback pcb) {
        if (channelKeywords.length==0) return;
        List<OmeroImageMetadata> images0 = images.stream().filter(i -> i.getFileName().contains(channelKeywords[0])).collect(Collectors.toList());
        logger.debug("import images in dir: {} number of candidates: {}", images.get(0).getDatasetName(), images0.size());
        for (OmeroImageMetadata i0 : images0) {
            OmeroImageMetadata[] allChannels = new OmeroImageMetadata[channelKeywords.length];
            allChannels[0] = i0;
            boolean allFiles = true;
            for (int c = 1; c < channelKeywords.length; ++c) {
                String name = i0.getFileName().replace(channelKeywords[0], channelKeywords[c]);
                OmeroImageMetadata ic = images.stream().filter(i -> i.getFileName().equals(name)).findAny().orElse(null);
                if (ic==null) {
                    logger.warn("missing file: {}", name);
                    allFiles=false;
                    break;
                } else allChannels[c] = ic;
            }
            if (allFiles) {
                String name = Utils.removeExtension(i0.getFileName().replace(channelKeywords[0], ""));
                addContainerChannel(allChannels, name, xp, containersTC, pcb);
            }
            
        }
    }

    protected static void importImagesCTP(List<OmeroImageMetadata> images, Experiment xp, String[] channelKeywords, ArrayList<MultipleImageContainer> containersTC, ProgressCallback pcb) {
        String posSep = xp.getImportImagePositionSeparator();
        String frameSep = xp.getImportImageFrameSeparator();
        if (channelKeywords.length==0) return;

        // split by position / channel (check number) / frames (check same number between channels & continuity)
        
        Pattern timePattern = Pattern.compile(".*"+frameSep+"(\\d+).*");
        Map<String, List<OmeroImageMetadata>> filesByPosition=null;
        if (posSep.length()>0) {
            Pattern posPattern = Pattern.compile(".*("+posSep+"\\d+).*");
            try {
                filesByPosition = images.stream().collect(Collectors.groupingBy(f -> MultipleImageContainerPositionChannelFrame.getAsString(f.getFileName(), posPattern)));
            } catch (Exception e) {
                if (pcb!=null) pcb.log("No position with keyword: "+posSep+" could be found");
                logger.error("no position could be identified");
                return;
            }
        } else { // one position per dataset name
            filesByPosition = images.stream().collect(Collectors.groupingBy(OmeroImageMetadata::getDatasetName));
        }
        logger.debug("# positions: {}", filesByPosition.size());
        if (pcb!=null) {
            pcb.log("Number of position found: "+ filesByPosition.size()+ ". Checking validity...");
            pcb.incrementTaskNumber(filesByPosition.size());
        }
        PosLoop : for (Entry<String, List<OmeroImageMetadata>> positionFiles : filesByPosition.entrySet()) {
            logger.debug("Pos: {}, grouping {} files by channels...", positionFiles.getKey(), positionFiles.getValue().size());
            Map<String, List<OmeroImageMetadata>> filesByChannel = positionFiles.getValue().stream().collect(Collectors.groupingBy(f -> MultipleImageContainerPositionChannelFrame.getKeyword(f.getFileName(), channelKeywords, "")));
            logger.debug("Pos: {}, channel found: {}", positionFiles.getKey(),filesByChannel.keySet() );

            if (filesByChannel.size()==channelKeywords.length) {
                Integer frameNumber = null;
                boolean ok = true;
                for (Entry<String, List<OmeroImageMetadata>> channelFiles : filesByChannel.entrySet()) {
                    logger.debug("grouping {} files for channel {} by time point...", channelFiles.getValue().size(), channelFiles.getKey());
                    Map<Integer, OmeroImageMetadata> filesByTimePoint = channelFiles.getValue().stream().collect(Collectors.toMap(f -> MultipleImageContainerPositionChannelFrame.get(f.getFileName(), timePattern), Function.identity()));
                    logger.debug("files grouped. checking frame continuity...");
                    List<Integer> tpList = new ArrayList<>(new TreeMap<>(filesByTimePoint).keySet());
                    int minTimePoint = tpList.get(0);
                    int maxFrameNumberSuccessive=1;
                    while(maxFrameNumberSuccessive<tpList.size() && tpList.get(maxFrameNumberSuccessive-1)+1==tpList.get(maxFrameNumberSuccessive)) {++maxFrameNumberSuccessive;}
                    int maxTimePoint = tpList.get(tpList.size()-1);
                    //int maxTimePoint = Collections.max(filesByTimePoint.entrySet(), (e1, e2) -> e1.getKey() - e2.getKey()).getKey();
                    //int minTimePoint = Collections.min(filesByTimePoint.entrySet(), (e1, e2) -> e1.getKey() - e2.getKey()).getKey();
                    int theoframeNumberCurrentChannel = maxTimePoint-minTimePoint+1;
                    
                    if (theoframeNumberCurrentChannel != maxFrameNumberSuccessive) {
                        logger.warn("Position: {}, missing time points for channel: {}, 1st: {}, last: {}, count: {}, max successive: {}", positionFiles.getKey(), channelFiles.getKey(), minTimePoint, maxTimePoint, filesByTimePoint.size(), maxFrameNumberSuccessive);
                        //ok = false;
                        //break;
                    } 
                    // check if all channels have same number of Frames
                    if (frameNumber == null) frameNumber = maxFrameNumberSuccessive;
                    else {
                        if (frameNumber!=maxFrameNumberSuccessive) {
                            logger.warn("Position: {}, Channel: {}, {} timepoint found instead of {}", positionFiles.getKey(), channelFiles.getKey(), maxFrameNumberSuccessive, frameNumber);
                            if (pcb!=null) pcb.log("Position: "+positionFiles.getKey()+", Channel: "+channelFiles.getKey()+", "+maxFrameNumberSuccessive+" timepoint found instead of "+frameNumber);
                            ok = false;
                            break;
                        }
                    }
                    logger.debug("continuity checked");
                }
                if (ok) {
                    logger.debug("creating container...");
                    List<List<String>> fileIDsCT = new ArrayList<>(filesByChannel.size());
                    for (int c = 0; c<channelKeywords.length; ++c) fileIDsCT.add(filesByChannel.get(c).stream().map(i -> String.valueOf(i.getFileId())).collect(Collectors.toList()));
                    containersTC.add(
                        new MultipleImageContainerPositionChannelFrame(
                            fileIDsCT,
                            frameNumber,
                            filesByChannel.get(0).get(0).getScaleXY(),
                            filesByChannel.get(0).get(0).getScaleZ(),
                            positionFiles.getKey()
                        ));
                    logger.debug("container created");
                }
                
            } else {
                logger.warn("Position: {}, {} channels instead of {}", positionFiles.getKey(), filesByChannel.size(), channelKeywords.length);
                if (pcb!=null) pcb.log("Position: "+positionFiles.getKey()+". Wrong channel number: "+filesByChannel.size()+" channels instead of "+channelKeywords.length);
            }
            if (pcb!=null) pcb.incrementProgress();
        }
    }
    
    protected static void addContainerChannel(OmeroImageMetadata[] imageC, String fieldName, Experiment xp, ArrayList<MultipleImageContainer> containersTC, ProgressCallback pcb) {
        int timePointNumber=0;
        int[] sizeZC = new int[imageC.length];
        int[] frameNumberC = new int[imageC.length];
        boolean[] singleFile = new boolean[imageC.length];
        double[] scaleXYZ=null;
        int scaleChannel=0;
        ToIntFunction<Integer> getChannelIdx = idx -> (int)Arrays.stream(imageC).limit(idx).filter(s -> s.equals(imageC[idx])).count();
        int[] channelIdx = IntStream.range(0, imageC.length).boxed().mapToInt(getChannelIdx).toArray();
        int[] channelModulo = new int[channelIdx.length];
        Arrays.fill(channelModulo, 1);
        Map<String, Integer> channelDuplicatedCount = Arrays.stream(imageC).collect(Collectors.groupingBy(OmeroImageMetadata::getFileName)).entrySet().stream().collect(Collectors.toMap(Entry::getKey, e->e.getValue().size()));
        logger.debug("images: {} , channelIdx: {}, channel number: {}", imageC, channelIdx, channelDuplicatedCount);
        Experiment.AXIS_INTERPRETATION axisInterpretation = xp.getAxisInterpretation();
        boolean[] invertZTbyC = new boolean[imageC.length];
        List<Experiment.AXIS_INTERPRETATION> axisInterpretationByC = xp.getChannelImages().getChildren().stream().map(ChannelImage::getAxisInterpretation).collect(Collectors.toList());
        for (int c = 0; c< imageC.length; ++c) {
            Experiment.AXIS_INTERPRETATION ax = axisInterpretationByC.get(c).equals(Experiment.AXIS_INTERPRETATION.AUTOMATIC) ? axisInterpretation : axisInterpretationByC.get(c);
            OmeroImageMetadata cur = imageC[c];
            int sizeT = cur.getSizeT();
            int sizeZ = cur.getSizeZ();
            if ((ax.equals(Experiment.AXIS_INTERPRETATION.TIME) && cur.getSizeZ()>1 && cur.getSizeT()==1) || (ax.equals(Experiment.AXIS_INTERPRETATION.Z) && cur.getSizeZ()==1 && cur.getSizeT()>1) || (axisInterpretation.equals(Experiment.AXIS_INTERPRETATION.AUTOMATIC) && xp.isImportImageInvertTZ())) {
                invertZTbyC[c] = true;
                sizeT = cur.getSizeZ();
                sizeZ = cur.getSizeT();
            }
            logger.debug("channel : {} invert: {}", c, invertZTbyC[c]);
            if (channelDuplicatedCount.get(cur.getFileName())>1 && cur.getSizeC()==1) {
                channelModulo[c] = channelDuplicatedCount.get(cur.getFileName());
                if (sizeT%channelModulo[c]!=0) {
                    logger.warn("File: {} contains {} frames and one channel but is expected to contain {} channels: number of frames should be a multiple of number of expected channels", imageC[c], cur.getSizeC(), channelModulo[c]);
                    if (pcb!=null) pcb.log("File: "+imageC[c]+" contains "+sizeT+" frames and one channel but is expected to contain "+channelModulo[c]+" channels: number of frames should be a multiple of number of expected channels");
                    return;
                }
                sizeT /= channelModulo[c];
            } else if (cur.getSizeC()<=channelIdx[c]) {
                logger.warn("File: {} contains {} channels, but is expected to contain at least {} channels", imageC[c], cur.getSizeC(), channelIdx[c]+1);
                if (pcb!=null) pcb.log("File: "+imageC[c]+" contains "+cur.getSizeC()+" channels, but is expected to contain at least "+channelIdx[c]+1+" channels");
                return;
            }
            //if (stc[0][1]>1) logger.warn("Import method selected = one file per channel and per microscopy field, but file: {} contains {} channels", imageC[c], stc[0][1]);
            if (c==0) {
                timePointNumber=sizeT;
                frameNumberC[0] = sizeT;
                singleFile[c] = sizeT == 1;
                sizeZC[c] = sizeZ;
                scaleXYZ = new double[]{cur.getScaleXY(), cur.getScaleZ()};
                logger.debug("Channel0: frames: {}, Z: {}", timePointNumber, sizeZC[0]);
            }
            else {
                if (timePointNumber==1 && sizeT>1) timePointNumber = sizeT;
                if (sizeT!=timePointNumber && sizeT!=1) {
                    logger.warn("Invalid file: {}. Contains {} time points whereas file: {} contains: {} time points", imageC[c], sizeT, imageC[0], timePointNumber);
                    if (pcb!=null) pcb.log("Invalid file: "+imageC[c]+". Contains "+sizeT+" time points whereas file: "+imageC[0]+" contains: "+timePointNumber+" time points");
                    return;
                }
                singleFile[c] = sizeT == 1;
                sizeZC[c] = sizeZ;
                if (sizeZC[c]>sizeZC[scaleChannel]) scaleXYZ = new double[]{cur.getScaleXY(), cur.getScaleZ()};
            }


        }
        if (timePointNumber>0) {
            List<ImageIOCoordinates.RGB> rgbC = xp.getChannelImages().getChildren().stream().map(ChannelImage::getRGB).collect(Collectors.toList());
            MultipleImageContainerChannelSerie c = new MultipleImageContainerChannelSerie(fieldName, Arrays.stream(imageC).map(i -> "omeroID_"+i.getFileId()).toArray(String[]::new), channelIdx, channelModulo, timePointNumber, singleFile, sizeZC, scaleXYZ[0], scaleXYZ[1], axisInterpretation.equals(Experiment.AXIS_INTERPRETATION.AUTOMATIC) && xp.isImportImageInvertTZ(), invertZTbyC, rgbC);
            for (int cIdx = 0; cIdx<imageC.length; ++cIdx) {
                if (imageC[cIdx].getTimepoints()!=null && imageC[cIdx].getTimepoints().size()==c.getFrameNumber()) c.setTimePoints(cIdx, imageC[cIdx].getTimepoints());
            }
            containersTC.add(c);
        }
    }
    
    private static Pattern getAllChannelPattern(String[] channelKeywords) {
        String pat = ".*("+channelKeywords[0]+")";
        for (int i = 1; i<channelKeywords.length; ++i) pat+="|("+channelKeywords[i]+")";
        pat+=".*";
        return Pattern.compile(pat);
    }
}
