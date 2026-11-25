package bacmman.dl.wsl;

import bacmman.data_structure.dao.UUID;
import bacmman.utils.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Status and installation of the WSL distribution used by WSLEngine.
 * The installer (resource /wsl/install-bacmman-wslengine.ps1) is bundled with BACMMAN, so that its version always
 * matches the engine. It is opened in a new console window, where the user follows the installation.
 */
public class WSLInstallation {
    private static final Logger logger = LoggerFactory.getLogger(WSLInstallation.class);
    static final String INSTALLER_RESOURCE = "/wsl/install-bacmman-wslengine.ps1";
    public static final int EXIT_OK = 0, EXIT_REBOOT = 3;

    public enum State {OK, NOT_INSTALLED, NO_WSL, ERROR}

    public static class Status {
        public final State state;
        public final String tensorflowVersion; // installed version, null if not installed
        public final String message;
        Status(State state, String tensorflowVersion, String message) {
            this.state = state;
            this.tensorflowVersion = tensorflowVersion;
            this.message = message;
        }
        @Override
        public String toString() {
            return state + (tensorflowVersion != null ? " tensorflow=" + tensorflowVersion : "") + (message != null ? " (" + message + ")" : "");
        }
    }

    /**
     * Reads the TensorFlow version installed in the distribution (from the package metadata: tensorflow is not imported,
     * this takes about a second once WSL is started).
     */
    public static Status getStatus(String distribution) {
        List<String> cmd = Arrays.asList("wsl.exe", "-d", distribution, "-u", "root", "--exec", WSLPredictionServer.PYTHON, "-c",
                "import importlib.metadata as m; print('TENSORFLOW=' + m.version('tensorflow'))");
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        pb.environment().put("WSL_UTF8", "1");
        String output;
        int exit;
        try {
            Process p = pb.start();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            Thread reader = new Thread(() -> {
                try (InputStream is = p.getInputStream()) {
                    byte[] buf = new byte[4096];
                    int r;
                    while ((r = is.read(buf)) > 0) bos.write(buf, 0, r);
                } catch (IOException e) { }
            });
            reader.setDaemon(true);
            reader.start();
            if (!p.waitFor(300, TimeUnit.SECONDS)) { // first start of WSL can be slow
                p.destroyForcibly();
                return new Status(State.ERROR, null, "WSL did not answer within 5 minutes");
            }
            reader.join(5000);
            exit = p.exitValue();
            output = new String(bos.toByteArray(), StandardCharsets.UTF_8).replace("\u0000", "");
        } catch (IOException e) {
            return new Status(State.NO_WSL, null, "wsl.exe not found: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Status(State.ERROR, null, "interrupted");
        }
        logger.debug("WSL status of {}: exit {} output: {}", distribution, exit, output);
        Matcher m = Pattern.compile("TENSORFLOW=(\\S+)").matcher(output);
        if (exit == 0 && m.find()) return new Status(State.OK, m.group(1), null);
        String lower = output.toLowerCase();
        if (lower.contains("wsl_e_distro_not_found") || lower.contains("there is no distribution") || lower.contains("no such file") || lower.contains("packagenotfounderror") || lower.contains("no package metadata"))
            return new Status(State.NOT_INSTALLED, null, null);
        if (lower.contains("not installed") || lower.contains("not enabled") || lower.contains("wsl --install"))
            return new Status(State.NO_WSL, null, output.trim());
        return new Status(State.ERROR, null, output.trim());
    }

    /**
     * Opens the installer in a new console window and waits until it is closed.
     * @param update also update Ubuntu and python packages
     * @param automatic no question in the installer window (WSL is updated if needed), and the window closes at the end unless the installation failed
     * @return exit code of the installer: {@link #EXIT_OK}, {@link #EXIT_REBOOT} (WSL was installed: restart Windows), other: error or canceled
     */
    public static int runInstaller(String distribution, String tensorflowVersion, boolean update, boolean automatic) throws IOException, InterruptedException {
        if (!Utils.isWindows()) throw new IOException("The WSL installer only runs on Windows");
        Path dir = Paths.get(System.getProperty("java.io.tmpdir"), "bacmman-wsl");
        Files.createDirectories(dir);
        Path script = dir.resolve("install-bacmman-wslengine_" + UUID.get().toHexString() + ".ps1");
        try (InputStream is = WSLInstallation.class.getResourceAsStream(INSTALLER_RESOURCE)) {
            if (is == null) throw new IOException("resource not found: " + INSTALLER_RESOURCE);
            Files.copy(is, script, StandardCopyOption.REPLACE_EXISTING);
        }
        try {
            // java starts processes without console window: "start /wait" opens one, in which the user follows the installation
            List<String> cmd = new ArrayList<>(Arrays.asList("cmd.exe", "/c", "start", "BACMMAN TensorFlow GPU (WSL) installer", "/wait",
                    "powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", script.toString(),
                    "-DistroName", distribution, "-TensorflowVersion", tensorflowVersion));
            if (automatic) cmd.addAll(Arrays.asList("-Quiet", "-PauseOnError"));
            else cmd.add("-PauseAtEnd");
            if (update) cmd.add("-Update");
            logger.debug("running WSL installer: {}", cmd);
            Process p = new ProcessBuilder(cmd).start();
            p.getInputStream().close();
            return p.waitFor();
        } finally {
            try {
                Files.deleteIfExists(script);
            } catch (IOException e) { }
        }
    }
}
