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
public class BooleanParameter extends AbstractChoiceParameter<Boolean, BooleanParameter> {
    
    public BooleanParameter(String name) {
        this(name, false);
    }
    
    public BooleanParameter(String name, boolean defaultValue) {
        super(name, new String[]{"true", "false"}, defaultValue?"true":"false", s-> "true".equals(s), false);
    }
    
    public BooleanParameter(String name, String trueLabel, String falseLabel, boolean defaultValue) {
        super(name, new String[]{trueLabel, falseLabel}, defaultValue?trueLabel:falseLabel, s-> trueLabel.equals(s), false);
        //if (listChoice.length!=2) throw new IllegalArgumentException("List choice should be of length 2");
    }

    public BooleanParameter setLegacyInitializationValue(boolean selected) {
        this.setLegacyInitializationValue(listChoice[selected?0:1]);
        return this;
    }

    public boolean getSelected() {
        return this.getSelectedIndex()==0;
    }
    
    public void setSelected(boolean selected){
        if (selected) super.setSelectedIndex(0);
        else super.setSelectedIndex(1);
    }
    @Override public BooleanParameter duplicate() {
        BooleanParameter res = new BooleanParameter(name, this.listChoice[0], this.listChoice[1], this.getSelected());
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
    @Override
    public Object toJSONEntry() {
        return selectedItem;
    }

    @Override
    public void initFromJSONEntry(Object json) {
        if (json instanceof String) {
            setSelectedItem((String)json);
        } else throw new IllegalArgumentException("JSON Entry is not String");
    }
}
