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
package bacmman.utils;

import bacmman.core.Core;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 *
 * @author Jean Ollion
 */
public class ArrayFileWriter {
    final static String separator =";";

    Map<String, Object> arrays = new LinkedHashMap<String, Object>();
    public ArrayFileWriter addArray(String columnName, float[] array) {
        arrays.put(columnName, array);
        return this;
    }
    public void writeToFile(String outputFile) {
        try {
            if (arrays.isEmpty()) throw new IllegalArgumentException("no data to write");
            FileWriter fStream;
            BufferedWriter out;
            File output = new File(outputFile);
            output.delete();
            fStream = new FileWriter(output);
            out = new BufferedWriter(fStream);
            Set<String> keys = arrays.keySet();
            int maxLength = 0;
            for (String k : keys) {
                int l = getArrayLength(arrays.get(k));
                if (l>maxLength) maxLength=l;
            }
            String[] lines = new String[maxLength+1];
            for (String k : keys) {
                if (lines[0]==null) lines[0] = k;
                else lines[0]+=separator+k;
                appendArray(arrays.get(k), lines);
            }
            out.write(lines[0]);
            for (int i = 1; i<lines.length; ++i) {
                out.newLine();
                out.write(lines[i]);
            }
            out.close();
        } catch (IOException ex) {
            Core.userLog("Error while writing array"+ ex);
        }
    }
    
    private static int getArrayLength(Object o) {
        if (o instanceof float[]) return ((float[])o).length;
        return 0;
    }
    private static void appendArray(Object o, String[] lines) {
        if (o instanceof float[]) {
            float[] array = (float[])o;
            for (int i = 0; i<array.length; ++i) {
                if (lines[i+1]==null) lines[i+1] = String.valueOf(array[i]);
                else lines[i+1]+=separator+array[i];
            }
            for (int i = array.length+1; i<lines.length; ++i) {
                if (lines[i]==null) lines[i] = String.valueOf(Float.NaN);
                else lines[i]+=separator+Float.NaN;
            } 
        }
    }
}
