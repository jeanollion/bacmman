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
package bacmman.dummy_plugins;

import bacmman.configuration.parameters.BooleanParameter;
import bacmman.configuration.parameters.NumberParameter;
import bacmman.configuration.parameters.Parameter;
import bacmman.data_structure.Region;
import bacmman.data_structure.RegionPopulation;
import bacmman.data_structure.SegmentedObject;
import bacmman.image.BlankMask;
import bacmman.image.Image;
import bacmman.image.ImageMask;
import java.util.ArrayList;

import bacmman.plugins.Segmenter;

/**
 *
 * @author Jean Ollion
 */
public class DummySegmenter implements Segmenter {
    BooleanParameter segDir = new BooleanParameter("Segmentation direction", "X", "Y", true);
    NumberParameter objectNb = new NumberParameter("Number of Objects", 0, 2);
    Parameter[] parameters = new Parameter[]{objectNb, segDir};
    public DummySegmenter(){}
    public DummySegmenter(boolean dirX, int objectNb) {
        this.segDir.setSelected(dirX);
        this.objectNb.setValue(objectNb);
    }
    
    public RegionPopulation runSegmenter(Image input, int objectClassIdx, SegmentedObject structureObject) {
        ImageMask mask;
        if (structureObject==null) mask = new BlankMask(input);
        else mask = structureObject.getMask();
        int nb = objectNb.getValue().intValue();
        //System.out.println("dummy segmenter: nb of objects: "+nb+ " segDir: "+segDir.getSelectedItem());
        BlankMask[] masks = new BlankMask[nb];
        if (segDir.getSelected()) {
            double w = Math.max((mask.sizeX()+0.0d) / (2*nb+1.0), 1);
            int h = (int)(mask.sizeY()*0.8d);
            for (int i = 0; i<nb; ++i) masks[i] = new BlankMask( (int)w, h, mask.sizeZ(), (int)((2*i+1)*w) ,(int)(0.1*mask.sizeY()), 0, mask.getScaleXY(), mask.getScaleZ());
        } else {
            double h = Math.max((mask.sizeY()+0.0d) / (2*nb+1.0), 1);
            int w = (int)(mask.sizeX()*0.8d);
            for (int i = 0; i<nb; ++i) masks[i] = new BlankMask( w, (int)h, mask.sizeZ(), (int)(0.1*mask.sizeX()) ,(int)((2*i+1)*h), 0, mask.getScaleXY(), mask.getScaleZ());
        }
        ArrayList<Region> objects = new ArrayList<>(nb); int idx=1;
        for (BlankMask m :masks) objects.add(new Region(m, idx++, mask.sizeZ()==1));
        return new RegionPopulation(objects, input);
    }

    public boolean callOnlyOnTrackHeads() {
        return false;
    }

    public Parameter[] getParameters() {
        return parameters;
    }

    public boolean does3D() {
        return true;
    }
    
    public void setSegmentationDirection(boolean dirX) {
        segDir.setSelected(dirX);
    }
    
    public void setObjectNumber(int objectNb) {
        this.objectNb.setValue(objectNb);
    }
    
}
