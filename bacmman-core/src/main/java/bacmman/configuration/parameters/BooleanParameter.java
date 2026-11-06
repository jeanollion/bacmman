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


/**
 * ChoiceParameter with two elements, 1st = true, 2nd = false
 * @author Jean Ollion
 */
public class BooleanParameter extends AbstractChoiceParameterFixedChoiceList<Boolean, BooleanParameter> {
    boolean defaultValue; // used when no value is set (e.g. empty value in a configuration file): a boolean parameter always has a value

    public BooleanParameter(String name) {
        this(name, false);
    }
    
    public BooleanParameter(String name, boolean defaultValue) {
        super(name, new String[]{"true", "false"}, defaultValue?"true":"false", "true"::equals, null, false);
        this.defaultValue = defaultValue;
        toString = s -> getChoiceList()[s ? 0 : 1];
    }
    
    public BooleanParameter(String name, String trueLabel, String falseLabel, boolean defaultValue) {
        super(name, new String[]{trueLabel, falseLabel}, defaultValue?trueLabel:falseLabel, trueLabel::equals, null, false);
        this.defaultValue = defaultValue;
        toString = s -> getChoiceList()[s ? 0 : 1];
        //if (listChoice.length!=2) throw new IllegalArgumentException("List choice should be of length 2");
    }

    public boolean getSelected() {
        return this.selectedItem == null ? defaultValue : this.selectedItem;
    }

    @Override
    public void setSelectedItem(String selectedItem) {
        if (selectedItem == null || selectedItem.isEmpty()) setSelected(defaultValue);
        else super.setSelectedItem(selectedItem);
    }

    @Override
    public void setContentFrom(Parameter other) {
        if (other instanceof BooleanParameter) setSelected(((BooleanParameter) other).getSelected());
        else super.setContentFrom(other);
        if (!(this.selectedItem instanceof Boolean)) setSelected(defaultValue); // other had no selection, or a value of another type
    }
    
    public void setSelected(boolean selected){
        if (selected) super.setSelectedIndex(0);
        else super.setSelectedIndex(1);
    }
    @Override public BooleanParameter duplicate() {
        BooleanParameter res = new BooleanParameter(name, this.listChoice[0], this.listChoice[1], this.getSelected());
        res.defaultValue = defaultValue;
        res.setListeners(listeners);
        res.addValidationFunction(additionalValidation);
        res.setHint(toolTipText);
        res.setSimpleHint(toolTipTextSimple);
        res.setEmphasized(isEmphasized);
        return res;
    }
    @Override
    public Boolean getValue() {
        return getSelected();
    }

    @Override
    public void setValue(Boolean value) {
        this.setSelected(value);
    }

    @Override
    public String getNoSelectionString() {
        return null;
    }

}
