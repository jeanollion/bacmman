package bacmman.ui.gui;

import bacmman.core.ProgressCallback;
import bacmman.data_structure.BacmmanToTrackMate;
import bacmman.data_structure.SegmentedObject;
import bacmman.data_structure.TrackMateToBacmman;
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
import fiji.plugin.trackmate.io.TmXmlReader;
import fiji.plugin.trackmate.visualization.TrackMateModelView;
import fiji.plugin.trackmate.visualization.hyperstack.HyperStackDisplayer;
import ij.IJ;
import ij.ImagePlus;
import org.slf4j.LoggerFactory;

import javax.swing.*;

import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static fiji.plugin.trackmate.gui.Icons.TRACKMATE_ICON;

public class TrackMateRunner extends TrackMatePlugIn {
    public static final org.slf4j.Logger logger = LoggerFactory.getLogger(TrackMateRunner.class);
    TrackMateModelView displayer;
    WizardSequence sequence;
    TrackMate trackmate;
    ImagePlus imp;
    public static void importToBacmman(Object model, List<SegmentedObject> parentTrack, int objectClassIdx, boolean overwrite, boolean trackOnly, boolean matchWithOverlap, double matchThreshold, ProgressCallback progress) {
        TrackMateToBacmman.storeTrackMateObjects(((Model) model).getSpots(), ((Model) model).getTrackModel(), parentTrack, objectClassIdx, overwrite, trackOnly, matchWithOverlap, matchThreshold, progress);
    }
    public static List runTM(List<SegmentedObject> parentTrack, int objectClassIdx, JComponent container) {
        Model model = BacmmanToTrackMate.getSpotsAndTracks(parentTrack, objectClassIdx);
        Image hook = IJVirtualStack.openVirtual(parentTrack, objectClassIdx, false, objectClassIdx, false);
        ImagePlus imp = (ImagePlus)ImageWindowManagerFactory.getImageManager().getDisplayer().getImage(hook);
        imp.setTitle("TrackMate HyperStack");
        TrackMateRunner tmr = runTM(model, container, imp);
        Runnable close = () -> {
            ImageWindowManagerFactory.getImageManager().getDisplayer().close(hook);
            tmr.close();
        };
        return new ArrayList(){{add(model); add(hook); add(close);}};
    }
    public static List runTM(File tmFile, List<SegmentedObject> parentTrack, int objectClassIdx, JComponent container) {
        TmXmlReader reader = new TmXmlReader(tmFile);
        logger.debug("reader init {}", reader.isReadingOk());
        if (!reader.isReadingOk()) throw new RuntimeException("Could not read file "+ reader.getErrorMessage());
        Model model = reader.getModel();
        logger.debug("imported from file: {}: spots: {},  tracks: {}", tmFile, model.getSpots().getNSpots(false), model.getTrackModel().nTracks(false));
        Image hook = IJVirtualStack.openVirtual(parentTrack, objectClassIdx, false, objectClassIdx, false);
        ImagePlus imp = (ImagePlus)ImageWindowManagerFactory.getImageManager().getDisplayer().getImage(hook);

        TrackMateRunner tmr = runTM(model, container, imp);
        Runnable close = () -> {
            ImageWindowManagerFactory.getImageManager().getDisplayer().close(hook);
            tmr.close();
        };
        return new ArrayList(){{add(model); add(hook); add(close);}};
    }

    public static List runTM(File tmFile, JComponent container) {
        TmXmlReader reader = new TmXmlReader(tmFile);
        logger.debug("reader init {}", reader.isReadingOk());
        if (!reader.isReadingOk()) throw new RuntimeException("Could not read file "+ reader.getErrorMessage());
        Model model = reader.getModel();

        ImagePlus imp = reader.readImage();

        TrackMateRunner tmr = runTM(model, container, imp);
        Runnable close = () -> {
            imp.close();
            tmr.close();
        };
        return new ArrayList(){{add(model); add(close);}};
    }

    public static TrackMateRunner runTM(Model model, JComponent container, ImagePlus imp) {
        return new TrackMateRunner(model, container, imp);
    }

    public TrackMateRunner(Model model, JComponent container, ImagePlus imp) {
        this.imp=imp;
        imp.setOpenAsHyperStack( true );
        imp.setDisplayMode( IJ.COMPOSITE );
        if ( !imp.isVisible() )
            imp.show();

        // Main objects.
        final Settings settings = createSettings( imp );
        model.setPhysicalUnits(
                imp.getCalibration().getUnit(),
                imp.getCalibration().getTimeUnit() );
        trackmate = createTrackMate( model, settings );
        final SelectionModel selectionModel = new SelectionModel( model );
        final DisplaySettings displaySettings = createDisplaySettings();
        // Main view.
        displayer = new HyperStackDisplayer( model, selectionModel, imp, displaySettings );
        displayer.render();

        // Wizard.
        sequence = createSequence( trackmate, selectionModel, displaySettings );
        sequence.setCurrent("ConfigureViews");
        if (container==null) {
            final JFrame frame = sequence.run("TrackMate" + imp.getShortTitle());
            frame.setIconImage(TRACKMATE_ICON.getImage());
            GuiUtils.positionWindow(frame, imp.getWindow());
            frame.setVisible(true);
        } else {
            final WizardController controller = new WizardController( sequence );
            if (container instanceof JScrollPane) ((JScrollPane)container).setViewportView(controller.getWizardPanel());
            else container.add( controller.getWizardPanel() );
            controller.init();
        }
    }
    public void close() {
        trackmate.cancel("closing");
        trackmate=null;
        displayer.clear();
        displayer=null;
        sequence.onClose();
        sequence=null;
        imp = null; //should closed independently
    }
}
