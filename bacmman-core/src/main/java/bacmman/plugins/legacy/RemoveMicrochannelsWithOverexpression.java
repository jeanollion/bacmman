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
package bacmman.plugins.legacy;

/**
 *
 * @author Jean Ollion
 */
public class RemoveMicrochannelsWithOverexpression {/*implements TrackPostFilter {
    PluginParameter<ThresholderHisto> satThld = new PluginParameter<>("Method", ThresholderHisto.class, new BackgroundFit(40), false);
    BooleanParameter trim = new BooleanParameter("Remove Method", "Trim Track", "Remove Whole Track", true);
    Parameter[] parameters = new Parameter[]{intensityPercentile, interQuartileFactor, trim};
    final static int SUCCESSIVE_SATURATED = 3;
    public RemoveMicrochannelsWithOverexpression() {}
    public RemoveMicrochannelsWithOverexpression(double intensityPercentile, double interQuartileFactor) {
        this.intensityPercentile.setValue(intensityPercentile);
        this.interQuartileFactor.setValue(interQuartileFactor);
    }
    public RemoveMicrochannelsWithOverexpression setTrim(boolean trim) {
        this.trim.setSelected(trim);
        return this;
    }
    @Override
    public void filter(int structureIdx, List<StructureObject> parentTrack) {
        Map<StructureObject, List<StructureObject>> allTracks = StructureObjectUtils.getAllTracks(parentTrack, structureIdx);
        double per = intensityPercentile.getValue().doubleValue()/100d;
        Map<StructureObject, Double> value = Utils.flattenMap(allTracks).parallelStream().collect(Collectors.toMap(o->o, o->ImageOperations.getQuantiles(o.getRawImage(structureIdx), o.getMask(), null, per)[0]));
        List<Double> distribution = new ArrayList<>(value.values());
        double quart1 = ArrayUtil.quantile(distribution, 0.25);
        double quart2 = ArrayUtil.quantile(distribution, 0.5);
        double quart3 = ArrayUtil.quantile(distribution, 0.75);
        double thld = quart2 + (quart3-quart1) * interQuartileFactor.getValue().doubleValue();
        if (testMode) logger.debug("RemoveMicrochannelsWithOverexpression Q1: {}, Q2: {}, Q3: {}, Thld: {}", quart1, quart2, quart3, thld);
        List<StructureObject> objectsToRemove = new ArrayList<>();
        for (Entry<StructureObject, List<StructureObject>> e : allTracks.entrySet()) {
            int sat = 0;
            int idx = 0;
            for (StructureObject o : e.getValue()) {
                if (value.get(o)>thld) ++sat;
                if (sat>=SUCCESSIVE_SATURATED) {
                    if (trim.getSelected()) {
                        int idxStart = idx-sat+1;
                        if (idxStart==1) idxStart = 0;
                        objectsToRemove.addAll(e.getValue().subList(idx-sat+1, e.getValue().size()));
                    } else objectsToRemove.addAll(e.getValue());
                }
                ++idx;
            }
        }
        //logger.debug("remove track trackLength: #objects to remove: {}", objectsToRemove.size());
        if (!objectsToRemove.isEmpty()) ManualEdition.deleteObjects(null, objectsToRemove, ManualEdition.ALWAYS_MERGE, false);
    }
    
    @Override
    public Parameter[] getParameters() {
        return parameters;
    }
    boolean testMode;
    public void setTestMode(boolean testMode) {this.testMode=testMode;}
*/
}
