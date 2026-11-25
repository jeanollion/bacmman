package bacmman.data_structure;

import bacmman.configuration.experiment.Experiment;
import bacmman.configuration.experiment.Structure;
import bacmman.data_structure.dao.MasterDAO;
import bacmman.data_structure.dao.ObjectDAO;
import bacmman.image.BlankMask;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

/**
 * Safe Mode (undo): modifications made while safe mode is ON are reverted by rollback, kept by commit / when safe mode is turned OFF.
 */
public class SafeModeDAOTest {
    static final Logger logger = LoggerFactory.getLogger(SafeModeDAOTest.class);
    @Rule
    public TemporaryFolder testFolder = new TemporaryFolder();
    static {MasterDAOFactory.findModules("bacmman.data_structure.dao");}
    static final String POSITION = "testField";

    @Test
    public void rollbackMapDB() throws Exception {
        testRollback("MapDB");
    }
    @Test
    public void rollbackObjectBox() throws Exception {
        testRollback("ObjectBox");
    }

    @Test
    public void commitAndToggleMapDB() throws Exception {
        testCommitAndToggle("MapDB");
    }
    @Test
    public void commitAndToggleObjectBox() throws Exception {
        testCommitAndToggle("ObjectBox");
    }

    /**
     * roots at frame 0 & 1; children: frame 0: idx 0, 1 ; frame 1: idx 0
     */
    private MasterDAO generateDB(String type) throws Exception {
        File dir = testFolder.newFolder();
        MasterDAO db = MasterDAOFactory.getDAO(Paths.get(dir.getAbsolutePath()), type);
        db.setConfigurationReadOnly(false);
        db.lockPositions();
        Experiment xp = new Experiment("");
        xp.setImportImageMethod(Experiment.IMPORT_METHOD.SINGLE_FILE);
        File output = testFolder.newFolder();
        xp.setPath(Paths.get(output.getParent()));
        xp.setOutputDirectory(output.getAbsolutePath());
        xp.getStructures().insert(new Structure("MicroChannel", -1, 0));
        xp.createPosition(POSITION);
        db.setExperiment(xp, true);
        ObjectDAO<?> dao = db.getDao(POSITION);
        SegmentedObjectAccessor accessor = getAccessor();
        SegmentedObject root0 = accessor.createRoot(0, new BlankMask(1, 1, 1), dao);
        SegmentedObject root1 = accessor.createRoot(1, new BlankMask(1, 1, 1), dao);
        root0.setTrackLinks(root1, true, true);
        List<SegmentedObject> c0 = new ArrayList<>(Arrays.asList(newObject(0, 0, root0), newObject(0, 1, root0)));
        List<SegmentedObject> c1 = new ArrayList<>(Collections.singletonList(newObject(1, 0, root1)));
        accessor.setChildren(root0, c0, 0);
        accessor.setChildren(root1, c1, 0);
        dao.store(Arrays.asList(root0, root1));
        List<SegmentedObject> children = new ArrayList<>(c0);
        children.addAll(c1);
        dao.store(children);
        dao.clearCache();
        return db;
    }

    private static SegmentedObject newObject(int frame, int idx, SegmentedObject parent) {
        Region r = new Region(new BlankMask(1, 1, 1, idx, 0, 0, 1, 1), idx+1, false);
        return new SegmentedObject(frame, 0, idx, r, parent);
    }

    private void testRollback(String type) throws Exception {
        MasterDAO db = generateDB(type);
        ObjectDAO<?> dao = db.getDao(POSITION);
        List<String> original = snapshot(dao);
        logger.debug("original: {}", original);
        db.setSafeMode(true);

        // 1) link (single-object stores), then modify the same objects a second time (rollback must restore the original version, not the intermediate one)
        List<SegmentedObject> roots = dao.getRoots();
        SegmentedObject a = roots.get(0).getChildren(0).filter(o -> o.getIdx()==0).findFirst().get();
        SegmentedObject b = roots.get(0).getChildren(0).filter(o -> o.getIdx()==1).findFirst().get();
        SegmentedObject c = roots.get(1).getChildren(0).findFirst().get();
        TrackLinkEditor editor = new TrackLinkEditor(0, new HashSet<>(), true);
        editor.setTrackLinks(a, c, true, true, true);
        dao.store(a);
        dao.store(c);
        assertNotEquals("modified after link", original, snapshot(dao));
        editor.resetTrackLinks(c, true, false, true);
        editor.setTrackLinks(b, c, true, true, true);
        dao.store(Arrays.asList(a, b, c));
        // 2) delete with relabel + creation
        dao.delete(Collections.singletonList(a), true, true, true);
        SegmentedObject created = newObject(1, 1, roots.get(1));
        getAccessor().getDirectChildren(roots.get(1), 0).add(created);
        dao.store(created);
        // 3) clear cache with pending changes (e.g. GUI "Clear Memory"): must not discard nor commit them
        dao.clearCache();
        logger.debug("after clear cache: {}", snapshot(dao));
        assertNotEquals("modifications kept after clear cache", original, snapshot(dao));
        // 4) delete children of a parent
        dao.deleteChildren(dao.getRoots().get(1), 0);
        assertNotEquals("modified", original, snapshot(dao));

        db.rollback();
        dao.clearCache();
        assertEquals("rollback "+type, original, snapshot(dao));
        db.setSafeMode(false);
        // persisted after re-opening
        db.unlockPositions();
        db.lockPositions();
        assertEquals("rollback after re-open "+type, original, snapshot(db.getDao(POSITION)));
        db.unlockPositions();
        db.eraseAll();
    }

    private void testCommitAndToggle(String type) throws Exception {
        MasterDAO db = generateDB(type);
        ObjectDAO<?> dao = db.getDao(POSITION);
        List<String> original = snapshot(dao);
        db.setSafeMode(true);
        // commit: changes kept
        SegmentedObject a = dao.getRoots().get(0).getChildren(0).filter(o -> o.getIdx()==0).findFirst().get();
        dao.delete(Collections.singletonList(a), true, true, true);
        db.commit();
        db.rollback();
        dao.clearCache();
        List<String> afterCommit = snapshot(dao);
        assertEquals("commit "+type, original.size()-1, afterCommit.size());
        // toggling safe mode off keeps the changes: a later rollback must not revert them
        SegmentedObject c = dao.getRoots().get(1).getChildren(0).findFirst().get();
        dao.delete(Collections.singletonList(c), true, true, false);
        db.setSafeMode(false);
        db.setSafeMode(true);
        db.rollback();
        dao.clearCache();
        List<String> afterToggle = snapshot(dao);
        assertEquals("toggle off then rollback "+type+" : "+afterToggle, original.size()-2, afterToggle.size());
        db.setSafeMode(false);
        db.unlockPositions();
        db.eraseAll();
    }

    private static List<String> snapshot(ObjectDAO<?> dao) {
        return dao.getRoots().stream().flatMap(r -> r.getChildren(0)).map(SafeModeDAOTest::toString).sorted().collect(Collectors.toList());
    }
    private static String toString(SegmentedObject o) {
        return o.getId() + " f=" + o.getFrame() + " idx=" + o.getIdx()
                + " prev=" + (o.getPrevious() == null ? null : o.getPrevious().getId())
                + " next=" + (o.getNext() == null ? null : o.getNext().getId())
                + " th=" + (o.getTrackHead() == null ? null : o.getTrackHead().getId())
                + " bds=" + (o.getRegion()==null ? null : o.getBounds());
    }

    private static SegmentedObjectAccessor getAccessor() {
        try {
            Constructor<SegmentedObjectAccessor> constructor = SegmentedObjectAccessor.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
