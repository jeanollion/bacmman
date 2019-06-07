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
import org.json.simple.JSONObject;
import bacmman.utils.JSONSerializable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 *
 * @author Jean Ollion
 */

public abstract class MultipleImageContainer implements JSONSerializable {
    public static final Logger logger = LoggerFactory.getLogger(MultipleImageContainer.class);
    double scaleXY, scaleZ;
    public abstract int getFrameNumber();
    public abstract int getChannelNumber();
    public abstract int getSizeZ(int channel);
    public abstract Image getImage(int timePoint, int channel);
    public abstract Image getImage(int timePoint, int channel, MutableBoundingBox bounds);
    public abstract void flush();
    public abstract String getName();
    public float getScaleXY() {return (float)scaleXY;}
    public float getScaleZ() {return (float)scaleZ;}
    public abstract double getCalibratedTimePoint(int t, int c, int z);
    public abstract MultipleImageContainer duplicate();
    public abstract boolean singleFrame(int channel);
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
    public static MultipleImageContainer createImageContainerFromJSON(Path path, JSONObject jsonEntry) {
        MultipleImageContainer res=null;
        if (jsonEntry.containsKey("filePathC")) {
            res = new MultipleImageContainerChannelSerie().setPath(path);
        } else if (jsonEntry.containsKey("filePath")) {
            res = new MultipleImageContainerSingleFile().setPath(path);
        } else if (jsonEntry.containsKey("inputDir")) {
            res = new MultipleImageContainerPositionChannelFrame().setPath(path);
        }
        if (res!=null) res.initFromJSONEntry(jsonEntry);
        return res;
    }
    public static String getKey(int c, int z, int t) {
        return new StringBuilder(11).append(c).append(";").append(z).append(";").append(t).toString();
    }
    protected String relativePath(String absolutePath) {
        return path.relativize(Paths.get(absolutePath)).toString();
    }
    protected String absolutePath(String relativePath) {
        return path.resolve(relativePath).normalize().toFile().getAbsolutePath();
    }
}
