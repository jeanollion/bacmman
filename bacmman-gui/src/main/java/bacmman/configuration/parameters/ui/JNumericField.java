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

import java.awt.Color;
import java.awt.Dimension;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import javax.swing.JTextField;
import javax.swing.text.*;


public class JNumericField extends JTextField {
    private static final long serialVersionUID = 1L;

    private static final char DOT = '.';
    private static final char NEGATIVE = '-';
    private static final String BLANK = "";
    private static final int DEF_PRECISION = 2;

    public static final int NUMERIC = 2;
    public static final int DECIMAL = 3;
    
    public static final String FM_NUMERIC = "0123456789";
    public static final String FM_DECIMAL = FM_NUMERIC + DOT; 

    private int maxLength = 0;
    private int format = NUMERIC;
    private String negativeChars = BLANK;
    private String allowedChars = null;
    private boolean allowNegative = false;
    private int precision = 0;
    private DecimalFormat df;
    protected PlainDocument numberFieldFilter;
    
    public JNumericField(int precision) {
        this(50, precision, precision==0?NUMERIC:DECIMAL);
    }

    private JNumericField(int iMaxLen, int precision, int iFormat) {
        DecimalFormatSymbols otherSymbols = new DecimalFormatSymbols();
        otherSymbols.setDecimalSeparator('.');
        df = new DecimalFormat("#0", otherSymbols);
        setAllowNegative(true);
        setMaxLength(iMaxLen);
        setFormat(iFormat);
        setPrecision(precision);
        numberFieldFilter = new JNumberFieldFilter();
        super.setDocument(numberFieldFilter);
        super.setPreferredSize(new Dimension(100, 25));
    }
    

    public void setMaxLength(int maxLen) {
        if (maxLen > 0)
            maxLength = maxLen;
        else
            maxLength = 0;
    }

    public int getMaxLength() {
        return maxLength;
    }

    public void setEnabled(boolean enable) {
        super.setEnabled(enable);

        if (enable) {
            setBackground(Color.white);
            setForeground(Color.black);
        } else {
            setBackground(Color.lightGray);
            setForeground(Color.darkGray);
        }
    }

    public void setEditable(boolean enable) {
        super.setEditable(enable);

        if (enable) {
            setBackground(Color.white);
            setForeground(Color.black);
        } else {
            setBackground(Color.lightGray);
            setForeground(Color.darkGray);
        }
    }

    public void setPrecision(int iPrecision) {
        if (format == NUMERIC)
            return;

        if (iPrecision >= 0)
            precision = iPrecision;
        else
            precision = DEF_PRECISION;
        df.setMaximumFractionDigits(precision);
    }

    public int getPrecision() {
        return precision;
    }

    public Number getNumber() {
        if (getText().length()==0 || getText().equals("-")) return null;
        Number number = null;
        if (format == NUMERIC)
            number = new Integer(getText());
        else
            number = new Double(getText());

        return number;
    }

    public void setNumber(Number value) {
        setText(df.format(value));
        //setText(String.valueOf(value));
        //logger.trace("ser text: value: {}, format: {}, getText():{}", value, df.format(value), getText());
        
    }

    public int getInt() {
        return Integer.parseInt(getText());
    }

    /*public void setInt(int value) {
        setText(String.valueOf(value));
    }*/

    public float getFloat() {
        return (new Float(getText())).floatValue();
    }

    /*public void setFloat(float value) {
        setText(String.valueOf(value));
    }*/

    public double getDouble() {
        return (new Double(getText())).doubleValue();
    }

    /*public void setDouble(double value) {
        setText(String.valueOf(value));
    }*/

    public int getFormat() {
        return format;
    }

    public void setFormat(int iFormat) {
        switch (iFormat) {
        case NUMERIC:
        default:
            format = NUMERIC;
            precision = 0;
            allowedChars = FM_NUMERIC;
            break;

        case DECIMAL:
            format = DECIMAL;
            precision = DEF_PRECISION;
            allowedChars = FM_DECIMAL;
            break;
        }
        df.setMaximumFractionDigits(precision);
    }

    public void setAllowNegative(boolean b) {
        allowNegative = b;

        if (b)
            negativeChars = "" + NEGATIVE;
        else
            negativeChars = BLANK;
    }

    public boolean isAllowNegative() {
        return allowNegative;
    }

    public void setDocument(Document document) {
    }

    class JNumberFieldFilter extends PlainDocument {
        private static final long serialVersionUID = 1L;

        public JNumberFieldFilter() {
            super();
        }

        public void insertString(int offset, String str, AttributeSet attr)
                throws BadLocationException {
            String text = getText(0, offset) + str
                    + getText(offset, (getLength() - offset));

            if (str == null || text == null)
                return;

            for (int i = 0; i < str.length(); i++) {
                if ((allowedChars + negativeChars).indexOf(str.charAt(i)) == -1)
                    return;
            }

            int precisionLength = 0, dotLength = 0, minusLength = 0;
            int textLength = text.length();
            if (textLength==0) return;
            try {
                if (format == NUMERIC) {
                    if (!((text.equals(negativeChars)) && (text.length() == 1)))
                        new Long(text);
                } else if (format == DECIMAL) {
                    if (!((text.equals(negativeChars)) && (text.length() == 1))) 
                        new Double(text);

                    int dotIndex = text.indexOf(DOT);
                    if (dotIndex != -1) {
                        dotLength = 1;
                        precisionLength = textLength - dotIndex - dotLength;

                        if (precisionLength > precision)
                            return;
                    }
                }
            } catch (Exception ex) {
                return;
            }

            if (text.startsWith("" + NEGATIVE)) {
                if (!allowNegative)
                    return;
                else
                    minusLength = 1;
            }

            if (maxLength < (textLength - dotLength - precisionLength - minusLength))
                return;

            super.insertString(offset, str, attr);
            
        }

    }

}
