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

import bacmman.configuration.experiment.Experiment;
import bacmman.configuration.parameters.FileChooser;
import bacmman.configuration.parameters.Parameter;
import bacmman.configuration.parameters.ParameterUtils;
import bacmman.ui.gui.configuration.ConfigurationTreeModel;
import java.io.File;
import javax.swing.JFileChooser;

/**
 *
 * @author Jean Ollion
 */
public class FileChooserUI implements ParameterUI {
    FileChooser fcParam;
    String curDir;
    ConfigurationTreeModel model;
    public FileChooserUI(FileChooser fc, ConfigurationTreeModel model) {
        this.fcParam=fc;
        String[] fp = fc.getSelectedFilePath();
        if (fp!=null && fp.length>0) curDir = fp[0];
        this.model=model;
    }
    @Override
    public Object[] getDisplayComponent() { // ouvre directement la fenêtre pour choisir des dossers
        final JFileChooser fc = new JFileChooser();
        fc.setFileSelectionMode(fcParam.getOption().getOption());
        //fc.setFileHidingEnabled(false);
        fc.setMultiSelectionEnabled(fcParam.getOption().getMultipleSelectionEnabled());
        if (curDir != null) fc.setCurrentDirectory(new File(curDir).getParentFile());
        else {
            Experiment xp = ParameterUtils.getExperiment(fcParam);
            if (xp!=null && xp.getPath()!=null) fc.setCurrentDirectory(xp.getPath().toFile());
        }
        fc.setDialogTitle(fcParam.getName());
        int returnval = fc.showOpenDialog(model.getTree());
        Parameter.logger.debug("file chooser: {}: returned value? {}", fcParam.getName(), returnval == JFileChooser.APPROVE_OPTION);
        if (returnval == JFileChooser.APPROVE_OPTION) {
            if (fcParam.getOption().getMultipleSelectionEnabled()) fcParam.setSelectedFiles(fc.getSelectedFiles());
            else fcParam.setSelectedFiles(fc.getSelectedFile());
            model.nodeChanged(fcParam);
        }
        return new Object[]{};
        
    }
    
    
    
    
    
}
