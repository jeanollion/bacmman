package bacmman.github.gist;

import bacmman.utils.JSONUtils;
import bacmman.utils.Pair;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.ParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Repository backed by GitLab personal snippets (multi-file snippets). https://docs.gitlab.com/api/snippets/
 * Works with gitlab.com or any self-hosted instance.
 * Listing uses the GraphQL API ({@code snippets(authorId, type: personal)}), as the REST API cannot list the snippets of a given user:
 * <ul>
 *     <li>with an account and no token: public snippets of the account</li>
 *     <li>with a token: snippets of the account (or of the token owner if no account is set) visible with the token, including private ones</li>
 * </ul>
 * A snippet requires a title: the first file name is used. The item description is stored in the snippet description.
 * Timestamps are truncated to the second, as the REST and GraphQL APIs return them with different precisions.
 */
public class GitLabRepository implements GistRepository {
    public static final Logger logger = LoggerFactory.getLogger(GitLabRepository.class);
    public static final String SNIPPET_PATH = "/-/snippets/";
    public static final String GITLAB_COM = "https://gitlab.com";
    final String serverURL, apiURL, graphQLURL;
    final UserAuth auth;
    public static int GRAPHQL_PAGE_SIZE = 100;
    private volatile String authorGID; // GraphQL global id of the account, resolved lazily
    private volatile String tokenOwner; // username of the owner of the token, when no account is set

    /**
     * @param serverURL url of the GitLab server: gitlab.com or a self-hosted instance (possibly with a path, e.g. https://example.org/gitlab). null or empty: gitlab.com
     */
    public GitLabRepository(String serverURL, UserAuth auth) {
        this.serverURL = normalizeServerURL(serverURL);
        this.apiURL = serverURL + "/api/v4";
        this.graphQLURL = serverURL + "/api/graphql";
        this.auth = auth == null ? new NoAuth() : auth;
    }

    public String getServerURL() {
        return serverURL;
    }

    @Override
    public TYPE getType() {
        return TYPE.GITLAB;
    }

    /**
     * @return "gitlab:{server}:{account}". If no account is set but a token is, the account is the owner of the token (retrieved from the server), so that the key does not depend on how the repository was created
     */
    @Override
    public String getKey() {
        return "gitlab:" + serverURL + ":" + (getAccount() == null ? "" : getAccount());
    }

    @Override
    public String getAccount() {
        String account = auth.getAccount();
        if (account != null && !account.isEmpty()) return account;
        if (auth instanceof NoAuth) return null;
        if (tokenOwner == null) {
            try {
                getAuthorGID();
            } catch (IOException e) {
                logger.debug("Could not retrieve owner of token", e);
            }
        }
        return tokenOwner;
    }

    @Override
    public UserAuth getAuth() {
        return auth;
    }

    @Override
    public GitLabRepository withAuth(UserAuth auth) {
        if (auth == this.auth) return this;
        return new GitLabRepository(serverURL, auth);
    }

    @Override
    public boolean isWritable() {
        return !(auth instanceof NoAuth);
    }

    @Override
    public double getMaxChunkSizeMB() {
        return 10;
    }

    @Override
    public int getMaxChunksPerItem() {
        return 3; // GitLab limits the size of snippets (50MB by default on gitlab.com)
    }

    @Override
    public long getTimestampSettleDelay() {
        return 3000; // updated_at is modified again about one second after a write
    }

    @Override
    public boolean privateItemsAccessibleByURL() {
        return false;
    }

    protected JSONQuery query(String url, List<Pair<String, String>> params, JSONQuery.METHOD method) {
        return new JSONQuery(url, "application/json", params).method(method).authenticate(auth, TYPE.GITLAB);
    }

    protected JSONQuery query(String url, JSONQuery.METHOD method) {
        return query(url, Collections.emptyList(), method);
    }

    protected JSONQuery bodyQuery(String url, JSONQuery.METHOD method, JSONObject body) {
        JSONQuery q = new JSONQuery(url, "application/json", Collections.emptyList()).method(method).header("Content-Type", "application/json; charset=UTF-8").authenticate(auth, TYPE.GITLAB);
        return q.setBody(body.toJSONString());
    }

    static final String LIST_QUERY = "query($author: UserID!, $after: String) { snippets(authorId: $author, type: personal, first: %d, after: $after) { "
            + "pageInfo { hasNextPage endCursor } nodes { id description visibilityLevel updatedAt blobs { nodes { path rawPath size } } } } }";

