package bacmman.github.gist;

import bacmman.configuration.experiment.ConfigIDAware;
import bacmman.configuration.experiment.Experiment;
import bacmman.configuration.parameters.FileChooser;
import bacmman.configuration.parameters.MLModelFileParameter;
import org.json.simple.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.*;

public class LocalLinksTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void relativizeAndResolve() throws Exception {
        File root = tmp.getRoot().getCanonicalFile();
        Path dataset = new File(root, "Images/MotherMachine/dataset1").toPath();
        Files.createDirectories(dataset);
        LocalRepository repo = new LocalRepository(new File(root, "TestRepo"));
        String link = repo.toURL("abc123");
        String rel = LocalLinks.relativize(link, dataset);
        assertEquals("file:../../../TestRepo/abc123", rel);
        assertEquals(link, LocalLinks.resolve(rel, dataset));
        // repository inside the dataset folder
        assertEquals("file:./TestRepo/abc123", LocalLinks.relativize(link, root.toPath()));
        assertEquals(link, LocalLinks.resolve("file:./TestRepo/abc123", root.toPath()));
        // other links are unchanged
        assertEquals("abc123", LocalLinks.relativize("abc123", dataset));
        assertEquals("https://gitlab.com/-/snippets/12", LocalLinks.relativize("https://gitlab.com/-/snippets/12", dataset));
        assertEquals(link, LocalLinks.resolve(link, dataset));
        assertEquals(link, LocalLinks.relativize(link, null));
        // relative links are urls (not GitHub ids), and are not resolved without the dataset folder
        assertTrue(rel.contains("/"));
        assertNull(GistRepository.fromURL(rel, null));
    }

    @Test
    public void noRelativePath() {
        // no relative path (e.g. other drive on Windows; here: relative reference): paths are kept absolute
        Path ref = Paths.get("relative/ref");
        assertEquals("/data/x", FileChooser.toRelativePath(ref, "/data/x"));
        assertEquals("/data/x", FileChooser.toAbsolutePath(ref, "/data/x"));
        assertEquals("a/b", FileChooser.toRelativePath(Paths.get("/data"), "a/b")); // already relative
    }

    @Test
    public void configurationStoresRelativeLinks() throws Exception {
        File root = tmp.getRoot().getCanonicalFile();
        Path dsDir = new File(root, "Images/dataset1").toPath();
        Files.createDirectories(dsDir);
        LocalRepository repo = new LocalRepository(new File(root, "TestRepo"));
        String configLink = repo.toURL("config1");
        String modelLink = repo.toURL("model1");
        Experiment xp = new Experiment("dataset1").setPath(dsDir);
        xp.setConfigID(configLink);
        xp.getStructures().insert(xp.getStructures().createChildInstance());
        xp.getStructure(0).getProcessingPipelineParameter().setConfigID(configLink);
        MLModelFileParameter model = new MLModelFileParameter("model");
        model.setParent(xp);
        model.configureFromMetadata(modelLink, new DLModelMetadata());
        // stored relative, used absolute
        JSONObject json = xp.toJSONEntry();
        assertEquals("file:../../TestRepo/config1", json.get(ConfigIDAware.key));
        assertEquals(configLink, xp.getConfigID());
        assertEquals(configLink, xp.getStructure(0).getProcessingPipelineParameter().getConfigID());
        assertFalse(json.toJSONString(), json.toJSONString().contains(root.getPath().replace("/", "\\/")));
        assertEquals("file:../../TestRepo/model1", ((JSONObject) model.toJSONEntry()).get("id"));
        assertEquals(modelLink, model.getID());
        // dataset reloaded
        Experiment xp2 = new Experiment("dataset1").setPath(dsDir);
        xp2.initFromJSONEntry(json);
        assertEquals(configLink, xp2.getConfigID());
        assertEquals(configLink, xp2.getStructure(0).getProcessingPipelineParameter().getConfigID());
        // copy to a configuration without dataset folder (e.g. library): absolute links
        MLModelFileParameter detached = new MLModelFileParameter("model");
        detached.setContentFrom(model);
        assertEquals(modelLink, detached.getID());
        assertEquals(modelLink, ((JSONObject) detached.toJSONEntry()).get("id"));
        Experiment lib = new Experiment("lib");
        lib.setContentFrom(xp);
        assertEquals(configLink, lib.getStructure(0).getProcessingPipelineParameter().getConfigID());
        // JSON of the dataset resolved for a library
        LocalLinks.resolveAll(json, dsDir);
        assertEquals(configLink, json.get(ConfigIDAware.key));
        // absolute links (e.g. dataset created from a library) are stored relative
        Experiment xp3 = new Experiment("dataset1").setPath(dsDir);
        xp3.initFromJSONEntry(json);
        assertEquals("file:../../TestRepo/config1", xp3.toJSONEntry().get(ConfigIDAware.key));
    }

    @Test
    public void sameLinkWrittenDifferently() throws Exception {
        File root = tmp.getRoot().getCanonicalFile();
        Path dataset = new File(root, "Documents/BACMMAN/Images/dataset1").toPath();
        Files.createDirectories(dataset);
        LocalRepository repo = new LocalRepository(new File(root, "Documents/BACMMAN/Configurations"));
        String id = "288028a4604b47679f4855e3274f0940";
        String abs = repo.toURL(id);
        String rel = LocalLinks.relativize(abs, dataset);
        assertEquals("file:../../Configurations/" + id, rel);
        // absolute url, bare id (link inside the repository), other spelling of the same folder
        assertTrue(GistConfiguration.sameLink(abs, id));
        assertTrue(GistConfiguration.sameLink(id, abs));
        assertTrue(GistConfiguration.sameLink(LocalLinks.resolve(rel, dataset), abs));
        String otherSpelling = "file:" + new File(root, "Documents/BACMMAN/Images/../Configurations").getAbsolutePath().replace(File.separatorChar, '/') + "/" + id;
        assertTrue(otherSpelling, GistConfiguration.sameLink(otherSpelling, abs));
        assertEquals(id, repo.idFromURL(otherSpelling));
        // different items or repositories
        assertFalse(GistConfiguration.sameLink(abs, repo.toURL("ffff28a4604b47679f4855e3274f0940")));
        assertFalse(GistConfiguration.sameLink(abs, "ffff28a4604b47679f4855e3274f0940"));
        LocalRepository other = new LocalRepository(new File(root, "OtherRepo"));
        assertFalse(GistConfiguration.sameLink(abs, other.toURL(id)));
        assertFalse(GistConfiguration.sameLink(abs, null));
        assertTrue(GistConfiguration.sameLink(null, null));
        assertFalse(GistConfiguration.sameLink("abc", "abd"));
    }

    @Test
    public void linksToTargetRepositoryBecomeBareIds() throws Exception {
        File root = tmp.getRoot().getCanonicalFile();
        LocalRepository repo = new LocalRepository(new File(root, "Configurations"));
        LocalRepository other = new LocalRepository(new File(root, "Other"));
        String id = "288028a4604b47679f4855e3274f0940";
        JSONObject block = new JSONObject();
        block.put(ConfigIDAware.key, repo.toURL(id));
        JSONObject block2 = new JSONObject();
        block2.put(ConfigIDAware.key, other.toURL(id));
        JSONObject content = new JSONObject();
        content.put("a", block);
        content.put("b", block2);
        GistConfiguration.convertLinks(content, null, repo); // dataset configuration saved to the library
        assertEquals("link to the target repository: bare id", id, block.get(ConfigIDAware.key));
        assertEquals("link to another repository: unchanged", other.toURL(id), block2.get(ConfigIDAware.key));
    }

    @Test
    public void linkedBlocksComparedByItem() throws Exception {
        File root = tmp.getRoot().getCanonicalFile();
        Path dsDir = new File(root, "Images/dataset1").toPath();
        Files.createDirectories(dsDir);
        LocalRepository repo = new LocalRepository(new File(root, "Configurations"));
        String id = "288028a4604b47679f4855e3274f0940";
        // dataset: relative link, resolved with the dataset folder
        Experiment dataset = new Experiment("ds");
        dataset.setPath(dsDir);
        dataset.getPreProcessingTemplate().setConfigID(repo.toURL(id));
        assertTrue(LocalLinks.isRelative((String) dataset.getPreProcessingTemplate().toJSONEntry().get(ConfigIDAware.key)));
        // library: bare id (link inside the repository), or absolute url
        Experiment lib = new Experiment("lib");
        lib.getPreProcessingTemplate().setConfigID(id);
        assertTrue(dataset.getPreProcessingTemplate().sameContent(lib.getPreProcessingTemplate()));
        lib.getPreProcessingTemplate().setConfigID(repo.toURL(id));
        assertTrue(dataset.getPreProcessingTemplate().sameContent(lib.getPreProcessingTemplate()));
        lib.getPreProcessingTemplate().setConfigID(repo.toURL("ffff28a4604b47679f4855e3274f0940"));
        assertFalse(dataset.getPreProcessingTemplate().sameContent(lib.getPreProcessingTemplate()));
    }

    @Test
    public void modelLinkComparedByItem() throws Exception {
        // the case reported on Windows: dataset stores file:../../../Configurations/<id>, library stores the absolute url
        File root = tmp.getRoot().getCanonicalFile();
        Path dsDir = new File(root, "Documents/BACMMAN/Images/Workshop/dataset1").toPath();
        Files.createDirectories(dsDir);
        LocalRepository repo = new LocalRepository(new File(root, "Documents/BACMMAN/Configurations"));
        String id = "288028a4604b47679f4855e3274f0940";
        Experiment xp = new Experiment("dataset1").setPath(dsDir);
        MLModelFileParameter model = new MLModelFileParameter("model");
        model.setParent(xp);
        model.configureFromMetadata(repo.toURL(id), new DLModelMetadata());
        assertEquals("file:../../../Configurations/" + id, ((JSONObject) model.toJSONEntry()).get("id"));
        MLModelFileParameter lib = new MLModelFileParameter("model"); // library: no dataset folder
        lib.configureFromMetadata(repo.toURL(id), new DLModelMetadata());
        assertTrue(model.getChildren().get(1).sameContent(lib.getChildren().get(1))); // "Model ID" row of the tree
        assertTrue(lib.getChildren().get(1).sameContent(model.getChildren().get(1)));
        MLModelFileParameter other = new MLModelFileParameter("model");
        other.configureFromMetadata(repo.toURL("ffff28a4604b47679f4855e3274f0940"), new DLModelMetadata());
        assertFalse(model.getChildren().get(1).sameContent(other.getChildren().get(1)));
    }
}
