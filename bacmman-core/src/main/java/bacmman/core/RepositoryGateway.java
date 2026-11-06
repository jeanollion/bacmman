package bacmman.core;

import bacmman.github.gist.*;
import bacmman.ui.logger.ProgressDisplay;
import bacmman.utils.Pair;

import java.io.File;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Consumer;

import static bacmman.github.gist.JSONQuery.logger;

/**
 * Credentials of the repositories (GitHub, GitLab, local) used during the session, and access to items by id or url.
 * Passwords (used to decrypt stored tokens) are kept in memory only. They are recorded per repository type, server and user name.
 * Methods without repository type concern GitHub (legacy).
 */
public class RepositoryGateway {
    public static final String GITHUB_SERVER = "https://github.com";
    final protected Map<String, char[]> passwords = new HashMap<>(); // GitHub: username -> password
    final protected Map<String, char[]> serverPasswords = new HashMap<>(); // "type|server|username" -> password
    final protected Map<String, String> lastUsernames = new HashMap<>(); // "type|server" -> last user name
    protected char[] password;
    protected String username;
    ProgressDisplay bacmmanLogger;
    private BiFunction<RepositoryGateway, String, Pair<String, char[]>> promptCredientialFunction;

    public RepositoryGateway setLogger(ProgressDisplay logger) {
        bacmmanLogger = logger;
        return this;
    }

    /**
     * Downloads a large file (e.g. a model file) and unzips it if needed
     * @param idOrURL GitHub gist id, or url of an item of any repository
     */
    public File downloadFile(String idOrURL, File destFile) {
        try {
            GistRepository repo = getRepository(idOrURL);
            if (repo == null) throw new IOException("Unknown repository for: " + idOrURL);
            LargeFileGist lf = new LargeFileGist(repo, repo.parseId(idOrURL));
            return lf.retrieveFile(destFile, false, true, null, null, bacmmanLogger);
        }  catch (IOException ex) {
            if (bacmmanLogger!=null) bacmmanLogger.setMessage("Error trying to download model: "+ex.getMessage());
            logger.debug("error trying to download model", ex);
            return null;
        }
    }

    public void clear() {
        passwords.clear();
        serverPasswords.clear();
        lastUsernames.clear();
    }

    // repositories of any type

    static String normalizeServer(GistRepository.TYPE type, String server) {
        if (GistRepository.TYPE.GITHUB.equals(type)) return GitHubRepository.GITHUB_COM;
        return GitLabRepository.normalizeServerURL(server); // gitlab.com if not set, any self-hosted server otherwise
    }

    static String key(GistRepository.TYPE type, String server) {
        return type + "|" + normalizeServer(type, server);
    }

    /**
     * Records the credentials used to connect to a repository during this session (password kept in memory only)
     * @param server server of GitLab repositories, ignored for GitHub
     */
    public void setCredentials(GistRepository.TYPE type, String server, String username, char[] password) {
        if (GistRepository.TYPE.LOCAL.equals(type) || username == null || username.isEmpty()) return;
        if (GistRepository.TYPE.GITHUB.equals(type)) {
            setCredentials(username, password);
            return;
        }
        lastUsernames.put(key(type, server), username);
        if (password != null && password.length > 0) serverPasswords.put(key(type, server) + "|" + username, password);
    }

    /**
     * @return password recorded during this session for an account, null if none
     */
    public char[] getPassword(GistRepository.TYPE type, String server, String username) {
        if (GistRepository.TYPE.GITHUB.equals(type)) return getPassword(username);
        return serverPasswords.get(key(type, server) + "|" + username);
    }

    /**
     * @return last user name used during this session for this type and server, null if none
     */
    public String getUsername(GistRepository.TYPE type, String server) {
        if (GistRepository.TYPE.GITHUB.equals(type)) return getUsername();
        return lastUsernames.get(key(type, server));
    }

    /**
     * @return authentication of the last account used during this session for this type and server: a token if a password was recorded, NoAuth otherwise
     */
    public UserAuth getAuthentication(GistRepository.TYPE type, String server) {
        switch (type) {
            case LOCAL:
                return new NoAuth();
            case GITHUB:
                return getAuthentication(false);
            case GITLAB:
            default: {
                String u = getUsername(type, server);
                if (u == null) return new NoAuth();
                char[] p = getPassword(type, server, u);
                if (p == null || p.length == 0) return new NoAuth(u);
                try {
                    return new TokenAuth(u, p, type, server);
                } catch (GeneralSecurityException | RuntimeException e) {
                    logger.debug("Could not load {} token of {}", type, u, e);
                    return new NoAuth(u);
                }
            }
        }
    }

