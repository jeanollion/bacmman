package bacmman.github.gist;

import bacmman.utils.JSONUtils;
import org.json.simple.JSONObject;
import org.json.simple.parser.ParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

import static bacmman.github.gist.JSONQuery.GIST_BASE_URL;

/**
 * Repository backed by GitHub gists. https://docs.github.com/en/rest/gists
 */
public class GitHubRepository implements GistRepository {
    public static final Logger logger = LoggerFactory.getLogger(GitHubRepository.class);
    public static String API_URL = "https://api.github.com";
    public static final String GITHUB_COM = "https://github.com";
    final UserAuth auth;

    public GitHubRepository(UserAuth auth) {
        this.auth = auth == null ? new NoAuth() : auth;
    }

    @Override
    public TYPE getType() {
        return TYPE.GITHUB;
    }

    @Override
    public String getKey() {
        return "github:" + (getAccount() == null ? "" : getAccount());
    }

    @Override
    public String getAccount() {
        return auth.getAccount();
    }

    @Override
    public UserAuth getAuth() {
        return auth;
    }

    @Override
    public GitHubRepository withAuth(UserAuth auth) {
        if (auth == this.auth) return this;
        return new GitHubRepository(auth);
    }

    @Override
    public boolean isWritable() {
        return !(auth instanceof NoAuth);
    }

    /**
     * Single request on the authenticated user (if authentication is set), on the account otherwise
     */
    @Override
    public void checkConnection() throws IOException {
        String url = auth instanceof NoAuth ? API_URL + "/users/" + getAccount() : API_URL + "/user";
        JSONQuery.fetchRaw(() -> new JSONQuery(url).method(JSONQuery.METHOD.GET).authenticate(auth), 1);
    }

    /**
     * Lists gists of the authenticated user if authentication is set, public gists of the account otherwise
     */
    @Override
    public List<GistItem> list() throws IOException {
        if (auth instanceof NoAuth) {
            if (getAccount() == null) throw new IOException("Cannot list gists: no account set");
            return list(API_URL + "/users/" + getAccount() + "/gists");
        } else return list(API_URL + "/gists");
    }

    /**
     * Lists gists from an API URL that returns a list of gists (e.g. /gists or /users/{account}/gists)
     */
    public List<GistItem> list(String url) throws IOException {
        try {
            List<JSONObject> gists = JSONQuery.fetchAllPages(p -> new JSONQuery(url, JSONQuery.REQUEST_PROPERTY_GITHUB_JSON, JSONQuery.getDefaultParameters(p)).method(JSONQuery.METHOD.GET).authenticate(auth));
            return gists.stream().map(this::parse).collect(Collectors.toList());
        } catch (ParseException e) {
            throw new IOException(e);
        }
    }

    @Override
    public GistItem get(String id) throws IOException {
        String gistId = parseId(id);
        String response = JSONQuery.fetchRaw(() -> new JSONQuery(API_URL + "/gists/" + gistId).method(JSONQuery.METHOD.GET).authenticate(auth), 3);
        return parse(response);
    }

    @Override
    public String readFile(GistItem item, GistItem.FileEntry file) throws IOException {
        if (file.location == null) throw new IOException("No URL for file: " + file.name + " of gist: " + item.id);
        return JSONQuery.fetchRaw(() -> new JSONQuery(file.location, JSONQuery.REQUEST_PROPERTY_GITHUB_BASE64, Collections.emptyList()).method(JSONQuery.METHOD.GET).authenticate(auth), JSONQuery.MAX_TRYOUTS);
    }

    @Override
    public GistItem create(String description, boolean visible, Map<String, String> files, String preferredId) throws IOException {
        JSONObject gist = new JSONObject();
        gist.put("files", filesObject(files));
        gist.put("description", description == null ? "" : description);
        gist.put("public", visible);
        String body = gist.toJSONString();
        // no retry: a retry could create a duplicated gist
        String res = new JSONQuery(API_URL + "/gists").method(JSONQuery.METHOD.POST).authenticate(auth).setBody(body).fetchRaw();
        return parse(res);
    }

    @Override
    public GistItem update(String id, String description, Boolean visible, Map<String, String> files) throws IOException {
        String gistId = parseId(id);
        JSONObject gist = new JSONObject();
        if (files != null && !files.isEmpty()) gist.put("files", filesObject(files));
        if (description != null) gist.put("description", description);
        // GitHub does not support changing visibility of a gist
        String body = gist.toJSONString();
        String res = JSONQuery.fetchRaw(() -> new JSONQuery(API_URL + "/gists/" + gistId, JSONQuery.REQUEST_PROPERTY_GITHUB_BASE64).method(JSONQuery.METHOD.PATCH).authenticate(auth).setBody(body), JSONQuery.MAX_TRYOUTS); // PATCH is idempotent
        return parse(res);
    }

    @Override
    public void delete(String id) throws IOException {
        String gistId = parseId(id);
        JSONQuery.fetchRaw(() -> new JSONQuery(API_URL + "/gists/" + gistId).method(JSONQuery.METHOD.DELETE).authenticate(auth), 3);
    }

    @Override
    public String toURL(String id) {
        return GIST_BASE_URL + id;
    }

    @Override
    public String idFromURL(String url) {
        if (url == null || !url.startsWith(GIST_BASE_URL)) return null;
        String id = url.substring(GIST_BASE_URL.length());
        int anchor = id.indexOf('#');
        if (anchor >= 0) id = id.substring(0, anchor);
        while (id.endsWith("/")) id = id.substring(0, id.length() - 1);
        int lastSlash = id.lastIndexOf('/'); // https://gist.github.com/{user}/{id}
        if (lastSlash >= 0) id = id.substring(lastSlash + 1);
        return id.isEmpty() ? null : id;
    }

    protected static JSONObject filesObject(Map<String, String> files) {
        JSONObject filesO = new JSONObject();
        for (Map.Entry<String, String> e : files.entrySet()) {
            if (e.getValue() == null) filesO.put(e.getKey(), null); // deletes the file
            else {
                JSONObject f = new JSONObject();
                f.put("content", e.getValue());
                filesO.put(e.getKey(), f);
            }
        }
        return filesO;
    }

    protected GistItem parse(String response) throws IOException {
        try {
            return parse(JSONUtils.parse(response));
        } catch (ParseException e) {
            logger.error("Error parsing gist: {} response: {}", e, response);
            throw new IOException(e);
        }
    }

    protected GistItem parse(JSONObject gist) {
        List<GistItem.FileEntry> entries = new ArrayList<>();
        Object files = gist.get("files");
        if (files instanceof JSONObject) {
            for (Object o : ((JSONObject) files).values()) {
                if (!(o instanceof JSONObject)) continue;
                JSONObject f = (JSONObject) o;
                String name = (String) f.get("filename");
                if (name == null) continue;
                long size = f.get("size") instanceof Number ? ((Number) f.get("size")).longValue() : -1;
                boolean truncated = Boolean.TRUE.equals(f.get("truncated"));
                String content = (!truncated && f.containsKey("content")) ? (String) f.get("content") : null;
                entries.add(new GistItem.FileEntry(name, size, (String) f.get("raw_url"), content));
            }
        }
        Object visible = gist.get("public");
        return new GistItem(this, (String) gist.get("id"), (String) gist.get("description"), visible == null || Boolean.TRUE.equals(visible), (String) gist.get("updated_at"), entries);
    }

    @Override
    public String toString() {
        return getKey();
    }
}
