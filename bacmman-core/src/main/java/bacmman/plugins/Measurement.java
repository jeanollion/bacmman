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
package bacmman.plugins;

import bacmman.data_structure.SegmentedObject;
import bacmman.measurement.MeasurementKey;

import java.util.List;

/**
 *
 * @author Jean Ollion
 */
public interface Measurement extends Plugin {
    /**
     * 
     * @return index of structure of the objects that will be provided to the method {@link Measurement#performMeasurement(SegmentedObject)}  }.
     * In case the measurement depends on several structures, it should be the index of the fisrt common parent
     */
    public int getCallObjectClassIdx();
    /**
     * 
     * @return true if the measurement should be called only on track heads, false if it should be called on each frame
     */
    public boolean callOnlyOnTrackHeads();
    /**
     * 
     * @return the list of MeasurementKeys generated by this measurement. They correspond to a Key (String) which is the column in the output data and to an object class index to which the measurement will be associated.
     */
    public List<MeasurementKey> getMeasurementKeys();
    /**
     * 
     * @param object object to perform measurement on. The object class index is the one returned by {@link Measurement#getCallObjectClassIdx()}. If {@link Measurement#callOnlyOnTrackHeads()} is true {@param object} is first element of its track
     */
    public void performMeasurement(SegmentedObject object);
}