    /**
     * @param idOrURL GitHub gist id (bare id: legacy), or url of an item of any repository (GitHub gist, GitLab snippet, local item)
     * @return repository that contains the item, with the credentials recorded during this session for this repository. null if the url is not recognized
     */
    public GistRepository getRepository(String idOrURL) {
        GistRepository repo = GistRepository.fromURL(idOrURL, null);
        if (repo == null) return null;
        switch (repo.getType()) {
            case LOCAL:
                return repo;
            case GITLAB:
                return repo.withAuth(getAuthentication(GistRepository.TYPE.GITLAB, ((GitLabRepository) repo).getServerURL()));
            case GITHUB:
            default:
                return repo.withAuth(getAuthentication(GistRepository.TYPE.GITHUB, null));
        }
    }

    /**
     * @return repository of the given type, with the credentials recorded during this session (last account used)
     * @param serverOrFolder server for GitLab repositories, folder for local repositories, ignored for GitHub
     */
    public GistRepository getRepository(GistRepository.TYPE type, String serverOrFolder) {
        switch (type) {
            case LOCAL:
                return serverOrFolder == null ? null : GistRepository.local(new File(serverOrFolder));
            case GITLAB:
                return GistRepository.gitlab(serverOrFolder, getAuthentication(type, serverOrFolder));
            case GITHUB:
            default:
                return GistRepository.github(getAuthentication(false));
        }
    }

    // GitHub (legacy)

    public char[] getPassword(String username) {
        if (passwords.containsKey(username)) return passwords.get(username);
        return null;
    }
    public String getUsername() {
        return username;
    }

    /**
     * Records GitHub credentials
     */
    public void setCredentials(String username, char[] password) {
        this.username=username;
        this.password = password;
        if (username!=null && !username.isEmpty()) passwords.put(username, password);
    }
    public UserAuth getAuthentication(boolean promptIfNecessary) {
        return getAuthentication(promptIfNecessary, null);
    }
    public UserAuth getAuthentication(boolean promptIfNecessary, String message) {
        if (password==null || password.length == 0 || username==null || username.isEmpty()) {
            if (promptIfNecessary && promptCredientialFunction!=null) {
                Pair<String, char[]> cred = promptCredientialFunction.apply(this, message);
                if (cred != null) {
                    username = cred.key;
                    password = cred.value;
                    if (password!=null && password.length>0 && username!=null && !username.isEmpty()) return getAuthentication(false);
                }
            }
            return new NoAuth(username);
        }
        else {
            try {
                TokenAuth auth = new TokenAuth(username, password);
                passwords.put(username, password);
                return auth;
            } catch (IllegalArgumentException e) {
                if (bacmmanLogger!=null && !promptIfNecessary) bacmmanLogger.setMessage("No token associated with this username found");
                if (promptIfNecessary) {
                    Pair<String, char[]> cred = promptCredientialFunction.apply(this, message);
                    if (cred != null) {
                        username = cred.key;
                        password = cred.value;
                        if (password!=null && password.length>0 && username!=null && !username.isEmpty()) return getAuthentication(false);
                    }
                }
                return new NoAuth(username);
            } catch (Throwable t) {
                if (bacmmanLogger!=null && !promptIfNecessary) bacmmanLogger.setMessage("Token could not be retrieved. Wrong password ?");
                if (promptIfNecessary) {
                    Pair<String, char[]> cred = promptCredientialFunction.apply(this, message);
                    if (cred != null) {
                        username = cred.key;
                        password = cred.value;
                        if (password!=null && password.length>0 && username!=null && !username.isEmpty()) return getAuthentication(false);
                    }
                }
                return new NoAuth(username);
            }
        }
    }
    public void setPromptGithubCredientials(BiFunction<RepositoryGateway, String, Pair<String, char[]>> prompt) {
        promptCredientialFunction = prompt;
    }
    public UserAuth promptCredentials(Consumer<String> error, String message) {
        Pair<String, char[]> cred = promptCredientialFunction.apply(this, message);
        if (cred == null || cred.key.isEmpty() || cred.value.length==0) return new NoAuth(cred==null? null : cred.key);
        else {
            try {
                TokenAuth auth = new TokenAuth(cred.key, cred.value);
                passwords.put(cred.key, cred.value);
                return auth;
            } catch (GeneralSecurityException e) {
                if (error!=null) error.accept("Could not retried Token. Has Token been stored ?");
                logger.info("Authentication error", e);
                return new NoAuth(cred.key);
            }
        }
    }
}
