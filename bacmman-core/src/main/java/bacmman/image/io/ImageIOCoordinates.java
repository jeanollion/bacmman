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
package bacmman.image.io;

import bacmman.image.BoundingBox;
import bacmman.image.SimpleBoundingBox;


/**
 *
 * @author Jean Ollion
 */

public class ImageIOCoordinates {
    public enum RGB{
        R(0), G(1), B(2);
        public final int idx;
        RGB(int idx) {this.idx=idx;}
    }
    int serie, channel, timePoint;
    BoundingBox bounds;
    RGB rgb=RGB.R;
    public ImageIOCoordinates(int serie, int channel, int timePoint) {
        this.serie = serie;
        this.channel = channel;
        this.timePoint = timePoint;
    }
    
    public ImageIOCoordinates(int serie, int channel, int timePoint, BoundingBox bounds) {
        this.serie = serie;
        this.channel = channel;
        this.timePoint = timePoint;
        this.bounds = bounds;
    }
    public ImageIOCoordinates setRGB(RGB rgb) {
        this.rgb = rgb;
        return this;
    }
    public ImageIOCoordinates setRGB(int rgb) {
        switch (rgb) {
            default:
            case 0:
                this.rgb = RGB.R;
                break;
            case 1:
                this.rgb = RGB.G;
                break;
            case 2:
                this.rgb = RGB.B;
                break;
        }
        return this;
    }
    public ImageIOCoordinates(BoundingBox bounds) {
        this.bounds=bounds;
    }
    
    public ImageIOCoordinates() {}

    public int getSerie() {
        return serie;
    }

    public int getChannel() {
        return channel;
    }

    public int getTimePoint() {
        return timePoint;
    }

    public BoundingBox getBounds() {
        return bounds;
    }
    
    public ImageIOCoordinates setBounds(BoundingBox bounds) {
        this.bounds=bounds;
        return this;
    }

    public RGB getRGB() {
        return rgb;
    }

    public ImageIOCoordinates duplicate() {
        return new ImageIOCoordinates(serie, channel, timePoint, (bounds!=null)?new SimpleBoundingBox(bounds):null);
    }
    
}
