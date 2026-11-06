package bacmman.github.gist;

import java.io.IOException;
import java.util.*;

/**
 * Repository-independent view of a stored item: a GitHub gist, a GitLab snippet or a folder of a local repository.
 * An item has an id, a description, a visibility flag and a set of named text files.
 * File content can be included in the item (e.g. returned inline by GitHub) or fetched lazily from the repository.
 */
public class GistItem {
    final GistRepository repository;
    final String id;
    final String description;
    final boolean visible;
    final String updatedAt;
    final Map<String, FileEntry> files;

    public GistItem(GistRepository repository, String id, String description, boolean visible, String updatedAt, Collection<FileEntry> files) {
        this.repository = repository;
        this.id = id;
        this.description = description;
        this.visible = visible;
        this.updatedAt = updatedAt;
        this.files = new TreeMap<>();
        if (files != null) for (FileEntry f : files) {
            f.item = this;
            this.files.put(f.name, f);
        }
    }

    public GistRepository getRepository() {
        return repository;
    }

    public String getID() {
        return id;
    }

    public String getDescription() {
        return description;
    }

    public boolean isVisible() {
        return visible;
    }

    /**
     * @return modification timestamp as returned by the repository (used for incremental synchronization). May be null.
     */
    public String getUpdatedAt() {
        return updatedAt;
    }

    /**
     * @return file names, sorted
     */
    public Set<String> getFileNames() {
        return Collections.unmodifiableSet(files.keySet());
    }

    public boolean hasFile(String name) {
        return files.containsKey(name);
    }

    public FileEntry getFile(String name) {
        return files.get(name);
    }

    public Collection<FileEntry> getFiles() {
        return Collections.unmodifiableCollection(files.values());
    }

    /**
     * @return content of the file, retrieved from the repository if not included in the item. null if no file with this name
     */
    public String getContent(String fileName) throws IOException {
        FileEntry f = files.get(fileName);
        if (f == null) return null;
        return f.getContent();
    }

    @Override
    public String toString() {
        return repository + "/" + id + " files=" + files.keySet();
    }

    public static class FileEntry {
        GistItem item;
        final String name;
        final long size; // -1 if unknown
        final String location; // backend-specific (url, path...)
        String content; // may be null: retrieved lazily

        public FileEntry(String name, long size, String location, String content) {
            this.name = name;
            this.size = size;
            this.location = location;
            this.content = content;
        }

        public String getName() {
            return name;
        }

        public GistItem getItem() {
            return item;
        }

        /**
         * @return size in bytes, -1 if unknown
         */
        public long getSize() {
            return size;
        }

        public String getLocation() {
            return location;
        }

        public boolean isContentLoaded() {
            return content != null;
        }

        public synchronized String getContent() throws IOException {
            if (content == null) content = item.repository.readFile(item, this);
            return content;
        }

        /**
         * Retrieves content without keeping it in memory (useful for large chunks)
         */
        public String readContent() throws IOException {
            if (content != null) return content;
            return item.repository.readFile(item, this);
        }
    }
}
