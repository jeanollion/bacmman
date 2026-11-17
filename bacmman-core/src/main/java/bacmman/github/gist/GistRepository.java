package bacmman.github.gist;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Storage backend for configurations, DL models and (chunked) large files.
 * An item is equivalent to a GitHub gist: an id, a description, a visibility flag and a set of named text files (flat, no sub-directories).
 * <ul>
 *     <li>{@link TYPE#GITHUB}: GitHub gists ({@link GitHubRepository})</li>
 *     <li>{@link TYPE#GITLAB}: GitLab personal snippets ({@link GitLabRepository})</li>
 *     <li>{@link TYPE#LOCAL}: a folder in the file system, one sub-folder per item ({@link LocalRepository})</li>
 * </ul>
 * Use {@link RepositorySync} to synchronize one repository from another.
 */
public interface GistRepository {
    enum TYPE {GITHUB, GITLAB, LOCAL}

    TYPE getType();

    /**
     * @return a string that identifies this repository (type, server, account or folder). Used to store synchronization state
     */
    String getKey();

    /**
     * @return account (user name) associated with this repository, may be null
     */
    String getAccount();

    /**
     * @return a repository identical to this one, with another authentication. For a local repository, returns this
     */
    GistRepository withAuth(UserAuth auth);

    UserAuth getAuth();

    /**
     * @return whether create / update / delete operations are possible (e.g. authentication is set)
     */
    boolean isWritable();

    /**
     * Checks that the repository can be reached with its authentication (e.g. valid token)
     * @throws IOException if the repository cannot be reached or the authentication is refused
     */
    default void checkConnection() throws IOException {
        list();
    }

    /**
     * Lists all items of the repository (of the account for remote repositories). File content may not be included in the returned items.
     */
    List<GistItem> list() throws IOException;

    /**
     * @param id item id or item URL
     */
    GistItem get(String id) throws IOException;

    /**
     * @return whether an item with this id exists. Throws an exception if existence cannot be determined (e.g. network error)
     */
    default boolean exists(String id) throws IOException {
        try {
            return get(id) != null;
        } catch (IOException e) {
            if (JSONQuery.isNotFound(e)) return false;
            throw e;
        }
    }

    /**
     * Reads the content of a file of an item. Called by {@link GistItem.FileEntry#getContent()} when content was not included in the item.
     */
    String readFile(GistItem item, GistItem.FileEntry file) throws IOException;

    /**
     * Creates a new item.
     * @param files file name -> content. content must be non-empty for remote repositories.
     * @param preferredId id to use if supported by the repository and not already used (only local repositories support it: this allows mirrors to keep ids of the source repository). may be null
     * @return the created item
     */
    GistItem create(String description, boolean visible, Map<String, String> files, String preferredId) throws IOException;

    default GistItem create(String description, boolean visible, Map<String, String> files) throws IOException {
        return create(description, visible, files, null);
    }

    /**
     * Updates an existing item
     * @param description new description, null to keep the current one
     * @param visible new visibility, null to keep the current one (GitHub does not support visibility change)
     * @param files files to create or update (file name -> content). A null content deletes the file. Files not included in the map are not modified
     * @return the updated item (file content may not be included)
     */
    GistItem update(String id, String description, Boolean visible, Map<String, String> files) throws IOException;

    void delete(String id) throws IOException;

    /**
     * @return a URL that identifies the item. Used to reference an item from another item (e.g. DL model metadata pointing to a large file)
     */
    String toURL(String id);

    /**
     * @return id of the item if the url points to an item of this repository, null otherwise
     */
    String idFromURL(String url);

    /**
     * @return id if idOrURL is an url of this repository, idOrURL otherwise
     */
    default String parseId(String idOrURL) {
        if (idOrURL == null) return null;
        String id = idFromURL(idOrURL);
        return id == null ? idOrURL : id;
    }

    /**
     * @return maximal size of a chunk of a large file (in MB, before base64 encoding)
     */
    default double getMaxChunkSizeMB() {
        return 10;
    }

    /**
     * @return maximal number of chunks of a large file stored in a single item. Additional items (sub-files) are created for larger files
     */
    default int getMaxChunksPerItem() {
        return 30;
    }

    /**
     * @return delay (ms) after which the modification timestamp of a written item is final. Some servers (GitLab) update the timestamp asynchronously after the write request returns: synchronization refreshes the recorded timestamps after this delay
     */
    default long getTimestampSettleDelay() {
        return 0;
    }

    /**
     * @return whether items that are not visible can be read without authentication (e.g. secret gists of GitHub are accessible with their URL, private snippets of GitLab are not)
     */
    default boolean privateItemsAccessibleByURL() {
        return true;
    }

    // factories

    static GitHubRepository github(UserAuth auth) {
        return new GitHubRepository(auth);
    }

    static GitHubRepository github(String publicAccount) {
        return new GitHubRepository(new NoAuth(publicAccount));
    }

    /**
     * @param serverURL e.g. https://gitlab.com or the url of a self-hosted instance
     */
    static GitLabRepository gitlab(String serverURL, UserAuth auth) {
        return new GitLabRepository(serverURL, auth);
    }

    static LocalRepository local(File folder) {
        return new LocalRepository(folder);
    }

    /**
     * Identifies the repository from an item URL: GitHub gist URL, GitLab snippet URL ({server}/-/snippets/{id}) or file URL of a local repository.
     * A bare id (not an URL) is considered to be a GitHub gist id (legacy behavior)
     * @param auth authentication used for remote repositories
     * @return the repository that contains the item, or null if the url is not recognized
     */
    static GistRepository fromURL(String url, UserAuth auth) {
        if (url == null || url.isEmpty()) return null;
        if (url.startsWith(JSONQuery.GIST_BASE_URL) || !url.contains("/")) return github(auth == null ? new NoAuth() : auth);
        if (url.startsWith("file:")) return LocalRepository.fromURL(url);
        int idx = url.indexOf(GitLabRepository.SNIPPET_PATH);
        if (idx > 0 && (url.startsWith("http://") || url.startsWith("https://"))) return gitlab(url.substring(0, idx), auth == null ? new NoAuth() : auth);
        return null;
    }
}
