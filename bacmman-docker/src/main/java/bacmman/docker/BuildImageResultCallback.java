package bacmman.docker;

import com.github.dockerjava.api.async.ResultCallbackTemplate;
import com.github.dockerjava.api.exception.DockerClientException;
import com.github.dockerjava.api.model.BuildResponseItem;
import com.github.dockerjava.api.model.ResponseItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public class BuildImageResultCallback extends ResultCallbackTemplate<com.github.dockerjava.api.command.BuildImageResultCallback, BuildResponseItem> {
    private static final Logger logger = LoggerFactory.getLogger(BuildImageResultCallback.class);
    private Consumer<String> stdout, stderr;

    public BuildImageResultCallback(Consumer<String> stdout, Consumer<String> stderr) {
        this.stdout = stdout;
        this.stderr = stderr;
    }

    protected String imageId;
    protected ResponseItem.ErrorDetail error;

    @Override
    public void onNext(BuildResponseItem item) {
        if (item.isBuildSuccessIndicated()) {
            this.imageId = item.getImageId();
        } else if (item.isErrorIndicated()) {
            if (this.stderr!=null) this.stderr.accept(item.getErrorDetail().getMessage());
            error = item.getErrorDetail();
        } else {
            if (this.stdout!=null) this.stdout.accept(item.getStream());
        }
        //logger.debug("{}", item);
    }

    /**
     * Awaits the image id from the response stream.
     *
     * @throws DockerClientException
     *             if the build fails.
     */
    public String awaitImageId() {
        try {
            awaitCompletion();
        } catch (InterruptedException e) {
            throw new DockerClientException("", e);
        }

        return getImageId();
    }

    /**
     * Awaits the image id from the response stream.
     *
     * @throws DockerClientException
     *             if the build fails or the timeout occurs.
     */
    public String awaitImageId(long timeout, TimeUnit timeUnit) {
        try {
            awaitCompletion(timeout, timeUnit);
        } catch (InterruptedException e) {
            throw new DockerClientException("Awaiting image id interrupted: ", e);
        }

        return getImageId();
    }

    private String getImageId() {
        if (imageId != null) {
            return imageId;
        }

        if (error == null) {
            throw new DockerClientException("Could not build image");
        }

        throw new DockerClientException("Could not build image: " + error);
    }
}
