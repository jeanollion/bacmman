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

import bacmman.data_structure.input_image.InputImage;
import bacmman.data_structure.input_image.InputImagesImpl;
import bacmman.image.MutableBoundingBox;
import bacmman.image.Image;
import org.json.simple.JSONObject;

/**
 *
 * @author Jean Ollion
 */
public class MemoryImageContainer extends MultipleImageContainer {
    Image[][] imageCT;
    public MemoryImageContainer(Image[][] imageCT) {
        super(imageCT[0][0].getScaleXY(), imageCT[0][0].getScaleZ());
        this.imageCT=imageCT;
    }
    @Override
    public boolean sameContent(MultipleImageContainer other) {
        return true;
    }
    @Override
    public Object toJSONEntry() {
        JSONObject res = new JSONObject();
        return res;
    }

    @Override
    public void initFromJSONEntry(Object jsonEntry) {
    }
    
    @Override
    public int getFrameNumber() {
        return imageCT[0].length;
    }

    @Override
    public int getChannelNumber() {
        return imageCT.length;
    }

    @Override
    public int getSizeZ(int channel) {
        return imageCT[0][0].sizeZ();
    }

    @Override
    public Image getImage(int timePoint, int channel) {
        return imageCT[channel][timePoint];
    }

    @Override
    public Image getImage(int timePoint, int channel, MutableBoundingBox bounds) {
        return getImage(timePoint, channel).crop(bounds);
    }

    @Override
    public void flush() {
        imageCT = null;
    }

    @Override
    public String getName() {
        return "memory image container";
    }

    @Override
    public double getCalibratedTimePoint(int t, int c, int z) {
        return t;
    }

    @Override
    public MultipleImageContainer duplicate() {
        return new MemoryImageContainer(imageCT);
    }

    @Override
    public boolean singleFrame(int channel) {
        return false;
    }
    public InputImagesImpl getInputImages(String position) {
        InputImage[][] inputCT = new InputImage[getChannelNumber()][getFrameNumber()];
        for (int t = 0; t<getFrameNumber(); ++t) {
            for (int c = 0; c<getChannelNumber(); ++c) {
                inputCT[c][t] = new InputImage(c, c, t, t, position, this, null);
            }
        }
        return new InputImagesImpl(inputCT, 0, null);
    }
}
