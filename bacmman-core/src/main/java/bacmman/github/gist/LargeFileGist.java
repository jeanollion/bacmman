package bacmman.github.gist;

import bacmman.core.DefaultWorker;
import bacmman.ui.logger.ProgressLogger;
import bacmman.utils.*;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static bacmman.github.gist.JSONQuery.GIST_BASE_URL;
import static bacmman.utils.ThreadRunner.sleep;
import static org.apache.commons.codec.digest.MessageDigestAlgorithms.MD5;

public class LargeFileGist {
    public static final Logger logger = LoggerFactory.getLogger(LargeFileGist.class);
    public static String BASE_URL = "https://api.github.com";
    static double MAX_CHUNK_SIZE_MB = 10;
    static int MAX_CHUNKS_PER_SUBFILE = 30;
    Map<String, String> chunksURL;
    double sizeMb;
    int chunkSize, nChunks, nChunksPerSubFile;
    String fileType, fullFileName;
    Map<String, byte[]> checksum_md5;
    String description, id;
    boolean visible, wasZipped;
    List<String> subFileIds;
    Consumer<String> ensureChunkRetrieved;
    public LargeFileGist(String id, UserAuth auth) throws IOException {
        if (id.startsWith(GIST_BASE_URL)) id=id.replace(GIST_BASE_URL, "");
        try {
            String response = new JSONQuery(BASE_URL + "/gists/" + id).authenticate(auth).method(JSONQuery.METHOD.GET).fetch();
            if (response != null) {
                try {
                    Object json = new JSONParser().parse(response);
                    setGistData((JSONObject)json, auth);
                } catch (ParseException e) {
                    logger.error("Error parsing response (LargeFileGist creation). Error: {} response: {}", e, response);
                    throw new IOException(e);
                }
            } else throw new RuntimeException("Could not retrieve Large File Gist");
        } catch (IOException e) {
            throw e;
        }
    }
    public LargeFileGist(JSONObject gist, UserAuth auth) throws IOException {
        setGistData(gist, auth);
    }
    public String getFileName() {
        if (fullFileName.endsWith(".zip")) return fullFileName.substring(0, fullFileName.length()-4);
        else return fullFileName;
    }
    public static List<File> downloadGist(String id, String dir) throws IOException {
        Map<String, String> files = downloadGist(id);
        return files.entrySet().stream().map(e -> {
            Path path = Paths.get(dir, e.getKey());
            FileIO.TextFile file = new FileIO.TextFile(path.toString(), true, true);
            file.write(e.getValue(), false);
            file.close();
            return path.toFile();
        }).collect(Collectors.toList());
    }
    public static Map<String, String> downloadGist(String id) throws IOException {
        if (id.startsWith(GIST_BASE_URL)) id=id.replace(GIST_BASE_URL, "");
        String response = new JSONQuery(BASE_URL + "/gists/" + id).method(JSONQuery.METHOD.GET).fetch();
        Map<String, String> res = new HashMap<>();
        if (response != null) {
            try {
                JSONObject json = (JSONObject)new JSONParser().parse(response);
                Object files = json.get("files");
                if (files!=null) {
                    JSONObject allFiles = (JSONObject) files;
                    Stream<Object> s = allFiles.values().stream();
                    s.forEach( f -> {
                        JSONObject fJson = (JSONObject)f;
                        String content = null;
                        if (fJson.containsKey("content") && !(Boolean)fJson.getOrDefault("truncated", false)) content =  (String)fJson.get("content");
                        else content = new JSONQuery((String) fJson.get("raw_url"), JSONQuery.REQUEST_PROPERTY_GITHUB_BASE64).fetchSilently();
                        String fileName = (String) fJson.get("filename");
                        res.put(fileName, content);
                    });
                }
            } catch (ParseException e) {
                logger.error("Error parsing response @download. Error: {} response: {}", e, response);
                throw new IOException(e);
            }
        } else throw new IOException("GIST not found");
        return res;
    }
    protected void setGistData(JSONObject gist, UserAuth auth) throws IOException {
        description = (String)gist.get("description");
        id = (String)gist.get("id");
        visible = (Boolean)gist.get("public");
        Object files = gist.get("files");
        if (files!=null) {
            JSONObject allFiles = (JSONObject) files;
            Stream<Object> s = allFiles.values().stream();
            chunksURL = s.filter(f -> ((String) ((JSONObject) f).get("filename")).startsWith("chunk_"))
                    .collect(Collectors.toMap(f -> (String) ((JSONObject) f).get("filename"), f -> (String) ((JSONObject) f).get("raw_url")));
            chunksURL = new TreeMap<>(chunksURL); // sorted map
            JSONObject master = ((JSONObject) allFiles.values().stream().filter(f-> ((String)((JSONObject)f).get("filename")).endsWith("master_file.json") || ((String)((JSONObject)f).get("filename")).matches("(.*)_subfile_(\\d+).json$")).findFirst().orElse(null));
            if (master != null) {
                String masterFileUrl = (String) (master).get("raw_url");
                String masterFileContent;
                if (master.containsKey("content") && !(Boolean)master.getOrDefault("truncated", false)) masterFileContent =  (String)master.get("content");
                else masterFileContent = new JSONQuery(masterFileUrl, JSONQuery.REQUEST_PROPERTY_GITHUB_BASE64).authenticate(auth).fetchSilently();
                JSONObject masterFileJSON = null;
                try {
                    masterFileJSON = JSONUtils.parse(masterFileContent);
                } catch (ParseException e) {
                    logger.error("Error parsing masterfileContent @setFistData. Error: {} response: {}", e, masterFileContent);
                    throw new IOException(e);
                }
                nChunks = ((Number) masterFileJSON.get("n_chunks")).intValue();
                nChunksPerSubFile = ((Number) masterFileJSON.getOrDefault("n_chunks_per_subfile", MAX_CHUNKS_PER_SUBFILE)).intValue();
                if (masterFileJSON.containsKey("size_mb")) sizeMb = ((Number) masterFileJSON.get("size_mb")).doubleValue();
                chunkSize = ((Number) masterFileJSON.getOrDefault("chunk_size", 1024*1024*9)).intValue();
                if (masterFileJSON.containsKey("file_type")) fileType = (String) (masterFileJSON).get("file_type");
                if (masterFileJSON.containsKey("file_name")) fullFileName = (String) (masterFileJSON).get("file_name");
                wasZipped = (Boolean) masterFileJSON.getOrDefault("was_zipped", false);
                if (masterFileJSON.containsKey("sub_file_ids")) {
                    this.subFileIds = new ArrayList<>();
                    List<?> ids = ((JSONArray) masterFileJSON.get("sub_file_ids"));
                    for (Object id : ids) subFileIds.add((String)id);
                    ensureChunkRetrieved = chunkName -> {
                        if (chunksURL.containsKey(chunkName)) return;
                        int idx = getChunkIndex(chunkName);
                        if (idx>=nChunks) return;
                        int i = idx/nChunksPerSubFile;
                        if (i>0) {
                            String id = subFileIds.get(i-1);
                            try {
                                logger.debug("retrieving chunks of subfile {}: id={}", idx, id);
                                LargeFileGist lf = new LargeFileGist(id, auth);
                                chunksURL.putAll(lf.chunksURL);
                            } catch (IOException io) {
                                throw new RuntimeException("Error while retrieving chunks of subfile ID="+id, io);
                            }
                        }
                    };
                } else {
                    ensureChunkRetrieved = id -> {};
                }
                if (masterFileJSON.get("checksum_md5") != null) {
                    retrieveMD5((JSONArray) masterFileJSON.get("checksum_md5"));
                }
            }

        }
    }

