package bacmman.data_structure;

import bacmman.configuration.experiment.Experiment;
import bacmman.configuration.experiment.Structure;
import bacmman.data_structure.dao.MasterDAO;
import bacmman.data_structure.dao.ObjectDAO;
import bacmman.image.BlankMask;
import bacmman.utils.Utils;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * ObjectBox deleteAllObjects deletes stores without opening them: stores that are not open are deleted from disk, and the object folder is never (re-)created.
 * Opening stores only to delete them re-created the object folder, which failed on Windows (AccessDeniedException) when the folder was pending deletion.
 */
public class ObjectBoxDeleteAllTest {
    @Rule
    public TemporaryFolder testFolder = new TemporaryFolder();
    static {MasterDAOFactory.findModules("bacmman.data_structure.dao");}
    static final String POSITION = "testField";

    @Test
    public void deleteAllObjectsOfClosedStores() throws Exception {
        MasterDAO db = generateDB();
        ObjectDAO<?> dao = db.getDao(POSITION);
        Path objectDir = objectDir(db);
        dao.clearCache(); // stores are closed
        assertTrue("object store on disk", Files.isDirectory(objectDir.resolve("objects_0")));
        dao.deleteAllObjects();
        assertFalse("object store deleted", Files.exists(objectDir.resolve("objects_0")));
        assertFalse("root store deleted", Files.exists(objectDir.resolve("objects_-1")));
        assertFalse("no measurement store created", Files.exists(objectDir.resolve("measurements_0")));
        assertTrue("no objects", dao.getRoots().isEmpty());
        db.unlockPositions();
        db.eraseAll();
    }

    @Test
    public void deleteAllObjectsDoesNotCreateObjectFolder() throws Exception {
        MasterDAO db = generateDB();
        ObjectDAO<?> dao = db.getDao(POSITION);
        Path objectDir = objectDir(db);
        dao.clearCache();
        assertTrue("object folder deleted", Utils.deleteDirectory(objectDir.toFile()));
        dao.deleteAllObjects();
        assertFalse("object folder not re-created", Files.exists(objectDir));
        db.unlockPositions();
        db.eraseAll();
    }

    private static Path objectDir(MasterDAO db) {
        return Paths.get(db.getExperiment().getOutputDirectory(), POSITION, "objectbox");
    }

    private MasterDAO generateDB() throws Exception {
        File dir = testFolder.newFolder();
        MasterDAO db = MasterDAOFactory.getDAO(Paths.get(dir.getAbsolutePath()), "ObjectBox");
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
        SegmentedObject child = new SegmentedObject(0, 0, 0, new Region(new BlankMask(1, 1, 1, 0, 0, 0, 1, 1), 1, false), root0);
        accessor.setChildren(root0, Collections.singletonList(child), 0);
        dao.store(Collections.singletonList(root0));
        dao.store(Arrays.asList(child));
        return db;
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
