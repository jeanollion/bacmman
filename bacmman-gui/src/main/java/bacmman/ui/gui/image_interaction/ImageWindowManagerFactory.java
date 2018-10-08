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
import bacmman.image.Image;

/**
 *
 * @author Jean Ollion
 */
public class ImageWindowManagerFactory {
    private static ImageWindowManager currentImageManager;
    private static ImageDisplayer imageDisplayer;
    public static <I> void setImageDisplayer(ImageDisplayer<I> imageDisplayer, ImageWindowManager<I, ?, ?> windowsManager) {
        ImageWindowManagerFactory.imageDisplayer=imageDisplayer;
        currentImageManager=windowsManager;
    }
    public static ImageWindowManager getImageManager() {
        if (currentImageManager==null) currentImageManager = new IJImageWindowManager(null, getDisplayer());
        return currentImageManager;
    }
    public static Object showImage(Image image) {
        return getDisplayer().showImage(image);
    }
    public static Object showImage5D(String title, Image[][] imageTC) {
        return getDisplayer().showImage5D(title, imageTC);
    }
    private static ImageDisplayer getDisplayer() {
        if (imageDisplayer==null) imageDisplayer = new IJImageDisplayer();
        return imageDisplayer;
    }
}
