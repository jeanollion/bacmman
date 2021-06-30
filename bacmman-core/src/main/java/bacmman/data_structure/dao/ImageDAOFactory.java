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

import java.util.HashMap;

/**
 *
 * @author Jean Ollion
 */
public class ImageDAOFactory {
    public enum ImageDAOTypes {LocalTIF};
    static HashMap<String, LocalTIFImageDAO> localDirDAO = new HashMap<String, LocalTIFImageDAO>(1);
    public ImageDAO getImageDAO(ImageDAOTypes type, String localDir) {
        switch (type) {
            case LocalTIF:
            default: {
                return getLocalTIFImageDAO(localDir);
            }
        }
    }
    public static LocalTIFImageDAO getLocalTIFImageDAO(String localDirectory) {
        LocalTIFImageDAO dao = localDirDAO.get(localDirectory);
        if (dao==null) {
            dao = new LocalTIFImageDAO(localDirectory);
            localDirDAO.put(localDirectory, dao);
        }
        return dao;
    }
}
