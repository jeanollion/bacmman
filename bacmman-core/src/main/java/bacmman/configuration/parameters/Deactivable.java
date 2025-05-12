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
package bacmman.configuration.parameters;

import org.json.simple.JSONArray;
import org.json.simple.JSONAware;
import org.json.simple.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author Jean Ollion
 */
public interface Deactivable {
    Logger logger = LoggerFactory.getLogger(Deactivable.class);
    boolean isActivated();
    void setActivated(boolean activated);
    static boolean needsRemoveActivatedProperty(Object entry) {
        if (entry instanceof JSONArray) {
            JSONArray ja = (JSONArray) entry;
            for (Object o : ja) {
                if (o instanceof JSONObject && ((JSONObject)o).containsKey("activated")) return true;
            }
            return false;
        }
        return false;
    }
    static Object copyAndRemoveActivatedPropertyIfNecessary(Object entry) {
        if (!needsRemoveActivatedProperty(entry)) return entry;
        if (entry instanceof JSONArray) {
            JSONArray newJA = new JSONArray();
            for (Object o : (JSONArray)entry) {
                if (o instanceof JSONObject && ((JSONObject) o).containsKey("activated")) continue;
                newJA.add(o);
            }
            return newJA;
        } else return entry;
    }
    static boolean getActivated(Object entry) {
        if (entry instanceof JSONArray) {
            JSONArray ja = (JSONArray) entry;
            for (Object o : ja) {
                if (o instanceof JSONObject && ((JSONObject)o).containsKey("activated")) return (Boolean)((JSONObject) o).get("activated");
            }
            return true;
        } else if (entry instanceof JSONObject) {
            JSONObject jo = (JSONObject) entry;
            return (Boolean)jo.getOrDefault("activated", true);
        } else return true;
    }
    static void appendActivated(JSONAware entry, boolean activated) {
        if (entry instanceof JSONArray) {
            JSONObject o = new JSONObject();
            o.put("activated", activated);
            ((JSONArray)entry).add(o);
        } else if (entry instanceof JSONObject) {
            ((JSONObject) entry).put("activated", activated);
        }
    }
}
