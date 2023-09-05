package bacmman.docker;

import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.model.PullResponseItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.function.Consumer;

public class PullImageResultCallback extends ResultCallback.Adapter<PullResponseItem> {
    private static final Logger logger = LoggerFactory.getLogger(PullImageResultCallback.class);
    PullResponseItem error;
    final private Consumer<String> stdout, stderr;

    public PullImageResultCallback(Consumer<String> stdout, Consumer<String> stderr) {
        this.stdout = stdout;
        this.stderr = stderr;
    }
    @Override
    public void onNext(PullResponseItem item) {
        if (item.isPullSuccessIndicated()) {
            if (stdout!=null) stdout.accept(messageFromPullResult(item));
        } else {
            if (stderr!=null) stderr.accept(messageFromPullResult(item));
            error = item;
        }

        //logger.debug("{}", item);
    }

    public boolean success() {
        return error == null;
    }
    private String messageFromPullResult(PullResponseItem pullResponseItem) {
        return (pullResponseItem.getErrorDetail() != null) ? pullResponseItem.getErrorDetail().getMessage() : pullResponseItem.getStatus();
    }
}
