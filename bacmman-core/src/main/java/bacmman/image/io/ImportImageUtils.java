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

import bacmman.image.Image;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 *
 * @author Jean Ollion
 */
public class ImportImageUtils {
    public static List<Double> paseDVLogFile(String path, String key) {
        if (!new File(path).exists()) return null;
        Map<Integer, Double> map = new HashMap<>();
        FileReader input = null;
        Pattern p = Pattern.compile("-?\\d+.?\\d+|-?\\d+");
        try {
            input = new FileReader(path);
            BufferedReader bufRead = new BufferedReader(input);
            String myLine = null;
            int currentImage = -1;
            while ( (myLine = bufRead.readLine()) != null) {
                if (currentImage==-1 && myLine.startsWith("Image ") && myLine.contains(".")) {
                    currentImage = Integer.parseInt(myLine.substring(6, myLine.indexOf('.')))-1;
                    //logger.debug("parse dv log: Image={} @ line: {}", currentImage, myLine);
                } else if (currentImage>=0 && myLine.contains(key)) {
                    Matcher m = p.matcher(myLine);
                    if (m.find()) {
                        double d = Double.parseDouble(m.group());
                        //logger.debug("parse dv log: Key=\"{}\" found @ line: {}", d, myLine);
                        map.put(currentImage, d);
                        currentImage = -1;
                    }
                }
            }
        } catch (IOException ex) {
            map = null;
            Image.logger.debug("an error occured trying to retrieve .dv timePoints: {}", ex);
        } finally {
            try {
                if (input!=null) input.close();
            } catch (IOException ex) {
                
            }
        }
        List<Double> res = new ArrayList<>(new TreeMap(map).values());
        return res;
    }
}