    protected void retrieveMD5(JSONArray md5) {
        List<String> sortedChunkNames = new ArrayList<>(this.chunksURL.keySet());
        if (md5.size() == nChunks) {
            checksum_md5 = IntStream.range(0, sortedChunkNames.size()).boxed().collect(Collectors.toMap(sortedChunkNames::get, i -> Base64.getDecoder().decode((String) md5.get(i))));
        }
    }
    public double getSizeMb() {
        return sizeMb;
    }

    public byte[] retrieveChunk(String chunkName, UserAuth auth) throws IOException {
        ensureChunkRetrieved.accept(chunkName);
        String chunkURL = chunksURL.get(chunkName);
        byte[] md5 = checksum_md5==null ? null:checksum_md5.get(chunkName);
        String chunkB64 = new JSONQuery(chunkURL, JSONQuery.REQUEST_PROPERTY_GITHUB_BASE64).authenticate(auth).fetch();
        byte[] chunk = null;
        try {
            chunk = Base64.getDecoder().decode(chunkB64);
        } catch (IllegalArgumentException e) {
            if (e.getMessage().contains("Illegal base64 character")) logger.debug("Illegal B64 chunk: {}", chunkB64);
            throw e;
        }
        logger.debug("chunk {} length: {}", chunkName, chunk.length);
        if (md5!=null) {
            boolean checksum = false;
            try {
                checksum = checkSum(chunk, md5);
            } catch (NoSuchAlgorithmException e) { // cannot perform checksum
                return chunk;
            }
            if (checksum) {
                return chunk;
            }
            else throw new ChecksumException("Invalid Checksum");
        } else return chunk;
    }

