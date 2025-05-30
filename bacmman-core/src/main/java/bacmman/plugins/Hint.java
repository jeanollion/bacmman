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
package bacmman.plugins;

/**
 *
 * @author Jean Ollion
 */
public interface Hint {
    /**
     * The returned tooltip string will be formatted as html in a box with a fixed with, so the \"<html>\" tag is not needed, and line skipping will be automatically managed
     * @return the (html) string that will be displayed as a tool tip when scrolling over the name of the plugin
     */
    String getHintText();
    default boolean hintRequiresParametrization() { return false;} // for plugins only whether content of hint depends on parametrization or not. true for measurements.
    int TOOL_TIP_BOX_WIDTH = 500;
    static String formatHint(String hint) {return formatHint(hint, true);}
    static String formatHint(String hint, boolean limitWidth) {
        return formatHint(hint, limitWidth?TOOL_TIP_BOX_WIDTH:0);
    }
    static String formatHint(String hint, int withLimit) {
        if (hint==null) return null;
        hint = hint.replace("<html>", "").replace("</html>", "");
        if (withLimit>0)  return "<html><div style=\"width:"+withLimit+"px\">" + hint + "</div></html>";
        else return "<html>"+hint+"</html>";
    }
}
