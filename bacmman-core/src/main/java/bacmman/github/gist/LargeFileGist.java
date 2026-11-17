package bacmman.github.gist;

import bacmman.core.DefaultWorker;
import bacmman.ui.logger.ProgressDisplay;
import bacmman.utils.*;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.ParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.function.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static bacmman.utils.ThreadRunner.sleep;
import static org.apache.commons.codec.digest.MessageDigestAlgorithms.MD5;

/**
 * A file stored in a {@link GistRepository}, split in base64-encoded chunks.
 * The main item contains a master file (metadata: file name, number of chunks, checksums...) and the first chunks.
 * When the number of chunks exceeds {@link GistRepository#getMaxChunksPerItem()}, additional items (sub-files) are created, and their ids are stored in the master file.
 * A small string can also be stored as a single file item (no master file).
 */
public class LargeFileGist {
    public static final Logger logger = LoggerFactory.getLogger(LargeFileGist.class);
    public static String BASE_URL = GitHubRepository.API_URL;
    static double MAX_CHUNK_SIZE_MB = 10;
    static int MAX_CHUNKS_PER_SUBFILE = 30;
    GistRepository repository;
    GistItem item;
    TreeMap<String, GistItem> chunkItems; // chunk name -> item that contains the chunk
    double sizeMb;
    int chunkSize, nChunks, nChunksPerSubFile;
    String fileType, fullFileName;
    List<byte[]> checksum_md5; // indexed by chunk index
    String description, id;
    boolean visible, wasZipped;
    List<String> subFileIds;
    boolean subFileChunksRetrieved;
    String stringContent; // if LFG is a string
    GistItem.FileEntry stringContentFile;

    /**
     * @param id gist id, or URL of an item of any repository (see {@link GistRepository#fromURL(String, UserAuth)}). A bare id is considered to be a GitHub gist id.
     */
    public LargeFileGist(String id, UserAuth auth) throws IOException {
        GistRepository repo = GistRepository.fromURL(id, auth);
        if (repo == null) repo = GistRepository.github(auth);
        this.repository = repo;
        setData(repo.get(repo.parseId(id)));
    }

    public LargeFileGist(GistRepository repository, String id) throws IOException {
        this.repository = repository;
        setData(repository.get(repository.parseId(id)));
    }

    public LargeFileGist(GistItem item) throws IOException {
        this.repository = item.getRepository();
        setData(item);
    }

    /**
     * @param gist JSON object returned by the GitHub gist API
     */
    public LargeFileGist(JSONObject gist, UserAuth auth) throws IOException {
        GitHubRepository repo = GistRepository.github(auth);
        this.repository = repo;
        setData(repo.parse(gist));
    }

    public GistRepository getRepository() {
        return repository;
    }

    public String getFileName() {
        if (fullFileName.endsWith(".zip")) return fullFileName.substring(0, fullFileName.length()-4);
        else return fullFileName;
    }

    public String getDescription() {
        return description;
    }

    public String getID() {
        return id;
    }

    /**
     * @return URL of this file in its repository (can be used to reference this file, see {@link GistRepository#fromURL(String, UserAuth)})
     */
    public String getURL() {
        return repository.toURL(id);
    }

    /**
     * Uses the authentication if provided, the authentication of the repository otherwise
     */
    protected GistRepository repo(UserAuth auth) {
        if (auth == null || auth instanceof NoAuth) return repository;
        return repository.withAuth(auth);
    }

    public static List<File> downloadGist(String id, String dir) throws IOException {
        Map<String, String> files = downloadGist(id);
        List<File> res = new ArrayList<>(files.size());
        for (Map.Entry<String, String> e : files.entrySet()) {
            Path path = Paths.get(dir, e.getKey());
            FileIO.TextFile file = new FileIO.TextFile(path.toString(), true, true);
            file.write(e.getValue(), false);
            file.close();
            res.add(path.toFile());
        }
        return res;
    }

    /**
     * @param id gist id or item URL
     * @return file name -> content of all files of the item
     */
    public static Map<String, String> downloadGist(String id) throws IOException {
        GistRepository repo = GistRepository.fromURL(id, new NoAuth());
        if (repo == null) repo = GistRepository.github(new NoAuth());
        GistItem item = repo.get(repo.parseId(id));
        Map<String, String> res = new HashMap<>();
        for (GistItem.FileEntry f : item.getFiles()) res.put(f.getName(), f.getContent());
        return res;
    }

    protected static boolean isMasterFile(String fileName) {
        return fileName.endsWith("master_file.json") || fileName.matches("(.*)_subfile_(\\d+).json$");
    }

