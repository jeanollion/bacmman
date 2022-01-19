package bacmman.github.gist;

import bacmman.core.DefaultWorker;
import bacmman.ui.logger.ProgressLogger;
import bacmman.utils.JSONUtils;
import bacmman.utils.Pair;
import bacmman.utils.Utils;
import bacmman.utils.ZipUtils;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static bacmman.utils.ThreadRunner.sleep;
import static org.apache.commons.codec.digest.MessageDigestAlgorithms.MD5;

public class LargeFileGist {
    public static final Logger logger = LoggerFactory.getLogger(LargeFileGist.class);
    public static String BASE_URL = "https://api.github.com";
    static double MAX_CHUNK_SIZE_MB = 10;
    Map<String, String> chunksURL;
    double sizeMb;
    int chunkSize;
    String fileType, fullFileName;
    boolean valid;
    Map<String, byte[]> checksum_md5;
    String description, id;
    boolean visible, wasZipped;

    public LargeFileGist(String id) throws IOException {
        try {
            String response = new JSONQuery(BASE_URL + "/gists/" + id).method(JSONQuery.METHOD.GET).fetch();
            if (response != null) {
                try {
                    Object json = new JSONParser().parse(response);
                    setGistData((JSONObject)json);
                } catch (ParseException e) {
                    throw new IOException(e);
                }
            }
        } catch (IOException e) {
            if (e.getMessage().contains("")) {

            } else throw e;
        }

    }
    public LargeFileGist(JSONObject gist) throws IOException {
        setGistData(gist);
    }
    public String getFileName() {
        if (fullFileName.endsWith(".zip")) return fullFileName.substring(0, fullFileName.length()-4);
        else return fullFileName;
    }
    protected void setGistData(JSONObject gist) throws IOException {
        description = (String)gist.get("description");
        id = (String)gist.get("id");
        visible = (Boolean)gist.get("public");
        Object files = gist.get("files");
        if (files!=null) {
            JSONObject allFiles = (JSONObject) files;
            JSONObject master = ((JSONObject) allFiles.values().stream().filter(f-> ((String)((JSONObject)f).get("filename")).endsWith("master_file.json")).findFirst().orElse(null));
            if (master != null) {
                String masterFileUrl = (String) (master).get("raw_url");
                String masterFileContent;
                if (master.containsKey("content") && !(Boolean)master.getOrDefault("truncated", false)) masterFileContent =  (String)master.get("content");
                else masterFileContent = new JSONQuery(masterFileUrl, JSONQuery.REQUEST_PROPERTY_GITHUB_BASE64).fetchSilently();
                JSONObject masterFileJSON = JSONUtils.parse(masterFileContent);
                int n_chunks = ((Number) masterFileJSON.get("n_chunks")).intValue();
                sizeMb = ((Number) masterFileJSON.get("size_mb")).doubleValue();
                chunkSize = ((Number) masterFileJSON.getOrDefault("chunk_size", 1024*1024*9)).intValue();
                fileType = (String) (masterFileJSON).get("file_type");
                fullFileName = (String) (masterFileJSON).get("file_name");
                wasZipped = (Boolean) masterFileJSON.getOrDefault("was_zipped", false);
                Stream<Object> s = allFiles.values().stream();
                chunksURL = s.filter(f -> ((String) ((JSONObject) f).get("filename")).startsWith("chunk_"))
                        .collect(Collectors.toMap(f -> (String) ((JSONObject) f).get("filename"), f -> (String) ((JSONObject) f).get("raw_url")));
                chunksURL = new TreeMap(chunksURL); // sorted map
                valid = n_chunks == chunksURL.size();
                if (masterFileJSON.get("checksum_md5") != null) {
                    List<?> cs = (JSONArray) masterFileJSON.get("checksum_md5");
                    List<String> sortedChunkNames = new ArrayList<>(chunksURL.keySet());
                    if (cs.size() == n_chunks) {
                        checksum_md5 = IntStream.range(0, n_chunks).boxed().collect(Collectors.toMap(sortedChunkNames::get, i -> Base64.getDecoder().decode((String) cs.get(i))));
                    }
                }
            } else throw new IOException("Master file not found");
        }
    }
    public double getSizeMb() {
        return sizeMb;
    }
    public boolean isValid() {
        return valid;
    }

