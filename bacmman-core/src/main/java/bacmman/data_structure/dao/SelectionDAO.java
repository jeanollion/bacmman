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

import bacmman.data_structure.Selection;
import bacmman.utils.FileIO;
import bacmman.utils.HashMapGetCreate;
import bacmman.utils.JSONUtils;
import org.json.simple.parser.ParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 *
 * @author Jean Ollion
 */
public interface SelectionDAO {
    static final Logger logger = LoggerFactory.getLogger(SelectionDAO.class);
    void clearCache();
    
    Selection getOrCreate(String name, boolean clearIfExisting);
    
    List<Selection> getSelections();
        
    void clearSelectionCache(String... positions);

    void store(Selection s);
    
    void delete(String id);
    
    void delete(Selection o);
    
    public void deleteAllObjects();
    void erase();
    boolean isReadOnly();

    static List<Selection> readFile(File file) throws IOException {
        if (file.getName().endsWith(".txt") || file.getName().endsWith(".json")) return readJSON(file);
        else if (file.getName().endsWith(".csv")) return readCSV(file);
        else throw new IOException("Ivalid file format: "+file);
    }

    static List<Selection> readCSV(File csv) throws IOException  {
        FileIO.TextFile tf = new FileIO.TextFile(csv.getAbsolutePath(), false, false);
        List<String> lines = tf.readLines();
        List<String> header = Arrays.asList(lines.remove(0).split(";"));
        int posIdx = header.indexOf("Position");
        int ocIdx = header.indexOf("ObjectClassIdx");
        int indicesIdx = header.indexOf("Indices");
        int selNameIdx = header.indexOf("SelectionName");
        if (posIdx < 0 || ocIdx < 0 || indicesIdx < 0 || selNameIdx < 0 ) throw new IOException("Invalid header");
        Map<String, Selection> selectionMap = new HashMapGetCreate.HashMapGetCreateRedirected<>(n -> new Selection(n, null));
        for (String l : lines) {
            String[] attr = l.split(";");
            Selection s = selectionMap.get(attr[selNameIdx]);
            int oc;
            try {
                oc = Integer.parseInt(attr[ocIdx]);
            } catch (NumberFormatException e) {
                throw new IOException(e);
            }
            if (s.isEmpty()) s.setObjectClassIdx(oc);
            else if (s.getObjectClassIdx() != oc) throw new IOException("Several object classes in selection" + s.getName());
            s.addElementString(attr[posIdx], attr[indicesIdx]);
        }
        return new ArrayList<>(selectionMap.values());
    };

    static List<Selection> readJSON(File json) throws IOException {
        try {
            return FileIO.readFromFile(json.getAbsolutePath(), s -> {
                try {
                    return JSONUtils.parse(Selection.class, s);
                } catch (ParseException e) {
                    logger.error("Error parsing selection: {}", e.toString());
                    throw new RuntimeException(e);
                }
            }, s -> logger.error("Error while converting json file content: {} -> content :{}", json, s));
        } catch (RuntimeException e) {
            throw new IOException(e.getCause()==null?e:e.getCause());
        }
    };
}
