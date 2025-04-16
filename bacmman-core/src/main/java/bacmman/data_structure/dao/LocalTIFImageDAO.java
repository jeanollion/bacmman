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
import java.io.FileNotFoundException;
import java.io.IOException;
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
public class LocalTIFImageDAO implements ImageDAO {
    private final static Logger logger = LoggerFactory.getLogger(LocalTIFImageDAO.class);
    final String directory;
    final String position;
    final IntPredicate isSingleFrameChannel;
    static final int idxZeros = 5;
    
    public LocalTIFImageDAO(String position, String localDirectory, IntPredicate isSingleFrameChannel) {
        this.position = position;
        this.directory=localDirectory;
        this.isSingleFrameChannel =isSingleFrameChannel;
    }

    @Override
    public void eraseAll() {
        Utils.deleteDirectory(Paths.get(directory, position, "pre_processed").toString());
    }

    @Override
    public void freeMemory() {}

    @Override
    public String getImageExtension() {
        return ".tif";
    }

    @Override
    public Image openPreProcessedImage(int channelImageIdx, int timePoint) throws IOException {
        if (isSingleFrameChannel.test(channelImageIdx)) timePoint = 0;
        String path = getPreProcessedImagePath(channelImageIdx, timePoint);
        File f = new File(path);
        if (f.exists()) {
            //long t0 = System.currentTimeMillis();
            return ImageReaderFile.openImage(path);
            //long t1 = System.currentTimeMillis();
            //logger.debug("Opening pre-processed image:  channel: {} timePoint: {} position: {}, in {}ms path : {}", channelImageIdx, timePoint, position, t1-t0, path);
            //return im;
        } else {
            throw new FileNotFoundException(path);
        }
    }

    @Override
    public Image openPreProcessedImage(int channelImageIdx, int timePoint, BoundingBox bounds) throws IOException {
        if (isSingleFrameChannel.test(channelImageIdx)) timePoint = 0;
        String path = getPreProcessedImagePath(channelImageIdx, timePoint);
        File f = new File(path);
        if (f.exists()) {
            //long t0 = System.currentTimeMillis();
            return ImageReaderFile.openImage(path, new ImageIOCoordinates(bounds));
            //long t1 = System.currentTimeMillis();
            //logger.debug("Opening pre-processed image:  channel: {} timePoint: {} position: {} bounds: {} in {}ms", channelImageIdx, timePoint, position, bounds, t1-t0);
            //return res;
        } else {
            throw new FileNotFoundException(path);
        }
    }

    @Override
    public Image openPreProcessedImagePlane(int z, int channelImageIdx, int timePoint) throws IOException {
        if (isSingleFrameChannel.test(channelImageIdx)) timePoint = 0;
        String path = getPreProcessedImagePath(channelImageIdx, timePoint);
        File f = new File(path);
        if (f.exists()) {
            //long t0 = System.currentTimeMillis();
            return ImageReaderFile.openImage(path, new ImageIOCoordinates(new SimpleBoundingBox(0, -1, 0, -1, z, z)));
            //long t1 = System.currentTimeMillis();
            //logger.debug("Opening pre-processed plane:  channel: {} timePoint: {} position: {} z: {} in {}ms", channelImageIdx, timePoint, position, z, t1-t0);
            //return res;
        } else {
            throw new FileNotFoundException(path);
        }
    }

    @Override
    public void deletePreProcessedImage(int channelImageIdx, int timePoint) {
        String path = getPreProcessedImagePath(channelImageIdx, timePoint);
        File f = new File(path);
        if (f.exists()) f.delete();
    }

    @Override
    public BlankMask getPreProcessedImageProperties(int channelIdx) throws FileNotFoundException{
        String path = getPreProcessedImagePath(channelIdx, 0);
        File f = new File(path);
        if (f.exists()) {
            Pair<int[][], double[]> info = ImageReaderFile.getImageInfo(path);
            int[][] STCXYZ = info.key;
            double[] scale = new double[]{info.value[0], info.value[2]};
            //logger.debug("image info for: {}, sX={}, sY={}, sZ={}, T={} C={}", microscopyFieldName, STCXYZ[0][2], STCXYZ[0][3], STCXYZ[0][4], STCXYZ[0][0], STCXYZ[0][1]);
            return new BlankMask( STCXYZ[0][2], STCXYZ[0][3], STCXYZ[0][4], 0, 0, 0, (float)scale[0], (float)scale[1]);
        } else {
            throw new FileNotFoundException(path);
        }
    }

    @Override
    public boolean isEmpty() {
        String path = getPreProcessedImagePath(0, 0);
        return !new File(path).exists();
    }

    @Override
    public void writePreProcessedImage(Image image, int channelImageIdx, int timePoint) {
        String path = getPreProcessedImagePath(channelImageIdx, timePoint);
        File f = new File(path);
        f.mkdirs();
        //logger.trace("writing preprocessed image to path: {}", path);
        //if (f.exists()) f.delete();
        ImageWriter.writeToFile(image, path, ImageFormat.TIF);
    }

    protected String getPreProcessedImagePath(int channelImageIdx, int timePoint) {
        return Paths.get(directory, position, "pre_processed", "t"+Utils.formatInteger(5, timePoint)+"_c"+Utils.formatInteger(2, channelImageIdx)+".tif").toString();
    }

    private String getTrackImageFolder(int parentStructureIdx) {
        return Paths.get(directory, position, "track_images_"+parentStructureIdx).toString();
    }
}
