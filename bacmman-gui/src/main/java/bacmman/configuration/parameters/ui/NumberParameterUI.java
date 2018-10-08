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
package bacmman.configuration.parameters.ui;

import bacmman.configuration.parameters.BoundedNumberParameter;
import bacmman.configuration.parameters.NumberParameter;
import bacmman.ui.gui.configuration.ConfigurationTreeModel;
import bacmman.configuration.parameters.ListParameter;
import bacmman.configuration.parameters.SimpleListParameter;
import java.awt.Dimension;
import java.awt.event.FocusEvent;
import java.awt.event.FocusListener;
import java.math.BigDecimal;
import javax.swing.JSlider;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/**
 *
 * @author Jean Ollion
 */
public class NumberParameterUI implements ParameterUI {

    static final int componentMinWith = 80;
    JNumericField number;
    NumberParameter parameter;
    ConfigurationTreeModel model;
    Number lowerBound, upperBound;
    JSlider slider;
    double sliderCoeff;
    boolean editing=false;
    public NumberParameterUI(NumberParameter parameter_, ConfigurationTreeModel model) {
        this.parameter = parameter_;
        this.number = new JNumericField(parameter.getDecimalPlaceNumber());
        this.number.setNumber(parameter.getValue());
        this.model = model;
        if (parameter instanceof BoundedNumberParameter) {
            lowerBound = ((BoundedNumberParameter)parameter).getLowerBound();
            upperBound = ((BoundedNumberParameter)parameter).getUpperBound();
            if (lowerBound!=null && upperBound!=null) {
                sliderCoeff = Math.pow(10, parameter.getDecimalPlaceNumber());
                slider = new JSlider((int)(lowerBound.doubleValue()*sliderCoeff), getSliderValue(upperBound), getSliderValue(upperBound));
                if (parameter.getValue()!=null) slider.setValue(getSliderValue(parameter.getValue()));
                slider.addChangeListener(new ChangeListener() {
                    @Override
                    public void stateChanged(ChangeEvent e) {
                        //if (slider.getValueIsAdjusting()) return;
                        if (editing) return;
                        double d = (slider.getValue()+0.0)/sliderCoeff;
                        number.setNumber(d);
                        if (parameter.getValue().doubleValue()!=d) {
                            parameter.setValue(d);
                        }
                        updateNode();
                    }
                });
            }
        }
        this.number.getDocument().addDocumentListener(new DocumentListener() {

            @Override public void insertUpdate(DocumentEvent e) {
                //System.out.println("insert");
                updateNumber();
            }

            @Override public void removeUpdate(DocumentEvent e) {
                //System.out.println("remove");
                updateNumber();
            }

            @Override public void changedUpdate(DocumentEvent e) {
                //System.out.println("change");
                updateNumber();
            }
        });
        // if bounds were not respected -> after focus is lost always ensure consistency with parameter
        this.number.addFocusListener(new FocusListener() {
            @Override public void focusGained(FocusEvent e) {}

            @Override
            public void focusLost(FocusEvent e) {
                if (number.getDouble()!=parameter.getValue().doubleValue()) number.setNumber(parameter.getValue());
            }
        });
    }

    @Override
    public Object[] getDisplayComponent() {
        if (slider==null) return new Object[]{number};
        else return new Object[]{number, slider};
    }

    private void updateNumber() {
        editing = true;
        boolean modif = false;
        Number n = number.getNumber();
        if (n != null) {
            if (lowerBound!=null && compare(n, lowerBound)<0) {
                n=lowerBound;
                //number.setNumber(n);
            }
            if (upperBound!=null && compare(n, upperBound)>0) {
                n=upperBound;
                //number.setNumber(n);
            }
            if (slider!=null) slider.setValue(getSliderValue(n));
            if (!parameter.getValue().equals(n)) {
                modif = true;
                parameter.setValue(n); // fire listener
            }
        }
        editing = false;
        if (modif) {
            number.setPreferredSize(new Dimension(Math.max(componentMinWith, number.getText().length() * 9), number.getPreferredSize().height)); 
            updateNode();
            //parameter.fireListeners(); // fired when set value
        }
    }
    
    private void updateNode() {
        if (model != null) {
            model.nodeChanged(parameter);
            if (parameter.getParent() instanceof ListParameter) {
                model.nodeStructureChanged(parameter.getParent());
                if (parameter.getParent() instanceof SimpleListParameter) ((SimpleListParameter)parameter.getParent()).resetName(null);
            }
        }
    }
    
    private int getSliderValue(Number a) {
        if (a instanceof Integer || a instanceof Short || a instanceof Byte) return (int)(a.intValue()*sliderCoeff);
        else return (int)(a.doubleValue()*sliderCoeff+0.5);
    }
    private int compare(Number a, Number b){
        return new BigDecimal(a.toString()).compareTo(new BigDecimal(b.toString()));
    }

}
