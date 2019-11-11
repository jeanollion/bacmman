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
package bacmman.measurement;
import static bacmman.data_structure.Processor.logger;
import bacmman.data_structure.dao.MasterDAO;
import bacmman.data_structure.Selection;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.List;

/**
 *
 * @author Jean Ollion
 */
public class SelectionExtractor {
    final static String separator =";";
    public final static String NaN = "NaN";
    protected static StringBuilder getHeader() { //TODO split Indicies column ...
        StringBuilder sb = new StringBuilder(70);
        sb.append("Position").append(separator);
        sb.append("PositionIdx").append(separator);
        sb.append("ObjectClassIdx").append(separator);
        sb.append("Indices").append(separator);
        sb.append("Frame").append(separator);
        sb.append("SelectionName");
        return sb;
    }
    protected static StringBuilder getLine(Selection s, String position, int positionIdx, String object) {
        StringBuilder sb = new StringBuilder();
        sb.append(position).append(separator);
        sb.append(positionIdx).append(separator);
        sb.append(s.getStructureIdx()).append(separator);
        sb.append(object).append(separator);
        int[] idx = Selection.parseIndices(object);
        sb.append(idx[0]).append(separator);
        sb.append(s.getName());
        return sb;
    }
    public static void extractSelections(MasterDAO db, List<Selection> selections, String outputFile) {
        long t0 = System.currentTimeMillis();
        FileWriter fstream;
        BufferedWriter out;
        int count = 0;
        try {
            File output = new File(outputFile);
            output.delete();
            fstream = new FileWriter(output);
            out = new BufferedWriter(fstream);
            out.write(getHeader().toString()); 
            for (Selection s : selections) {
                for (String position : s.getAllPositions()) {
                    int pIdx = db.getExperiment().getPositionIdx(position);
                    for (String object : s.getElementStrings(position)) {
                        out.newLine();
                        out.write(getLine(s, position, pIdx, object).toString()); 
                        ++count;
                    }
                }
            }
            out.close();
            long t1 = System.currentTimeMillis();
            logger.debug("selection extractions: {} line in: {} ms", count, t1-t0);
        } catch (IOException ex) {
            logger.debug("init extract selection error: {}", ex);
        }
    }
}