    /**
     * Lists personal snippets of the account: public ones without token, all snippets visible with the token otherwise.
     * If no account is set, lists snippets of the token owner.
     */
    @Override
    public List<GistItem> list() throws IOException {
        String author = getAuthorGID();
        String query = String.format(LIST_QUERY, GRAPHQL_PAGE_SIZE);
        List<GistItem> res = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        String after = null;
        while (true) {
            JSONObject variables = new JSONObject();
            variables.put("author", author);
            variables.put("after", after);
            JSONObject snippets = (JSONObject) graphQL(query, variables).get("snippets");
            if (snippets == null) break;
            for (Object n : (JSONArray) snippets.get("nodes")) {
                GistItem item = parseGraphQL((JSONObject) n);
                if (ids.add(item.getID())) res.add(item);
            }
            JSONObject pageInfo = (JSONObject) snippets.get("pageInfo");
            if (pageInfo == null || !Boolean.TRUE.equals(pageInfo.get("hasNextPage")) || pageInfo.get("endCursor") == null) break;
            after = (String) pageInfo.get("endCursor");
        }
        return res;
    }

    /**
     * @return GraphQL global id of the account (resolved from its user name), or of the token owner if no account is set
     */
    protected String getAuthorGID() throws IOException {
        if (authorGID != null) return authorGID;
        String account = auth.getAccount();
        JSONObject user;
        try {
            if (account != null && !account.isEmpty()) {
                List<JSONObject> users = JSONQuery.parseJSON(JSONQuery.fetchRaw(() -> query(apiURL + "/users", Collections.singletonList(new Pair<>("username", encode(account))), JSONQuery.METHOD.GET), 3));
                user = users.isEmpty() ? null : users.get(0);
                if (user == null || user.get("id") == null) throw new IOException("GitLab user not found: " + account + " on " + serverURL);
            } else if (!(auth instanceof NoAuth)) {
                user = JSONUtils.parse(JSONQuery.fetchRaw(() -> query(apiURL + "/user", JSONQuery.METHOD.GET), 3));
                tokenOwner = (String) user.get("username");
            } else throw new IOException("GitLab: an account or a token is required to list snippets");
        } catch (ParseException e) {
            throw new IOException(e);
        }
        authorGID = "gid://gitlab/User/" + ((Number) user.get("id")).longValue();
        return authorGID;
    }

    /**
     * Executes a GraphQL query
     * @return the data object of the response
     */
    protected JSONObject graphQL(String query, JSONObject variables) throws IOException {
        JSONObject body = new JSONObject();
        body.put("query", query);
        if (variables != null) body.put("variables", variables);
        String res = JSONQuery.fetchRaw(() -> bodyQuery(graphQLURL, JSONQuery.METHOD.POST, body), 3); // read-only query: retry is safe
        JSONObject json;
        try {
            json = JSONUtils.parse(res);
        } catch (ParseException e) {
            throw new IOException("Invalid GraphQL response: " + res, e);
        }
        Object errors = json.get("errors");
        if (errors instanceof JSONArray && !((JSONArray) errors).isEmpty()) throw new IOException("GitLab GraphQL error: " + errors);
        JSONObject data = (JSONObject) json.get("data");
        if (data == null) throw new IOException("GitLab GraphQL: no data in response: " + res);
        return data;
    }

    protected GistItem parseGraphQL(JSONObject node) {
        String gid = (String) node.get("id"); // gid://gitlab/PersonalSnippet/{id}
        String id = gid.substring(gid.lastIndexOf('/') + 1);
        List<GistItem.FileEntry> entries = new ArrayList<>();
        JSONObject blobs = (JSONObject) node.get("blobs");
        if (blobs != null && blobs.get("nodes") instanceof JSONArray) {
            for (Object o : (JSONArray) blobs.get("nodes")) {
                JSONObject b = (JSONObject) o;
                String path = (String) b.get("path");
                if (path == null) continue;
                long size = b.get("size") instanceof Number ? ((Number) b.get("size")).longValue() : -1;
                entries.add(new GistItem.FileEntry(path, size, (String) b.get("rawPath"), null));
            }
        }
        boolean visible = "public".equals(node.get("visibilityLevel"));
        return new GistItem(this, id, (String) node.get("description"), visible, normalizeTimestamp((String) node.get("updatedAt")), entries);
    }

    /**
     * REST and GraphQL APIs return timestamps with different precisions: truncate to the second so that they can be compared
     */
    protected static String normalizeTimestamp(String timestamp) {
        if (timestamp == null) return null;
        try {
            return OffsetDateTime.parse(timestamp).toInstant().truncatedTo(ChronoUnit.SECONDS).toString();
        } catch (DateTimeParseException e) {
            return timestamp;
        }
    }

