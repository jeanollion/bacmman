package bacmman.github.gist;

import bacmman.configuration.experiment.ConfigIDAware;
import bacmman.configuration.experiment.Experiment;
import bacmman.configuration.parameters.MLModelFileParameter;
import bacmman.utils.JSONUtils;
import org.apache.commons.io.FileUtils;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * Links stored in library configurations: relative to the repository (bare ids) or to its folder (other local repositories), resolved when used outside of the repository
 */
public class LinkFormTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    static class GitLabLike extends LocalRepository {
        GitLabLike(File root) {super(root);}
        @Override public TYPE getType() {return TYPE.GITLAB;}
    }

    static Map<String, String> files(String name, String content) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(name, content);
        return m;
    }

    static JSONObject modelParam(String link) {
        JSONObject p = new JSONObject();
        p.put("file", new JSONArray());
        p.put("id", link);
        return p;
    }

    /**
     * whole configuration: processing pipeline linked to {@param block}, with a DL model parameter linked to {@param model}
     */
    static JSONObject whole(String block, String model) {
        JSONObject ps = new JSONObject();
        ps.put(ConfigIDAware.key, block);
        ps.put("model", modelParam(model));
        JSONObject oc = new JSONObject();
        oc.put("name", "Bacteria");
        oc.put("processingScheme", ps);
        JSONArray st = new JSONArray();
        st.add(oc);
        JSONObject c = new JSONObject();
        c.put("structures", st);
        return c;
    }
    static JSONObject ps(JSONObject whole) {
        return (JSONObject) ((JSONObject) ((JSONArray) whole.get("structures")).get(0)).get("processingScheme");
    }
    static String model(JSONObject whole) {
        return (String) ((JSONObject) ps(whole).get("model")).get("id");
    }
    static String block(JSONObject whole) {
        return (String) ps(whole).get(ConfigIDAware.key);
    }
    static JSONObject copy(JSONObject o) throws Exception {
        return JSONUtils.parse(o.toJSONString());
    }

    @Test
    public void storedAndExternalForms() throws Exception {
        File root = tmp.getRoot().getCanonicalFile();
        LocalRepository lib = new LocalRepository(new File(root, "BACMMAN/Configurations"));
        LocalRepository models = new LocalRepository(new File(root, "BACMMAN/Models"));
        GitLabLike gitlab = new GitLabLike(new File(root, "gitlab"));
        String blockId = "aaaa28a4604b47679f4855e3274f0940", fileId = "288028a4604b47679f4855e3274f0940", github = "0123456789abcdef0123456789abcdef";

        // links to the library: bare ids, links to another local repository: relative to the folder of the library
        assertEquals(fileId, GistConfiguration.storedLink(lib.toURL(fileId), lib, true));
        assertEquals(blockId, GistConfiguration.storedLink(lib.toURL(blockId), lib, false));
        assertEquals("file:../Models/" + fileId, GistConfiguration.storedLink(models.toURL(fileId), lib, true));
        assertEquals(github, GistConfiguration.storedLink(github, lib, true)); // GitHub id
        assertEquals("https://gist.github.com/" + github, GistConfiguration.storedLink("https://gist.github.com/" + github, lib, false));
        // GitLab: model links are urls (bare model ids are GitHub ids), blocks bare ids, local links absolute
        assertEquals(gitlab.toURL(fileId), GistConfiguration.storedLink(gitlab.toURL(fileId), gitlab, true));
        assertEquals(blockId, GistConfiguration.storedLink(gitlab.toURL(blockId), gitlab, false));
        assertEquals(models.toURL(fileId), GistConfiguration.storedLink(models.toURL(fileId), gitlab, true));

        // external form
        assertEquals(lib.toURL(fileId), GistConfiguration.externalLink(fileId, lib, true, false));
        assertEquals(models.toURL(fileId), GistConfiguration.externalLink("file:../Models/" + fileId, lib, true, false));
        assertEquals(blockId, GistConfiguration.externalLink(blockId, lib, false, false)); // resolved with the repository of the configuration
        assertEquals(lib.toURL(blockId), GistConfiguration.externalLink(blockId, lib, false, true));
        assertEquals(github, GistConfiguration.externalLink(github, GistRepository.github(new NoAuth()), true, true));
    }

    @Test
    public void datasetToLibraryAndBack() throws Exception {
        File root = tmp.getRoot().getCanonicalFile();
        Path dsDir = new File(root, "BACMMAN/Images/Workshop/dataset1").toPath();
        Files.createDirectories(dsDir);
        LocalRepository lib = new LocalRepository(new File(root, "BACMMAN/Configurations"));
        LocalRepository models = new LocalRepository(new File(root, "BACMMAN/Models"));
        String blockId = lib.create("", true, files("bacmman-config-pro_Blocks_Bacteria.json", "{\"list\":[]}")).getID();
        String fileId = lib.create("", true, files("weights.txt", "w")).getID();
        String otherFileId = models.create("", true, files("weights.txt", "w")).getID();

        // dataset configuration: links stored relative to the dataset folder
        JSONObject datasetContent = whole("file:../../../Configurations/" + blockId, "file:../../../Configurations/" + fileId);
        // Save to library (new): links resolved with the dataset folder, then stored relative to the library
        JSONObject content = copy(datasetContent);
        LocalLinks.resolveAll(content, dsDir);
        GistConfiguration.convertLinks(content, null, lib);
        GistConfiguration.toStoredForm(content, lib);
        assertEquals(blockId, block(content));
        assertEquals("model file of the library: bare id", fileId, model(content));
        String wholeId = lib.create("", true, files("bacmman-config-whole_Lib_dataset1.json", content.toJSONString())).getID();

        // displayed in the library: model link usable (absolute), block link resolved with the repository
        GistConfiguration gist = new GistConfiguration(lib, wholeId);
        // display (valid configuration): links resolved, the stored content is not modified
        Experiment stored = new Experiment("lib");
        stored.getPreProcessingTemplate().setConfigID("file:../Models/" + otherFileId);
        JSONObject storedJSON = stored.toJSONEntry();
        String before = storedJSON.toJSONString();
        Experiment displayed = GistConfiguration.getExperiment(storedJSON, GistConfiguration.TYPE.WHOLE, wholeId, lib, false);
        assertEquals(models.toURL(otherFileId), displayed.getPreProcessingTemplate().getConfigID());
        assertEquals(before, storedJSON.toJSONString());
        // a library model parameter (no dataset folder) and the dataset parameter designate the same model
        MLModelFileParameter libParam = new MLModelFileParameter("model");
        JSONObject ext = copy(content);
        GistConfiguration.toExternalForm(ext, lib, false);
        libParam.initFromJSONEntry(ps(ext).get("model"));
        assertEquals(lib.toURL(fileId), libParam.getID());
        Experiment ds = new Experiment("dataset1").setPath(dsDir);
        MLModelFileParameter dsParam = new MLModelFileParameter("model");
        dsParam.setParent(ds);
        dsParam.initFromJSONEntry(ps(datasetContent).get("model"));
        assertTrue(dsParam.getChildren().get(1).sameContent(libParam.getChildren().get(1)));

        // dataset created from the library: absolute links, stored again relative to the new dataset folder
        JSONObject external = GistConfiguration.toExternalContent(gist);
        assertEquals(lib.toURL(fileId), model(external));
        assertEquals(lib.toURL(blockId), block(external));

        // link to another local repository: relative to the folder of the library, resolved when used
        JSONObject c2 = whole(lib.toURL(blockId), models.toURL(otherFileId));
        GistConfiguration.toStoredForm(c2, lib);
        assertEquals("file:../Models/" + otherFileId, model(c2));
        JSONObject e2 = copy(c2);
        GistConfiguration.toExternalForm(e2, lib, true);
        assertEquals(models.toURL(otherFileId), model(e2));

        // the library and the model folder are moved together: links still valid
        File moved = new File(root, "Moved");
        FileUtils.copyDirectory(new File(root, "BACMMAN/Configurations"), new File(moved, "Configurations"));
        FileUtils.copyDirectory(new File(root, "BACMMAN/Models"), new File(moved, "Models"));
        LocalRepository movedLib = new LocalRepository(new File(moved, "Configurations"));
        LocalRepository movedModels = new LocalRepository(new File(moved, "Models"));
        JSONObject e3 = copy(c2);
        GistConfiguration.toExternalForm(e3, movedLib, true);
        assertEquals(movedModels.toURL(otherFileId), model(e3));
        assertEquals(movedLib.toURL(blockId), block(e3));

        // copied to another repository (A vs B): links of the source resolved, then stored in the form of the target
        GitLabLike gitlab = new GitLabLike(new File(root, "gitlab"));
        JSONObject c4 = copy(c2);
        GistConfiguration.convertLinks(c4, lib, gitlab);
        GistConfiguration.toStoredForm(c4, gitlab);
        assertEquals("relative to the source library", "file:../Models/" + otherFileId, model(c4));
        assertEquals("library name in the link", "file:../Configurations/" + blockId, block(c4));
        // back to a local library at the same relative location: links usable
        LocalRepository lib2 = new LocalRepository(new File(root, "BACMMAN/Configurations2"));
        JSONObject c5 = copy(c4);
        GistConfiguration.convertLinks(c5, gitlab, lib2);
        GistConfiguration.toStoredForm(c5, lib2);
        JSONObject e5 = copy(c5);
        GistConfiguration.toExternalForm(e5, lib2, true);
        assertEquals(models.toURL(otherFileId), model(e5));
    }

    @Test
    public void synchronizationKeepsRelativeLinksValid() throws Exception {
        File root = tmp.getRoot().getCanonicalFile();
        LocalRepository lib = new LocalRepository(new File(root, "BACMMAN/Configurations"));
        LocalRepository models = new LocalRepository(new File(root, "BACMMAN/Models"));
        String otherFileId = models.create("", true, files("weights.txt", "w")).getID();
        JSONObject c = whole(null, models.toURL(otherFileId));
        ps(c).remove(ConfigIDAware.key);
        GistConfiguration.toStoredForm(c, lib);
        assertEquals("file:../Models/" + otherFileId, model(c));
        String id = lib.create("", true, files("bacmman-config-whole_Lib_conf.json", c.toJSONString())).getID();
        // backup in a folder at another depth: the relative link is rewritten from the new folder
        LocalRepository backup = new LocalRepository(new File(root, "Backup/2026/Configurations"));
        RepositorySync.Result r = new RepositorySync(lib, backup).setFilter(RepositorySync.CONFIGURATIONS).run();
        assertFalse(r.getMessages().toString(), r.hasErrors());
        JSONObject synced = new GistConfiguration(backup, r.getIdMap().get(id)).getContent();
        assertEquals("file:../../../BACMMAN/Models/" + otherFileId, model(synced));
        JSONObject ext = copy(synced);
        GistConfiguration.toExternalForm(ext, backup, true);
        assertEquals(models.toURL(otherFileId), model(ext));
    }

    @Test
    public void missingLocalModel() throws Exception {
        File root = tmp.getRoot().getCanonicalFile();
        LocalRepository lib = new LocalRepository(new File(root, "BACMMAN/Configurations"));
        String fileId = lib.create("", true, files("weights.txt", "w")).getID();
        File modelDir = new File(root, "model");
        // model linked to an existing item, model file not downloaded yet: valid
        MLModelFileParameter p = new MLModelFileParameter("model");
        ((bacmman.configuration.parameters.TextParameter) p.getChildren().get(1)).setValue(lib.toURL(fileId));
        p.setSelectedFilePath(new File(modelDir, "notDownloaded").getAbsolutePath());
        assertTrue(p.isLinkedItemFound());
        assertTrue(p.getChildren().get(1).isValid());
        assertTrue(p.isValid());
        // local repository not found (other computer, moved): the link is displayed in red, and the parameter is an error if the model file is not found either
        String missing = new LocalRepository(new File(root, "Elsewhere/Configurations")).toURL(fileId);
        ((bacmman.configuration.parameters.TextParameter) p.getChildren().get(1)).setValue(missing);
        assertFalse(p.isLinkedItemFound());
        assertFalse(p.getChildren().get(1).isValid());
        assertTrue(p.getChildren().get(1).getHintText().contains("not found"));
        assertFalse(p.isValid());
        // model file present: the parameter is valid, the link is still displayed in red
        Files.createDirectories(modelDir.toPath());
        p.setSelectedFilePath(modelDir.getAbsolutePath());
        assertTrue(p.isValid());
        assertFalse(p.getChildren().get(1).isValid());
        // item deleted from an existing repository
        ((bacmman.configuration.parameters.TextParameter) p.getChildren().get(1)).setValue(lib.toURL("ffff28a4604b47679f4855e3274f0940"));
        assertFalse(p.isLinkedItemFound());
        // GitHub id: not checked here
        ((bacmman.configuration.parameters.TextParameter) p.getChildren().get(1)).setValue("0123456789abcdef0123456789abcdef");
        assertTrue(p.isLinkedItemFound());
    }

    @Test
    public void remoteKeepsLinksRelativeToDataset() throws Exception {
        File root = tmp.getRoot().getCanonicalFile();
        Path dsDir = new File(root, "BACMMAN/Images/Workshop/dataset1").toPath();
        Files.createDirectories(dsDir);
        LocalRepository lib = new LocalRepository(new File(root, "BACMMAN/Configurations"));
        String id = "288028a4604b47679f4855e3274f0940";
        JSONObject c = whole(lib.toURL(id), lib.toURL(id));
        GistConfiguration.relativizeLocalLinks(c, dsDir);
        assertEquals("file:../../../Configurations/" + id, model(c));
        assertEquals("file:../../../Configurations/" + id, block(c));
        // remote library: relative link kept (not resolved), and compared by item with the dataset link
        GitLabLike gitlab = new GitLabLike(new File(root, "gitlab"));
        assertEquals("file:../../../Configurations/" + id, GistConfiguration.externalLink("file:../../../Configurations/" + id, gitlab, true, true));
        assertEquals("file:../../../Configurations/" + id, GistConfiguration.storedLink("file:../../../Configurations/" + id, gitlab, true));
        assertTrue(GistConfiguration.sameLink("file:../../../Configurations/" + id, lib.toURL(id)));
        assertFalse(GistConfiguration.sameLink("file:../../../Configurations/" + id, lib.toURL("ffff28a4604b47679f4855e3274f0940")));
        // dataset created from the remote configuration (same layout): resolved with its folder
        Path ds2 = new File(root, "BACMMAN/Images/Other/dataset2").toPath();
        Files.createDirectories(ds2);
        assertEquals(lib.toURL(id), LocalLinks.resolve(model(c), ds2));
    }

    @Test
    public void githubLinksStayURLs() {
        GistRepository github = GistRepository.github(new NoAuth());
        String id = "0123456789abcdef0123456789abcdef";
        String url = github.toURL(id);
        assertTrue(url.contains("/"));
        // stored in a GitHub configuration: urls are kept, for blocks and models
        assertEquals(url, GistConfiguration.storedLink(url, github, false));
        assertEquals(url, GistConfiguration.storedLink(url, github, true));
        JSONObject c = whole(url, url);
        GistConfiguration.convertLinks(c, null, github);
        assertEquals(url, block(c));
        assertEquals(url, model(c));
        // used outside of the repository (dataset): url
        assertEquals(url, GistConfiguration.externalLink(github, id));
        assertEquals(url, GistDLModel.toModelLink(url));
        // legacy bare ids: still read as GitHub ids, and recognized as the same item as the url
        assertEquals(id, GistConfiguration.storedLink(id, github, false));
        assertTrue(GistConfiguration.sameLink(id, url));
        assertTrue(GistConfiguration.sameLink(url, id));
        assertEquals(GistRepository.TYPE.GITHUB, GistRepository.fromURL(id, null).getType());
    }

    @Test
    public void synchronizationToRemoteKeepsLinksRelative() throws Exception {
        File root = tmp.getRoot().getCanonicalFile();
        LocalRepository lib = new LocalRepository(new File(root, "BACMMAN/Configurations"));
        LocalRepository models = new LocalRepository(new File(root, "BACMMAN/Models"));
        String otherFileId = models.create("", true, files("weights.txt", "w")).getID();
        JSONObject rel = whole(null, "file:../Models/" + otherFileId);
        ps(rel).remove(ConfigIDAware.key);
        JSONObject abs = whole(null, models.toURL(otherFileId)); // legacy absolute link
        ps(abs).remove(ConfigIDAware.key);
        String relId = lib.create("", true, files("bacmman-config-whole_Lib_rel.json", rel.toJSONString())).getID();
        String absId = lib.create("", true, files("bacmman-config-whole_Lib_abs.json", abs.toJSONString())).getID();
        GitLabLike gitlab = new GitLabLike(new File(root, "gitlab"));
        RepositorySync.Result r = new RepositorySync(lib, gitlab).setFilter(RepositorySync.CONFIGURATIONS).run();
        assertFalse(r.getMessages().toString(), r.hasErrors());
        assertEquals("file:../Models/" + otherFileId, model(new GistConfiguration(gitlab, r.getIdMap().get(relId)).getContent()));
        assertEquals("file:../Models/" + otherFileId, model(new GistConfiguration(gitlab, r.getIdMap().get(absId)).getContent()));
    }

    @Test
    public void libraryNameInRelativeLinks() throws Exception {
        File root = tmp.getRoot().getCanonicalFile();
        LocalRepository lib = new LocalRepository(new File(root, "BACMMAN/Configurations"));
        LocalRepository models = new LocalRepository(new File(root, "BACMMAN/Models"));
        Path libDir = lib.getFolder().toPath();
        String id = "288028a4604b47679f4855e3274f0940";
        assertEquals("file:../Configurations/" + id, LocalLinks.relativizeToLibrary(lib.toURL(id), libDir));
        assertEquals("file:../Models/" + id, LocalLinks.relativizeToLibrary(models.toURL(id), libDir));
        assertEquals(lib.toURL(id), LocalLinks.resolve("file:../Configurations/" + id, libDir));
        // dataset folder: unchanged convention
        assertEquals("file:./sub/" + id, LocalLinks.relativize(new LocalRepository(new File(root, "BACMMAN/Images/sub")).toURL(id), new File(root, "BACMMAN/Images").toPath()));
    }
}