    protected static boolean isChunk(String fileName) {
        return fileName.startsWith("chunk_");
    }

    protected void setData(GistItem item) throws IOException {
        this.item = item;
        description = item.getDescription();
        id = item.getID();
        visible = item.isVisible();
        chunkItems = new TreeMap<>();
        for (String f : item.getFileNames()) if (isChunk(f)) chunkItems.put(f, item);
        String master = item.getFileNames().stream().filter(LargeFileGist::isMasterFile).findFirst().orElse(null);
        stringContent = null;
        stringContentFile = null;
        checksum_md5 = null;
        if (master != null) {
            String masterFileContent = item.getContent(master);
            JSONObject masterFileJSON;
            try {
                masterFileJSON = JSONUtils.parse(masterFileContent);
            } catch (ParseException e) {
                logger.error("Error parsing masterfile Content @setData. Error: {} response: {}", e, masterFileContent);
                throw new IOException(e);
            }
            nChunks = ((Number) masterFileJSON.get("n_chunks")).intValue();
            nChunksPerSubFile = ((Number) masterFileJSON.getOrDefault("n_chunks_per_subfile", MAX_CHUNKS_PER_SUBFILE)).intValue();
            if (masterFileJSON.containsKey("size_mb")) sizeMb = ((Number) masterFileJSON.get("size_mb")).doubleValue();
            chunkSize = ((Number) masterFileJSON.getOrDefault("chunk_size", 1024*1024*9)).intValue();
            if (masterFileJSON.containsKey("file_type")) fileType = (String) (masterFileJSON).get("file_type");
            if (masterFileJSON.containsKey("file_name")) fullFileName = (String) (masterFileJSON).get("file_name");
            wasZipped = (Boolean) masterFileJSON.getOrDefault("was_zipped", false);
            subFileIds = null;
            if (masterFileJSON.containsKey("sub_file_ids")) {
                this.subFileIds = new ArrayList<>();
                List<?> ids = ((JSONArray) masterFileJSON.get("sub_file_ids"));
                for (Object id : ids) subFileIds.add((String)id);
            }
            subFileChunksRetrieved = subFileIds == null || subFileIds.isEmpty();
            if (masterFileJSON.get("checksum_md5") != null) {
                JSONArray md5 = (JSONArray) masterFileJSON.get("checksum_md5");
                if (md5.size() == nChunks) checksum_md5 = ((Stream<String>)md5.stream()).map(s -> Base64.getDecoder().decode(s)).collect(Collectors.toList());
            }
        } else { // only one file not chunked
            if (item.getFileNames().size() != 1 || !chunkItems.isEmpty()) throw new IOException("Invalid LargeFileGist");
            stringContentFile = item.getFiles().iterator().next();
            if (stringContentFile.isContentLoaded()) stringContent = stringContentFile.getContent();
            nChunks = 0;
            nChunksPerSubFile = 0;
            chunkSize = (int)(1024*1024*MAX_CHUNK_SIZE_MB);
            sizeMb = stringContent == null ? (stringContentFile.getSize()>=0 ? stringContentFile.getSize() / ((double)1024*1024) : chunkSize / ((double)1024*1024)) : stringContent.length() / ((double)1024*1024);
            fullFileName = stringContentFile.getName();
            fileType = fullFileName.contains(".") ? fullFileName.substring(fullFileName.indexOf(".")) : "";
            wasZipped = false;
            subFileIds = null;
            subFileChunksRetrieved = true;
        }
    }

    protected synchronized void ensureChunkRetrieved() {
        if (subFileChunksRetrieved) return;
        if (chunkItems.size() < nChunks) {
            for (String id : subFileIds) {
                try {
                    GistItem sub = repository.get(id);
                    for (String f : sub.getFileNames()) if (isChunk(f)) chunkItems.put(f, sub);
                } catch (IOException io) {
                    throw new RuntimeException("Error while retrieving chunks of subfile ID=" + id, io);
                }
            }
        }
        subFileChunksRetrieved = true;
    }

    /**
     * @return file name of the large file stored in the item (master file or single file), or null if the item is not a large file
     */
    public static String getFileName(GistItem item) {
        String masterFileName = item.getFileNames().stream().filter(f -> f.endsWith("master_file.json")).findFirst().orElse(null);
        if (masterFileName != null) {
            masterFileName = masterFileName.replace("_master_file.json", "");
            if (masterFileName.startsWith("_")) return masterFileName.substring(1);
            else return masterFileName;
        } else if (item.getFileNames().size() == 1) { // may be single file if small string was stored
            return item.getFileNames().iterator().next();
        } else return null;
    }

