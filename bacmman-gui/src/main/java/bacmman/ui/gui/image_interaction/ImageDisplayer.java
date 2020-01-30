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
package bacmman.ui.gui.image_interaction;

import bacmman.image.BoundingBox;
import bacmman.image.Histogram;
import bacmman.image.HistogramFactory;
import bacmman.image.Image;
import static bacmman.image.Image.logger;
import bacmman.image.ImageInteger;
import bacmman.image.ImageMask;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 *
 * @author Jean Ollion
 * @param <T>
 */
public interface ImageDisplayer<T> {
    public static double zoomMagnitude=1;
    public boolean isDisplayed(T image);
    public T showImage(Image image, double... displayRange);
    public void close(Image image);
    public void close(T image);
    public T getImage(Image image);
    public Image getImage(T image);
    public void updateImageDisplay(Image image, double... displayRange);
    public void updateImageRoiDisplay(Image image);
    public T showImage5D(String title, Image[][] imageTC);
    public BoundingBox getDisplayRange(Image image);
    public void setDisplayRange(BoundingBox bounds, Image image);
    public T getCurrentImage();
    public Image getCurrentImage2();
    public Image[][] getCurrentImageCT();
    public void flush();
    public void addMouseWheelListener(Image image, Predicate<BoundingBox> movementCallBack);
    //public int[] getFCZCount(T image);
    //public boolean isVisible(Image image);
    //public Image[][] reslice(Image image, int[] FCZCount);
    
    static Image[][] reslice(Image image, int[] FCZCount, Function<int[], Integer> getStackIndex) {
        if (image.sizeZ()!=FCZCount[0]*FCZCount[1]*FCZCount[2]) {
            ImageWindowManagerFactory.showImage(image.setName("slices: "+image.sizeZ()));
            throw new IllegalArgumentException("Wrong number of images ("+image.sizeZ()+" instead of "+FCZCount[0]*FCZCount[1]*FCZCount[2]);
        }
        logger.debug("reslice: FCZ:{}", FCZCount);
        Image[][] resTC = new Image[FCZCount[1]][FCZCount[0]];
        for (int f = 0; f<FCZCount[0]; ++f) {
            for (int c = 0; c<FCZCount[1]; ++c) {
                List<Image> imageSlices = new ArrayList<>(FCZCount[2]);
                for (int z = 0; z<FCZCount[2]; ++z) {
                    imageSlices.add(image.getZPlane(getStackIndex.apply(new int[]{f, c, z})));
                }
                resTC[c][f] = Image.mergeZPlanes(imageSlices);
            }
        }
        return resTC;
    }
    static double[] getDisplayRange(Image im, ImageMask mask) {
        Histogram hist = HistogramFactory.getHistogram(()->im.stream(mask, true).parallel(), HistogramFactory.BIN_SIZE_METHOD.AUTO_WITH_LIMITS);
        int minIdx = hist.getMinNonNullIdx();
        int maxIdx = hist.getMaxNonNullIdx();
        double minValue = hist.getValueFromIdx(minIdx);
        double maxValue = hist.getValueFromIdx(maxIdx);
        if (hist.getData().length==2 || minIdx==maxIdx+1 || minIdx==maxIdx) return new double[]{minValue, minValue+hist.getBinSize()}; // binary image
        //hist.removeSaturatingValue(5, true);
        hist.removeSaturatingValue(5, false);
        double[] per =  hist.getQuantiles(0, 1);
        if (per[0]==per[1]) {
            per[0] = minValue;
            per[1] = maxValue;
        }
        if (per[0]>0 &&  im instanceof ImageInteger) per[0] -= 1; // possibly a label image
        return per;
    }
}
