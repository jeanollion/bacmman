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

/**
 *
 * @author Jean Ollion
 */
public enum ImageFormat {
        PNG(".png", true, false, false),
        TIF(".tif", false, true, false), // si on n'écrit pas avec le writer de bio-formats
        OMETIF(".ome.tiff", false, true, true),
        DV(".dv", false, true, true);
        final private String extension;
        final private boolean invertTZ;
        final private boolean view;
        final private boolean allowMultipleTimeAndChannel;
        ImageFormat(String extension, boolean invertTZ, boolean view, boolean allowMutlipleTimeAndChannel) {
            this.extension=extension;
            this.invertTZ=invertTZ;
            this.view=view;
            this.allowMultipleTimeAndChannel=allowMutlipleTimeAndChannel;
        }
        public static ImageFormat getExtension(String extension) {
            for (ImageFormat e : ImageFormat.values()) if (e.getExtension().equals(extension)) return e;
            return null;
        }
        public String getExtension() {
            return extension;
        }
        public boolean getInvertTZ() {
            return invertTZ;
        }
        public boolean getSupportView() {
            return view;
        }
        public boolean getSupportMultipleTimeAndChannel() {
            return this.allowMultipleTimeAndChannel;
        }
        @Override public String toString() {return extension;}
    }
