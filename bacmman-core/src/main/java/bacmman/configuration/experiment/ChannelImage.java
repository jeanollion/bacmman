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
package bacmman.configuration.experiment;

import bacmman.configuration.experiment.Experiment.IMPORT_METHOD;
import static bacmman.configuration.experiment.Experiment.IMPORT_METHOD.ONE_FILE_PER_CHANNEL_POSITION;

import bacmman.configuration.parameters.EnumChoiceParameter;
import bacmman.configuration.parameters.ParameterUtils;
import bacmman.configuration.parameters.ContainerParameterImpl;
import bacmman.configuration.parameters.TextParameter;

import java.awt.*;
import java.util.function.Predicate;

import bacmman.image.io.ImageIOCoordinates;
import org.json.simple.JSONObject;

/**
 *
 * @author Jean Ollion
 */
public class ChannelImage extends ContainerParameterImpl<ChannelImage> {

    Predicate<TextParameter> kwValid = (p) -> {
        Experiment xp = ParameterUtils.getExperiment(this);
        if (xp!=null) {
            IMPORT_METHOD method = xp.getImportImageMethod();
            if (IMPORT_METHOD.SINGLE_FILE.equals(method)) return true;
            if (xp.getChannelImageCount(false)>1 && method==IMPORT_METHOD.ONE_FILE_PER_CHANNEL_POSITION && this.getParent().getIndex(this)==0 && this.getImportImageChannelKeyword().length()==0) return false; // first must be non null if there are several channels
            return true;
            //long distinctKW = xp.getChannelImages().getChildren().stream().map(c -> c.getImportImageChannelKeyword()).distinct().count();
            //return distinctKW == xp.getChannelImageCount(false);
        } else return true;
    };
    TextParameter importKeyWord = new TextParameter("Channel keyword", "", true).addValidationFunction(kwValid).setHint("Keyword allowing to distinguish the file containing channel during image import, when dataset is composed of several files per position. <br />"
            + "For a given position, the name of the file containing the detection channel must contain this keyword and all the files from the same position must differ only by this keyword (and eventually by frame number if each frame is in a separate file). "
            + "<br />First channel must have a non-null keyword is import method is <em>"+ONE_FILE_PER_CHANNEL_POSITION.getMethod()+"</em> and that there are several channels. "
            + "<br />All keywords should be distinct.");
    EnumChoiceParameter<ImageIOCoordinates.RGB> importRGBChannel = new EnumChoiceParameter<>("RGB Channel", ImageIOCoordinates.RGB.values(), ImageIOCoordinates.RGB.R).setHint("In case input images is a color image, choose which channel to import");
    public enum CHANNEL_COLOR {
        RED(Color.RED), GREEN(Color.GREEN), BLUE(Color.BLUE), MAGENTA(Color.MAGENTA), CYAN(Color.CYAN), ORANGE(Color.ORANGE), YELLOW(Color.YELLOW), GREY(Color.GRAY);
        final Color c;
        CHANNEL_COLOR(Color c) {
            this.c =c;
        }
    }
    EnumChoiceParameter<CHANNEL_COLOR> color = new EnumChoiceParameter<>("Color", CHANNEL_COLOR.values(), CHANNEL_COLOR.GREY).setAllowNoSelection(true).setHint("Display color");
    EnumChoiceParameter<Experiment.AXIS_INTERPRETATION> axesInterpretation = new EnumChoiceParameter<>("Axes Interpretation", Experiment.AXIS_INTERPRETATION.values(), Experiment.AXIS_INTERPRETATION.AUTOMATIC).setHint("Defines how to interpret the third axis (after X, Y). Automatic: axis as defined in the image file, Z: axis is interpreted as Z if several frames and only one z-slice are detected, Time: axis is interpreted as time, if several z-slices and only one frame are detected.");

    public ChannelImage(String name) {
        super(name);
    }
    
    public ChannelImage(String name, String keyword) {
        this(name);
        setImportImageChannelKeyword(keyword);
    }
    public Color getColor() {return color.getSelectedEnum()==null ? CHANNEL_COLOR.GREY.c : color.getSelectedEnum().c;}
    public String getColorName() {return color.getSelectedEnum()==null ? null : color.getSelectedEnum().toString();}
    public String getImportImageChannelKeyword() {return importKeyWord.getValue();}
    public void setImportImageChannelKeyword(String keyword) {importKeyWord.setValue(keyword);}
    public ImageIOCoordinates.RGB getRGB() {
        return importRGBChannel.getSelectedEnum();
    }
    public Experiment.AXIS_INTERPRETATION getAxisInterpretation() {return axesInterpretation.getSelectedEnum();}
    @Override
    protected void initChildList() {
        super.initChildren(importKeyWord, axesInterpretation, importRGBChannel, color);
    }
    @Override 
    public boolean isEmphasized() {
        return false;
    }

    @Override
    public Object toJSONEntry() {
        JSONObject res = new JSONObject();
        res.put("name", name);
        res.put("importKeyword", this.importKeyWord.toJSONEntry());
        res.put("rgb", this.importRGBChannel.toJSONEntry());
        if (color.getSelectedEnum()!=null) res.put("color", this.color.toJSONEntry());
        res.put("axesInterpretation", axesInterpretation.toJSONEntry());
        return res;
    }

    @Override
    public void initFromJSONEntry(Object jsonEntry) {
        JSONObject jsonO = (JSONObject)jsonEntry;
        name = (String)jsonO.get("name");
        importKeyWord.initFromJSONEntry(jsonO.get("importKeyword"));
        if (jsonO.containsKey("rgb")) this.importRGBChannel.initFromJSONEntry(jsonO.get("rgb"));
        if (jsonO.containsKey("color")) this.color.initFromJSONEntry(jsonO.get("color"));
        if (jsonO.containsKey("axesInterpretation")) this.axesInterpretation.initFromJSONEntry(jsonO.get("axesInterpretation"));
    }
    
}