    /**
     * @return URL of the page of the GitLab server where a personal access token can be created, with prefilled name, description and scope (api: needed to create and modify snippets)
     */
    /**
     * @return normalized url of a GitLab server, so that it can be compared and used as a key: trimmed, https scheme added if missing, lower case scheme and host, without trailing slash. null or empty: gitlab.com
     */
    public static String normalizeServerURL(String serverURL) {
        if (serverURL == null || serverURL.trim().isEmpty()) return GITLAB_COM;
        String s = serverURL.trim();
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        int schemeEnd = s.indexOf("://");
        if (schemeEnd < 0) {
            s = "https://" + s;
            schemeEnd = 5;
        }
        int pathStart = s.indexOf('/', schemeEnd + 3);
        String base = pathStart < 0 ? s : s.substring(0, pathStart);
        String path = pathStart < 0 ? "" : s.substring(pathStart);
        return base.toLowerCase() + path;
    }

    public static String getTokenCreationURL(String serverURL) {
        serverURL = normalizeServerURL(serverURL);
        return serverURL + "/-/user_settings/personal_access_tokens?name=BACMMAN&description=BACMMAN+configuration+and+model+library&scopes=api";
    }

    public static class TokenInfo {
        public final String username, expiresAt;
        public final List<String> scopes;
        TokenInfo(String username, List<String> scopes, String expiresAt) {
            this.username = username;
            this.scopes = scopes;
            this.expiresAt = expiresAt;
        }
        public boolean canWrite() {
            return scopes == null || scopes.contains("api");
        }
    }

    /**
     * Checks a personal access token on the server
     * @return owner of the token, its scopes and expiration date (scopes and expiration are null if they could not be retrieved)
     * @throws IOException if the token is invalid (e.g. 401)
     */
    public static TokenInfo checkToken(String serverURL, String token) throws IOException {
        GitLabRepository repo = new GitLabRepository(serverURL, new TokenAuth(token));
        String username;
        try {
            JSONObject user = JSONUtils.parse(JSONQuery.fetchRaw(() -> repo.query(repo.apiURL + "/user", JSONQuery.METHOD.GET), 1));
            username = (String) user.get("username");
        } catch (ParseException e) {
            throw new IOException(e);
        }
        List<String> scopes = null;
        String expiresAt = null;
        try {
            JSONObject self = JSONUtils.parse(JSONQuery.fetchRaw(() -> repo.query(repo.apiURL + "/personal_access_tokens/self", JSONQuery.METHOD.GET), 1));
            if (self.get("scopes") instanceof JSONArray) {
                scopes = new ArrayList<>();
                for (Object o : (JSONArray) self.get("scopes")) scopes.add(String.valueOf(o));
            }
            expiresAt = (String) self.get("expires_at");
        } catch (IOException | ParseException | RuntimeException e) {
            logger.debug("Could not retrieve token scopes", e);
        }
        return new TokenInfo(username, scopes, expiresAt);
    }

    @Override
    public GistItem get(String id) throws IOException {
        String sid = parseId(id);
        String res = JSONQuery.fetchRaw(() -> query(apiURL + "/snippets/" + sid, JSONQuery.METHOD.GET), 3);
        return parse(res);
    }

    @Override
    public String readFile(GistItem item, GistItem.FileEntry file) throws IOException {
        String ref = getRef(file.location);
        String url = ref == null ? apiURL + "/snippets/" + item.id + "/raw" // legacy single-file snippet
                : apiURL + "/snippets/" + item.id + "/files/" + encode(ref) + "/" + encode(file.name) + "/raw";
        return JSONQuery.fetchRaw(() -> new JSONQuery(url, "*/*", Collections.emptyList()).method(JSONQuery.METHOD.GET).authenticate(auth, TYPE.GITLAB), JSONQuery.MAX_TRYOUTS);
    }

    /**
     * raw_url of a file of a multi-file snippet: {server}/-/snippets/{id}/raw/{ref}/{file_path}
     */
    protected static String getRef(String rawURL) {
        if (rawURL == null) return null;
        int i = rawURL.indexOf("/raw/");
        if (i < 0) return null;
        String rem = rawURL.substring(i + 5);
        int j = rem.indexOf('/');
        if (j <= 0) return null;
        return rem.substring(0, j);
    }

