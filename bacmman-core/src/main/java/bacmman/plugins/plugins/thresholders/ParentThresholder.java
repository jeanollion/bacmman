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
package bacmman.plugins.plugins.thresholders;

import bacmman.configuration.parameters.BooleanParameter;
import bacmman.configuration.parameters.ConditionalParameter;
import bacmman.configuration.parameters.Parameter;
import bacmman.configuration.parameters.ParentObjectClassParameter;
import bacmman.configuration.parameters.PluginParameter;
import bacmman.configuration.parameters.ObjectClassParameter;
import bacmman.data_structure.Selection;
import bacmman.data_structure.SegmentedObject;
import bacmman.data_structure.SegmentedObjectUtils;
import bacmman.image.Histogram;
import bacmman.image.HistogramFactory;
import bacmman.image.Image;
import bacmman.image.ImageMask;
import bacmman.plugins.Thresholder;
import bacmman.plugins.ThresholderHisto;
import bacmman.utils.JSONUtils;
import bacmman.utils.Utils;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 *
 * @author Jean Ollion
 */
public class ParentThresholder implements Thresholder {
    public ParentObjectClassParameter parent = new ParentObjectClassParameter("Run Thresholder On Parent:").setAutoConfiguration(ParentObjectClassParameter.defaultAutoConfigurationParent());
    public ParentObjectClassParameter structureIdx = new ParentObjectClassParameter("Run Thresholder On Image:").setAutoConfiguration((p)->{int s = ObjectClassParameter.structureInParents().applyAsInt(p); p.setMaxStructureIdx(s+1); p.setSelectedIndex(s);}).setAllowNoSelection(false);
    public BooleanParameter runThresholderOnWholeTrack = new BooleanParameter("Run On:", "Whole Track", "Each Object Separately", true);
    public PluginParameter<Thresholder> thresholder = new PluginParameter("Thresholder", Thresholder.class, false);
    public PluginParameter<ThresholderHisto> thresholderHisto = new PluginParameter("Thresholder", ThresholderHisto.class, false);
    ConditionalParameter cond = new ConditionalParameter(runThresholderOnWholeTrack).setActionParameters("Whole Track", thresholderHisto).setActionParameters("Each Object Separately", thresholder);
    Parameter[] parameters = new Parameter[]{parent, structureIdx, cond};
    
    public ParentThresholder setIndividualThresholder(Thresholder thresholder) {
        this.thresholder.setPlugin(thresholder);
        return this;
    }
    
    public ParentThresholder setTrackThresholder(ThresholderHisto thresholder) {
        this.thresholderHisto.setPlugin(thresholder);
        return this;
    }
    
    public ParentThresholder() {
        /*parent.addListener((p)-> { // parent should be a parent from this structure
            if (parent.getSelectedIndex()>=0) {
                Structure s = ParameterUtils.getFirstParameterFromParents(Structure.class, p, false);
                Experiment xp = ParameterUtils.getExperiment(s);
                if (s!=null) {
                    int sIdx = s.getIndex();
                    if (!xp.isChildOf(parent.getSelectedIndex(), sIdx)) {
                        parent.setSelectedIndex(-1);
                    }
                }
            }
            //checkStructureIdx();
        });*/
        //structureIdx.addListener((p)-> checkStructureIdx());
    }
    
    /*private void checkStructureIdx() {
        if (parent.getSelectedIndex()>=0 && structureIdx.getSelectedIndex()>=0) {
            Experiment xp = ParameterUtils.getExperiment(parent);
            if (!xp.isDirectChildOf(parent.getSelectedIndex(), structureIdx.getSelectedIndex())) {
                structureIdx.setSelectedIndex(-1);
            }
        }
    }*/
    
    @Override
    public double runThresholder(Image input, SegmentedObject structureObject) {
        SegmentedObject p = ((SegmentedObject)structureObject).getParent(parent.getSelectedIndex());
        int sIdx = structureIdx.getSelectedIndex();
        if (runThresholderOnWholeTrack.getSelected()) {
            p = p.getTrackHead();
            List<SegmentedObject> track = SegmentedObjectUtils.getTrack(p, false);
            String key = JSONUtils.toJSON(Arrays.asList(parameters)).toJSONString()+Utils.toStringList(track, s->Selection.indicesString(s)); // key involves configuration + track
            if (!p.getAttributeKeys().contains(key)) { // compute threshold on whole track
                synchronized(p) {
                    // get track histogram
                    Map<Image, ImageMask> map = track.stream().collect(Collectors.toMap(o->o.getRawImage(sIdx), o->o.getMask()));
                    Histogram  histo = HistogramFactory.getHistogram(()->Image.stream(map, true).parallel(), HistogramFactory.BIN_SIZE_METHOD.AUTO_WITH_LIMITS);
                    double thld = this.thresholderHisto.instanciatePlugin().runThresholderHisto(histo);
                    logger.debug("computing thld : {}, thresholder {} on track: {}, key: {}", thld, this.thresholderHisto.getPluginName(), p, key);
                    p.setAttribute(key, thld);
                }
            }
            return p.getAttribute(key, Double.NaN);
        } else { 
            String key = JSONUtils.toJSON(Arrays.asList(parameters)).toJSONString();
            if (!p.getAttributeKeys().contains(key)) { // compute threshold on single object
                synchronized(p) {
                    double thld = thresholder.instanciatePlugin().runThresholder(p.getRawImage(sIdx), p);
                    logger.debug("computing : threshold {}, thresholder: {}, on object: {}, key: {}", thld, this.thresholderHisto.getPluginName(), p , key);
                    
                    p.setAttribute(key, thld);
                }
            }
            return p.getAttribute(key, Double.NaN);
        }
    }

    @Override
    public Parameter[] getParameters() {
        return parameters;
    }
    
}
