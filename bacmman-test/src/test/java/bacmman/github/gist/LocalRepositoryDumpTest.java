package bacmman.github.gist;

import bacmman.configuration.experiment.ConfigIDAware;
import bacmman.configuration.experiment.Experiment;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.Assert.*;

/**
 * Integration test of all functions on a local repository that contains a dump of a GitHub library (e.g. account labmuse).
 * Location: system property bacmman.testLocalRepository (default /data/testLocalRepository). Skipped if the folder contains no item.
 * Read tests use the dumped items; write tests create their own items and delete them.
 */
public class LocalRepositoryDumpTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();
    LocalRepository repo;

    static boolean pluginsFound;

    @Before
    public void init() throws IOException {
        if (!pluginsFound) { // needed to parse configurations
            bacmman.plugins.PluginFactory.findPlugins("bacmman.plugins.plugins");
            pluginsFound = true;
        }
        File folder = new File(System.getProperty("bacmman.testLocalRepository", "/data/testLocalRepository"));
        Assume.assumeTrue("no local repository dump at " + folder, folder.isDirectory());
        repo = GistRepository.local(folder);
        Assume.assumeFalse("empty local repository " + folder, repo.list().isEmpty());
    }

    @Test
    public void configurations() throws IOException {
        List<GistConfiguration> configs = GistConfiguration.getConfigurations(repo, null);
        assertFalse(configs.isEmpty());
        Set<String> ids = repo.list().stream().map(GistItem::getID).collect(Collectors.toSet());
        for (GistConfiguration c : configs) {
            String m = c.getID() + " " + c.folder() + "/" + c.name();
            assertNotNull(m, c.getType());
            assertNotNull(m, c.folder());
            assertNotNull(m, c.name());
            JSONObject content = c.getContent();
            assertNotNull(m, content);
            // linked configuration blocks point to items of the repository
            for (String linked : configIds(content)) assertTrue(m + " links to missing configuration " + linked, ids.contains(linked));
            Experiment xp = c.getExperiment(null, false); // also resolves linked blocks (auto-update) from the local repository
            assertNotNull(m, xp);
            assertNotNull(m, GistConfiguration.getParameter(c, -1, c.getType(), null));
            if (repo.get(c.getID()).hasFile("thumbnail")) {
                List<BufferedImage> thumb = GistConfiguration.TYPE.WHOLE.equals(c.getType()) ? null : c.getThumbnail();
                if (!GistConfiguration.TYPE.WHOLE.equals(c.getType())) assertTrue(m + " thumbnail", thumb != null && !thumb.isEmpty());
            }
            GistConfiguration byId = new GistConfiguration(repo, c.getID());
            assertEquals(m, c.getContent(), byId.getContent());
            assertEquals(m, c.getDescription(), byId.getDescription());
        }
    }

    static List<String> configIds(Object json) {
        List<String> res = new ArrayList<>();
        if (json instanceof JSONObject) {
            for (Object e : ((JSONObject) json).entrySet()) {
                Map.Entry en = (Map.Entry) e;
                if (ConfigIDAware.key.equals(en.getKey()) && en.getValue() instanceof String) res.add((String) en.getValue());
                else res.addAll(configIds(en.getValue()));
            }
        } else if (json instanceof JSONArray) for (Object o : (JSONArray) json) res.addAll(configIds(o));
        return res;
    }

    @Test
    public void dlModels() throws IOException {
        List<GistDLModel> models = GistDLModel.get(repo, null);
        assertFalse(models.isEmpty());
        int downloaded = 0;
        for (GistDLModel model : models) {
            String m = model.getID() + " " + model.folder + "/" + model.name;
            assertNotNull(m, model.getMetadata());
            String url = model.getModelURL();
            assertNotNull(m, url);
            if (repo.idFromURL(url) == null) continue; // model file stored elsewhere (e.g. external url)
            assertEquals(m, url, model.getModelLink()); // local file: link is the url (bare ids are GitHub ids)
            LargeFileGist lf = model.getLargeFileGist(null);
            assertEquals(m, repo.getKey(), lf.getRepository().getKey());
            // same resolution as MLModelFileParameter: from the url alone
            assertEquals(m, lf.getID(), new LargeFileGist(url, new NoAuth()).getID());
            File out = tmp.newFolder();
            File file = lf.retrieveFile(out, false, false, null, null, null); // not unzipped: size can be compared. checksums are verified for each chunk
            assertTrue(m + " " + file, file.isFile());
            assertEquals(m + " size", lf.getSizeMb(), file.length() / (1024d * 1024d), 0.01);
            if (downloaded == 0) { // unzipped, as done when a model is used
                File unzipped = lf.retrieveFile(tmp.newFolder(), false, true, null, null, null);
                assertTrue(m + " " + unzipped, unzipped.exists());
            }
            ++downloaded;
            deleteRecursively(out);
        }
        assertTrue("no model file downloaded", downloaded > 0);
    }

    static void deleteRecursively(File f) throws IOException {
        Files.walk(f.toPath()).sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
    }

    @Test
    public void writeConfiguration() throws IOException {
        GistConfiguration source = GistConfiguration.getConfigurations(repo, null).stream().filter(c -> !GistConfiguration.TYPE.WHOLE.equals(c.getType())).findFirst().orElse(null);
        assertNotNull(source);
        GistConfiguration c = new GistConfiguration("TestFolder", "test_" + System.nanoTime(), "test description", source.getContent(), source.getType());
        c.setThumbnail(new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB));
        c.createNewGist(repo);
        try {
            GistConfiguration read = GistConfiguration.getConfigurations(repo, null).stream().filter(x -> x.getID().equals(c.getID())).findFirst().orElse(null);
            assertNotNull(read);
            assertEquals("TestFolder", read.folder());
            assertEquals(source.getContent(), read.getContent());
            assertEquals(1, read.getThumbnail().size());
            JSONObject modified = new JSONObject(source.getContent());
            modified.put("test_key", "test_value");
            read.setJsonContent(modified).setDescription("new description");
            read.setThumbnail(null);
            read.uploadIfNecessary(null);
            GistConfiguration reread = new GistConfiguration(repo, c.getID());
            assertEquals("test_value", reread.getContent().get("test_key"));
            assertEquals("new description", reread.getDescription());
            assertFalse(repo.get(c.getID()).hasFile("thumbnail"));
        } finally {
            c.delete(null);
        }
        assertFalse(repo.exists(c.getID()));
    }

    @Test
    public void writeDLModelAndLargeFiles() throws IOException {
        // model with a model file (directory: zipped)
        File modelDir = tmp.newFolder("model_dir");
        byte[] data = new byte[3 * 1024 * 1024 + 123];
        new Random(0).nextBytes(data);
        Files.write(new File(modelDir, "weights.bin").toPath(), data);
        // storeFile uses the model's repository: create the model in the local repository first
        GistDLModel model = new GistDLModel("TestFolder", "test_" + System.nanoTime(), "test model", null, new DLModelMetadata());
        model.createNewGist(repo);
        String id = model.getID();
        try {
            model.storeFile(modelDir, "dl_model", null, false, null);
            model.uploadIfNecessary(null);
            GistDLModel read = new GistDLModel(repo, id);
            assertEquals(repo.getKey(), read.getModelRepository(null).getKey());
            LargeFileGist lf = read.getLargeFileGist(null);
            File out = tmp.newFolder("out");
            File res = lf.retrieveFile(out, false, true, null, null, null);
            assertArrayEquals(data, Files.readAllBytes(new File(res, "weights.bin").toPath()));
            String fileId = lf.getID();
            assertTrue(read.delete(null, true));
            assertFalse(repo.exists(id));
            assertFalse(repo.exists(fileId));
        } finally {
            if (repo.exists(id)) repo.delete(id);
        }

        // large strings (e.g. notebooks)
        String name = "jnb_test_" + System.nanoTime() + ".ipynb";
        String big = randomString(25 * 1024 * 1024); // > max chunk size -> chunks
        String lfId = LargeFileGist.storeString(repo, name, big, false, "nb", ".ipynb", false, null, null).key;
        try {
            String listedName = name.substring(0, name.length() - ".ipynb".length()); // large files are listed by file name without extension
            List<LargeFileGist> fetched = LargeFileGist.fetch(repo, n -> n.equals(listedName), null).collect(Collectors.toList());
            assertEquals(1, fetched.size());
            LargeFileGist lf = fetched.get(0);
            assertEquals(big, lf.retrieveString(null, null));
            lf.updateDescripton("nb2", null);
            assertEquals("nb2", repo.get(lfId).getDescription());
            lf.updateString("small content", "nb3", null, false, null, null);
            LargeFileGist small = new LargeFileGist(repo, lfId);
            assertEquals("small content", small.retrieveString(null, null));
            assertEquals(1, repo.get(lfId).getFileNames().size());
            String big2 = randomString(12 * 1024 * 1024);
            small.updateString(big2, "nb4", null, false, null, null);
            assertEquals(big2, new LargeFileGist(repo, lfId).retrieveString(null, null));
            assertTrue(new LargeFileGist(repo, lfId).delete(null));
            assertFalse(repo.exists(lfId));
        } finally {
            if (repo.exists(lfId)) repo.delete(lfId);
        }
    }

    static String randomString(int length) {
        Random r = new Random(length);
        char[] c = new char[length];
        for (int i = 0; i < length; ++i) c[i] = (char) ('a' + r.nextInt(26));
        return new String(c);
    }

    @Test
    public void syncToAnotherLocalRepository() throws IOException {
        LocalRepository copy = GistRepository.local(tmp.newFolder("copy"));
        RepositorySync.Result r = new RepositorySync(repo, copy).setFilter(RepositorySync.libraries(RepositorySync.LIBRARY.CONFIGURATIONS)).run();
        assertFalse(r.getMessages().toString(), r.hasErrors());
        assertEquals(GistConfiguration.getConfigurations(repo, null).size(), GistConfiguration.getConfigurations(copy, null).size());
        assertTrue(GistDLModel.get(copy, null).isEmpty());
        r = new RepositorySync(repo, copy).run();
        assertFalse(r.getMessages().toString(), r.hasErrors());
        assertEquals(GistDLModel.get(repo, null).size(), GistDLModel.get(copy, null).size());
        assertEquals(0, new RepositorySync(repo, copy).run().count(RepositorySync.ACTION.CREATED));
    }
}
