package bacmman.core;

import bacmman.github.gist.*;
import bacmman.ui.logger.ProgressDisplay;

import java.io.File;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.HashMap;
import java.util.Map;

import static bacmman.github.gist.JSONQuery.logger;

/**
 * Credentials of the repositories (GitHub, GitLab, local) used during the session, and access to items by id or url.
 * Passwords (used to decrypt stored tokens) are kept in memory only. They are recorded per repository type, server and user name.
 * Methods without repository type concern GitHub (default type).
 */
public class RepositoryGateway {
    public static final GistRepository.TYPE DEFAULT_TYPE = GistRepository.TYPE.GITHUB;
    final protected Map<String, char[]> passwords = new HashMap<>(); // "type|server|username" -> password
    final protected Map<String, String> lastUsernames = new HashMap<>(); // "type|server" -> last user name
    ProgressDisplay bacmmanLogger;
    private RepositoryPrompt repositoryPrompt;

    /**
     * Prompts the user for a repository of any type (GitHub, GitLab, local folder) with its credentials
     */
    @FunctionalInterface
    public interface RepositoryPrompt {
        /**
         * @param writable whether the repository must be writable
         * @return the selected repository, null if canceled
         */
        GistRepository prompt(RepositoryGateway gateway, String message, boolean writable);
    }

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
        lastUsernames.clear();
    }

    // credentials of repositories of any type

    static GistRepository.TYPE type(GistRepository.TYPE type) {
        return type == null ? DEFAULT_TYPE : type;
    }

    static String normalizeServer(GistRepository.TYPE type, String server) {
        if (GistRepository.TYPE.GITHUB.equals(type(type))) return GitHubRepository.GITHUB_COM;
        return GitLabRepository.normalizeServerURL(server); // gitlab.com if not set, any self-hosted server otherwise
    }

    static String key(GistRepository.TYPE type, String server) {
        return type(type) + "|" + normalizeServer(type, server);
    }

    /**
     * Records the credentials used to connect to a repository during this session (password kept in memory only)
     * @param type null: GitHub
     * @param server server of GitLab repositories, ignored for GitHub
     */
    public void setCredentials(GistRepository.TYPE type, String server, String username, char[] password) {
        if (GistRepository.TYPE.LOCAL.equals(type) || username == null || username.isEmpty()) return;
        lastUsernames.put(key(type, server), username);
        if (password != null && password.length > 0) passwords.put(key(type, server) + "|" + username, password);
    }

    /**
     * @return password recorded during this session for an account, null if none
     */
    public char[] getPassword(GistRepository.TYPE type, String server, String username) {
        if (username == null) return null;
        return passwords.get(key(type, server) + "|" + username);
    }

    /**
     * @return last user name used during this session for this type and server, null if none
     */
    public String getUsername(GistRepository.TYPE type, String server) {
        if (GistRepository.TYPE.LOCAL.equals(type)) return null;
        return lastUsernames.get(key(type, server));
    }

    /**
     * @return authentication of the last account used during this session for this type and server: a token if a password was recorded, NoAuth otherwise
     */
    public UserAuth getAuthentication(GistRepository.TYPE type, String server) {
        type = type(type);
        if (GistRepository.TYPE.LOCAL.equals(type)) return new NoAuth();
        String u = getUsername(type, server);
        if (u == null || u.isEmpty()) return new NoAuth();
        char[] p = getPassword(type, server, u);
        if (p == null || p.length == 0) return new NoAuth(u);
        try {
            return new TokenAuth(u, p, type, server);
        } catch (GeneralSecurityException | RuntimeException e) {
            if (bacmmanLogger != null) bacmmanLogger.setMessage("Token of " + u + " could not be retrieved: no token stored or wrong password");
            logger.debug("Could not load {} token of {}", type, u, e);
            return new NoAuth(u);
        }
    }

    // default type (GitHub)

    public void setCredentials(String username, char[] password) {
        setCredentials(DEFAULT_TYPE, null, username, password);
    }

    public char[] getPassword(String username) {
        return getPassword(DEFAULT_TYPE, null, username);
    }

    public String getUsername() {
        return getUsername(DEFAULT_TYPE, null);
    }

    public UserAuth getAuthentication() {
        return getAuthentication(DEFAULT_TYPE, null);
    }

    // repositories

    /**
     * @param idOrURL GitHub gist id (bare id), or url of an item of any repository (GitHub gist, GitLab snippet, local item)
     * @return repository that contains the item, with the credentials recorded during this session for this repository. null if the url is not recognized
     */
    public GistRepository getRepository(String idOrURL) {
        GistRepository repo = GistRepository.fromURL(idOrURL, null);
        if (repo == null || GistRepository.TYPE.LOCAL.equals(repo.getType())) return repo;
        String server = repo instanceof GitLabRepository ? ((GitLabRepository) repo).getServerURL() : null;
        return repo.withAuth(getAuthentication(repo.getType(), server));
    }

    /**
     * @return repository of the given type, with the credentials recorded during this session (last account used)
     * @param type null: GitHub
     * @param serverOrFolder server for GitLab repositories, folder for local repositories, ignored for GitHub
     */
    public GistRepository getRepository(GistRepository.TYPE type, String serverOrFolder) {
        switch (type(type)) {
            case LOCAL:
                return serverOrFolder == null ? null : GistRepository.local(new File(serverOrFolder));
            case GITLAB:
                return GistRepository.gitlab(serverOrFolder, getAuthentication(type, serverOrFolder));
            case GITHUB:
            default:
                return GistRepository.github(getAuthentication(type, null));
        }
    }

    public RepositoryGateway setRepositoryPrompt(RepositoryPrompt prompt) {
        this.repositoryPrompt = prompt;
        return this;
    }

    /**
     * Prompts the user for a repository of any type (GitHub, GitLab, local folder), with its credentials
     * @param writable whether the repository must be writable (authentication with a token for remote repositories)
     * @return the selected repository. null if canceled, if no prompt is set (e.g. headless mode) or if the repository is not writable while required
     */
    public GistRepository promptRepository(String message, boolean writable) {
        if (repositoryPrompt == null) return null;
        return repositoryPrompt.prompt(this, message, writable);
    }
}