    public byte[] retrieveChunk(String chunkName) throws IOException {
        String chunkURL = chunksURL.get(chunkName);
        String chunkB64 = new JSONQuery(chunkURL, JSONQuery.REQUEST_PROPERTY_GITHUB_BASE64).fetch();
        byte[] chunk = null;
        try {
            chunk = Base64.getDecoder().decode(chunkB64);
        } catch (IllegalArgumentException e) {
            if (e.getMessage().contains("Illegal base64 character")) logger.debug("Illegal B64 chunk: {}", chunkB64);
            throw e;
        }
        logger.debug("chunk {} length: {}", chunkName, chunk.length);
        byte[] md5 = checksum_md5==null ? null:checksum_md5.get(chunkName);
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

    public File retrieveFile(File outputFile, boolean background, boolean unzipIfPossible, Runnable callback, ProgressLogger pcb) throws IOException {
        assert outputFile!=null;
        if (!isValid()) throw new IOException("File is corrupted / not fully uploaded");
        boolean willUnzip = unzipIfPossible && ( wasZipped || (fullFileName.endsWith(".zip") && !outputFile.getName().endsWith(".zip")) );
        File actualOutputFile = outputFile.isDirectory()? new File(outputFile, willUnzip? (fullFileName.endsWith(".zip") ? fullFileName.substring(0, fullFileName.length()-4) : fullFileName) : fullFileName) : outputFile;
        File targetFile = willUnzip ? new File(actualOutputFile.getParentFile(), actualOutputFile.getName()+".zip") : (wasZipped ? new File(outputFile, fullFileName+".zip") : actualOutputFile);
        FileOutputStream fos = new FileOutputStream(targetFile,false);
        List<String> chunkNames = new ArrayList<>(chunksURL.keySet());
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
            callback.run();
        }) : callback;

