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

import bacmman.core.OmeroGateway;
import bacmman.image.BoundingBox;
import bacmman.image.Image;
import org.json.simple.JSONObject;
import bacmman.utils.FileIO;
import bacmman.utils.JSONSerializable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Map;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 *
 * @author Jean Ollion
 */

public abstract class MultipleImageContainer implements JSONSerializable {
    public static final Logger logger = LoggerFactory.getLogger(MultipleImageContainer.class);
    double scaleXY, scaleZ;
    protected OmeroGateway omeroGateway;
    public abstract int getFrameNumber();
    public abstract int getChannelNumber();
    public abstract int getSizeZ(int channel);
    public abstract Image getImage(int timePoint, int channel) throws IOException;
    public abstract Image getPlane(int z, int timePoint, int channel) throws IOException;
    public abstract Image getImage(int timePoint, int channel, BoundingBox bounds) throws IOException;
    public abstract void freeMemory();
    public abstract String getName();
    public float getScaleXY() {return (float)scaleXY;}
    public float getScaleZ() {return (float)scaleZ;}
    public abstract double getCalibratedTimePoint(int t, int c, int z);

    /**
     * Converts absolute time points (in seconds) to times relative to the first time point, rounded to the microsecond (removes floating point errors due to large absolute values)
     */
    protected static void toRelativeTime(Map<String, Double> timePoints) {
        if (timePoints.isEmpty()) return;
        double ref = timePoints.values().stream().mapToDouble(d -> d).min().getAsDouble();
        timePoints.replaceAll((k, v) -> Math.round((v - ref) * 1e6) / 1e6);
    }
    public abstract MultipleImageContainer duplicate();
    public abstract boolean singleFrame(int channel);
    public abstract boolean isEmpty();
    protected Path path;
    public MultipleImageContainer setPath(Path path) {
        this.path=path;
        return this;
    }

    public MultipleImageContainer(double scaleXY, double scaleZ) {
        this.scaleXY = scaleXY;
        this.scaleZ = scaleZ;
    }
    public abstract boolean sameContent(MultipleImageContainer other);

    /**
     * @return absolute paths of the input image files (or folders, e.g. zarr) of this container, empty if the images are not files (e.g. Omero, memory)
     */
    public java.util.List<String> getInputPaths() {
        return java.util.Collections.emptyList();
    }

    /**
     * @return absolute paths of the folders that contain the input images
     */
    public java.util.List<String> getInputFolders() {
        return getInputPaths().stream().map(p -> new java.io.File(p).getParentFile()).filter(java.util.Objects::nonNull).map(java.io.File::getAbsolutePath).distinct().collect(java.util.stream.Collectors.toList());
    }

    /**
     * Input images were moved: each folder of {@link #getInputFolders()} is replaced by the corresponding folder
     * @param folderMapping absolute path of an input folder -> absolute path of its new location
     */
    public void relocateInputFolders(java.util.function.UnaryOperator<String> folderMapping) {}

    static String relocate(String file, java.util.function.UnaryOperator<String> folderMapping) {
        java.io.File f = new java.io.File(file);
        if (f.getParentFile() == null) return file;
        String newFolder = folderMapping.apply(f.getParentFile().getAbsolutePath());
        return newFolder == null ? file : new java.io.File(newFolder, f.getName()).getAbsolutePath();
    }
    public abstract boolean fromOmero();
    public MultipleImageContainer setOmeroGateway(OmeroGateway omeroGateway) {
        this.omeroGateway=omeroGateway;
        return this;
    }
    public static MultipleImageContainer createImageContainerFromJSON(Path path, JSONObject jsonEntry) {
        MultipleImageContainer res=null;
        if (jsonEntry.containsKey("filePathC")) {
            res = new MultipleImageContainerChannelSerie().setPath(path);
        } else if (jsonEntry.containsKey("filePath")) {
            res = new MultipleImageContainerSingleFile().setPath(path);
        } else if (jsonEntry.containsKey("inputDir") || jsonEntry.containsKey("omeroID_c0")) {
            res = new MultipleImageContainerPositionChannelFrame().setPath(path);
        } else {
            logger.error("Invalid JSON entry for container: {}", jsonEntry);
            throw new IllegalArgumentException("Cannot initiate Image container from entry");
        }
        if (res!=null) res.initFromJSONEntry(jsonEntry);
        return res;
    }
    public static String getKey(int c, int z, int t) {
        return new StringBuilder(11).append(c).append(";").append(z).append(";").append(t).toString();
    }
    public static String getKeyCT(int c, int t) {
        return new StringBuilder(7).append(c).append(";").append(t).toString();
    }
    /**
     * @return path relative to the dataset path, with '/' as separator (independent of the system) to be stored
     */
    protected String relativePath(String absolutePath) {
        try {
            return FileIO.toPortablePath(path.relativize(Paths.get(absolutePath)).toString());
        } catch(IllegalArgumentException e) { // under windows, some path have different roots and cannot be relativized
            return FileIO.toPortablePath(absolutePath);
        }
    }

    /**
     * @param relativePath stored path, relative to the dataset path (or absolute), with '/' as separator or '\\' (saved on Windows by previous versions)
     * @return absolute path with separators of the current system
     */
    protected String absolutePath(String relativePath) {
        Path p = path.resolve(FileIO.fromPortablePath(relativePath));
        if (p.isAbsolute()) return p.toFile().getAbsolutePath();
        return p.normalize().toFile().getAbsolutePath();

    }
}