    public static String getFileName(JSONObject gist) {
        return getFileName(GistRepository.github(new NoAuth()).parse(gist));
    }

    public double getSizeMb() {
        return sizeMb;
    }

    protected static String read(GistRepository repo, GistItem.FileEntry entry) throws IOException {
        if (entry.isContentLoaded()) return entry.getContent();
        return repo.readFile(entry.getItem(), entry);
    }

    public byte[] retrieveChunk(String chunkName, UserAuth auth) throws IOException {
        ensureChunkRetrieved();
        GistItem holder = chunkItems.get(chunkName);
        if (holder == null) throw new IOException("Chunk not found: " + chunkName);
        String chunkB64 = read(repo(auth), holder.getFile(chunkName)).trim();
        byte[] chunk;
        try {
            chunk = Base64.getDecoder().decode(chunkB64);
        } catch (IllegalArgumentException e) {
            if (e.getMessage().contains("Illegal base64 character")) logger.debug("Illegal B64 chunk: {}", chunkB64);
            throw e;
        }
        logger.debug("chunk {} length: {}", chunkName, chunk.length);
        byte[] md5 = null;
        if (checksum_md5 != null) {
            int idx = getChunkIndex(chunkName);
            if (idx>=0 && idx<checksum_md5.size()) md5 = checksum_md5.get(idx);
        }
        if (md5!=null) {
            boolean checksum;
            try {
                checksum = checkSum(chunk, md5);
            } catch (NoSuchAlgorithmException e) { // cannot perform checksum
                return chunk;
            }
            if (checksum) return chunk;
            else throw new ChecksumException("Invalid Checksum");
        } else return chunk;
    }

    public static class ChecksumException extends IOException{
        public ChecksumException(String message) {
            super(message);
        }
    }

    public File retrieveFile(File outputFile, boolean background, boolean unzipIfPossible, UserAuth auth, Consumer<File> callback, ProgressDisplay pcb) throws IOException {
        if (outputFile == null) throw new RuntimeException("OutputFile cannot be null");
        boolean willUnzip = unzipIfPossible && ( wasZipped || (fullFileName.endsWith(".zip") && !outputFile.getName().endsWith(".zip")) );
        ensureChunkRetrieved();
        File actualOutputFile = outputFile.isDirectory()? new File(outputFile, willUnzip? (fullFileName.endsWith(".zip") ? fullFileName.substring(0, fullFileName.length()-4) : fullFileName) : fullFileName) : outputFile;
        File targetFile = willUnzip ? new File(actualOutputFile.getParentFile(), actualOutputFile.getName()+".zip") : (wasZipped && outputFile.isDirectory() ? new File(outputFile, fullFileName+".zip") : actualOutputFile);
        File resultFile = willUnzip ? actualOutputFile : targetFile; // zipped file is returned if not unzipped
        if (getStringContent(auth) != null) {
            FileIO.TextFile f = new FileIO.TextFile(actualOutputFile.getAbsolutePath(), true, false);
            f.write(stringContent, false);
            if (callback != null) callback.accept(actualOutputFile);
            return actualOutputFile;
        }
        FileOutputStream fos = new FileOutputStream(targetFile,false);
        List<String> chunkNames = new ArrayList<>(this.chunkItems.keySet());
        Runnable closeStream = () -> {
            try {
                fos.close();
            } catch (IOException e) {
                logger.debug("error closing file", e);
            }
        };
        Runnable unzipCallback = () -> {
            try {
                ZipUtils.unzipFile(targetFile, actualOutputFile);
                targetFile.delete();
            } catch (IOException e) {
                logger.debug("error while unzipping", e);
            }
        };
        Runnable actualCallback = willUnzip ? (callback==null ? unzipCallback : () -> {
            unzipCallback.run();
            callback.accept(actualOutputFile);
        }) : ( callback==null ? () -> {} : () ->  callback.accept(resultFile) );
        Runnable endOfWork = () -> {
            closeStream.run();
            actualCallback.run();
        };
        DefaultWorker.WorkerTask task = i -> {
            byte[] chunk;
            try {
                chunk = retrieveChunk(chunkNames.get(i), auth);
            } catch(ChecksumException e) {
                // try to retrieve a second time
                logger.debug("checksum error, will try to retrieve a second time");
                chunk = retrieveChunk(chunkNames.get(i), auth);
            }
            fos.write(chunk, 0, chunk.length);
            return null;
        };
        if (background) DefaultWorker.execute(task, chunkNames.size(), pcb).appendEndOfWork(endOfWork);
        else {
            try {
                DefaultWorker.executeInForeground(task, chunkNames.size());
            } catch (RuntimeException e) {
                closeStream.run();
                throw e;
            }
            endOfWork.run();
        }
        return resultFile;
    }

