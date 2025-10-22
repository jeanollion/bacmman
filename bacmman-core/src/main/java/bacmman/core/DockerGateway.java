package bacmman.core;

import bacmman.utils.Pair;
import bacmman.utils.UnaryPair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public interface DockerGateway {
    Logger logger = LoggerFactory.getLogger(DockerGateway.class);
    Stream<String[]> listImages();
    String buildImage(String tag, File dockerFile, Consumer<String> stdOut, Consumer<String> stdErr, BiConsumer<Integer, Integer> stepProgress);
    boolean pullImage(String image, String version, Consumer<String> stdOut, Consumer<String> stdErr, BiConsumer<Integer, Integer> stepProgress);
    boolean removeImage(String imageId);
    Stream<DockerContainer> listContainers();
    String createContainer(String image, double shmSizeGb, int[] gpuIds, List<UnaryPair<Integer>> portBindingHostToContainer, List<UnaryPair<String>> environmentVariables, UnaryPair<String>... mountDirs);
    void exec(String containerId, Consumer<String> stdOut, Consumer<String> stdErr, boolean remove, String... cmds) throws InterruptedException;

    /**
     * log container
     * @param containerId
     * @param stopLogging condition to stop logging: input = previous logs, current log
     * @param stdErr include stdErr in logs
     * @param stdOut include stdOut in logs
     * @return logs
     * @throws InterruptedException
     */
    List<String> logContainer(String containerId, BiPredicate<List<String>, String> stopLogging, boolean stdOut, boolean stdErr) throws InterruptedException;
    void stopContainer(String containerId);
    void authenticate(String user, String token);
    static Consumer<String> applyToSplit(Consumer<String> consumer) {
        return message -> {
            if (message == null || message.isEmpty()) return;
            if (message.contains("\n")) {
                String[] split = message.split("\n");
                for (String s : split) consumer.accept(s);
            } else consumer.accept(message);
        };
    }

    static Consumer<String> filterOutANSIEscapeSequences(Consumer<String> consumer) {
        return message -> {
            if (message != null && !message.isEmpty()) {
                message = message.replaceAll("\\033\\[\\d+[;\\d+]*m", "");
            }
            consumer.accept(message);
        };
    }

    static Comparator<int[]> versionComparator() {
        return (v1, v2) -> {
            for (int i = 0; i<Math.min(v1.length, v2.length); ++i) {
                int c = Integer.compare(v1[i], v2[i]);
                if (c!=0) return c;
            }
            return Integer.compare(v1.length,v2.length); // if one is longer considered as later version
        };
    }

    static Comparator<String> tagComparator(){
        return (t1, t2) -> {
            Pair<String, int[]> v1 = parseVersion(t1);
            Pair<String, int[]> v2 = parseVersion(t2);
            if (v1.key != null || v2.key!=null) {
                if (v1.key==null) return -1;
                else if (v2.key == null) return 1;
                else {
                    int c = v1.key.compareTo(v2.key);
                    if (c!=0) return c;
                }
            }
            return versionComparator().compare(v1.value, v2.value);
        };
    }

    static Comparator<String> dockerFileComparator(){
        return (f1, f2) -> tagComparator().compare(formatDockerTag(f1), formatDockerTag(f2));
    }

    static Pair<String, int[]> parseVersion(String tag) {
        // image name
        int i = tag.indexOf(':');
        if (i>=0) tag = tag.substring(i+1);
        // version prefix
        String versionPrefix = null;
        i = tag.indexOf('-');
        if (i>=0) {
            versionPrefix = tag.substring(0, i);
            tag = tag.substring(i+1);
        }
        int[] version = Arrays.stream(tag.split("\\.")).mapToInt(Integer::parseInt).toArray();
        return new Pair<>(versionPrefix, version);
    }

    static String parseImageName(String tag) {
        int i = tag.indexOf(':');
        if (i>=0) return tag.substring(0,i);
        else return tag;
    }

    static String formatDockerTag(String tag) {
        tag = tag.replace(".dockerfile", "");
        tag = tag.replace("--", ":");
        Pattern p = Pattern.compile("(?<=[0-9])-(?=[0-9])");
        Matcher m = p.matcher(tag);
        return m.replaceAll(".");
    }

    Pattern buildProgressPattern = Pattern.compile("^Step (\\d+)/(\\d+)");
    Pattern numberPattern = Pattern.compile("[+-]?\\d+(\\.\\d*)?([eE][+-]?\\d+)?");

    static int[] parseBuildProgress(String message) {
        if (message == null || message.isEmpty()) return null;
        Matcher m = buildProgressPattern.matcher(message);
        if (m.find()) {
            return parseProgress(message);
        } else {
            return null;
        }
    }
    static int[] parseProgress(String message) {
        Matcher m = numberPattern.matcher(message);
        m.find();
        int step = Integer.parseInt(m.group());
        m.find();
        int totalSteps = Integer.parseInt(m.group());
        return new int[]{step, totalSteps};
    }
    static boolean hasShm() {
        try {
            return Files.isDirectory(Paths.get("/dev/shm"));
        } catch (Exception e) {
            return false;
        }
    }
    class DockerContainer {
        final String id;
        final DockerImage image;
        final String state; // created, running, paused, restarting, exited, dead
        final List<UnaryPair<String>> mountsHostToContainer;
        final List<UnaryPair<Integer>> portsHostToContainer;
        public DockerContainer(String id, String imageID, String imageTag, String state, List<UnaryPair<String>> mountsHostToContainer, List<UnaryPair<Integer>> portsHostToContainer) {
            this.id = id;
            this.image = new DockerImage(imageTag, imageID);
            this.state = state;
            this.mountsHostToContainer = mountsHostToContainer;
            this.portsHostToContainer = portsHostToContainer;
        }

        public String getId() {
            return id;
        }

        public DockerImage getImage() {
            return image;
        }

        public boolean fromImage(String imageID) {
            return imageID.equals(this.image.imageID);
        }

        public boolean isRunning() {
            return state.equals("running");
        }

        public Stream<UnaryPair<String>> getMounts() {
            return mountsHostToContainer.stream().distinct();
        }

        public Stream<UnaryPair<Integer>> getPorts() {
            return portsHostToContainer.stream().distinct();
        }

        public String toString() {
            int[] ports = getPorts().filter(Objects::nonNull).mapToInt(i->i.key).toArray();
            String portString = ports == null ? "" : (ports.length == 1 ? ",port="+ports[0] : ",ports="+ Arrays.toString(ports));
            return image.getTag() + " id="+id + " (" + state + portString + ")";
        }

        public static String parseId(String containerString) {
            if (containerString == null) return null;
            int idx = containerString.indexOf("id=");
            if (idx>0) containerString =  containerString.substring(idx+3);
            idx = containerString.indexOf(" (");
            if (idx > 0 ) containerString = containerString.substring(0, idx);
            return containerString;
        }
    }

    class DockerImage implements Comparable<DockerImage> {
        String imageName, version, versionPrefix, fileName;
        String imageID;
        int[] versionNumber;

        public DockerImage(String tag, String imageID) {
            this(tag, imageID, null);
        }

        public DockerImage(String tag, String imageID, String fileName) {
            init(tag);
            this.imageID= imageID;
            this.fileName = fileName;
        }

        public DockerImage setFileName(String fileName) {
            this.fileName = fileName;
            return this;
        }

        protected void init(String tag) {
            int i = tag.indexOf(':');
            if (i>=0) {
                imageName = tag.substring(0, i);
                version = tag.substring(i+1);
                try {
                    Pair<String, int[]> t = parseVersion(tag);
                    versionPrefix = t.key;
                    versionNumber = t.value;
                } catch (NumberFormatException e) {
                    versionNumber = new int[0];
                }
            } else {
                imageName = tag;
                version = "";
                versionNumber = new int[0];
            }
        }

        public boolean isInstalled() {
            return imageID != null;
        }

        public String getImageName() {
            return imageName;
        }

        public String getFileName() {
            return fileName;
        }

        public String getVersion() {
            return version;
        }

        public String getVersionPrefix() {
            return versionPrefix;
        }

        public String getId() { return imageID; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o instanceof DockerImage) {
                DockerImage that = (DockerImage) o;
                return Objects.equals(imageName, that.imageName) && Objects.equals(version, that.version);
            } else if (o instanceof String) {
                return ((String) o).replace(" (not installed)", "").equals(getTag());
            } else return false;
        }

        @Override
        public int hashCode() {
            return Objects.hash(imageName, version);
        }
        public String getTag() {
            return imageName+":"+version;
        }

        @Override
        public String toString() {
            return getTag() + (isInstalled() ? "" : " (not installed)");
        }

        public static String parseTagString(String string) {
            if (string == null) return null;
            int idx = string.indexOf(" ");
            if (idx > 0) return string.substring(0, idx);
            else return string;
        }

        public int compareVersionNumber(int[] version) {
            return versionComparator().compare(versionNumber, version);
        }

        @Override
        public int compareTo(DockerImage o) {
            int c = imageName.compareTo(o.imageName);
            if (c==0) {
                return -versionComparator().compare(versionNumber, o.versionNumber);
            } else return c;
        }
    }
}
