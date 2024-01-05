package bacmman.ui.gui;

import bacmman.core.ProgressCallback;
import bacmman.data_structure.BacmmanToTrackMate;
import bacmman.data_structure.SegmentedObject;
import bacmman.data_structure.TrackMateToBacmman;
import bacmman.image.LazyImage5D;
import bacmman.ui.GUI;
import bacmman.ui.gui.image_interaction.HyperStack;
import bacmman.ui.gui.image_interaction.ImageWindowManagerFactory;
import fiji.plugin.trackmate.*;
import fiji.plugin.trackmate.gui.GuiUtils;
import fiji.plugin.trackmate.gui.displaysettings.DisplaySettings;
import fiji.plugin.trackmate.gui.displaysettings.DisplaySettingsLazy;
import fiji.plugin.trackmate.gui.wizard.WizardController;
import fiji.plugin.trackmate.gui.wizard.WizardSequence;
import fiji.plugin.trackmate.gui.wizard.descriptors.ConfigureViewsDescriptor;
import fiji.plugin.trackmate.io.TmXmlReader;
import fiji.plugin.trackmate.visualization.TrackMateModelView;
import fiji.plugin.trackmate.visualization.hyperstack.HyperStackDisplayer;
import ij.IJ;
import ij.ImagePlus;
import ij.measure.Calibration;
import org.slf4j.LoggerFactory;

import javax.swing.*;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static fiji.plugin.trackmate.gui.Icons.TRACKMATE_ICON;

public class TrackMateRunner extends TrackMatePlugIn {
    public static final org.slf4j.Logger logger = LoggerFactory.getLogger(TrackMateRunner.class);
    TrackMateModelView displayer;
    WizardSequence sequence;
    TrackMate trackmate;
    DisplaySettings displaySettings;
    ImagePlus imp;
    public static void importToBacmman(Object model, List<SegmentedObject> parentTrack, int objectClassIdx, boolean overwrite, boolean trackOnly, boolean matchWithOverlap, double matchThreshold, ProgressCallback progress) {
        TrackMateToBacmman.storeTrackMateObjects(((Model) model).getSpots(), ((Model) model).getTrackModel(), parentTrack, objectClassIdx, overwrite, trackOnly, matchWithOverlap, matchThreshold, progress);
    }
    public static List runTM(List<SegmentedObject> parentTrack, int objectClassIdx, JComponent container) {
        Model model = BacmmanToTrackMate.getSpotsAndTracks(parentTrack, objectClassIdx);
        HyperStack h = HyperStack.generateHyperstack(parentTrack, null, objectClassIdx);
        LazyImage5D im = h.generateImage();
        ImagePlus imp = (ImagePlus)ImageWindowManagerFactory.getImageManager().getDisplayer().displayImage(im);
        imp.setTitle("TrackMate HyperStack");
        Calibration cal = imp.getCalibration();
        cal.frameInterval = BacmmanToTrackMate.getFrameDuration(parentTrack.get(0));
        //cal.setTimeUnit("frame");
        imp.setCalibration(cal);
        TrackMateRunner tmr = runTM(model, container, imp);
        Runnable close = () -> {
            ImageWindowManagerFactory.getImageManager().getDisplayer().close(im);
            tmr.close();
        };
        return new ArrayList(){{add(model); add(im); add(close);}};
    }
    public static List runTM(File tmFile, List<SegmentedObject> parentTrack, int objectClassIdx, JComponent container) {
        TmXmlReader reader = new TmXmlReader(tmFile);
        logger.debug("reader init {}", reader.isReadingOk());
        if (!reader.isReadingOk()) throw new RuntimeException("Could not read file "+ reader.getErrorMessage());
        Model model = reader.getModel();
        logger.debug("imported from file: {}: spots: {},  tracks: {}", tmFile, model.getSpots().getNSpots(false), model.getTrackModel().nTracks(false));
        HyperStack h = HyperStack.generateHyperstack(parentTrack, null, objectClassIdx);
        LazyImage5D im = h.generateImage();
        ImagePlus imp = (ImagePlus)ImageWindowManagerFactory.getImageManager().getDisplayer().getImage(im);

        TrackMateRunner tmr = runTM(model, container, imp);
        Runnable close = () -> {
            ImageWindowManagerFactory.getImageManager().getDisplayer().close(im);
            tmr.close();
        };
        return new ArrayList(){{add(model); add(im); add(close);}};
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
        //logger.debug("computing object features...");
        //GUI.log("Computing Object Features....");
        //trackmate.computeSpotFeatures(true); // TODO set those that are already computed in bacmman
        logger.debug("computing edge features...");
        GUI.log("Computing Edge Features....");
        trackmate.computeEdgeFeatures(true);
        logger.debug("computing track features...");
        GUI.log("Computing Track Features....");
        trackmate.computeTrackFeatures(true);
        final SelectionModel selectionModel = new SelectionModel( model );
        displaySettings = new DisplaySettingsLazy(createDisplaySettings(), trackmate); // createDisplaySettings();//
        displaySettings.setTrackDisplayMode(DisplaySettings.TrackDisplayMode.LOCAL); // show tracks local in time. // TODO record GUI parameter
        // Main view.
        displayer = new HyperStackDisplayer( model, selectionModel, imp, displaySettings );
        displayer.render();

        // Wizard.
        sequence = createSequence( trackmate, selectionModel, displaySettings );
        sequence.setCurrent(ConfigureViewsDescriptor.KEY);

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
        //if (displaySettings!=null) DisplaySettingsIO.saveToUserDefault(displaySettings); // causes java.lang.IllegalArgumentException: class ij.gui.Toolbar declares multiple JSON fields named x
        trackmate.cancel("closing");
        trackmate=null;
        displayer.clear();
        displayer=null;
        sequence.onClose();
        sequence=null;
        imp = null; //should closed independently
    }
}
