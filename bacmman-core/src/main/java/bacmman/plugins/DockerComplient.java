package bacmman.plugins;

public interface DockerComplient {
    String getDockerImageName();
    default int[] minimalVersion() {return null;}
    default int[] maximalVersion() {return null;}
}
