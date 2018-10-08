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
package bacmman.plugins.plugins.post_filters;

import bacmman.configuration.parameters.BooleanParameter;
import bacmman.configuration.parameters.BoundedNumberParameter;
import bacmman.configuration.parameters.Parameter;
import bacmman.data_structure.Region;
import bacmman.data_structure.RegionPopulation;
import bacmman.data_structure.SegmentedObject;
import bacmman.measurement.GeometricalMeasurements;
import bacmman.plugins.PostFilter;
import bacmman.plugins.Hint;

/**
 *
 * @author Jean Ollion
 */
public class RemoveEndofChannelBacteria implements PostFilter, Hint {
    BoundedNumberParameter contactProportion = new BoundedNumberParameter("Contact Proportion", 3, 0.25, 0, 1).setHint("contact = number of pixels in contact with open end of channel / width of cell. If contact > this value, cell will be erased. If value = 0 -> this condition won't be tested");
    BoundedNumberParameter sizeLimit = new BoundedNumberParameter("Minimum Size", 0, 300, 0, null).setHint("If cell is in contact with open end of channel and size (in pixels) is inferior to this value, it will be erased even if the proportion of contact is lower than the threshold. O = no size limit");
    BooleanParameter doNotRemoveIfOnlyOne = new BooleanParameter("Do not remove if only one object", true).setHint("If only one object is present in microchannel, it won't be removed even if it is in contact with microchanel opened-end");
    BoundedNumberParameter contactSidesProportion = new BoundedNumberParameter("Contact with Sides Proportion", 3, 0, 0, 1).setHint("contact with sides = number of pixels in contact with left or right sides of microchannel / width of cell. If contact > this value, cell will be erased. If value = 0 -> this condition won't be tested");
    Parameter[] parameters = new Parameter[]{doNotRemoveIfOnlyOne, contactProportion, sizeLimit, contactSidesProportion};
    
    @Override
    public String getHintText() {
        return "Removes trimmed bacteria (in contact with open-end of microchannels)";
    }
    
    public RemoveEndofChannelBacteria(){}
    public RemoveEndofChannelBacteria setContactProportion(double prop) {
        this.contactProportion.setValue(prop);
        return this;
    }
    public RemoveEndofChannelBacteria setContactSidesProportion(double prop) {
        this.contactSidesProportion.setValue(prop);
        return this;
    }
    public RemoveEndofChannelBacteria setSizeLimit(double size) {
        this.sizeLimit.setValue(size);
        return this;
    }
    
    @Override public RegionPopulation runPostFilter(SegmentedObject parent, int childStructureIdx, RegionPopulation childPopulation) {
        if (doNotRemoveIfOnlyOne.getSelected() && childPopulation.getRegions().size()==1) return childPopulation;
        final RegionPopulation.ContactBorder yDown = new RegionPopulation.ContactBorder(0, parent.getMask(), RegionPopulation.Border.YDown);
        final RegionPopulation.ContactBorder xlr = new RegionPopulation.ContactBorder(0, parent.getMask(), RegionPopulation.Border.Xlr);
        final double contactThld=contactProportion.getValue().doubleValue();
        final double contactSideThld = contactSidesProportion.getValue().doubleValue();
        final double sizeLimit  = this.sizeLimit.getValue().doubleValue();
        childPopulation.filter(o->{
            double thick = o.size() / GeometricalMeasurements.getFeretMax(o) ; // estimation of width for rod-shapes objects
            if (contactThld>0) { // contact end
                double contactDown = yDown.getContact(o);
                if (contactDown>0 && sizeLimit>0 && o.size()<sizeLimit) return false;
                double contactYDownNorm = contactDown / thick;
                if (contactYDownNorm>=contactThld) return false;
            }
            if (contactSideThld>0) { // contact on sides
                double contactSides = xlr.getContact(o);
                if (contactSides>0 && sizeLimit>0 && o.size()<sizeLimit) return false;
                double contactXNorm = contactSides / thick;
                if (contactXNorm>=contactSideThld) return false;
            }
            //logger.debug("remove contactDown end of channel o: {}, value: {}({}/{}), remove?{}",getSO(parent, childStructureIdx, o), contactYDownNorm, contactDown, GeometricalMeasurements.medianThicknessX(o), contactYDownNorm >= contactThld);
            return true;
        });
        
        return childPopulation;
    }
    private static SegmentedObject getSO(SegmentedObject parent, int childStructureIdx, Region ob ) {
        return parent.getChildren(childStructureIdx).filter(o->o.getRegion()==ob).findAny().orElse(null);
    }

    @Override public Parameter[] getParameters() {
        return parameters;
    }

    
    
}
