package bacmman.core;

import bacmman.utils.Pair;
import bacmman.utils.UnaryPair;

import java.io.File;
import java.util.Arrays;
import java.util.Comparator;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public interface DockerGateway {
    Stream<String> listImages();
    String buildImage(String tag, File dockerFile, Consumer<String> stdOut, Consumer<String> stdErr, BiConsumer<Integer, Integer> stepProgress);
    boolean pullImage(String image, String version, Consumer<String> stdOut, Consumer<String> stdErr, BiConsumer<Integer, Integer> stepProgress);
    Stream<UnaryPair<String>> listContainers();
    Stream<UnaryPair<String>> listContainers(String imageId);
    String createContainer(String image, double shmSizeGb, double memoryGb, int[] gpuIds, UnaryPair<String>... mountDirs);
    void exec(String containerId, Consumer<String> stdOut, Consumer<String> stdErr, boolean remove, String... cmds) throws InterruptedException;
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

    static int[] parseGPUList(String gpuList) {
        if (gpuList==null || gpuList.isEmpty()) return new int[0];
        String[] split = gpuList.split(",");
        return Arrays.stream(split).filter(s->!s.isEmpty()).mapToInt(Integer::parseInt).toArray();
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
}
