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
import com.github.dockerjava.api.exception.ConflictException;
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
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.function.BiConsumer;
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
        try {
            return dockerClient.listImagesCmd().exec().stream().filter(i -> i.getRepoTags().length > 0).map(i -> i.getRepoTags()[0]);
        } catch (RuntimeException e ) {
            if (e.getMessage().startsWith("java.nio.file.NoSuchFileException")) {
                logger.error("Could not connect with Docker. "+(Utils.isWindows()?"Is Docker started ? " : "Is Docker installed ?"));
            }
            throw e;
        }
    }

    @Override
    public String buildImage(String tag, File dockerFile, Consumer<String> stdOut, Consumer<String> stdErr, BiConsumer<Integer, Integer> stepProgress) {
        bacmman.docker.BuildImageResultCallback buildCb = dockerClient.buildImageCmd(dockerFile)
                .withTags(Collections.singleton(tag)).withPull(true)
                .exec(new bacmman.docker.BuildImageResultCallback(DockerGateway.applyToSplit(stdOut), stdErr, stepProgress));
        return buildCb.awaitImageId();
    }

    @Override
    public boolean pullImage(String image, String version, Consumer<String> stdOut, Consumer<String> stdErr, BiConsumer<Integer, Integer> stepProgress) {
        try {
            if (image.contains("--")) {
                String[] split = image.split("--");
                image = split[0];
                if (version==null) version = split[1];
            }
            PullImageCmd cmd = dockerClient.pullImageCmd(image);
            if (version!=null) cmd = cmd.withTag(version);
            cmd.exec(new PullImageResultCallback(DockerGateway.applyToSplit(stdOut), stdErr, stepProgress)).awaitCompletion();
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
    public final String createContainer(String image, int shmSizeMb, int[] gpuIds, SymetricalPair<String>... mountDirs) {
        HostConfig hostConfig = HostConfig.newHostConfig()
                .withAutoRemove(true);
                //.withRestartPolicy(RestartPolicy.noRestart())
                //.withDevices(Collections.EMPTY_LIST)
                //.withBlkioDeviceReadBps(Collections.emptyList()).withBlkioDeviceWriteBps(Collections.emptyList()).withBlkioDeviceWriteIOps(Collections.emptyList()).withBlkioDeviceReadIOps(Collections.emptyList()).withBlkioWeightDevice(Collections.emptyList());
        if (gpuIds!=null) {
            DeviceRequest dr = new DeviceRequest().withDriver("nvidia")
                    .withCapabilities(Collections.singletonList(Collections.singletonList("gpu"))).withOptions(Collections.emptyMap());
            if (gpuIds.length>0) dr = dr.withDeviceIds(Arrays.stream(gpuIds).boxed().map(s->""+s).collect(Collectors.toList()));
            hostConfig = hostConfig.withDeviceRequests(Collections.singletonList(dr));//.withRuntime("nvidia"); // not working under windows ?
        }
        hostConfig = hostConfig.withShmSize((long)shmSizeMb * 1048576L);
        if (mountDirs!=null && mountDirs.length>0) {
            //List<Mount> mounts = Arrays.stream(mountDirs).map(p -> new Mount().withSource(p.key).withTarget(p.value).withType(MountType.BIND)).collect(Collectors.toList());
            //hostConfig = hostConfig.withMounts(mounts);
            Bind[] binds = Arrays.stream(mountDirs).map(p -> new Bind(p.key, new Volume(p.value))).toArray(Bind[]::new);
            hostConfig = hostConfig.withBinds(binds);
        }

        CreateContainerCmd cmd = dockerClient.createContainerCmd(image)
           .withHostConfig(hostConfig)
           .withTty(true);
        if (Utils.isUnix()) {
            int uid = Utils.getUID();
            logger.debug("Unix UID: {}", uid);
            if (uid>=0) cmd = cmd.withUser(uid+":"+uid);
        }
        CreateContainerResponse container = cmd.exec();
        logger.debug("create container response: {}", container);
        try {
            dockerClient.startContainerCmd(container.getId()).exec();
        } catch (RuntimeException e) {
            if (e.getMessage().contains("nvml error: driver not loaded: unknown")) {
                throw new RuntimeException("GPU not found: ", e);
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
            dockerClient.execStartCmd(createCmdResponse.getId()).exec(new ExecResultCallback(DockerGateway.applyToSplit(stdOut), stdErr)).awaitCompletion();
        } finally {
            if (remove) stopContainer(containerId);
        }
    }
    @Override
    public void stopContainer(String containerId) {
        dockerClient.stopContainerCmd(containerId).exec();
        try {
            dockerClient.removeContainerCmd(containerId).withForce(true).exec();
        } catch (ConflictException e) {
            if (!e.getMessage().contains("is already in progress")) throw e;
        }
    }

}
