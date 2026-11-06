package bacmman.github.gist;

import bacmman.core.RepositoryGateway;
import org.json.simple.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;

import static org.junit.Assert.*;

public class RepositorySyncTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    /**
     * Local repository with tiny chunks, to test chunking and sub-files without large data
     */
    static class SmallChunkRepository extends LocalRepository {
        SmallChunkRepository(File root) {super(root);}
        @Override public double getMaxChunkSizeMB() {return 1d / 1024;} // 1KB
        @Override public int getMaxChunksPerItem() {return 3;}
    }

    /**
     * Ignores preferred ids, like remote repositories: allows to test re-mapping of references
     */
    static class RemoteLikeRepository extends SmallChunkRepository {
        RemoteLikeRepository(File root) {super(root);}
        @Override public synchronized GistItem create(String description, boolean visible, Map<String, String> files, String preferredId) throws IOException {
            return super.create(description, visible, files, null);
        }
    }

    static Map<String, String> files(String... nameContent) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < nameContent.length; i += 2) m.put(nameContent[i], nameContent[i + 1]);
        return m;
    }

    static byte[] randomBytes(int size, long seed) {
        byte[] b = new byte[size];
        new Random(seed).nextBytes(b);
        return b;
    }

    @Test
    public void localRepositoryCRUD() throws IOException {
        LocalRepository repo = new LocalRepository(tmp.newFolder("repo"));
        GistItem i = repo.create("desc", false, files("a.json", "{\"x\":1}", "b.txt", "é\nline2\n"), "myid");
        assertEquals("myid", i.getID());
        assertEquals(new HashSet<>(Arrays.asList("a.json", "b.txt")), i.getFileNames());
        GistItem r = repo.get("myid");
        assertEquals("desc", r.getDescription());
        assertFalse(r.isVisible());
        assertEquals("é\nline2\n", r.getContent("b.txt"));
        GistItem other = repo.create("", true, files("c", "1"), "myid"); // preferred id already used
        assertNotEquals("myid", other.getID());
        Map<String, String> upd = files("b.txt", null, "c.txt", "new");
        GistItem u = repo.update("myid", "desc2", true, upd);
        assertEquals(new HashSet<>(Arrays.asList("a.json", "c.txt")), u.getFileNames());
        assertEquals("desc2", u.getDescription());
        assertNotEquals(i.getUpdatedAt(), u.getUpdatedAt());
        assertEquals(2, repo.list().size());
        assertEquals("myid", repo.idFromURL(repo.toURL("myid")));
        GistRepository fromUrl = GistRepository.fromURL(repo.toURL("myid"), null);
        assertEquals(repo.getKey(), fromUrl.getKey());
        repo.delete("myid");
        assertFalse(repo.exists("myid"));
        assertEquals(1, repo.list().size());
        try {
            repo.create("", true, files("../evil", "x"), null);
            fail("invalid file name accepted");
        } catch (IllegalArgumentException expected) {}
    }

    @Test
    public void localRepositoryIdentity() throws IOException {
        File f = tmp.newFolder("moved");
        LocalRepository repo = new LocalRepository(f);
        String key = repo.getKey();
        assertTrue(new File(f, LocalRepository.REPOSITORY_FILE).isFile());
        File moved = new File(tmp.getRoot(), "elsewhere");
        assertTrue(f.renameTo(moved));
        assertEquals(key, new LocalRepository(moved).getKey());
        assertTrue(new LocalRepository(moved).list().isEmpty()); // repository file is not an item
    }

    @Test
    public void repositoryFromURL() {
        assertEquals(GistRepository.TYPE.GITHUB, GistRepository.fromURL("https://gist.github.com/abcdef0123456789", null).getType());
        assertEquals(GistRepository.TYPE.GITHUB, GistRepository.fromURL("abcdef0123456789", null).getType());
        GistRepository gl = GistRepository.fromURL("https://git.example.org/-/snippets/42", null);
        assertEquals(GistRepository.TYPE.GITLAB, gl.getType());
        assertEquals("42", gl.idFromURL("https://git.example.org/-/snippets/42"));
        assertEquals("https://git.example.org/-/snippets/42", gl.toURL("42"));
        assertNull(GistRepository.fromURL("https://drive.google.com/file/d/xyz/view", null));
        assertEquals("abc", GistRepository.github(new NoAuth()).idFromURL("https://gist.github.com/user/abc"));
        assertEquals("main", GitLabRepository.getRef("https://gitlab.com/-/snippets/12/raw/main/file.json"));
        assertEquals("a%20b%2Ejson", GitLabRepository.encode("a b.json"));
    }

    @Test
    public void gitlabResponses() throws Exception {
        // REST (milliseconds) and GraphQL (seconds) timestamps must compare equal
        assertEquals("2026-11-04T21:20:56Z", GitLabRepository.normalizeTimestamp("2026-11-04T21:20:56.735Z"));
        assertEquals("2026-11-04T21:20:56Z", GitLabRepository.normalizeTimestamp("2026-11-04T21:20:56Z"));
        assertEquals("2026-11-04T19:20:56Z", GitLabRepository.normalizeTimestamp("2026-11-04T21:20:56.1+02:00"));
        GitLabRepository repo = GistRepository.gitlab(null, new NoAuth("someone"));
        String node = "{\"id\":\"gid://gitlab/PersonalSnippet/6075493\",\"description\":\"d\",\"visibilityLevel\":\"public\",\"updatedAt\":\"2026-11-04T21:20:56Z\","
                + "\"blobs\":{\"nodes\":[{\"path\":\"a.json\",\"rawPath\":\"/-/snippets/6075493/raw/main/a.json\",\"size\":12}]}}";
        GistItem item = repo.parseGraphQL(bacmman.utils.JSONUtils.parse(node));
        assertEquals("6075493", item.getID());
        assertTrue(item.isVisible());
        assertEquals("d", item.getDescription());
        assertEquals(12, item.getFile("a.json").getSize());
        assertEquals("main", GitLabRepository.getRef(item.getFile("a.json").getLocation()));
        String rest = "{\"id\":6075493,\"description\":\"d\",\"visibility\":\"public\",\"updated_at\":\"2026-11-04T21:20:56.735Z\","
                + "\"files\":[{\"path\":\"a.json\",\"raw_url\":\"https://gitlab.com/-/snippets/6075493/raw/main/a.json\"}]}";
        GistItem restItem = repo.parse(bacmman.utils.JSONUtils.parse(rest));
        assertEquals(item.getID(), restItem.getID());
        assertEquals(item.getUpdatedAt(), restItem.getUpdatedAt());
        assertEquals(item.getFileNames(), restItem.getFileNames());
    }

    @Test
    public void largeFileChunksAndSubFiles() throws IOException {
        SmallChunkRepository repo = new SmallChunkRepository(tmp.newFolder("lf"));
        byte[] data = randomBytes(10 * 1024 + 17, 1); // 11 chunks -> 4 items
        File f = tmp.newFile("model.bin");
        Files.write(f.toPath(), data);
        String id = LargeFileGist.storeFile(repo, f, false, "a model", "dl_model", false, null, null).key;
        assertEquals(4, repo.list().size());
        LargeFileGist lf = new LargeFileGist(repo, id);
        assertEquals("model.bin", lf.getFileName());
        File out = tmp.newFolder("out");
        File res = lf.retrieveFile(out, false, false, null, null, null);
        assertArrayEquals(data, Files.readAllBytes(res.toPath()));
        // url resolution, as used by DL models and MLModelFileParameter
        LargeFileGist fromUrl = new LargeFileGist(repo.toURL(id), new NoAuth());
        assertEquals(lf.getID(), fromUrl.getID());

        // update with fewer chunks: stale chunks and sub-files must be removed
        String content = new String(randomBytes(0, 0)) + repeat('x', 2500); // 3 chunks -> 1 item
        lf.updateString(content, "updated", null, false, null, null);
        assertEquals(1, repo.list().size());
        LargeFileGist lf2 = new LargeFileGist(repo, id);
        assertEquals(content, lf2.retrieveString(null, null));
        assertEquals("updated", lf2.getDescription());
        // small string: stored as a single file
        lf2.updateString("small", "updated", null, false, null, null);
        LargeFileGist lf3 = new LargeFileGist(repo, id);
        assertEquals(1, repo.get(id).getFileNames().size());
        assertEquals("small", lf3.retrieveString(null, null));
        assertTrue(lf3.delete(null));
        assertTrue(repo.list().isEmpty());
    }

    static String repeat(char c, int n) {
        char[] a = new char[n];
        Arrays.fill(a, c);
        return new String(a);
    }

    /**
     * source repository: a model file (large file with sub-files), a DL model pointing to it, a configuration with a linked block pointing to another configuration
     */
    static class Fixture {
        String modelFileId, dlModelId, linkedConfigId, configId;
        byte[] modelData;
        Fixture(GistRepository repo, File tmpFile) throws IOException {
            modelData = randomBytes(5000, 2);
            Files.write(tmpFile.toPath(), modelData);
            modelFileId = LargeFileGist.storeFile(repo, tmpFile, true, "weights", "dl_model", false, null, null).key;
            JSONObject dl = new JSONObject();
            dl.put("url", repo.toURL(modelFileId));
            dlModelId = repo.create("my model", true, files("dlmodel_folder_mymodel.json", dl.toJSONString())).getID();
            linkedConfigId = repo.create("measurements", true, files("bacmman-config-meas_folder_meas.json", "{\"measurements\":[]}")).getID();
            JSONObject conf = new JSONObject();
            JSONObject meas = new JSONObject();
            meas.put("config_id", linkedConfigId);
            conf.put("measurements", meas);
            conf.put("model_id", modelFileId); // bare id, as stored by MLModelFileParameter
            configId = repo.create("whole", true, files("bacmman-config-whole_folder_conf.json", conf.toJSONString(), "thumbnail", "aGVsbG8=")).getID();
        }
    }

    @Test
    public void syncRemapsReferences() throws Exception {
        SmallChunkRepository source = new SmallChunkRepository(tmp.newFolder("src"));
        RemoteLikeRepository target = new RemoteLikeRepository(tmp.newFolder("tgt"));
        Fixture fx = new Fixture(source, tmp.newFile("w.bin"));
        int nSource = source.list().size();

        // only configurations are selected: the linked configuration is a dependency, the model file is referenced by id
        RepositorySync.Result r = new RepositorySync(source, target).setFilter(RepositorySync.DL_MODELS.or(RepositorySync.CONFIGURATIONS)).run();
        assertFalse(r.getMessages().toString(), r.hasErrors());
        assertEquals(nSource, r.count(RepositorySync.ACTION.CREATED)); // model file + sub-files included as dependencies
        assertEquals(nSource + 1, target.list().size()); // + state item
        Map<String, String> idMap = r.getIdMap();
        for (String id : idMap.keySet()) assertNotEquals(id, idMap.get(id));

        // DL model url points to the target large file, which is complete
        GistDLModel dl = new GistDLModel(target, idMap.get(fx.dlModelId));
        assertEquals(target.toURL(idMap.get(fx.modelFileId)), dl.getModelURL());
        LargeFileGist lf = dl.getLargeFileGist(null);
        assertEquals(target.getKey(), lf.getRepository().getKey());
        File out = tmp.newFolder("out");
        assertArrayEquals(fx.modelData, Files.readAllBytes(lf.retrieveFile(out, false, false, null, null, null).toPath()));
        // configuration: linked block and model id re-mapped, thumbnail copied
        GistConfiguration conf = new GistConfiguration(target, idMap.get(fx.configId));
        JSONObject content = conf.getContent();
        assertEquals(idMap.get(fx.linkedConfigId), ((JSONObject) content.get("measurements")).get("config_id"));
        assertEquals(idMap.get(fx.modelFileId), content.get("model_id"));
        assertEquals("aGVsbG8=", target.get(conf.getID()).getContent("thumbnail"));
        assertEquals(GistConfiguration.TYPE.WHOLE, conf.getType());

        // second run: nothing to do
        r = new RepositorySync(source, target).run();
        assertEquals(r.toString(), nSource, r.count(RepositorySync.ACTION.UNCHANGED));

        // source modification is propagated
        source.update(fx.linkedConfigId, "measurements v2", null, null);
        r = new RepositorySync(source, target).run();
        assertEquals(Collections.singletonList(fx.linkedConfigId), r.get(RepositorySync.ACTION.UPDATED));
        assertEquals("measurements v2", target.get(idMap.get(fx.linkedConfigId)).getDescription());

        // target modification is a conflict, unless overwrite
        target.update(idMap.get(fx.dlModelId), "edited in target", null, null);
        r = new RepositorySync(source, target).run();
        assertEquals(Collections.singletonList(fx.dlModelId), r.get(RepositorySync.ACTION.CONFLICT));
        assertEquals("edited in target", target.get(idMap.get(fx.dlModelId)).getDescription());

        // reverse synchronization: the target change goes back to the original item, no duplicates, references mapped back
        r = new RepositorySync(target, source).run();
        assertFalse(r.getMessages().toString(), r.hasErrors());
        assertEquals(Collections.singletonList(idMap.get(fx.dlModelId)), r.get(RepositorySync.ACTION.UPDATED));
        assertEquals(nSource + 1, source.list().size()); // + state item, saved in the target of the reverse synchronization
        GistDLModel back = new GistDLModel(source, fx.dlModelId);
        assertEquals("edited in target", back.getDescription());
        assertEquals(source.toURL(fx.modelFileId), back.getModelURL());
        r = new RepositorySync(source, target).run();
        assertEquals(r.toString(), nSource, r.count(RepositorySync.ACTION.UNCHANGED));

        // the state is only stored in the repositories: a state loaded from scratch (e.g. another computer) gives the same mapping
        SyncState fresh = SyncState.load(source, source.list(), target, target.list());
        assertEquals(idMap.get(fx.dlModelId), fresh.get(source, fx.dlModelId).targetId);
        assertEquals(fx.dlModelId, fresh.get(target, idMap.get(fx.dlModelId)).targetId);
        // state items are not synchronized and not seen as configurations or models
        assertEquals(0, GistConfiguration.getConfigurations(target, null).stream().filter(c -> c.getID().equals(fresh.getStorageItemId())).count());

        // deletion
        source.delete(fx.configId);
        r = new RepositorySync(source, target).setDeleteRemoved(true).run();
        assertEquals(Collections.singletonList(fx.configId), r.get(RepositorySync.ACTION.DELETED));
        assertFalse(target.exists(idMap.get(fx.configId)));
    }

    @Test
    public void syncModelFilesToAnotherRepository() throws Exception {
        SmallChunkRepository source = new SmallChunkRepository(tmp.newFolder("src"));
        RemoteLikeRepository target = new RemoteLikeRepository(tmp.newFolder("tgt"));
        RemoteLikeRepository weights = new RemoteLikeRepository(tmp.newFolder("weights"));
        Fixture fx = new Fixture(source, tmp.newFile("w.bin"));
        Set<String> modelFileItems = new HashSet<>(new LargeFileGist(source, fx.modelFileId).subFileIds);
        modelFileItems.add(fx.modelFileId);
        RepositorySync.Result r = new RepositorySync(source, target).setModelFileTarget(weights).run();
        assertFalse(r.getMessages().toString(), r.hasErrors());
        Map<String, String> idMap = r.getIdMap();
        // model file (master + sub-files) in the weights repository, models and configurations in the target
        for (String id : modelFileItems) {
            assertEquals(weights.getKey(), r.getTargetRepositoryKey(id));
            assertTrue(weights.exists(idMap.get(id)));
            assertFalse(target.exists(idMap.get(id)));
        }
        for (String id : Arrays.asList(fx.dlModelId, fx.configId, fx.linkedConfigId)) assertEquals(target.getKey(), r.getTargetRepositoryKey(id));
        assertEquals(modelFileItems.size() + 1, weights.list().size()); // + state item
        GistDLModel dl = new GistDLModel(target, idMap.get(fx.dlModelId));
        assertEquals(weights.toURL(idMap.get(fx.modelFileId)), dl.getModelURL());
        LargeFileGist lf = dl.getLargeFileGist(null);
        assertEquals(weights.getKey(), lf.getRepository().getKey());
        assertArrayEquals(fx.modelData, Files.readAllBytes(lf.retrieveFile(tmp.newFolder("out"), false, false, null, null, null).toPath()));
        assertEquals(idMap.get(fx.modelFileId), new GistConfiguration(target, idMap.get(fx.configId)).getContent().get("model_id"));
        // second run: nothing to do (states stored in both target repositories)
        r = new RepositorySync(source, target).setModelFileTarget(weights).run();
        assertEquals(r.toString(), r.getActions().size(), r.count(RepositorySync.ACTION.UNCHANGED));
        // without model file repository: model files are stored along with the models
        RemoteLikeRepository target2 = new RemoteLikeRepository(tmp.newFolder("tgt2"));
        r = new RepositorySync(source, target2).setModelFileTarget(null).run();
        for (String id : modelFileItems) assertEquals(target2.getKey(), r.getTargetRepositoryKey(id));
    }

    @Test
    public void syncSelectedItems() throws Exception {
        SmallChunkRepository source = new SmallChunkRepository(tmp.newFolder("src"));
        Fixture fx = new Fixture(source, tmp.newFile("w.bin"));
        List<RepositorySync.SelectableItem> items = RepositorySync.listSelectableItems(source);
        assertEquals(3, items.size()); // DL model + 2 configurations; model file items are not selectable
        assertEquals(1, RepositorySync.listSelectableItems(source, RepositorySync.LIBRARY.DL_MODELS).size());
        RepositorySync.SelectableItem model = items.stream().filter(i -> i.library == RepositorySync.LIBRARY.DL_MODELS).findFirst().get();
        assertEquals("folder", model.folder);
        assertEquals("mymodel", model.name);
        // only the DL model: its model file is synchronized too, not the configurations
        RemoteLikeRepository target = new RemoteLikeRepository(tmp.newFolder("tgt"));
        RepositorySync.Result r = new RepositorySync(source, target).setSelection(Collections.singleton(fx.dlModelId)).run();
        assertFalse(r.hasErrors());
        assertTrue(r.getActions().containsKey(fx.dlModelId));
        assertTrue(r.getActions().containsKey(fx.modelFileId));
        assertFalse(r.getActions().containsKey(fx.configId));
        assertTrue(GistConfiguration.getConfigurations(target, null).isEmpty());
        // selection of the configuration that links another configuration: the linked one is synchronized too
        r = new RepositorySync(source, target).setSelection(Collections.singleton(fx.configId)).setFilter(RepositorySync.libraries(RepositorySync.LIBRARY.CONFIGURATIONS)).run();
        assertTrue(r.getActions().containsKey(fx.linkedConfigId));
        assertEquals(2, GistConfiguration.getConfigurations(target, null).size());
    }

    @Test
    public void syncNotebooks() throws Exception {
        SmallChunkRepository source = new SmallChunkRepository(tmp.newFolder("src"));
        new Fixture(source, tmp.newFile("w.bin"));
        String small = LargeFileGist.storeString(source, "jnb_Analysis_small.ipynb", "{\"cells\":[]}", true, "small notebook", ".ipynb", false, null, null).key;
        String bigContent = repeat('n', 5000); // chunks + sub-files
        String big = LargeFileGist.storeString(source, "jnb_Analysis_big.ipynb", bigContent, true, "big notebook", ".ipynb", false, null, null).key;
        List<RepositorySync.SelectableItem> nb = RepositorySync.listSelectableItems(source, RepositorySync.LIBRARY.NOTEBOOKS);
        assertEquals(2, nb.size());
        assertTrue(nb.stream().allMatch(i -> i.folder.equals("Analysis")));
        assertEquals(new HashSet<>(Arrays.asList("small", "big")), nb.stream().map(i -> i.name).collect(java.util.stream.Collectors.toSet()));
        RemoteLikeRepository target = new RemoteLikeRepository(tmp.newFolder("tgt"));
        RepositorySync.Result r = new RepositorySync(source, target).setFilter(RepositorySync.libraries(RepositorySync.LIBRARY.NOTEBOOKS)).run();
        assertFalse(r.getMessages().toString(), r.hasErrors());
        assertTrue(GistConfiguration.getConfigurations(target, null).isEmpty());
        assertTrue(GistDLModel.get(target, null).isEmpty());
        List<LargeFileGist> notebooks = LargeFileGist.fetch(target, n -> n.startsWith("jnb_"), null).collect(java.util.stream.Collectors.toList());
        assertEquals(2, notebooks.size());
        assertEquals("{\"cells\":[]}", new LargeFileGist(target, r.getIdMap().get(small)).retrieveString(null, null));
        assertEquals(bigContent, new LargeFileGist(target, r.getIdMap().get(big)).retrieveString(null, null)); // sub-file ids re-mapped
    }

    /** Behaves like GitLab: private items cannot be read with their url */
    static class PrivateRepository extends RemoteLikeRepository {
        PrivateRepository(File root) {super(root);}
        @Override public boolean privateItemsAccessibleByURL() {return false;}
    }

    @Test
    public void syncMakesLinkedItemsPublic() throws Exception {
        SmallChunkRepository source = new SmallChunkRepository(tmp.newFolder("src"));
        Fixture fx = new Fixture(source, tmp.newFile("w.bin"));
        source.update(fx.linkedConfigId, null, false, null); // private configuration linked by a public one
        String privateUnlinked = source.create("private", false, files("bacmman-config-meas_folder_alone.json", "{}")).getID();
        PrivateRepository target = new PrivateRepository(tmp.newFolder("tgt"));
        RepositorySync.Result r = new RepositorySync(source, target).run();
        assertFalse(r.hasErrors());
        assertTrue("linked configuration of a public configuration made public", target.get(r.getIdMap().get(fx.linkedConfigId)).isVisible());
        assertFalse("private configuration not linked by a public one stays private", target.get(r.getIdMap().get(privateUnlinked)).isVisible());
        assertTrue(r.getWarnings().stream().anyMatch(w -> w.contains("made public")));
        // repository that allows reading private items by url: visibility unchanged
        RemoteLikeRepository target2 = new RemoteLikeRepository(tmp.newFolder("tgt2"));
        r = new RepositorySync(source, target2).run();
        assertFalse(target2.get(r.getIdMap().get(fx.linkedConfigId)).isVisible());
    }

    @Test
    public void links() throws Exception {
        LocalRepository local = GistRepository.local(tmp.newFolder("lib"));
        GitLabRepository gitlab = GistRepository.gitlab("https://git.example.org", new NoAuth("user"));
        GitHubRepository github = GistRepository.github("user");
        // links stored outside of the repository
        assertEquals("abc", GistConfiguration.externalLink(github, "abc"));
        assertEquals("https://git.example.org/-/snippets/12", GistConfiguration.externalLink(gitlab, "12"));
        assertEquals(local.toURL("abc"), GistConfiguration.externalLink(local, "abc"));
        // resolution: bare ids in the repository of the referencing configuration, urls in their own repository
        assertSame(gitlab, GistConfiguration.resolveLink(gitlab, "12"));
        assertSame(gitlab, GistConfiguration.resolveLink(gitlab, gitlab.toURL("12")));
        assertEquals(local.getKey(), GistConfiguration.resolveLink(gitlab, local.toURL("abc")).getKey());
        assertEquals(GistRepository.TYPE.GITHUB, GistConfiguration.resolveLink(local, "https://gist.github.com/abc").getType());
        assertEquals(GistRepository.TYPE.GITLAB, GistConfiguration.resolveLink(github, "https://git.example.org/-/snippets/12").getType());
        // copy to a dataset: links are urls for repositories other than GitHub
        bacmman.plugins.PluginFactory.findPlugins("bacmman.plugins.plugins");
        bacmman.configuration.experiment.MeasurementList ml = new bacmman.configuration.experiment.Experiment("").getMeasurements();
        ml.setConfigID("tmp"); // serialized as an object, as stored measurement configurations
        org.json.simple.JSONObject measContent = (org.json.simple.JSONObject) ml.toJSONEntry();
        measContent.remove(bacmman.configuration.experiment.ConfigIDAware.key);
        String measId = local.create("meas", true, files("bacmman-config-meas_folder_meas.json", measContent.toJSONString())).getID();
        bacmman.configuration.experiment.Experiment remote = new bacmman.configuration.experiment.Experiment("");
        remote.getMeasurements().setConfigID(measId);
        org.json.simple.JSONObject content = remote.toJSONEntry();
        String wholeId = local.create("whole", true, files("bacmman-config-whole_folder_whole.json", content.toJSONString())).getID();
        GistConfiguration whole = new GistConfiguration(local, wholeId);
        bacmman.configuration.experiment.Experiment dataset = new bacmman.configuration.experiment.Experiment("dataset");
        GistConfiguration meas = new GistConfiguration(local, measId);
        GistConfiguration.copyRemoteToLocal(dataset, 0, GistConfiguration.TYPE.MEASUREMENTS, GistConfiguration.getParameter(meas, -1, GistConfiguration.TYPE.MEASUREMENTS, null), measId, -1, local, null);
        assertEquals(local.toURL(measId), dataset.getMeasurements().getConfigID());
        // same within the repository (auto-update of library configurations): links stay bare ids
        bacmman.configuration.experiment.Experiment libraryXP = new bacmman.configuration.experiment.Experiment("lib");
        GistConfiguration.copyRemoteToLocal(libraryXP, 0, GistConfiguration.TYPE.MEASUREMENTS, GistConfiguration.getParameter(meas, -1, GistConfiguration.TYPE.MEASUREMENTS, null), measId, -1, local, null, false);
        assertEquals(measId, libraryXP.getMeasurements().getConfigID());
        // new dataset from a whole configuration: the dataset and its linked blocks point to urls
        org.json.simple.JSONObject ext = GistConfiguration.toExternalContent(whole);
        assertEquals(local.toURL(wholeId), ext.get(bacmman.configuration.experiment.ConfigIDAware.key));
        assertEquals(local.toURL(measId), ((org.json.simple.JSONObject) ext.get("measurements")).get(bacmman.configuration.experiment.ConfigIDAware.key));
    }

    @Test
    public void customGitLabServer() {
        assertEquals("https://gitlab.com", GitLabRepository.normalizeServerURL(null));
        assertEquals("https://gitlab.com", GitLabRepository.normalizeServerURL(" "));
        assertEquals("https://git.example.org", GitLabRepository.normalizeServerURL("git.example.org/"));
        assertEquals("https://git.example.org/GitLab", GitLabRepository.normalizeServerURL(" HTTPS://Git.Example.org/GitLab// "));
        assertEquals("http://localhost:8080", GitLabRepository.normalizeServerURL("http://localhost:8080/"));
        GitLabRepository r = GistRepository.gitlab("Git.Example.org/gitlab/", new NoAuth("user"));
        assertEquals("https://git.example.org/gitlab", r.getServerURL());
        assertEquals("https://git.example.org/gitlab/-/snippets/5", r.toURL("5"));
        assertEquals("5", r.idFromURL("https://git.example.org/gitlab/-/snippets/5"));
        assertNull(r.idFromURL("https://gitlab.com/-/snippets/5")); // other server
        assertEquals("https://git.example.org/gitlab/-/user_settings/personal_access_tokens?name=BACMMAN&description=BACMMAN+configuration+and+model+library&scopes=api", GitLabRepository.getTokenCreationURL("git.example.org/gitlab"));
        GistRepository fromUrl = GistRepository.fromURL("https://git.example.org/gitlab/-/snippets/5", null);
        assertEquals(r.getKey().replace(":user", ":"), fromUrl.getKey());
        assertEquals("gitlab:https://git.example.org/gitlab:user", r.getKey());
        // gateway: credentials per server, whatever the spelling of the server
        bacmman.core.RepositoryGateway gw = new bacmman.core.RepositoryGateway();
        gw.setCredentials(GistRepository.TYPE.GITLAB, "git.example.org/gitlab", "user", "pw".toCharArray());
        assertEquals("user", gw.getUsername(GistRepository.TYPE.GITLAB, "https://Git.Example.org/gitlab/"));
        assertNull(gw.getUsername(GistRepository.TYPE.GITLAB, null)); // gitlab.com
        assertEquals("user", gw.getRepository("https://git.example.org/gitlab/-/snippets/5").getAuth().getAccount());
    }

    @Test
    public void gatewayCredentials() throws Exception {
        RepositoryGateway gw = new RepositoryGateway();
        gw.setCredentials("ghuser", "ghpw".toCharArray());
        gw.setCredentials(GistRepository.TYPE.GITLAB, "https://git.example.org/", "gluser", "glpw".toCharArray());
        assertEquals("ghuser", gw.getUsername()); // GitLab credentials do not modify GitHub ones
        assertEquals("gluser", gw.getUsername(GistRepository.TYPE.GITLAB, "https://git.example.org"));
        assertNull(gw.getUsername(GistRepository.TYPE.GITLAB, "https://gitlab.com"));
        assertArrayEquals("glpw".toCharArray(), gw.getPassword(GistRepository.TYPE.GITLAB, "https://git.example.org", "gluser"));
        GistRepository r = gw.getRepository("https://git.example.org/-/snippets/12");
        assertEquals(GistRepository.TYPE.GITLAB, r.getType());
        assertEquals("gluser", r.getAuth().getAccount()); // no token stored: NoAuth with the account of the session
        assertEquals(GistRepository.TYPE.GITHUB, gw.getRepository("abcdef0123456789").getType());
        File f = tmp.newFolder("loc");
        assertEquals(GistRepository.local(f).getKey(), gw.getRepository(GistRepository.local(f).toURL("x")).getKey());
    }

    @Test
    public void syncToLocalKeepsIds() throws Exception {
        RemoteLikeRepository source = new RemoteLikeRepository(tmp.newFolder("remote"));
        LocalRepository mirror = new LocalRepository(tmp.newFolder("mirror"));
        Fixture fx = new Fixture(source, tmp.newFile("w.bin"));
        RepositorySync.Result r = new RepositorySync(source, mirror).setDryRun(true).run();
        assertEquals(source.list().size(), r.count(RepositorySync.ACTION.CREATED));
        assertTrue(mirror.list().isEmpty());
        r = new RepositorySync(source, mirror).run();
        assertFalse(r.hasErrors());
        for (Map.Entry<String, String> e : r.getIdMap().entrySet()) assertEquals(e.getKey(), e.getValue());
        // state is stored as a private item of the target; if lost, items are matched by id, not duplicated
        GistItem stateItem = mirror.list().stream().filter(SyncState::isStateItem).findFirst().orElse(null);
        assertNotNull(stateItem);
        assertFalse(stateItem.isVisible());
        assertTrue(stateItem.hasFile(SyncState.fileName(source, mirror)));
        assertEquals(SyncState.fileName(source, mirror), SyncState.fileName(mirror, source));
        mirror.delete(stateItem.getID());
        int n = mirror.list().size();
        r = new RepositorySync(source, mirror).run();
        assertFalse(r.hasErrors());
        assertEquals(n + 1, mirror.list().size()); // + state item
        // model file of the mirrored DL model is read from the mirror
        assertEquals(mirror.getKey(), new GistDLModel(mirror, fx.dlModelId).getLargeFileGist(null).getRepository().getKey());
    }
}