    protected static String encode(String s) {
        try {
            // GitLab interprets a file extension as a format: dots must be encoded too
            return URLEncoder.encode(s, "UTF-8").replace("+", "%20").replace(".", "%2E");
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public GistItem create(String description, boolean visible, Map<String, String> files, String preferredId) throws IOException {
        if (files == null || files.isEmpty()) throw new IllegalArgumentException("Cannot create a snippet without file");
        JSONObject body = new JSONObject();
        body.put("title", new TreeMap<>(files).firstKey());
        body.put("description", description == null ? "" : description);
        body.put("visibility", visible ? "public" : "private");
        JSONArray filesA = new JSONArray();
        for (Map.Entry<String, String> e : files.entrySet()) {
            if (e.getValue() == null) continue;
            JSONObject f = new JSONObject();
            f.put("file_path", e.getKey());
            f.put("content", e.getValue());
            filesA.add(f);
        }
        body.put("files", filesA);
        // no retry in general, as a retry could create a duplicated snippet. GitLab sometimes answers "Repository Error creating the snippet" (400): the snippet was not created, retry is safe
        for (int tryout = 0; ; ++tryout) {
            try {
                String res = bodyQuery(apiURL + "/snippets", JSONQuery.METHOD.POST, body).fetchRaw();
                return parse(res);
            } catch (IOException e) {
                String m = e.getMessage() == null ? "" : e.getMessage();
                if (tryout >= 3 || !m.contains("Repository Error creating the snippet")) throw e;
                logger.debug("GitLab repository error while creating snippet, retry {}", tryout + 1);
                try {
                    Thread.sleep(2000L * (tryout + 1));
                } catch (InterruptedException ie) {
                    throw e;
                }
            }
        }
    }

    @Override
    public GistItem update(String id, String description, Boolean visible, Map<String, String> files) throws IOException {
        String sid = parseId(id);
        JSONObject body = new JSONObject();
        if (description != null) body.put("description", description);
        if (visible != null) body.put("visibility", visible ? "public" : "private");
        if (files != null && !files.isEmpty()) {
            Set<String> existing = get(sid).getFileNames();
            JSONArray filesA = new JSONArray();
            for (Map.Entry<String, String> e : files.entrySet()) {
                JSONObject f = new JSONObject();
                f.put("file_path", e.getKey());
                if (e.getValue() == null) {
                    if (!existing.contains(e.getKey())) continue;
                    f.put("action", "delete");
                } else {
                    f.put("action", existing.contains(e.getKey()) ? "update" : "create");
                    f.put("content", e.getValue());
                }
                filesA.add(f);
            }
            if (!filesA.isEmpty()) body.put("files", filesA);
        }
        if (body.isEmpty()) return get(sid);
        String res = JSONQuery.fetchRaw(() -> bodyQuery(apiURL + "/snippets/" + sid, JSONQuery.METHOD.PUT, body), JSONQuery.MAX_TRYOUTS);
        return parse(res);
    }

    @Override
    public void delete(String id) throws IOException {
        String sid = parseId(id);
        JSONQuery.fetchRaw(() -> query(apiURL + "/snippets/" + sid, JSONQuery.METHOD.DELETE), 3);
    }

    @Override
    public String toURL(String id) {
        return serverURL + SNIPPET_PATH + id;
    }

    @Override
    public String idFromURL(String url) {
        if (url == null || !url.startsWith(serverURL + SNIPPET_PATH)) return null;
        String rem = url.substring(serverURL.length() + SNIPPET_PATH.length());
        int end = 0;
        while (end < rem.length() && Character.isDigit(rem.charAt(end))) ++end;
        return end == 0 ? null : rem.substring(0, end);
    }

    protected GistItem parse(String response) throws IOException {
        try {
            return parse(JSONUtils.parse(response));
        } catch (ParseException e) {
            logger.error("Error parsing snippet: {} response: {}", e, response);
            throw new IOException(e);
        }
    }

    protected GistItem parse(JSONObject snippet) {
        Object idO = snippet.get("id");
        String id = idO instanceof Number ? String.valueOf(((Number) idO).longValue()) : String.valueOf(idO);
        List<GistItem.FileEntry> entries = new ArrayList<>();
        Object files = snippet.get("files");
        if (files instanceof JSONArray) {
            for (Object o : (JSONArray) files) {
                JSONObject f = (JSONObject) o;
                String path = (String) f.get("path");
                if (path == null) path = (String) f.get("file_path");
                if (path != null) entries.add(new GistItem.FileEntry(path, -1, (String) f.get("raw_url"), null));
            }
        } else if (snippet.get("file_name") != null) { // legacy single-file snippet
            entries.add(new GistItem.FileEntry((String) snippet.get("file_name"), -1, null, null));
        }
        boolean visible = "public".equals(snippet.get("visibility"));
        return new GistItem(this, id, (String) snippet.get("description"), visible, normalizeTimestamp((String) snippet.get("updated_at")), entries);
    }

    @Override
    public String toString() {
        return getKey();
    }
}
