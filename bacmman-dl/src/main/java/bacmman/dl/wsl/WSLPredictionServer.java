package bacmman.dl.wsl;

import bacmman.core.Core;
import bacmman.data_structure.dao.UUID;
import bacmman.dl.wsl.ArrayCodec.ArraySpec;
import bacmman.dl.wsl.ArrayCodec.DType;
import bacmman.utils.JSONUtils;
import bacmman.utils.Utils;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.ParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Python prediction server (resource /wsl/bacmman_dl_server.py) running in a WSL distribution, started through wsl.exe.
 * Requests and results are exchanged through the process' stdin / stdout (see the protocol in the python script):
 * no file is written, file sharing between Windows and WSL being slow.
 * Responses are read by a background thread, so that a request can be sent while the previous one is being processed.
 * <p>
 * For development on another system, the system property {@value #COMMAND_PROPERTY} replaces the command that starts
 * python (space-separated, e.g. "docker run -i --rm --gpus all -v /path:/path image python"): paths are then not converted.
 */
public class WSLPredictionServer implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(WSLPredictionServer.class);
    public static final String PYTHON = "/opt/bacmman-dl/venv/bin/python";
    public static final String COMMAND_PROPERTY = "bacmman.wslengine.command";
    static final String SCRIPT_RESOURCE = "/wsl/bacmman_dl_server.py";
    static final String USER_LOG_PREFIX = "BACMMAN> ";
    static final String[] IGNORED_STDERR = {"cpu_feature_guard", "TF_ENABLE_ONEDNN_OPTS", "successful NUMA node read", "Unable to register cu", "computation placer already registered", "oneDNN custom operations", "WARNING: All log messages before absl::InitializeLog"};
    static final Object EOF = new Object();
    static final Set<WSLPredictionServer> RUNNING = Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    /**
     * Stops all servers running in a distribution (e.g. before updating it). Engines restart their server at next use.
     */
    public static void closeAll(String distribution) {
        for (WSLPredictionServer s : new ArrayList<>(RUNNING)) {
            if (s.distribution.equalsIgnoreCase(distribution)) s.close();
        }
    }

    public static class Message {
        public final JSONObject header;
        public final List<ArraySpec> specs;
        public final List<byte[]> data;
        Message(JSONObject header, List<ArraySpec> specs, List<byte[]> data) {
            this.header = header;
            this.specs = specs;
            this.data = data;
        }
    }

    final String distribution;
    final Process process;
    final OutputStream out;
    final BlockingQueue<Object> responses = new LinkedBlockingQueue<>();
    final Deque<String> stderrTail = new ArrayDeque<>();
    final Path script;
    final Thread shutdownHook;
    volatile boolean closing;

    /**
     * @param gpuList comma-separated GPU ids (nvidia-smi order); empty: CPU
     */
    public WSLPredictionServer(String distribution, String gpuList, boolean allowGrowth) throws IOException {
        this.distribution = distribution;
        this.script = extractScript();
        List<String> command = new ArrayList<>();
        String override = System.getProperty(COMMAND_PROPERTY);
        if (override != null && !override.trim().isEmpty()) {
            command.addAll(Arrays.asList(override.trim().split("\\s+")));
        } else {
            command.addAll(Arrays.asList("wsl.exe", "-d", distribution, "--cd", "/", "--exec", PYTHON));
        }
        command.add(translatePath(script.toString()));
        command.add("--gpus=" + (gpuList == null ? "" : gpuList.replace(" ", "")));
        if (allowGrowth) command.add("--allow-growth");
        logger.debug("starting WSL prediction server: {}", command);
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.environment().put("WSL_UTF8", "1"); // wsl.exe messages in UTF-8 instead of UTF-16
        try {
            process = pb.start();
        } catch (IOException e) {
            deleteScript();
            if (Utils.isWindows()) throw new IOException("Could not start wsl.exe: is WSL installed? (" + e.getMessage() + ")", e);
            else throw e;
        }
        out = new BufferedOutputStream(process.getOutputStream(), 1 << 16);
        // if BACMMAN exits without closing the engine, stop the server so that it releases the GPU
        shutdownHook = new Thread(process::destroyForcibly, "WSLEngine-shutdown");
        Runtime.getRuntime().addShutdownHook(shutdownHook);
        RUNNING.add(this);
        Thread reader = new Thread(this::readResponses, "WSLEngine-reader");
        reader.setDaemon(true);
        reader.start();
        Thread errReader = new Thread(this::readStdErr, "WSLEngine-stderr");
        errReader.setDaemon(true);
        errReader.start();
    }

    static Path extractScript() throws IOException {
        Path dir = Paths.get(System.getProperty("java.io.tmpdir"), "bacmman-wsl");
        Files.createDirectories(dir);
        Path target = dir.resolve("bacmman_dl_server_" + UUID.get().toHexString() + ".py");
        try (InputStream is = WSLPredictionServer.class.getResourceAsStream(SCRIPT_RESOURCE)) {
            if (is == null) throw new IOException("resource not found: " + SCRIPT_RESOURCE);
            Files.copy(is, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return target;
    }

    /**
     * Converts a Windows path to the corresponding path inside WSL (C:\a\b -> /mnt/c/a/b).
     */
    public String translatePath(String path) {
        String override = System.getProperty(COMMAND_PROPERTY);
        if (override != null && !override.trim().isEmpty()) return path;
        return toWSLPath(path, distribution);
    }

    public static String toWSLPath(String path, String distribution) {
        String p = path.replace('\\', '/');
        if (p.matches("^[A-Za-z]:(/.*)?$")) return "/mnt/" + Character.toLowerCase(p.charAt(0)) + (p.length() > 2 ? p.substring(2) : "");
        Matcher m = Pattern.compile("^//(wsl\\.localhost|wsl\\$)/([^/]+)(/.*)?$", Pattern.CASE_INSENSITIVE).matcher(p);
        if (m.matches()) {
            if (m.group(2).equalsIgnoreCase(distribution)) return m.group(3) == null ? "/" : m.group(3);
            throw new IllegalArgumentException("Path " + path + " is in another WSL distribution (" + m.group(2) + "): copy it to a local drive");
        }
        if (p.startsWith("/")) return p; // already a linux path
        throw new IllegalArgumentException("Path " + path + " cannot be accessed from WSL (network path?): copy it to a local drive");
    }

    // ------------------------------------------------------------------------------------------- sending
    public void send(JSONObject header, List<ArraySpec> specs, List<byte[]> data) throws IOException {
        JSONObject h = new JSONObject();
        h.putAll(header);
        if (specs != null && !specs.isEmpty()) {
            JSONArray arrays = new JSONArray();
            for (ArraySpec s : specs) {
                JSONObject a = new JSONObject();
                a.put("name", s.name);
                a.put("dtype", s.dtype.name);
                JSONArray shape = new JSONArray();
                for (long l : s.shape) shape.add(l);
                a.put("shape", shape);
                arrays.add(a);
            }
            h.put("arrays", arrays);
        }
        byte[] headerBytes = h.toJSONString().getBytes(StandardCharsets.UTF_8);
        synchronized (out) {
            writeIntLE(out, headerBytes.length);
            out.write(headerBytes);
            if (data != null) for (byte[] d : data) out.write(d);
            out.flush();
        }
    }

    static void writeIntLE(OutputStream os, int v) throws IOException {
        os.write(v & 0xFF);
        os.write((v >>> 8) & 0xFF);
        os.write((v >>> 16) & 0xFF);
        os.write((v >>> 24) & 0xFF);
    }

    // ------------------------------------------------------------------------------------------- receiving
    /**
     * Waits for the next response.
     * @throws RuntimeException if the server reported an error, stopped, or did not answer within the timeout
     */
    public Message receive(double timeoutSeconds, String context) {
        Object r;
        try {
            r = timeoutSeconds > 0 ? responses.poll((long) (timeoutSeconds * 1000), TimeUnit.MILLISECONDS) : responses.take();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while waiting for the WSL prediction server", e);
        }
        if (r == null) throw new RuntimeException("Time out (" + timeoutSeconds + "s) while " + context + " (WSL prediction server)");
        if (r == EOF) throw new RuntimeException("The WSL prediction server stopped while " + context + ". " + diagnostic());
        if (r instanceof Throwable) throw new RuntimeException("Error communicating with the WSL prediction server while " + context, (Throwable) r);
        Message m = (Message) r;
        if ("error".equals(m.header.get("status"))) {
            Object tb = m.header.get("traceback");
            if (tb != null) logger.error("WSL prediction server error traceback: {}", tb);
            String message = String.valueOf(m.header.get("message"));
            if (message.contains("ResourceExhausted") || message.toLowerCase().contains("out of memory")) message = "out of GPU memory: reduce the Batch Size or select Half Precision (" + message.split("\n")[0] + ")";
            throw new RuntimeException("WSL prediction server error while " + context + ": " + message);
        }
        return m;
    }

    void readResponses() {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(process.getInputStream(), 1 << 16))) {
            while (true) {
                int b0 = in.read();
                if (b0 < 0) break;
                int b1 = in.readUnsignedByte(), b2 = in.readUnsignedByte(), b3 = in.readUnsignedByte();
                int size = b0 | (b1 << 8) | (b2 << 16) | (b3 << 24);
                if (size <= 0 || size > (1 << 24)) { // not the protocol: e.g. an error message of wsl.exe
                    recordTextOutput(new byte[]{(byte) b0, (byte) b1, (byte) b2, (byte) b3}, in);
                    break;
                }
                byte[] headerBytes = new byte[size];
                in.readFully(headerBytes);
                JSONObject header = JSONUtils.parse(new String(headerBytes, StandardCharsets.UTF_8));
                List<ArraySpec> specs = new ArrayList<>();
                List<byte[]> data = new ArrayList<>();
                JSONArray arrays = (JSONArray) header.get("arrays");
                if (arrays != null) {
                    for (Object o : arrays) {
                        JSONObject a = (JSONObject) o;
                        JSONArray shapeJ = (JSONArray) a.get("shape");
                        long[] shape = new long[shapeJ.size()];
                        for (int i = 0; i < shape.length; ++i) shape[i] = ((Number) shapeJ.get(i)).longValue();
                        ArraySpec spec = new ArraySpec((String) a.get("name"), DType.fromName((String) a.get("dtype")), shape);
                        long n = spec.byteCount();
                        if (n > Integer.MAX_VALUE - 16) throw new IOException("Prediction too large to be transferred (" + spec + "): reduce the batch size");
                        byte[] d = new byte[(int) n];
                        in.readFully(d);
                        specs.add(spec);
                        data.add(d);
                    }
                }
                responses.add(new Message(header, specs, data));
            }
        } catch (EOFException e) {
            // process ended
        } catch (IOException | ParseException | RuntimeException e) {
            if (!closing) {
                logger.error("error reading WSL prediction server output", e);
                responses.add(e);
            }
        }
        responses.add(EOF);
    }

    void recordTextOutput(byte[] start, InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bos.write(start);
        byte[] buf = new byte[8192];
        int r = in.read(buf);
        if (r > 0) bos.write(buf, 0, r);
        String text = new String(bos.toByteArray(), StandardCharsets.UTF_8).replace("\u0000", "");
        logger.error("unexpected output of the WSL prediction server: {}", text);
        synchronized (stderrTail) {
            for (String l : text.split("\r?\n")) if (!l.trim().isEmpty()) stderrTail.addLast(l.trim());
        }
    }

    void readStdErr() {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                String l = line.replace("\u0000", "").trim(); // wsl.exe may write UTF-16 despite WSL_UTF8
                if (l.isEmpty()) continue;
                synchronized (stderrTail) {
                    stderrTail.addLast(l);
                    while (stderrTail.size() > 60) stderrTail.removeFirst();
                }
                if (l.startsWith(USER_LOG_PREFIX)) {
                    String msg = l.substring(USER_LOG_PREFIX.length());
                    logger.info("WSLEngine: {}", msg);
                    Core.userLog(msg);
                } else if (Arrays.stream(IGNORED_STDERR).noneMatch(l::contains)) {
                    logger.debug("WSL prediction server: {}", l);
                }
            }
        } catch (IOException e) {
            // process ended
        }
    }

    /**
     * @return explanation of why the server stopped, from its last error output
     */
    public String diagnostic() {
        String tail;
        synchronized (stderrTail) {
            tail = String.join("\n", stderrTail);
        }
        String lower = tail.toLowerCase();
        if (lower.contains("there is no distribution with the supplied name") || lower.contains("wsl_e_distro_not_found"))
            return "The WSL distribution '" + distribution + "' is not installed: right-click on WSLEngine > Check / Install WSL Environment (see BACMMAN wiki, Installation page).";
        if (lower.contains("no such file or directory") && lower.contains(PYTHON))
            return "The python environment was not found in the WSL distribution '" + distribution + "': right-click on WSLEngine > Check / Install WSL Environment.";
        if (lower.contains("wsl") && (lower.contains("not installed") || lower.contains("not enabled")))
            return "WSL is not installed or not enabled.";
        String[] lines = tail.split("\n");
        return "Last messages:\n" + String.join("\n", Arrays.copyOfRange(lines, Math.max(0, lines.length - 15), lines.length));
    }

    public boolean isAlive() {
        return process.isAlive();
    }

    @Override
    public void close() {
        closing = true;
        RUNNING.remove(this);
        try {
            JSONObject exit = new JSONObject();
            exit.put("cmd", "exit");
            send(exit, null, null);
        } catch (IOException e) {
            // already stopped
        }
        try {
            out.close();
        } catch (IOException e) {
        }
        try {
            if (!process.waitFor(10, TimeUnit.SECONDS)) process.destroyForcibly();
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
        try {
            Runtime.getRuntime().removeShutdownHook(shutdownHook);
        } catch (IllegalStateException e) {
            // JVM already shutting down
        }
        deleteScript();
    }

    void deleteScript() {
        try {
            if (script != null) Files.deleteIfExists(script);
        } catch (IOException e) {
        }
    }
}
