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
package bacmman.data_structure.dao;

import bacmman.data_structure.Selection;
import bacmman.data_structure.SegmentedObject;
import bacmman.image.BlankMask;
import bacmman.image.BoundingBox;
import bacmman.image.Image;
import bacmman.image.SimpleBoundingBox;
import bacmman.image.io.ImageFormat;
import bacmman.image.io.ImageIOCoordinates;
import bacmman.image.io.ImageReaderFile;
import bacmman.image.io.ImageWriter;
import java.io.File;
import java.nio.file.Paths;
import java.util.function.IntPredicate;

import bacmman.utils.Pair;
import bacmman.utils.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author Jean Ollion
 */
public class LocalTIFImageDAO implements ImageDAO, ImageDAOTrack {
    private final static Logger logger = LoggerFactory.getLogger(LocalTIFImageDAO.class);
    final String directory;
    final String microscopyFieldName;
    final IntPredicate isSingleFrameChannel;
    static final int idxZeros = 5;
    
    public LocalTIFImageDAO(String microscopyFieldName, String localDirectory, IntPredicate isSingleFrameChannel) {
        this.microscopyFieldName = microscopyFieldName;
        this.directory=localDirectory;
        this.isSingleFrameChannel =isSingleFrameChannel;
    }

    @Override
    public void flush() {}

    @Override
    public String getImageExtension() {
        return ".tif";
    }

    @Override
    public Image openPreProcessedImage(int channelImageIdx, int timePoint) {
        if (isSingleFrameChannel.test(channelImageIdx)) timePoint = 0;
        String path = getPreProcessedImagePath(channelImageIdx, timePoint);
        File f = new File(path);
        if (f.exists()) {
            //long t0 = System.currentTimeMillis();
            Image im = ImageReaderFile.openImage(path);
            //long t1 = System.currentTimeMillis();
            //logger.debug("Opening pre-processed image:  channel: {} timePoint: {} position: {}, in {}ms path : {}", channelImageIdx, timePoint, microscopyFieldName, t1-t0, path);
            return im;
        } else {
            logger.trace("pre-processed image: {} not found", path);
            return null;
        }
    }
    @Override
    public Image openPreProcessedImage(int channelImageIdx, int timePoint, BoundingBox bounds) {
        if (isSingleFrameChannel.test(channelImageIdx)) timePoint = 0;
        String path = getPreProcessedImagePath(channelImageIdx, timePoint);
        File f = new File(path);
        if (f.exists()) {
            logger.trace("Opening pre-processed image:  channel: {} timePoint: {} fieldName: {} bounds: {}", channelImageIdx, timePoint, microscopyFieldName, bounds);
            return ImageReaderFile.openImage(path, new ImageIOCoordinates(bounds));
        } else {
            logger.error("pre-processed image: {} not found", path);
            return null;
        }
    }
    @Override
    public Image openPreProcessedImagePlane(int z, int channelImageIdx, int timePoint) {
        if (isSingleFrameChannel.test(channelImageIdx)) timePoint = 0;
        String path = getPreProcessedImagePath(channelImageIdx, timePoint);
        File f = new File(path);
        if (f.exists()) {
            //logger.debug("Opening pre-processed plane:  channel: {} timePoint: {} fieldName: {} z: {}", channelImageIdx, timePoint, microscopyFieldName, z);
            return ImageReaderFile.openImage(path, new ImageIOCoordinates(new SimpleBoundingBox(0, -1, 0, -1, z, z)));
        } else {
            logger.error("pre-processed image: {} not found", path);
            return null;
        }
    }
    @Override
    public void deletePreProcessedImage(int channelImageIdx, int timePoint) {
        String path = getPreProcessedImagePath(channelImageIdx, timePoint);
        File f = new File(path);
        if (f.exists()) f.delete();
    }

    @Override
    public BlankMask getPreProcessedImageProperties(int channelIdx) {
        String path = getPreProcessedImagePath(channelIdx, 0);
        File f = new File(path);
        if (f.exists()) {
            Pair<int[][], double[]> info = ImageReaderFile.getImageInfo(path);
            int[][] STCXYZ = info.key;
            double[] scale = new double[]{info.value[0], info.value[2]};
            //logger.debug("image info for: {}, sX={}, sY={}, sZ={}, T={} C={}", microscopyFieldName, STCXYZ[0][2], STCXYZ[0][3], STCXYZ[0][4], STCXYZ[0][0], STCXYZ[0][1]);
            return new BlankMask( STCXYZ[0][2], STCXYZ[0][3], STCXYZ[0][4], 0, 0, 0, (float)scale[0], (float)scale[1]);
        } else {
            //logger.debug("getPreProcessedImageProperties: pre-processed image {} not found", path);
            return null;
        }
    }

    
    @Override
    public void writePreProcessedImage(Image image, int channelImageIdx, int timePoint) {
        String path = getPreProcessedImagePath(channelImageIdx, timePoint);
        File f = new File(path);
        f.mkdirs();
        logger.trace("writing preprocessed image to path: {}", path);
        //if (f.exists()) f.delete();
        ImageWriter.writeToFile(image, path, ImageFormat.TIF);
    }

    protected String getPreProcessedImagePath(int channelImageIdx, int timePoint) {
        return Paths.get(directory, microscopyFieldName, "pre_processed", "t"+Utils.formatInteger(5, timePoint)+"_c"+Utils.formatInteger(2, channelImageIdx)+".tif").toString();
    }
    private String getTrackImageFolder(int parentStructureIdx) {
        return Paths.get(directory, microscopyFieldName, "track_images_"+parentStructureIdx).toString();
    }
    private String getTrackImagePath(SegmentedObject o, int channelImageIdx) {
        return Paths.get(getTrackImageFolder(o.getStructureIdx()), Selection.indicesString(o)+"_"+channelImageIdx+".tif").toString();
    }
    
    @Override
    public void writeTrackImage(SegmentedObject trackHead, int channelImageIdx, Image image) {
        String path = getTrackImagePath(trackHead, channelImageIdx);
        File f = new File(path);
        f.delete();
        f.getParentFile().mkdirs();
        logger.trace("writing track image to path: {}", path);
        ImageWriter.writeToFile(image, path, ImageFormat.TIF);
    }
    @Override
    public Image openTrackImage(SegmentedObject trackHead, int channelImageIdx) {
        String path = getTrackImagePath(trackHead, channelImageIdx);
        File f = new File(path);
        //logger.debug("opening track image: from {} c={}, path: {}, exists? {}", trackHead, channelImageIdx, path, f.exists());
        if (f.exists()) {
            //logger.trace("Opening track image:  trackHead: {}", trackHead);
            return ImageReaderFile.openImage(path);
        } else {
            return null;
        }
    }

    @Override
    public void deleteTrackImages(int parentStructureIdx) {
        String folder = getTrackImageFolder(parentStructureIdx);
        Utils.deleteDirectory(folder);
    }
    
}