    public static class ChecksumException extends IOException{
        public ChecksumException(String message) {
            super(message);
        }
    }

    public File retrieveFile(File outputFile, boolean background, boolean unzipIfPossible, UserAuth auth, Consumer<File> callback, ProgressLogger pcb) throws IOException {
        assert outputFile!=null;
        boolean willUnzip = unzipIfPossible && ( wasZipped || (fullFileName.endsWith(".zip") && !outputFile.getName().endsWith(".zip")) );
        File actualOutputFile = outputFile.isDirectory()? new File(outputFile, willUnzip? (fullFileName.endsWith(".zip") ? fullFileName.substring(0, fullFileName.length()-4) : fullFileName) : fullFileName) : outputFile;
        File targetFile = willUnzip ? new File(actualOutputFile.getParentFile(), actualOutputFile.getName()+".zip") : (wasZipped ? new File(outputFile, fullFileName+".zip") : actualOutputFile);
        FileOutputStream fos = new FileOutputStream(targetFile,false);
        List<String> chunkNames = new ArrayList<>(this.chunksURL.keySet());
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
        }) : ( callback==null ? () -> {} : () ->  callback.accept(actualOutputFile) );

        DefaultWorker.WorkerTask task = i -> {
            byte[] chunk = null;
            try {
                chunk = retrieveChunk(chunkNames.get(i), auth);
            } catch(ChecksumException e) {
                // try to retrieve a second time
                logger.debug("checksum error, will try to retrieve a second time");
                chunk = retrieveChunk(chunkNames.get(i), auth);
            }
            fos.write(chunk, 0, chunk.length);
            return null; //String.format("%.2f", chunk.length/(1024d*1024d))+"MB retrieved and written to "+actualOutputFile.getName();
        };
        if (background) DefaultWorker.execute(task, chunkNames.size(), pcb).appendEndOfWork(actualCallback);
        else {
            DefaultWorker.executeInForeground(task, chunkNames.size());
            actualCallback.run();
        }
        return actualOutputFile;
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
    public static Pair<String, DefaultWorker> storeFile(File file, boolean visible, String description, String fileType, UserAuth auth, boolean background, Consumer<String> callback, ProgressLogger pcb) throws IOException{
        if (file==null || !file.exists()) return null;
        InputStream is;
        boolean wasZipped = false;
        if (file.isDirectory()) { // start by zipping the file
            if (pcb!=null) pcb.setMessage("zipping directory...");
            is = ZipUtils.zipFile(file).getInputStream();
            wasZipped = true;
        } else is = new FileInputStream(file);
        Map<String, byte[]> chunks = nameChunks(splitFile(is, MAX_CHUNK_SIZE_MB));
        if (chunks==null) return null;
        List<String> md5 = getMD5(chunks.values());
        JSONObject jsonContent = new JSONObject();

        // if needed store subfiles and update current file
        int nSubFiles = (int)Math.ceil((double)chunks.size() / MAX_CHUNKS_PER_SUBFILE);
        List<String> fileIds = new ArrayList<>(nSubFiles);
        Function<Integer, String> getFileId = idx -> fileIds.get(idx/ MAX_CHUNKS_PER_SUBFILE);
        Function<Integer, String> getGistURL = idx -> BASE_URL + "/gists/" + getFileId.apply(idx);
        if (nSubFiles>1) {
            for (int i = 1; i<nSubFiles; ++i) fileIds.add(storeSubFile(file.getName(), i, md5, visible, description, auth));
            jsonContent.put("sub_file_ids", new ArrayList<>(fileIds));
            logger.debug("subfile ids: {}", fileIds);
        }
        double size = chunks.values().stream().mapToLong(b -> b.length).sum()/((double)1024*1024);
        jsonContent.put("chunk_size", chunks.values().iterator().next().length);
        jsonContent.put("file_name", file.getName());
        jsonContent.put("size_mb",  size);
        jsonContent.put("n_chunks", chunks.size());
        jsonContent.put("n_chunks_per_subfile", MAX_CHUNKS_PER_SUBFILE);
        jsonContent.put("file_type", fileType);
        jsonContent.put("checksum_md5", md5);
        jsonContent.put("was_zipped", wasZipped);
        JSONObject files = new JSONObject();
        JSONObject contentFile = new JSONObject();
        files.put("_"+Utils.removeExtension(file.getName())+"_master_file.json", contentFile); // leading underscore so it appears before chunks
        contentFile.put("content", jsonContent.toJSONString());
        JSONObject gist = new JSONObject();
        gist.put("files", files);
        gist.put("description", description);
        gist.put("public", visible);
        logger.debug("uploading master file {}", gist.toJSONString());
        String res = new JSONQuery(BASE_URL+"/gists").method(JSONQuery.METHOD.POST).authenticate(auth).setBody(gist.toJSONString()).fetchSilently();
        JSONObject json = null;
        try {
            json = JSONUtils.parse(res);
        } catch (ParseException e) {
            logger.error("Error parsing response @LargeFileGist store file. Error: {} response: {}", e, res);
            throw new IOException(e);
        }
        if (json!=null) {
            fileIds.add(0, (String) json.get("id"));
            if (pcb!=null) pcb.setMessage("storing file: size: "+String.format("%.2f", size)+"Mb # chunks: "+chunks.size() +" #n subfiles: "+nSubFiles);
            List<Map.Entry<String, byte[]>> chunkList = new ArrayList<>(chunks.entrySet());
            DefaultWorker.WorkerTask task = i -> {
                logger.debug("storing chunk {}/{}", i + 1, chunks.size());
                storeBytes(getGistURL.apply(i), chunkList.get(i).getKey(), chunkList.get(i).getValue(), auth, ()->chunkUploaded(getFileId.apply(i), chunkList.get(i).getKey(), auth));
                //sleep(1);
                return null; // String.format("%.2f", chunkList.get(i).getValue().length / (1024d * 1024d)) + "MB stored";
            };
            if (background) {
                DefaultWorker w = DefaultWorker.execute(task, chunks.size(), pcb).appendEndOfWork(callback==null?null:()->callback.accept(fileIds.get(0)));
                return new Pair<>(fileIds.get(0), w);
            }
            else {
                DefaultWorker.executeInForeground(task, chunks.size());
                if (callback!=null) callback.accept(fileIds.get(0));
                return new Pair<>(fileIds.get(0), null);
            }
        } else return null;
    }
    protected static String storeSubFile(String name, int idx, List<String> md5, boolean visible, String description, UserAuth auth) throws IOException {
        JSONObject jsonContent = new JSONObject();
        jsonContent.put("n_chunks", md5.size());
        jsonContent.put("n_chunks_per_subfile", MAX_CHUNKS_PER_SUBFILE);
        jsonContent.put("checksum_md5", md5);
        JSONObject files = new JSONObject();
        JSONObject contentFile = new JSONObject();
        files.put("_"+Utils.removeExtension(name)+"_subfile_"+idx+".json", contentFile); // leading underscore so it appears before chunks
        contentFile.put("content", jsonContent.toJSONString());
        JSONObject gist = new JSONObject();
        gist.put("files", files);
        gist.put("description", description);
        gist.put("public", visible);
        logger.debug("uploading master file {}", gist.toJSONString());
        String res = new JSONQuery(BASE_URL+"/gists").method(JSONQuery.METHOD.POST).authenticate(auth).setBody(gist.toJSONString()).fetchSilently();
        JSONObject json = null;
        try {
            json = JSONUtils.parse(res);
        } catch (ParseException e) {
            logger.error("Error parsing response @LargeFilGist store subFile. Error: {} response: {}", e, res);
            throw new IOException(e);
        }
        return (String) json.get("id");
    }
    public static boolean chunkUploaded(String id, String chunkName, UserAuth auth) {
        try {
            LargeFileGist lf = new LargeFileGist(id, auth);
            logger.debug("checking uploaded chunk {}: URL exists ? {}", chunkName, lf.chunksURL.containsKey(chunkName));
            return lf.retrieveChunk(chunkName, auth)!=null;
        } catch (IOException e) {
            //logger.debug("chunk uploaded test failed", e);
            return false;
        }
    }

    protected static boolean storeBytes(String gist_url, String name, byte[] bytes, UserAuth auth, BooleanSupplier chunkStored) throws IOException {
        String content = JSONQuery.encodeJSONBase64(name, bytes); // null will set empty content -> remove the file
        return storeTryout(gist_url, content, auth, chunkStored, JSONQuery.MAX_TRYOUTS, JSONQuery.TRYOUT_SLEEP);
    }
    protected static boolean storeInnerFile(String gistURL, String fileName, JSONObject fileContent, UserAuth auth) throws IOException {
        JSONObject files = new JSONObject();
        JSONObject contentFile = new JSONObject();
        files.put(fileName, contentFile);
        contentFile.put("content", fileContent.toJSONString());
        JSONObject gist = new JSONObject();
        gist.put("files", files);
        return storeTryout(gistURL, gist.toJSONString(), auth, ()->false, JSONQuery.MAX_TRYOUTS, JSONQuery.TRYOUT_SLEEP);
    }
    protected static boolean storeTryout(String gist_url, String content, UserAuth auth, BooleanSupplier isStored, int remainingTryouts, int sleep) throws IOException {
        JSONQuery q = new JSONQuery(gist_url, JSONQuery.REQUEST_PROPERTY_GITHUB_BASE64).method(JSONQuery.METHOD.PATCH).authenticate(auth).setBody(content);
        try {
            String answer = q.fetch();
            return answer!=null;
        } catch (IOException e) {
            if (e.getMessage().contains("HTTP response code: 50") && remainingTryouts>0) {
                // first check if chunk was stored:
                if (isStored.getAsBoolean()) return true;
                logger.debug("error {} -> try again, remaining tryouts: {}", e.getMessage(), remainingTryouts);
                sleep(sleep);
                storeTryout(gist_url, content, auth, isStored, remainingTryouts-1, sleep+JSONQuery.TRYOUT_SLEEP_INC);
            } else throw e;
        }
        return true;
    }

    public static List<byte[]> splitFile(InputStream inputStream, double sizeOfChunksInMB) throws IOException {
        List<byte[]> datalist = new ArrayList<>();
        int sizeOfChunk = (int) (1024 * 1024 * sizeOfChunksInMB);
        byte[] buffer = new byte[sizeOfChunk];
        BufferedInputStream bis = new BufferedInputStream(inputStream);
        int bytesAmount;
        while ((bytesAmount = bis.read(buffer)) > 0) {
            datalist.add(Arrays.copyOfRange(buffer, 0, bytesAmount));
        }
        bis.close();
        inputStream.close();
        return datalist;
    }
    public static List<byte[]> splitFile(File inputFile, double sizeOfChunksInMB) throws IOException {
        return splitFile(new FileInputStream(inputFile), sizeOfChunksInMB);
    }

    public static Map<String, byte[]> nameChunks(List<byte[]> chunks) {
        if (chunks==null) return null;
        int total = chunks.size();
        int numberOfZeros = (int)Math.log10(total) + 1;
        Map<String, byte[]> res = new TreeMap<>();
        for (int i = 0; i<chunks.size(); ++i) res.put(chunkName(i, numberOfZeros), chunks.get(i));
        return res;
    }

    public static void mergeChunks(File outputFile, Map<String, byte[]> chunks) {
        chunks = new TreeMap<>(chunks);
        try {
            int offset = 0;
            FileOutputStream fos = new FileOutputStream(outputFile,false);
            for (byte[] chunk : chunks.values()) {
                fos.write(chunk, offset, offset+chunk.length);
                offset+=chunk.length;
            }
        } catch (IOException e) {
            logger.debug("error while merging chunks", e);
        }
    }

    private static String chunkName(int i, int paddingSize) {
        return "chunk_" + Utils.formatInteger(paddingSize, i);
    }

    private static int getChunkIndex(String chunkName) {
        int i = chunkName.indexOf("chunk_")+6;
        return Integer.parseInt(chunkName.substring(i));
    }
}
