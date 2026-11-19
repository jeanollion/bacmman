package bacmman.github.gist;

import bacmman.configuration.experiment.ConfigIDAware;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.util.*;

import static org.junit.Assert.*;

public class LinkCheckTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    /**
     * Local storage that behaves as a remote repository (GitLab) for the link check
     */
    static class RemoteRepository extends LocalRepository {
        RemoteRepository(File root) {super(root);}
        @Override public TYPE getType() {return TYPE.GITLAB;}
        @Override public synchronized GistItem create(String description, boolean visible, Map<String, String> files, String preferredId) throws IOException {
            return super.create(description, visible, files, null); // remote repositories choose ids
        }
    }

    static Map<String, String> files(String name, String content) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(name, content);
        return m;
    }

    static JSONObject block(String link) {
        JSONObject b = new JSONObject();
        b.put(ConfigIDAware.key, link);
        b.put(ConfigIDAware.autoUpdateKey, true);
        b.put("list", new JSONArray());
        return b;
    }

    /**
     * whole configuration: pre-processing template linked to {@param template}, processing pipeline of object class "Bacteria" linked to {@param processing}
     */
    static JSONObject whole(String template, String processing) {
        JSONObject c = new JSONObject();
        c.put("template", block(template));
        JSONObject oc = new JSONObject();
        oc.put("name", "Bacteria");
        oc.put("processingScheme", block(processing));
        JSONArray structures = new JSONArray();
        structures.add(oc);
        c.put("structures", structures);
        return c;
    }

    @Test
    public void unreachableLinksCopiedOnce() throws IOException {
        LocalRepository local = new LocalRepository(tmp.newFolder("local"));
        RemoteRepository remote = new RemoteRepository(tmp.newFolder("remote"));
        // local library: a measurement block, a pre-processing block, and a processing block that links the measurement block
        String measId = local.create("meas", true, files("bacmman-config-meas_Blocks_meas.json", "{\"list\":[]}")).getID();
        String ppId = local.create("pp", true, files("bacmman-config-pre_Blocks_PhC.json", "{\"list\":[]}")).getID();
        JSONObject proc = block(measId); // link inside the local library: bare id
        String procId = local.create("proc", true, files("bacmman-config-pro_Blocks_Bacteria.json", proc.toJSONString())).getID();
        String githubId = "0123456789abcdef0123456789abcdef";
        String remoteBlock = remote.create("other", true, files("bacmman-config-pre_Blocks_other.json", "{\"list\":[]}")).getID();

        // configuration stored on the remote repository: links to the local library, to GitHub, and to the remote repository (bare id)
        JSONObject content = whole(local.toURL(ppId), local.toURL(procId));
        content.put("measurements", block(remoteBlock));
        content.put("other", block("https://gist.github.com/" + githubId));
        List<LinkCheck.Link> links = LinkCheck.unreachableLinks(content, GistConfiguration.TYPE.WHOLE, remote);
        assertEquals("only the links to the local library", 2, links.size());
        LinkCheck.Link pp = links.stream().filter(l -> l.itemId.equals(ppId)).findFirst().get();
        LinkCheck.Link pr = links.stream().filter(l -> l.itemId.equals(procId)).findFirst().get();
        assertEquals(Collections.singletonList("Pre-processing template"), pp.getBlockDescriptions());
        assertEquals(Collections.singletonList("Processing pipeline of Bacteria"), pr.getBlockDescriptions());
        assertEquals("Blocks", pp.getLinkedConfiguration().folder());
        assertEquals("PhC", pp.getLinkedConfiguration().name());
        assertEquals(GistConfiguration.TYPE.PRE_PROCESSING, pp.getType());
        assertNull(pp.getPreviousCopyId());

        // copy: the processing block is copied with the measurement block it links
        String ppCopy = LinkCheck.copy(pp, remote, null);
        String prCopy = LinkCheck.copy(pr, remote, null);
        assertEquals(ppCopy, ((JSONObject) content.get("template")).get(ConfigIDAware.key));
        JSONObject oc = (JSONObject) ((JSONArray) content.get("structures")).get(0);
        assertEquals(prCopy, ((JSONObject) oc.get("processingScheme")).get(ConfigIDAware.key));
        assertEquals("auto update is kept", true, ((JSONObject) content.get("template")).get(ConfigIDAware.autoUpdateKey));
        GistConfiguration copied = new GistConfiguration(remote.get(prCopy));
        String linkedMeas = (String) copied.getContent().get(ConfigIDAware.key);
        assertFalse("link of the copy points to the remote repository: " + linkedMeas, linkedMeas.contains("/"));
        assertEquals(GistConfiguration.TYPE.MEASUREMENTS, new GistConfiguration(remote.get(linkedMeas)).getType());
        assertTrue(LinkCheck.unreachableLinks(content, GistConfiguration.TYPE.WHOLE, remote).isEmpty());
        int nItems = remote.list().size();

        // same links stored again: the previous copies are found and reused
        JSONObject content2 = whole(local.toURL(ppId), local.toURL(procId));
        List<LinkCheck.Link> links2 = LinkCheck.unreachableLinks(content2, GistConfiguration.TYPE.WHOLE, remote);
        LinkCheck.Link pp2 = links2.stream().filter(l -> l.itemId.equals(ppId)).findFirst().get();
        assertEquals(ppCopy, pp2.getPreviousCopyId());
        assertEquals(ppCopy, LinkCheck.copy(pp2, remote, null));
        assertEquals("no duplicate", nItems, remote.list().size());

        // unlink: the block keeps its content without link
        LinkCheck.Link pr2 = links2.stream().filter(l -> l.itemId.equals(procId)).findFirst().get();
        pr2.unlink();
        JSONObject ps = (JSONObject) ((JSONObject) ((JSONArray) content2.get("structures")).get(0)).get("processingScheme");
        assertFalse(ps.containsKey(ConfigIDAware.key));
        assertFalse(ps.containsKey(ConfigIDAware.autoUpdateKey));
        assertTrue(ps.containsKey("list"));

        // a configuration stored in a local repository can link another local repository
        assertTrue(LinkCheck.unreachableLinks(whole(local.toURL(ppId), local.toURL(procId)), GistConfiguration.TYPE.WHOLE, new LocalRepository(tmp.newFolder("local2"))).isEmpty());
    }

    @Test
    public void blockConfigurationRootDescribed() throws IOException {
        LocalRepository local = new LocalRepository(tmp.newFolder("local"));
        RemoteRepository remote = new RemoteRepository(tmp.newFolder("remote"));
        String measId = local.create("meas", true, files("bacmman-config-meas_Blocks_meas.json", "{\"list\":[]}")).getID();
        JSONObject content = block(local.toURL(measId)); // processing block that links a measurement block
        List<LinkCheck.Link> links = LinkCheck.unreachableLinks(content, GistConfiguration.TYPE.PROCESSING, remote);
        assertEquals(1, links.size());
        assertEquals(Collections.singletonList("Processing pipeline"), links.get(0).getBlockDescriptions());
        // a link to a configuration that no longer exists: found, but cannot be read (it can only be unlinked)
        JSONObject missing = block(local.toURL("ffffffffffffffffffffffffffffffff"));
        List<LinkCheck.Link> l2 = LinkCheck.unreachableLinks(missing, GistConfiguration.TYPE.PROCESSING, remote);
        assertEquals(1, l2.size());
        assertNull(l2.get(0).getLinkedConfiguration());
    }

    @Test
    public void modelLinks() throws IOException {
        LocalRepository local = new LocalRepository(tmp.newFolder("local"));
        RemoteRepository remote = new RemoteRepository(tmp.newFolder("remote"));
        // local library: model file (weights) and the DL model that uses it
        File weights = tmp.newFile("weights.bin");
        byte[] data = new byte[3000];
        new Random(1).nextBytes(data);
        java.nio.file.Files.write(weights.toPath(), data);
        String fileId = LargeFileGist.storeFile(local, weights, true, "weights", "dl_model", false, null, null).key;
        JSONObject dl = new JSONObject();
        dl.put("url", local.toURL(fileId));
        String dlId = local.create("my model", true, files("dlmodel_Segmentation_DiSTNet2D.json", dl.toJSONString())).getID();
        // configuration with a DL model parameter linked to the local model file, and a GitHub model (reachable)
        JSONObject content = new JSONObject();
        JSONObject param = new JSONObject();
        param.put("file", new JSONArray());
        param.put("id", local.toURL(fileId));
        JSONObject githubParam = new JSONObject();
        githubParam.put("file", new JSONArray());
        githubParam.put("id", "0123456789abcdef0123456789abcdef");
        JSONObject proc = new JSONObject();
        proc.put("model", param);
        proc.put("model2", githubParam);
        content.put("processing", proc);
        List<LinkCheck.Link> links = LinkCheck.unreachableLinks(content, GistConfiguration.TYPE.PROCESSING, remote);
        assertEquals(1, links.size());
        LinkCheck.Link l = links.get(0);
        assertEquals(LinkCheck.KIND.DL_MODEL, l.kind);
        assertEquals(dlId, l.getModel().getID());
        assertEquals("Segmentation/DiSTNet2D", l.getName());
        assertEquals(Collections.singletonList("Processing pipeline (DL model)"), l.getBlockDescriptions());
        try {
            l.unlink();
            fail("a DL model parameter cannot be unlinked");
        } catch (IllegalArgumentException expected) {}
        // copy: the DL model and its model file are copied, the parameter links the copy of the model file
        String link = LinkCheck.copy(l, remote, null);
        assertEquals(link, param.get("id"));
        String copyFileId = remote.idFromURL(link);
        assertNotNull("link to the remote repository: " + link, copyFileId);
        assertArrayEquals(data, java.nio.file.Files.readAllBytes(new LargeFileGist(remote, copyFileId).retrieveFile(tmp.newFolder("out"), false, false, null, null, null).toPath()));
        List<GistDLModel> remoteModels = GistDLModel.list(remote);
        assertEquals(1, remoteModels.size());
        assertEquals(copyFileId, remote.idFromURL(remoteModels.get(0).getModelURL()));
        assertEquals("other parameters unchanged", "0123456789abcdef0123456789abcdef", githubParam.get("id"));
        // stored again: the previous copy is found and reused
        param.put("id", local.toURL(fileId));
        LinkCheck.Link l2 = LinkCheck.unreachableLinks(content, GistConfiguration.TYPE.PROCESSING, remote).get(0);
        assertEquals(copyFileId, l2.getPreviousCopyId());
        int n = remote.list().size();
        assertEquals(link, LinkCheck.copy(l2, remote, null));
        assertEquals("no duplicate", n, remote.list().size());
    }
}
