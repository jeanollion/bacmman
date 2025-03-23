package bacmman.docker;

import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.model.Frame;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.function.BiPredicate;

public class LogContainerResultCallback extends ResultCallback.Adapter<Frame> {
    public final CountDownLatch latch = new CountDownLatch(1);
    List<String> logs = new ArrayList<>();
    BiPredicate<List<String>, String> stopLog;
    public LogContainerResultCallback(BiPredicate<List<String>, String> stopLog) {
        this.stopLog = stopLog;
    }
    public List<String> getLogs() {
        return logs;
    }
    @Override
    public void onNext(Frame object) {
        String logLine = new String(object.getPayload());
        logs.add(logLine);
        if (stopLog != null && stopLog.test(logs, logLine)) {
            latch.countDown();
        }
        super.onNext(object);
    }
}
