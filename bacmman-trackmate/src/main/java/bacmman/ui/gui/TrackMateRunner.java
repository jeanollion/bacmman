package bacmman.ui.gui;

import bacmman.data_structure.BacmmanToTrackMate;
import bacmman.data_structure.SegmentedObject;
import bacmman.image.Image;
import bacmman.ui.gui.image_interaction.IJVirtualStack;
import bacmman.ui.gui.image_interaction.ImageWindowManagerFactory;
import fiji.plugin.trackmate.*;
import fiji.plugin.trackmate.gui.GuiUtils;
import fiji.plugin.trackmate.gui.displaysettings.DisplaySettings;
import fiji.plugin.trackmate.gui.wizard.TrackMateWizardSequence;
import fiji.plugin.trackmate.gui.wizard.WizardController;
import fiji.plugin.trackmate.gui.wizard.WizardSequence;
import fiji.plugin.trackmate.gui.wizard.descriptors.GrapherDescriptor;
import fiji.plugin.trackmate.visualization.TrackMateModelView;
import fiji.plugin.trackmate.visualization.hyperstack.HyperStackDisplayer;
import ij.IJ;
import ij.ImagePlus;
import org.slf4j.LoggerFactory;

import javax.swing.*;

import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.List;

import static fiji.plugin.trackmate.gui.Icons.TRACKMATE_ICON;

public class TrackMateRunner extends TrackMatePlugIn {
    public static final org.slf4j.Logger logger = LoggerFactory.getLogger(TrackMateRunner.class);

    public static Model runTM(List<SegmentedObject> parentTrack, int objectClassIdx, JPanel container) {
        Model model = BacmmanToTrackMate.getSpotsAndTracks(parentTrack, objectClassIdx);
        Image hook = IJVirtualStack.openVirtual(parentTrack, objectClassIdx, false, objectClassIdx);
        ImagePlus imp = (ImagePlus)ImageWindowManagerFactory.getImageManager().getDisplayer().getImage(hook);
        imp.setTitle("TrackMate HyperStack");
        runTM(model, container, imp);
        return model;
    }
    public static void runTM(Model model, JPanel container, ImagePlus imp) {
        new TrackMateRunner(model, container, imp);
    }
    public TrackMateRunner(Model model, JPanel container, ImagePlus imp) {

        imp.setOpenAsHyperStack( true );
        imp.setDisplayMode( IJ.COMPOSITE );
        if ( !imp.isVisible() )
            imp.show();

        GuiUtils.userCheckImpDimensions( imp );

        // Main objects.
        final Settings settings = createSettings( imp );
        model.setPhysicalUnits(
                imp.getCalibration().getUnit(),
                imp.getCalibration().getTimeUnit() );
        final TrackMate trackmate = createTrackMate( model, settings );
        final SelectionModel selectionModel = new SelectionModel( model );
        final DisplaySettings displaySettings = createDisplaySettings();

        // Main view.
        final TrackMateModelView displayer = new HyperStackDisplayer( model, selectionModel, imp, displaySettings );
        displayer.render();

        // Wizard.
        final WizardSequence sequence = createSequence( trackmate, selectionModel, displaySettings );
        sequence.setCurrent("ConfigureViews");
        if (container==null) {
            final JFrame frame = sequence.run("TrackMate" + imp.getShortTitle());
            frame.setIconImage(TRACKMATE_ICON.getImage());
            GuiUtils.positionWindow(frame, imp.getWindow());
            frame.setVisible(true);
        } else {
            final WizardController controller = new WizardController( sequence );
            logger.debug("wizzard panel null ? {}", controller.getWizardPanel()==null);
            container.add( controller.getWizardPanel() );
            controller.init();
        }
    }
}
