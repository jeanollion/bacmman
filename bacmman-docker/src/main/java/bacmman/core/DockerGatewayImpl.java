package bacmman.core;

import bacmman.core.DockerGateway;
import bacmman.docker.ExecResultCallback;
import bacmman.docker.PullImageResultCallback;
import bacmman.ui.PropertyUtils;
import bacmman.utils.SymetricalPair;
import bacmman.utils.Triplet;
import bacmman.utils.Utils;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.*;
import com.github.dockerjava.api.model.*;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.transport.DockerHttpClient;
import com.github.dockerjava.zerodep.ZerodepDockerHttpClient;
//import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import net.imagej.ops.Ops;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class DockerGatewayImpl implements DockerGateway {
    private static final Logger logger = LoggerFactory.getLogger(DockerGatewayImpl.class);
    static String defaultHostUnix = "unix:///var/run/docker.sock";
    static String defaultHostWindows = "docker.for.unix.localhost";
    DockerClient dockerClient;
    DockerGatewayImpl() {
        String dockerHost = PropertyUtils.get("docker_host", Utils.isUnix() ? defaultHostUnix : defaultHostWindows);
        DockerClientConfig config = DefaultDockerClientConfig.createDefaultConfigBuilder()
            //.withDockerHost(dockerHost)
            .withDockerTlsVerify(false)
            .build();
        //DockerHttpClient httpClient = new ApacheDockerHttpClient.Builder()
        DockerHttpClient httpClient = new ZerodepDockerHttpClient.Builder()
                .dockerHost(config.getDockerHost())
                .sslConfig(config.getSSLConfig())
                .maxConnections(100)
                .connectionTimeout(Duration.ofSeconds(30))
                .responseTimeout(Duration.ofSeconds(45))
                .build();

        dockerClient = DockerClientImpl.getInstance(config, httpClient);
    }
    @Override
    public Stream<String> listImages() {
        return dockerClient.listImagesCmd().exec().stream().filter(i -> i.getRepoTags().length>0).map(i -> i.getRepoTags()[0]);
    }

    @Override
    public String buildImage(String tag, File dockerFile, File baseDir, Consumer<String> stdOut, Consumer<String> stdErr) {
        bacmman.docker.BuildImageResultCallback buildCb = dockerClient.buildImageCmd(dockerFile)
                .withBaseDirectory(baseDir)
                .withTags(Collections.singleton(tag)).withPull(true).exec(new bacmman.docker.BuildImageResultCallback(stdOut, stdErr));
        return buildCb.awaitImageId();
    }

    @Override
    public boolean pullImage(String image, String version, Consumer<String> stdOut, Consumer<String> stdErr) {
        try {
            if (image.contains(":")) {
                String[] split = image.split(":");
                image = split[0];
                if (version==null) version = split[1];
            }
            PullImageCmd cmd = dockerClient.pullImageCmd(image);
            if (version!=null) cmd = cmd.withTag(version);
            cmd.exec(new PullImageResultCallback(stdOut, stdErr)).awaitCompletion();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        List<Image> images = dockerClient.listImagesCmd().withImageNameFilter(image).exec();
        return !images.isEmpty();
    }

    @Override
    public Stream<SymetricalPair<String>> listContainers() {
        return dockerClient.listContainersCmd().exec().stream().map(c -> new SymetricalPair<>(c.getImage(), c.getId()));
    }

    @Override
    public Stream<SymetricalPair<String>> listContainers(String imageId) {
        return dockerClient.listContainersCmd().exec().stream().filter(c -> c.getImageId().equals(imageId)).map(c -> new SymetricalPair<>(c.getImage(), c.getId()));
    }

    @SafeVarargs
    @Override
    public final String createContainer(String image, boolean tty, int[] gpuIds, SymetricalPair<String>... mountDirs) {
        HostConfig hostConfig = HostConfig.newHostConfig();
        if (gpuIds!=null) {
            DeviceRequest dr = new DeviceRequest().withDriver("nvidia")
                    .withCapabilities(Collections.singletonList(Collections.singletonList("gpu")));
            if (gpuIds.length>0) dr = dr.withDeviceIds(Arrays.stream(gpuIds).boxed().map(s->""+s).collect(Collectors.toList()));
            hostConfig = hostConfig.withDeviceRequests(Collections.singletonList(dr));
        }
        if (mountDirs!=null && mountDirs.length>0) {
            List<Mount> mounts = Arrays.stream(mountDirs).map(p -> new Mount().withSource(p.key).withTarget(p.value).withType(MountType.BIND)).collect(Collectors.toList());
            hostConfig = hostConfig.withMounts(mounts);
        }
        CreateContainerCmd cmd = dockerClient.createContainerCmd(image)
           .withHostConfig(hostConfig)
           .withTty(tty);
        if (Utils.isUnix()) cmd = cmd.withUser(new com.sun.security.auth.module.UnixSystem().getUid()+"");
        CreateContainerResponse container = cmd.exec();
        logger.debug("create container response: {}", container);
        try {
            dockerClient.startContainerCmd(container.getId()).exec();
        } catch (RuntimeException e) {
            if (e.getMessage().contains("nvml error: driver not loaded: unknown")) {
                throw new RuntimeException("GPU not found: ");
            } else throw e;
        }
        return container.getId();
    }

    @Override
    public void exec(String containerId, Consumer<String> stdOut, Consumer<String> stdErr, boolean remove, String... cmds) throws InterruptedException {
        ExecCreateCmd cmd = dockerClient.execCreateCmd(containerId)
            .withCmd(cmds)
            .withAttachStdout(stdOut!=null)
            .withAttachStderr(stdErr!=null);
        ExecCreateCmdResponse createCmdResponse = cmd.exec();
        try {
            dockerClient.execStartCmd(createCmdResponse.getId()).exec(new ExecResultCallback(stdOut, stdErr)).awaitCompletion();
        } finally {
            if (remove) stopContainer(containerId);
        }
    }
    @Override
    public void stopContainer(String containerId) {
        dockerClient.stopContainerCmd(containerId).exec();
        dockerClient.removeContainerCmd(containerId).withForce(true).exec();
    }
}