        DefaultWorker.WorkerTask task = i -> {
            byte[] chunk = null;
            try {
                chunk = retrieveChunk(chunkNames.get(i));
            } catch(ChecksumException e) {
                // try to retrieve a second time
                logger.debug("checksum error, will try to retrieve a second time");
                chunk = retrieveChunk(chunkNames.get(i));
            }
            fos.write(chunk, 0, chunk.length);
            return String.format("%.2f", chunk.length/(1024d*1024d))+"MB retrieved and written to "+actualOutputFile.getName();
        };
        if (background) DefaultWorker.execute(task, chunkNames.size(), pcb).appendEndOfWork(actualCallback);
        else {
            DefaultWorker.executeInForeground(task, chunkNames.size());
            if (actualCallback!=null) actualCallback.run();
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
        Map<String, byte[]> chunks = nameChunks("", splitFile(is, MAX_CHUNK_SIZE_MB));
        if (chunks==null) return null;
        double size = chunks.values().stream().mapToLong(b -> b.length).sum()/((double)1024*1024);
        JSONObject jsonContent = new JSONObject();
        jsonContent.put("chunk_size", chunks.values().iterator().next().length);
        jsonContent.put("file_name", file.getName());
        jsonContent.put("size_mb",  size);
        jsonContent.put("n_chunks", chunks.size());
        jsonContent.put("file_type", fileType);
        jsonContent.put("checksum_md5", getMD5(chunks.values()));
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
        JSONObject json = JSONUtils.parse(res);
        if (json!=null) {
            if (pcb!=null) pcb.setMessage("storing file: size: "+String.format("%.2f", size)+"Mb # chunks: "+chunks.size());
            String fileID = (String) json.get("id");
            String gist_url = BASE_URL + "/gists/" + fileID;
            List<Map.Entry<String, byte[]>> chunkList = new ArrayList<>(chunks.entrySet());
            DefaultWorker.WorkerTask task = i -> {
                logger.debug("storing chunk {}/{}", i + 1, chunks.size());
                storeBytes(gist_url, chunkList.get(i).getKey(), chunkList.get(i).getValue(), auth);
                //sleep(1);
                return String.format("%.2f", chunkList.get(i).getValue().length / (1024d * 1024d)) + "MB stored";
            };
            if (background) {
                DefaultWorker w = DefaultWorker.execute(task, chunks.size(), pcb).appendEndOfWork(callback==null?null:()->callback.accept(fileID));
                return new Pair<>(fileID, w);
            }
            else {
                DefaultWorker.executeInForeground(task, chunks.size());
                if (callback!=null) callback.accept(fileID);
                return new Pair<>(fileID, null);
            }
        } else return null;
    }
    public void repairFile(File file, UserAuth auth, boolean background, ProgressLogger pcb) throws IOException {
        Map<String, byte[]> chunks;
        try {
            InputStream is;
            if (file.isDirectory()) { // start by zipping the file
                if (pcb!=null) pcb.setMessage("zipping directgory...");
                is = ZipUtils.zipFile(file).getInputStream();
            } else is = new FileInputStream(file);
            chunks = nameChunks("", splitFile(is, (double)chunkSize/(1024*1024)));
        } catch (IOException e) {
            logger.debug("could not read file", e);
            throw e;
        }
        double size = chunks.values().stream().mapToLong(b -> b.length).sum()/((double)1024*1024);
        assert chunks.size() == this.chunksURL.size();
        assert size == this.sizeMb;

        String gist_url = BASE_URL + "/gists/" + id;
        List<String> chunkNames = new ArrayList<>(chunks.keySet());
        DefaultWorker.WorkerTask task = idx -> {
            String chunk = chunkNames.get(idx);
            try {
                retrieveChunk(chunk);
            } catch (IOException e) {
                logger.debug("invalid chunk found: {} reason: {}", chunk, e.getMessage());
                if (pcb!=null) pcb.setMessage("Invalid Chunk found: "+chunk+ " Problem: "+e.getMessage());
                logger.debug("storing chunk {} ({}/{})", chunk, idx + 1, chunks.size());
                storeBytes(gist_url, chunk, chunks.get(chunk), auth);
                sleep(1);
                return String.format("%.2f", chunks.get(chunk).length / (1024d * 1024d)) + "MB stored";
            } return chunk+" is valid";
        };
        if (background) DefaultWorker.execute(task, chunks.size(), pcb);
        else DefaultWorker.executeInForeground(task, chunks.size());
    }
    protected static boolean storeBytes(String gist_url, String name, byte[] bytes, UserAuth auth) throws IOException {
        String content = JSONQuery.encodeJSONBase64(name, bytes); // null will set empty content -> remove the file
        return storeBytesTryout(gist_url, content, auth, JSONQuery.MAX_TRYOUTS);
    }
    protected static boolean storeBytesTryout(String gist_url, String content, UserAuth auth, int remainingTryouts) throws IOException {
        JSONQuery q = new JSONQuery(gist_url, JSONQuery.REQUEST_PROPERTY_GITHUB_BASE64).method(JSONQuery.METHOD.PATCH).authenticate(auth).setBody(content);
        try {
            String answer = q.fetch();
            return answer!=null;
        } catch (IOException e) {
            if (e.getMessage().contains("HTTP response code: 50") && remainingTryouts>0) {
                logger.debug("error {} -> try again, remaining tryouts: {}", e.getMessage(), remainingTryouts);
                sleep(5000);
                storeBytesTryout(gist_url, content, auth, remainingTryouts-1);
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

    public static Map<String, byte[]> nameChunks(String prefix, List<byte[]> chunks) {
        if (chunks==null) return null;
        int total = chunks.size();
        int numberOfZeros = (int)Math.log(total) + 1;
        Map<String, byte[]> res = new TreeMap<>();
        for (int i = 0; i<chunks.size(); ++i) res.put(chunkName(prefix, i, numberOfZeros), chunks.get(i));
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

    private static String chunkName(String name, int i, int paddingSize) {
        String res = "chunk_" + Utils.formatInteger(paddingSize, i);
        //String res = "chunk_"+i;
        return name==null || name.length()==0 ?  res : name+"_"+res;
    }

    private static int getChunkIndex(String chunkName) {
        int i = chunkName.indexOf("chunk_")+6;
        return Integer.parseInt(chunkName.substring(i));
    }
}
