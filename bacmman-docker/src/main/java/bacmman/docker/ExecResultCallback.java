package bacmman.docker;

import bacmman.utils.Utils;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.model.Frame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;

public class ExecResultCallback extends ResultCallback.Adapter<Frame> {
    private static final Logger logger = LoggerFactory.getLogger(ExecResultCallback.class);
    private Consumer<String> stdOut, stdErr;

    public ExecResultCallback(Consumer<String> stdout, Consumer<String> stderr) {
        this.stdOut = stdout;
        this.stdErr = stderr;
    }

    @Override
    public void onNext(Frame frame) {
        if (frame != null) {
            String message = new String(Utils.replaceInvalidUTF8(frame.getPayload()));
            switch (frame.getStreamType()) {
                case STDOUT:
                case RAW:
                    if (stdOut != null) stdOut.accept(message);
                    break;
                case STDERR:
                    if (stdErr != null) stdErr.accept(message);
                    break;
                default:
                    logger.error("unknown stream type:" + frame.getStreamType());
            }
        }
    }
}
