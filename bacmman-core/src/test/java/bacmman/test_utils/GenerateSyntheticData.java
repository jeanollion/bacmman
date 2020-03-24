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
package bacmman.test_utils;

import bacmman.image.Image;
import bacmman.image.ImageByte;
import bacmman.image.io.ImageFormat;
import bacmman.image.io.ImageWriter;

/**
 *
 * @author Jean Ollion
 */
public class GenerateSyntheticData {
    //@Test
    public void generateImages() {
        ImageByte[][] imageTC = new ImageByte[3][2];
        for (int t = 0; t<3; ++t) {
            imageTC[t][0] = new ImageByte("T"+t+"C0", 50, 50, 3);
            fill(imageTC[t][0], 1, 10, 20, 10, 40, 0, 3);
            imageTC[t][1] = new ImageByte("T"+t+"C1", 50, 50, 3);
            fill(imageTC[t][1], 1, 11, 19, 15, 20, 0, 3);
            fill(imageTC[t][1], 2, 11, 19, 25, 30, 0, 3);
            fill(imageTC[t][1], 3, 11, 19, 35, 39, 0, 3);
        }
        fill(imageTC[0][0], 2, 30, 40, 10, 40, 0, 3);
        fill(imageTC[1][0], 2, 28, 40, 8, 40, 1, 3);
        fill(imageTC[2][0], 2, 30, 42, 10, 42, 0, 2);
        
        fill(imageTC[0][1], 4, 31, 39, 15, 20, 0, 1);
        fill(imageTC[1][1], 4, 31, 39, 15, 20, 1, 2);
        fill(imageTC[2][1], 4, 31, 39, 15, 20, 2, 3);
        
        fill(imageTC[0][1], 5, 31, 39, 25, 30, 1, 3);
        fill(imageTC[1][1], 5, 31, 39, 25, 30, 1, 3);
        fill(imageTC[2][1], 5, 31, 39, 25, 30, 1, 3);
        
        fill(imageTC[0][1], 6, 31, 39, 35, 39, 0, 3);
        fill(imageTC[1][1], 6, 33, 39, 37, 39, 0, 3);
        fill(imageTC[2][1], 6, 31, 37, 35, 37, 0, 3);
        
        ImageWriter.writeToFile("/data/Images/Test", "syntheticData", ImageFormat.OMETIF, imageTC);
    }

    /**
     * Generate images with one object (value=2) per time point / channel
     * @param name
     * @param dir
     * @param timePoints
     * @param channels
     * @param size
     * @return
     */
    public static ImageByte[][] generateImages(String name, String dir, int timePoints, int channels, int size) {
        if (size<1 || timePoints<1 || channels<1) throw new IllegalArgumentException("invalid dimension");
        ImageByte[][] imageTC = new ImageByte[timePoints][channels];
        for (int t = 0; t<imageTC.length; ++t) {
            for (int c = 0; c<imageTC[0].length; ++c) {
                imageTC[t][c] = new ImageByte("T"+t+"C"+c, size+2, size+2, size+2);
                fill(imageTC[t][c], 2, 1, size+1, 1, size+1, 1, size+1);
            }
        }
        if (dir!=null) ImageWriter.writeToFile(dir, name, ImageFormat.OMETIF, imageTC);
        return imageTC;
    }
    
    private static void fill(Image image, int value, int x0, int x1, int y0, int y1, int z0, int z1) {
        for (int z=z0; z<z1; ++z) {
            for (int y=y0; y<y1; ++y) {
                for (int x=x0; x<x1; ++x) {
                    image.setPixel(x, y, z, value);
                }
            }
        }
    }
}
