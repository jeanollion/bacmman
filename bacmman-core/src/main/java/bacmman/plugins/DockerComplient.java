package bacmman.plugins;

public interface DockerComplient {
    String getDockerImageName();
    String getVersionPrefix();
    default int[] minimalVersion() {return null;}
    default int[] maximalVersion() {return null;}
}