    public String retrieveString(UserAuth auth, ProgressDisplay pcb) throws IOException {
        String[] result = new String[1];
        retrieveString(s -> result[0] = s, false, auth, pcb);
        return result[0];
    }

    public void retrieveStringBackground(Consumer<String> getResult, UserAuth auth, ProgressDisplay pcb) throws IOException {
        retrieveString(getResult, true, auth, pcb);
    }

    protected String getStringContent(UserAuth auth) throws IOException {
        if (stringContent != null) return stringContent;
        if (stringContentFile != null) {
            stringContent = read(repo(auth), stringContentFile);
            sizeMb = stringContent.length() / ((double)1024*1024);
        }
        return stringContent;
    }

    protected void retrieveString(Consumer<String> getResult, boolean background, UserAuth auth, ProgressDisplay pcb) throws IOException {
        logger.debug("retrieve string: n chunks: {} content non null: {}", nChunks, stringContent!=null);
        getStringContent(auth);
        if (stringContent != null) {
            getResult.accept(stringContent);
            return;
        }
        boolean willUnzip =  wasZipped || (fullFileName.endsWith(".zip") );
        ensureChunkRetrieved();
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        List<String> chunkNames = new ArrayList<>(this.chunkItems.keySet());
        Runnable callback = () -> {
            try {
                byte[] data = outputStream.toByteArray();
                if (willUnzip) {
                    ByteArrayOutputStream unzipped = new ByteArrayOutputStream();
                    ZipUtils.unzipStream(new ByteArrayInputStream(data), unzipped);
                    getResult.accept(new String(unzipped.toByteArray(), StandardCharsets.UTF_8));
                } else {
                    getResult.accept(new String(data, StandardCharsets.UTF_8));
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        };

        DefaultWorker.WorkerTask task = i -> {
            byte[] chunk;
            try {
                chunk = retrieveChunk(chunkNames.get(i), auth);
            } catch(ChecksumException e) {
                // try to retrieve a second time
                logger.debug("checksum error, will try to retrieve a second time");
                chunk = retrieveChunk(chunkNames.get(i), auth);
            }
            synchronized (outputStream) {
                outputStream.write(chunk, 0, chunk.length);
            }
            return null;
        };
        try {
            if (background) DefaultWorker.execute(task, chunkNames.size(), pcb).appendEndOfWork(callback);
            else {
                DefaultWorker.executeInForeground(task, chunkNames.size());
                callback.run();
            }
        } catch (RuntimeException e) {
            if (e.getCause() instanceof IOException) throw (IOException) e.getCause();
            else throw e;
        }
    }

    protected static String toString(InputStream inputStream) throws IOException {
        return JSONQuery.readFully(inputStream);
    }

    public static List<String> getMD5(Collection<byte[]> chunks) {
         try {
             MessageDigest md = MessageDigest.getInstance(MD5);
             List<byte[]> checksum = chunks.stream().map(md::digest).collect(Collectors.toList());
             return checksum.stream().map(c -> Base64.getEncoder().encodeToString(c)).collect(Collectors.toList());
        } catch (NoSuchAlgorithmException e) {
            return null;
        }
    }

    public static boolean checkSum(byte[] data, byte[] checksumMD5) throws NoSuchAlgorithmException {
        MessageDigest md = MessageDigest.getInstance(MD5);
        byte[] dataCS =  md.digest(data);
        return Arrays.equals(dataCS, checksumMD5);
    }

    // storage

    protected static Pair<InputStream, Boolean> openFile(File file, ProgressDisplay pcb) throws IOException {
        if (file.isDirectory()) { // start by zipping the file
            if (pcb!=null) pcb.setMessage("zipping directory...");
            return new Pair<>(ZipUtils.zipFile(file).getInputStream(), true);
        } else return new Pair<>(new FileInputStream(file), false);
    }

    public static Pair<String, DefaultWorker> storeFile(File file, boolean visible, String description, String fileType, UserAuth auth, boolean background, Consumer<String> callback, ProgressDisplay pcb) throws IOException{
        return storeFile(GistRepository.github(auth), file, visible, description, fileType, background, callback, pcb);
    }

    /**
     * Stores a file or a directory (zipped) in a repository
     * @param callback receives the id of the stored file when upload is finished
     * @return id of the stored file and worker if background is true
     */
    public static Pair<String, DefaultWorker> storeFile(GistRepository repository, File file, boolean visible, String description, String fileType, boolean background, Consumer<String> callback, ProgressDisplay pcb) throws IOException{
        if (file==null || !file.exists()) return null;
        Pair<InputStream, Boolean> is = openFile(file, pcb);
        return storeInputStream(repository, is.key, file.getName(), is.value, visible, description, fileType, background, callback, pcb);
    }

    public Pair<String, DefaultWorker> updateFile(File file, String description, String fileType, UserAuth auth, boolean background, Consumer<String> callback, ProgressDisplay pcb) throws IOException{
        if (file==null || !file.exists()) return null;
        Pair<InputStream, Boolean> is = openFile(file, pcb);
        return updateInputStream(is.key, is.value, description, fileType, auth, background, callback, pcb);
    }

    public static Pair<String, DefaultWorker> storeString(String name, String content, boolean visible, String description, String fileType, UserAuth auth, boolean background, Consumer<String> callback, ProgressDisplay pcb) throws IOException{
        return storeString(GistRepository.github(auth), name, content, visible, description, fileType, background, callback, pcb);
    }

    public static Pair<String, DefaultWorker> storeString(GistRepository repository, String name, String content, boolean visible, String description, String fileType, boolean background, Consumer<String> callback, ProgressDisplay pcb) throws IOException{
        int sizeOfChunk = (int) (1024 * 1024 * repository.getMaxChunkSizeMB());
        if (content.length() < sizeOfChunk) { // store string as single file
            Map<String, String> files = new HashMap<>();
            files.put(name, content);
            String id = repository.create(description, visible, files).getID();
            if (callback != null) callback.accept(id);
            return new Pair<>(id, null);
        } else {
            InputStream is = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
            return storeInputStream(repository, is, name, false, visible, description, fileType, background, callback, pcb);
        }
    }

    public void updateDescripton(String description, UserAuth auth) {
        this.description = description;
        try {
            repo(auth).update(id, description, null, null);
        } catch (IOException e) {
            logger.error("Error updating description of {}", id, e);
        }
    }

    public Pair<String, DefaultWorker> updateString(String content, String description, UserAuth auth, boolean background, Consumer<String> callback, ProgressDisplay pcb) throws IOException {
        GistRepository repo = repo(auth);
        int sizeOfChunk = (int) (1024 * 1024 * repo.getMaxChunkSizeMB());
        if (content.length() < sizeOfChunk) { // store string as single file
            Map<String, String> files = new HashMap<>();
            for (String f : item.getFileNames()) if (!f.equals(fullFileName)) files.put(f, null); // remove master file and chunks if previously stored as chunks
            ensureChunkRetrieved();
            files.put(fullFileName, content);
            GistItem updated = repo.update(id, description, null, files);
            if (subFileIds != null) for (String subId : subFileIds) deleteSilently(repo, subId);
            this.description = description;
            setData(updated.hasFile(fullFileName) && updated.getFileNames().size()==1 ? updated : repo.get(id));
            if (callback != null) callback.accept(id);
            return new Pair<>(id, null);
        } else {
            InputStream is = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
            return updateInputStream(is, false, description, fileType, auth, background, callback, pcb);
        }
    }

    protected static JSONObject masterContent(Map<String, byte[]> chunks, List<String> md5, String fileName, String fileType, boolean wasZipped, int nChunksPerItem, List<String> subFileIds) {
        JSONObject jsonContent = new JSONObject();
        if (subFileIds != null && !subFileIds.isEmpty()) jsonContent.put("sub_file_ids", new ArrayList<>(subFileIds));
        double size = chunks.values().stream().mapToLong(b -> b.length).sum()/((double)1024*1024);
        jsonContent.put("chunk_size", chunks.values().iterator().next().length);
        jsonContent.put("file_name", fileName);
        jsonContent.put("size_mb",  size);
        jsonContent.put("n_chunks", chunks.size());
        jsonContent.put("n_chunks_per_subfile", nChunksPerItem);
        jsonContent.put("file_type", fileType);
        jsonContent.put("checksum_md5", md5);
        jsonContent.put("was_zipped", wasZipped);
        return jsonContent;
    }

    protected static String masterFileName(String fileName) {
        return "_"+Utils.removeExtension(fileName)+"_master_file.json"; // leading underscore so it appears before chunks
    }

    protected static Map<String, byte[]> split(InputStream is, GistRepository repo) throws IOException {
        Map<String, byte[]> chunks = nameChunks(splitFile(is, repo.getMaxChunkSizeMB()));
        if (chunks == null || chunks.isEmpty()) throw new IOException("Cannot store empty file");
        return chunks;
    }

    public Pair<String, DefaultWorker> updateInputStream(InputStream is, boolean wasZipped, String description, String fileType, UserAuth auth, boolean background, Consumer<String> callback, ProgressDisplay pcb) throws IOException {
        GistRepository repo = repo(auth);
        Map<String, byte[]> chunks = split(is, repo);
        List<String> md5 = getMD5(chunks.values());
        int nPerItem = repo.getMaxChunksPerItem();
        ensureChunkRetrieved();
        Map<String, GistItem> oldChunkItems = new HashMap<>(chunkItems);

        // if needed store subfiles and update current file
        int nSubFiles = (int)Math.ceil((double)chunks.size() / nPerItem);
        int oldSubFiles = subFileIds == null ? 1 : subFileIds.size() + 1;
        if (subFileIds == null) subFileIds = new ArrayList<>(nSubFiles);
        Set<String> deletedSubFiles = new HashSet<>();
        if (nSubFiles > oldSubFiles) { // create new subfiles
            for (int i = oldSubFiles; i<nSubFiles; ++i) this.subFileIds.add(storeSubFile(repo, fullFileName, i, md5, nPerItem, visible, description));
        } else if (nSubFiles < oldSubFiles) { // delete existing subfiles
            for (int i = oldSubFiles - 1; i>=nSubFiles; --i) {
                String subId = this.subFileIds.remove(i-1);
                deleteSilently(repo, subId);
                deletedSubFiles.add(subId);
                logger.debug("deleted subfile: {}, #{} / {} target: {}", subId, i, oldSubFiles, nSubFiles);
            }
        }
        List<String> allSubFileIds = new ArrayList<>(nSubFiles);
        allSubFileIds.add(id);
        allSubFileIds.addAll(subFileIds);
        Function<Integer, String> getFileId = idx -> allSubFileIds.get(idx / nPerItem);
        List<Map.Entry<String, byte[]>> chunkList = new ArrayList<>(chunks.entrySet());

        // remove chunks that are not part of the new file (fewer chunks, or chunk names changed)
        Map<String, Map<String, String>> toRemove = new HashMap<>();
        Map<String, String> newChunkLocation = new HashMap<>();
        for (int i = 0; i<chunkList.size(); ++i) newChunkLocation.put(chunkList.get(i).getKey(), getFileId.apply(i));
        for (Map.Entry<String, GistItem> e : oldChunkItems.entrySet()) {
            String holder = e.getValue().getID();
            if (deletedSubFiles.contains(holder)) continue;
            if (!holder.equals(newChunkLocation.get(e.getKey()))) toRemove.computeIfAbsent(holder, h -> new HashMap<>()).put(e.getKey(), null);
        }
        for (Map.Entry<String, Map<String, String>> e : toRemove.entrySet()) {
            if (e.getKey().equals(id)) continue; // done with master file update
            repo.update(e.getKey(), null, null, e.getValue());
        }
        Map<String, String> masterFiles = toRemove.getOrDefault(id, new HashMap<>());
        String masterName = masterFileName(fullFileName);
        for (String f : item.getFileNames()) if (!isChunk(f) && !f.equals(masterName)) masterFiles.put(f, null); // e.g. single file if previously stored as a string
        JSONObject jsonContent = masterContent(chunks, md5, fullFileName, fileType, wasZipped, nPerItem, subFileIds);
        masterFiles.put(masterName, jsonContent.toJSONString());
        logger.debug("uploading master file {}", jsonContent.toJSONString());
        repo.update(id, description, null, masterFiles);
        this.description = description;
        Runnable refresh = () -> {
            try {
                setData(repository.get(id));
            } catch (IOException e) {
                logger.debug("Could not refresh large file {}", id, e);
            }
        };
        return uploadChunks(repo, chunkList, getFileId, id, background, callback, refresh, pcb);
    }

    protected static Pair<String, DefaultWorker> uploadChunks(GistRepository repo, List<Map.Entry<String, byte[]>> chunkList, Function<Integer, String> getFileId, String id, boolean background, Consumer<String> callback, Runnable endOfWork, ProgressDisplay pcb) {
        double size = chunkList.stream().mapToLong(e -> e.getValue().length).sum()/((double)1024*1024);
        Set<String> items = new HashSet<>();
        for (int i = 0; i<chunkList.size(); ++i) items.add(getFileId.apply(i));
        if (pcb!=null) pcb.setMessage("storing file: size: "+String.format("%.2f", size)+"Mb # chunks: "+chunkList.size() +" #n subfiles: "+items.size());
        DefaultWorker.WorkerTask task = i -> {
            logger.debug("storing chunk {}/{}", i + 1, chunkList.size());
            storeChunk(repo, getFileId.apply(i), chunkList.get(i).getKey(), chunkList.get(i).getValue());
            return null;
        };
        Runnable end = () -> {
            if (endOfWork != null) endOfWork.run();
            if (callback != null) callback.accept(id);
        };
        if (background) {
            DefaultWorker w = DefaultWorker.execute(task, chunkList.size(), pcb).appendEndOfWork(end);
            return new Pair<>(id, w);
        } else {
            DefaultWorker.executeInForeground(task, chunkList.size());
            end.run();
            return new Pair<>(id, null);
        }
    }

    public static Pair<String, DefaultWorker> storeInputStream(InputStream is, String fileName, boolean wasZipped, boolean visible, String description, String fileType, UserAuth auth, boolean background, Consumer<String> callback, ProgressDisplay pcb) throws IOException{
        return storeInputStream(GistRepository.github(auth), is, fileName, wasZipped, visible, description, fileType, background, callback, pcb);
    }

    public static Pair<String, DefaultWorker> storeInputStream(GistRepository repo, InputStream is, String fileName, boolean wasZipped, boolean visible, String description, String fileType, boolean background, Consumer<String> callback, ProgressDisplay pcb) throws IOException{
        Map<String, byte[]> chunks = split(is, repo);
        List<String> md5 = getMD5(chunks.values());
        int nPerItem = repo.getMaxChunksPerItem();
        // if needed store subfiles
        int nSubFiles = (int)Math.ceil((double)chunks.size() / nPerItem);
        List<String> fileIds = new ArrayList<>(nSubFiles);
        for (int i = 1; i<nSubFiles; ++i) fileIds.add(storeSubFile(repo, fileName, i, md5, nPerItem, visible, description));
        if (!fileIds.isEmpty()) logger.debug("subfile ids: {}", fileIds);
        JSONObject jsonContent = masterContent(chunks, md5, fileName, fileType, wasZipped, nPerItem, fileIds);
        Map<String, String> files = new HashMap<>();
        files.put(masterFileName(fileName), jsonContent.toJSONString());
        logger.debug("uploading master file {}", jsonContent.toJSONString());
        String masterId = repo.create(description, visible, files).getID();
        fileIds.add(0, masterId);
        Function<Integer, String> getFileId = idx -> fileIds.get(idx / nPerItem);
        return uploadChunks(repo, new ArrayList<>(chunks.entrySet()), getFileId, masterId, background, callback, null, pcb);
    }

    protected static String storeSubFile(GistRepository repo, String name, int idx, List<String> md5, int nPerItem, boolean visible, String description) throws IOException {
        JSONObject jsonContent = new JSONObject();
        jsonContent.put("n_chunks", md5.size());
        jsonContent.put("n_chunks_per_subfile", nPerItem);
        jsonContent.put("checksum_md5", md5);
        Map<String, String> files = new HashMap<>();
        files.put("_"+Utils.removeExtension(name)+"_subfile_"+idx+".json", jsonContent.toJSONString()); // leading underscore so it appears before chunks
        return repo.create(description, visible, files).getID();
    }

    protected static boolean chunkUploaded(GistRepository repo, String id, String chunkName, String content) {
        try {
            GistItem item = repo.get(id);
            GistItem.FileEntry f = item.getFile(chunkName);
            if (f == null) return false;
            return content.equals(read(repo, f).trim());
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Stores a chunk, retrying in case of server error. As servers sometimes return an error although the chunk has been stored, checks if the chunk was stored before retrying
     */
    protected static void storeChunk(GistRepository repo, String id, String name, byte[] bytes) throws IOException {
        String content = Base64.getEncoder().encodeToString(bytes);
        Map<String, String> files = new HashMap<>();
        files.put(name, content);
        int sleep = JSONQuery.TRYOUT_SLEEP;
        for (int tryout = 0; ; ++tryout) {
            try {
                repo.update(id, null, null, files);
                return;
            } catch (IOException e) {
                String m = e.getMessage() == null ? "" : e.getMessage();
                boolean serverError = m.contains("HTTP response code: 50") || m.contains("HTTP response code: 429");
                if (!serverError || tryout >= JSONQuery.MAX_TRYOUTS) throw e;
                if (chunkUploaded(repo, id, name, content)) return;
                logger.debug("error {} -> try again, remaining tryouts: {}", m, JSONQuery.MAX_TRYOUTS - tryout);
                sleep(sleep);
                sleep += JSONQuery.TRYOUT_SLEEP_INC;
            }
        }
    }

    protected static void deleteSilently(GistRepository repo, String id) {
        try {
            repo.delete(id);
        } catch (IOException e) {
            logger.debug("Error deleting item {}", id, e);
        }
    }

    /**
     * Deletes the item and its sub-files
     */
    public boolean delete(UserAuth auth) {
        GistRepository repo = repo(auth);
        if (!repo.isWritable()) return false;
        if (subFileIds != null) for (String subId : subFileIds) deleteSilently(repo, subId);
        try {
            repo.delete(id);
            return true;
        } catch (IOException e) {
            logger.debug("Error deleting file", e);
            return false;
        }
    }

    public static boolean delete(String id, UserAuth auth) {
        String url = BASE_URL + "/gists/" + id;
        return JSONQuery.delete(url, auth);
    }

    public static Stream<LargeFileGist> fetch(UserAuth auth, Predicate<String> filenameFiler, ProgressDisplay pcb) {
        if (auth.getAccount() == null && auth instanceof NoAuth) {
            logger.error("Cannot fetch gists: no account set");
            return null;
        }
        return fetch(GistRepository.github(auth), filenameFiler, pcb);
    }

    /**
     * @return large files of the repository whose file name pass the filter
     */
    public static Stream<LargeFileGist> fetch(GistRepository repository, Predicate<String> filenameFiler, ProgressDisplay pcb) {
        try {
            List<GistItem> items = repository.list();
            return items.stream().map(item -> {
                String filename = getFileName(item);
                if (filename == null) return null;
                if (filenameFiler != null && !filenameFiler.test(filename)) return null;
                try {
                    LargeFileGist lf = new LargeFileGist(item);
                    if (lf.id == null) return null;
                    else return lf;
                } catch (IOException e) {
                    return null;
                }
            }).filter(Objects::nonNull);
        } catch (IOException e) {
            logger.error("Error getting files", e);
            if (pcb!=null) pcb.setMessage("Could not get files: "+e.getMessage());
            return Stream.empty();
        }
    }

    public static List<byte[]> splitFile(InputStream inputStream, double sizeOfChunksInMB) throws IOException {
        List<byte[]> datalist = new ArrayList<>();
        int sizeOfChunk = (int) (1024 * 1024 * sizeOfChunksInMB);
        byte[] buffer = new byte[sizeOfChunk];
        BufferedInputStream bis = new BufferedInputStream(inputStream);
        int bytesAmount;
        while ((bytesAmount = readFully(bis, buffer)) > 0) {
            datalist.add(Arrays.copyOfRange(buffer, 0, bytesAmount));
        }
        bis.close();
        inputStream.close();
        return datalist;
    }

    /**
     * Fills the buffer unless end of stream is reached (InputStream.read may return fewer bytes than available)
     */
    protected static int readFully(InputStream is, byte[] buffer) throws IOException {
        int total = 0;
        while (total < buffer.length) {
            int n = is.read(buffer, total, buffer.length - total);
            if (n < 0) break;
            total += n;
        }
        return total;
    }

    public static List<byte[]> splitFile(File inputFile, double sizeOfChunksInMB) throws IOException {
        return splitFile(new FileInputStream(inputFile), sizeOfChunksInMB);
    }

    public static Map<String, byte[]> nameChunks(List<byte[]> chunks) {
        if (chunks==null) return null;
        int total = chunks.size();
        int numberOfZeros = total == 0 ? 1 : (int)Math.log10(total) + 1;
        Map<String, byte[]> res = new TreeMap<>();
        for (int i = 0; i<chunks.size(); ++i) res.put(chunkName(i, numberOfZeros), chunks.get(i));
        return res;
    }

    public static void mergeChunks(File outputFile, Map<String, byte[]> chunks) {
        chunks = new TreeMap<>(chunks);
        try (FileOutputStream fos = new FileOutputStream(outputFile,false)) {
            for (byte[] chunk : chunks.values()) fos.write(chunk);
        } catch (IOException e) {
            logger.debug("error while merging chunks", e);
        }
    }

    private static String chunkName(int i, int paddingSize) {
        return "chunk_" + Utils.formatInteger(paddingSize, i);
    }

    private static int getChunkIndex(String chunkName) {
        int i = chunkName.indexOf("chunk_")+6;
        try {
            return Integer.parseInt(chunkName.substring(i));
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
