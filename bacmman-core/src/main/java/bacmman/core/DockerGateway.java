package bacmman.core;

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
    String createContainer(String image, int shmSizeMb, int[] gpuIds, UnaryPair<String>... mountDirs);
    void exec(String containerId, Consumer<String> stdOut, Consumer<String> stdErr, boolean remove, String... cmds) throws InterruptedException;
    void stopContainer(String containerId);
    static Consumer<String> applyToSplit(Consumer<String> consumer) {
        return message -> {
            if (message == null || message.isEmpty()) return;
            if (message.contains("\n")) {
                String[] split = message.split("\n");
                for (String s : split) consumer.accept(s);
            } else consumer.accept(message);
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
        return (t1, t2) -> versionComparator().compare(parseVersion(t1), parseVersion(t2));
    }
    static Comparator<String> dockerFileComparator(){
        return (f1, f2) -> tagComparator().compare(formatDockerTag(f1), formatDockerTag(f2));
    }
    static int[] parseVersion(String tag) {
        int i = tag.indexOf(':');
        if (i>=0) tag = tag.substring(i+1);
        i = tag.indexOf('-');
        if (i>=0) tag = tag.substring(i+1);
        return Arrays.stream(tag.split("\\.")).mapToInt(Integer::parseInt).toArray();
    }
    static String formatDockerTag(String tag) {
        tag = tag.replace(".dockerfile", "");
        tag = tag.replace("--", ":");
        Pattern p = Pattern.compile("(?<=[0-9])-(?=[0-9])");
        Matcher m = p.matcher(tag);
        return m.replaceAll(".");
    }

}
