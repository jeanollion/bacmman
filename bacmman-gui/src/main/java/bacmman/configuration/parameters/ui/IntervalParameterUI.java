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

import bacmman.configuration.parameters.*;
import bacmman.ui.gui.configuration.ConfigurationTreeModel;
import net.imglib2.Interval;

import javax.swing.*;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.FocusEvent;
import java.awt.event.FocusListener;
import java.math.BigDecimal;
import java.util.Map;

/**
 *
 * @author Jean Ollion
 */
public class IntervalParameterUI implements ParameterUI {

    static final int componentMinWith = 80;
    JNumericField number[];
    IntervalParameter parameter;
    ConfigurationTreeModel model;
    Number lowerBound, upperBound;
    JSlider slider[];
    double sliderCoeff;
    boolean editing=false;
    public IntervalParameterUI(IntervalParameter parameter_, ConfigurationTreeModel model) {
        this.parameter = parameter_;
        this.model = model;
        this.number = new JNumericField[parameter.getValues().length];

        for (int i = 0; i<number.length; ++i) {
            this.number[i] = new JNumericField(parameter.getDecimalPlaces());
            this.number[i].setNumber(parameter.getValues()[i]);
        }
        lowerBound = parameter.getLowerBound();
        upperBound = parameter.getUpperBound();
        if (lowerBound!=null && upperBound!=null) {
            sliderCoeff = Math.pow(10, parameter.getDecimalPlaces());
            this.slider = new JSlider[number.length];
            double[] values = parameter.getValuesAsDouble();
            for (int i = 0; i<slider.length; ++i) {
                slider[i] = new JSlider((int) (lowerBound.doubleValue() * sliderCoeff), getSliderValue(upperBound), getSliderValue(upperBound));
                slider[i].setValue(getSliderValue(values[i]));
                int ii = i;
                slider[i].addChangeListener((e) -> {
                    if (editing) return;
                    double d = (slider[ii].getValue() + 0.0) / sliderCoeff;
                    double[] vs = parameter.getValuesAsDouble();
                    /*if (vs[ii] != d) {
                        number[ii].setNumber(d);
                        parameter.setValue(d, ii); // only set if different
                        updateNode();
                    }*/
                    Map<Integer, Number> additionalBounds = parameter.getAdditionalBounds();
                    double low = ii==0 ? parameter.getLowerBound().doubleValue() : vs[ii-1];
                    double high = ii==vs.length-1 ? parameter.getUpperBound().doubleValue() : vs[ii+1];
                    if (d >=low && d<=high) { // within bounds
                        if (vs[ii] != d) { // only set if different
                            // also check bounds
                            double leftBound = additionalBounds.getOrDefault(ii-1, Double.NEGATIVE_INFINITY).doubleValue();
                            double rightBound = additionalBounds.getOrDefault(ii, Double.POSITIVE_INFINITY).doubleValue();
                            boolean set = true;
                            if (d < leftBound) {
                                if (Double.isFinite(leftBound)) d = leftBound;
                                else set = false;
                            }
                            if (d > rightBound) {
                                if (Double.isFinite(rightBound)) d = rightBound;
                                else set = false;
                            }
                            if (set) {
                                number[ii].setNumber(d);
                                parameter.setValue(d, ii);
                                updateNode();
                            }
                        }
                    } else if (d<low) slider[ii].setValue(getSliderValue(low));
                    else slider[ii].setValue(getSliderValue(high));
                });
            }
        }
        for (int i = 0; i<number.length; ++i) {
            int ii = i;
            this.number[i].getDocument().addDocumentListener(new DocumentListener() {

                @Override
                public void insertUpdate(DocumentEvent e) {
                    //System.out.println("insert");
                    updateNumber(ii);
                }

                @Override
                public void removeUpdate(DocumentEvent e) {
                    //System.out.println("remove");
                    updateNumber(ii);
                }

                @Override
                public void changedUpdate(DocumentEvent e) {
                    //System.out.println("change");
                    updateNumber(ii);
                }
            });
            // if bounds were not respected -> after focus is lost always ensure consistency with parameter
            this.number[i].addFocusListener(new FocusListener() {
                @Override public void focusGained(FocusEvent e) {}

                @Override
                public void focusLost(FocusEvent e) {
                    if (number[ii].getDouble()!=parameter.getValues()[ii].doubleValue()) number[ii].setNumber(parameter.getValues()[ii]);
                }
            });
        }
    }

    @Override
    public Object[] getDisplayComponent() {
        if (slider==null) return number;
        else {
            Object[] res = new Object[number.length*2];
            System.arraycopy(number, 0, res, 0 ,number.length);
            System.arraycopy(slider, 0, res, number.length ,number.length);
            return res;
        }
    }

    private void updateNumber(int index) {
        if (editing) return;
        editing = true;
        boolean modif = false;

        Number n = number[index].getNumber();
        if (n != null) {
            double d = n.doubleValue();
            double[] vs = parameter.getValuesAsDouble();
            /*double low = index==0 ? (parameter.getLowerBound()==null ? Double.NEGATIVE_INFINITY : parameter.getLowerBound().doubleValue()) : vs[index-1];
            double high = index==vs.length-1 ? (parameter.getUpperBound()==null ? Double.POSITIVE_INFINITY : parameter.getUpperBound().doubleValue())  : vs[index+1];
            if (d<low) d=low;
            if (d>high) d = high;*/
            if (slider != null) slider[index].setValue(getSliderValue(d));
            if (vs[index]!=d) {
                modif = true;
                parameter.setValue(d, index); // fire listener
            }
        }

        editing = false;
        if (modif) {
            number[index].setPreferredSize(new Dimension(Math.max(componentMinWith, number[index].getText().length() * 9), number[index].getPreferredSize().height));
            updateNode();
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
