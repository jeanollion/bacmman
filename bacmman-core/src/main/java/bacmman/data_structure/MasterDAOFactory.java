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
package bacmman.data_structure;


import bacmman.data_structure.dao.BasicMasterDAO;
import bacmman.data_structure.dao.DBMapMasterDAO;
import bacmman.data_structure.dao.MasterDAO;

/**
 *
 * @author Jean Ollion
 */
public class MasterDAOFactory {
    public enum DAOType {DBMap, Basic};
    private static DAOType currentType = DAOType.DBMap;

    public static DAOType getCurrentType() {
        return currentType;
    }

    public static void setCurrentType(DAOType currentType) {
        MasterDAOFactory.currentType = currentType;
    }
    
    public static MasterDAO createDAO(String dbName, String dir, DAOType daoType) {
        switch (daoType) {
            case DBMap:
                return new DBMapMasterDAO(dir, dbName, new SegmentedObjectAccessor());
            case Basic:
                return new BasicMasterDAO(new SegmentedObjectAccessor());
            default:
                return null;
        }
    }
    public static MasterDAO createDAO(String dbName, String dir) {
        return createDAO(dbName, dir, currentType);
    }
}
