package bacmman.data_structure;

import bacmman.core.Core;
import bacmman.data_structure.dao.MasterDAO;
import bacmman.data_structure.dao.ObjectDAO;
import bacmman.image.Image;
import bacmman.ui.gui.TrackMateRunner;
import bacmman.ui.gui.image_interaction.*;
import fiji.plugin.trackmate.Model;
import fiji.plugin.trackmate.Spot;
import fiji.plugin.trackmate.SpotCollection;
import fiji.plugin.trackmate.TrackModel;
import fiji.plugin.trackmate.io.TmXmlReader;
import fiji.plugin.trackmate.io.TmXmlWriter;
import ij.ImageJ;
import ij.ImagePlus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.List;

public class TrackMateIO {
    public static final Logger logger = LoggerFactory.getLogger(TrackMateIO.class);
    public static void main(String[] args) {
        initCore();
        //tmFakeTracksToBacmman();
        //fakeTracksToTM();
        //bactToTM();
        tmBactToBacmman();
        //Region r = TrackMateConverter.tmSpotToRegion(s, 1);
        //ImageDisplayer disp = new IJImageDisplayer();
        //disp.showImage(r.getMaskAsImageInteger());
    }

    private static void tmBactToBacmman() {
        String dir = "/data/Images/BACMMAN/dataset1/";
        TmXmlReader reader = new TmXmlReader(new File(dir + "TrackMate.xml"));
        logger.debug("reader init {}", reader.isReadingOk());
        //logger.debug("reader log: {}", reader.getLog());
        Model model = reader.getModel();
        SpotCollection allSpots = model.getSpots();
        TrackModel tracks =model.getTrackModel();
        logger.debug("all spots: {}", allSpots.getNSpots(true));
        logger.debug("all tracks: {}", model.getTrackModel().edgeSet().size());
        MasterDAO mDAO = MasterDAOFactory.createDAO("dataset1", dir);
        mDAO.lockPositions();
        ObjectDAO dao = mDAO.getDao("150609_21");
        List<SegmentedObject> roots = dao.getRoots();
        List<SegmentedObject> microchannels = SegmentedObjectUtils.getAllTracks(roots, 0).entrySet().stream().filter(e -> e.getKey().getIdx() == 0).findAny().get().getValue();
        logger.debug("n frames : {}",  microchannels.size() );
        logger.debug("before import n bacts: {}, n tracks: {}", SegmentedObjectUtils.getAllChildrenAsStream(microchannels.stream(), 1).count(), SegmentedObjectUtils.getAllTracks(microchannels, 1).size());

        TrackMateToBacmman.storeTrackMateObjects(allSpots, tracks, microchannels, 1, false, false, true, 90, null);
    }

    private static void tmFakeTracksToBacmman() {
        String dir = "/data/Images/MaximeDeforet/TestTrackMate/";
        TmXmlReader reader = new TmXmlReader(new File(dir + "FakeTracks.xml"));
        logger.debug("reader init {}", reader.isReadingOk());
        //logger.debug("reader log: {}", reader.getLog());
        Model model = reader.getModel();
        SpotCollection allSpots = model.getSpots();
        TrackModel tracks =model.getTrackModel();
        logger.debug("all spots: {}", allSpots.getNSpots(true));
        logger.debug("all tracks: {}", model.getTrackModel().edgeSet().size());
        MasterDAO mDAO = MasterDAOFactory.createDAO("TestTrackMate", dir);
        mDAO.lockPositions();
        // TODO  get or create root...
        logger.debug("n frames : {}",  mDAO.getDao("FakeTracks").getRoots().size() );
        List<SegmentedObject> roots = mDAO.getDao("FakeTracks").getRoots();

        TrackMateToBacmman.storeTrackMateObjects(allSpots, tracks, roots, 0, true, false, true, 95, null);
    }

    private static void bactToTM() {
        String dir = "/data/Images/BACMMAN/dataset1";
        MasterDAO mDAO = MasterDAOFactory.createDAO("dataset1", dir);
        mDAO.lockPositions();
        ObjectDAO dao = mDAO.getDao("150609_21");

        List<SegmentedObject> roots = dao.getRoots();
        List<SegmentedObject> microchannels = SegmentedObjectUtils.getAllTracks(roots, 0).entrySet().stream().filter(e -> e.getKey().getIdx() == 0).findAny().get().getValue();
        logger.debug("n frames : {}",  microchannels.size() );
        logger.debug("n bacts: {}, n tracks: {}", SegmentedObjectUtils.getAllChildrenAsStream(microchannels.stream(), 1).count(), SegmentedObjectUtils.getAllTracks(microchannels, 1).size());

        Model model = BacmmanToTrackMate.getSpotsAndTracks(microchannels, 1);
        initCore();
        Image im = IJVirtualStack.openVirtual(microchannels, 1, false, 1);
        ImageDisplayer<ImagePlus> disp = ImageWindowManagerFactory.getImageManager().getDisplayer();
        ImagePlus imp = disp.getImage(im);
        imp.close();

        TrackMateRunner.runTM(model, null, imp);
    }

    private static void fakeTracksToTM() {
        String dir = "/data/Images/MaximeDeforet/TestTrackMate/";
        MasterDAO mDAO = MasterDAOFactory.createDAO("TestTrackMate", dir);
        mDAO.lockPositions();
        // TODO  get or create root...
        logger.debug("n frames : {}",  mDAO.getDao("FakeTracks").getRoots().size() );
        List<SegmentedObject> roots = mDAO.getDao("FakeTracks").getRoots();
        Model model = BacmmanToTrackMate.getSpotsAndTracks(roots, 0);

        logger.debug("all spots: {}", model.getSpots().getNSpots(true));
        logger.debug("all tracks: {}", model.getTrackModel().edgeSet().size());
        initCore();

        Image im = IJVirtualStack.openVirtual(roots, 0, false, 0);
        ImageDisplayer<ImagePlus> disp = ImageWindowManagerFactory.getImageManager().getDisplayer();
        ImagePlus imp = disp.getImage(im);
        imp.close();
        TrackMateRunner.runTM(model, null, imp);
    }

    public static void initCore() {
        new ImageJ();
        IJImageDisplayer disp = new IJImageDisplayer();
        IJImageWindowManager man = new IJImageWindowManager(null, disp);
        ImageWindowManagerFactory.setImageDisplayer(disp, man);
        Core.getCore();
        Core.setFreeDisplayerMemory(man::flush);
        Core.setImageDisplayer(disp::showImage);
        Core.setImage5dDisplayer(disp::showImage5D);
    }
}
