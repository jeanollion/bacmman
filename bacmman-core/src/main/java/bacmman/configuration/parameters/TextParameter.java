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


import java.util.function.Consumer;

/**
 *
 * @author Jean Ollion
 */
public class TextParameter extends ParameterImpl<TextParameter> implements Listenable<TextParameter> {
    boolean allowSpecialCharacters, allowBlank;
    String value;
    
    public TextParameter(String name) {
        this(name, "", true, true);
    }
    
    public TextParameter(String name, String defaultText, boolean allowSpecialCharacters) {
        this(name, defaultText, allowSpecialCharacters, true);
    }
    public TextParameter(String name, String defaultText, boolean allowSpecialCharacters, boolean allowBlank) {
        super(name);
        this.value=defaultText;
        this.allowSpecialCharacters=allowSpecialCharacters;
        this.allowBlank=allowBlank;
    }

    public boolean isAllowSpecialCharacters() {
        return allowSpecialCharacters;
    }

    public boolean isAllowBlank() {
        return allowBlank;
    }

    @Override
    public boolean isValid() {
        if (!super.isValid()) return false;
        if (!allowBlank && this.value.isEmpty()) return false;
        return !(!allowSpecialCharacters && containsIllegalCharacters(value));
    }

    @Override
    public boolean sameContent(Parameter other) {
        if (other instanceof TextParameter) {
            if (!this.value.equals(((TextParameter)other).getValue())) {
                logger.trace("TextParameter: {}!={} value: {} vs {}", this, other, getValue(), ((TextParameter)other).getValue());
                return false;
            } else return true;
        } else return false;
    }
    @Override
    public void setContentFrom(Parameter other) {
        if (other instanceof TextParameter) this.value=((TextParameter)other).getValue();
        else throw new IllegalArgumentException("wrong parameter type");
        if (this instanceof Deactivable && other instanceof Deactivable) ((Deactivable)this).setActivated(((Deactivable)other).isActivated());
    }
    
    @Override public TextParameter duplicate() {
        TextParameter res =  new TextParameter(name, value, allowSpecialCharacters);
        res.setListeners(listeners);
        res.addValidationFunction(additionalValidation);
        res.setHint(toolTipText);
        res.setSimpleHint(toolTipTextSimple);
        res.setEmphasized(isEmphasized);
        return res;
    }
    
    public void setValue(String value) {
        this.value=value;
        this.fireListeners();
    }
    public String getValue() {return value;}
    
    @Override public String toString() {return getName()+": "+value;}

    @Override
    public Object toJSONEntry() {
        return value;
    }

    @Override
    public void initFromJSONEntry(Object jsonEntry) {
        this.value=(String)jsonEntry;
    }
    

    // recognition of illegal chars
    private static final char[] ILLEGAL_CHARACTERS = {'/', '\n', '\r', '\t', '\0', '\f', '`', '?', '*', '\\', '<', '>', '|', '\"', ':', '.', ';', ',', ' '};
    private static final char[] ILLEGAL_CHARACTERS_START = {'0', '1', '2', '3', '4', '5', '6', '7', '8', '9'};

    public static boolean containsIllegalCharacters(String s) {
        if (s.length()==0) return false;
        if (isIllegalCharStart(s.charAt(0))) return true;
        for (char c : s.toCharArray()) if (isIllegalChar(c)) return true;
        return false;
    }

    public static boolean isIllegalChar(char c) {
        for (int i = 0; i < ILLEGAL_CHARACTERS.length; i++)  {
            if (c == ILLEGAL_CHARACTERS[i])
                return true;
        }
        return false;
    }
    public static boolean isIllegalCharStart (char c) {
        for (int i = 0; i < ILLEGAL_CHARACTERS_START.length; i++) {
            if (c == ILLEGAL_CHARACTERS_START[i])
                return true;
        }
        return false;
    }
}
