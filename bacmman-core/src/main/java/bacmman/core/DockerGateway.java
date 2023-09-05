package bacmman.core;

import bacmman.utils.SymetricalPair;

import java.io.File;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

public interface DockerGateway {
    Stream<String> listImages();
    String buildImage(String tag, File dockerFile, Consumer<String> stdOut, Consumer<String> stdErr);
    boolean pullImage(String image, String version, Consumer<String> stdOut, Consumer<String> stdErr);
    Stream<SymetricalPair<String>> listContainers();
    Stream<SymetricalPair<String>> listContainers(String imageId);
    String createContainer(String image, boolean tty, int[] gpuIds, SymetricalPair<String>... mountDirs);
    void exec(String containerId, Consumer<String> stdOut, Consumer<String> stdErr, boolean remove, String... cmds) throws InterruptedException;
    void stopContainer(String containerId);
}
