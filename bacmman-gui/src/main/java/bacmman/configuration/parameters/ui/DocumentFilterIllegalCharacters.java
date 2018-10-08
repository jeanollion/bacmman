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

import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.DocumentFilter;

import static bacmman.configuration.parameters.TextParameter.isIllegalChar;
import static bacmman.configuration.parameters.TextParameter.isIllegalCharStart;


/**
 *
 **
 * /**
 * Copyright (C) 2012 Jean Ollion
 *
 *
 *
 * This file is part of tango
 *
 * tango is free software; you can redistribute it and/or modify it under the
 * terms of the GNU General Public License as published by the Free Software
 * Foundation; either version 3 of the License, or (at your option) any later
 * version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more
 * details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program. If not, see <http://www.gnu.org/licenses/>.
 *
 * @author Jean Ollion
 */

public class DocumentFilterIllegalCharacters extends DocumentFilter {



    /*public DocumentFilterIllegalCharacters(char[] illegalCharacters, char[] illegalCharactersStart) {
        if (illegalCharacters!=null) ILLEGAL_CHARACTERS=illegalCharacters;
        if (illegalCharactersStart!=null) ILLEGAL_CHARACTERS_START=illegalCharactersStart;
    }*/
    
    public DocumentFilterIllegalCharacters() {
    }
    
    
    @Override
    public void insertString (DocumentFilter.FilterBypass fb, int offset, String text, AttributeSet attr) throws BadLocationException
    {
        fb.insertString (offset, fixText(offset, text).toUpperCase(), attr);
    }
    @Override
    public void replace (DocumentFilter.FilterBypass fb, int offset, int length, String text, AttributeSet attr) throws BadLocationException
    {
        fb.replace(offset, length, fixText(offset, text), attr);
    }

    private String fixText (int offset, String s)
    {
        StringBuilder sb = new StringBuilder();
        if (offset==0) {
        if (!isIllegalCharStart(s.charAt(0)) && !isIllegalChar(s.charAt (0)))
                sb.append (s.charAt (0));
        }
        for(int i = offset==0?1:0; i < s.length(); ++i)
        {
            if (!isIllegalChar(s.charAt (i)))
                sb.append (s.charAt (i));
        }
        return sb.toString();
    }



}
