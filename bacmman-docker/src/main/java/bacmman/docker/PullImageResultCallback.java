package bacmman.docker;

import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.model.PullResponseItem;
import com.github.dockerjava.api.model.ResponseItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public class PullImageResultCallback extends ResultCallback.Adapter<PullResponseItem> {
    private static final Logger logger = LoggerFactory.getLogger(PullImageResultCallback.class);
    PullResponseItem error;
    final private Consumer<String> stdout, stderr;
    ProgressParser stepProgress;
    public PullImageResultCallback(Consumer<String> stdout, Consumer<String> stderr, BiConsumer<Integer, Integer> stepProgress) {
        this.stdout = stdout;
        this.stderr = stderr;
        this.stepProgress = new ProgressParser(stepProgress);
    }
    @Override
    public void onNext(PullResponseItem item) {
        if (item.isPullSuccessIndicated()) {
            if (stdout!=null) stdout.accept(messageFromPullResult(item));
        } else {
            if (stderr!=null) stderr.accept(messageFromPullResult(item));
            error = item;
        }
        stepProgress.accept(item);
        //logger.debug("{}", item);
    }

    public boolean success() {
        return error == null;
    }
    private String messageFromPullResult(PullResponseItem pullResponseItem) {
        return (pullResponseItem.getErrorDetail() != null) ? pullResponseItem.getErrorDetail().getMessage() : pullResponseItem.getStatus();
    }
}
