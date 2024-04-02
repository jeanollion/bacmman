package bacmman.docker;

import bacmman.plugins.Plugin;
import com.github.dockerjava.api.model.ResponseItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Objects;
import java.util.function.BiConsumer;

public class ProgressParser {
    public final static Logger logger = LoggerFactory.getLogger(ProgressParser.class);

    final private HashMap<String, ResponseItem.ProgressDetail> progress = new HashMap<>();
    //String currentStatus = null;
    final BiConsumer<Integer, Integer> stepProgress;
    public ProgressParser(BiConsumer<Integer, Integer> stepProgress) {
        this.stepProgress=stepProgress;
    }
    public void accept(ResponseItem item) {
        if (item.getStatus() == null) return;
        /*if (!Objects.equals(item.getStatus(), currentStatus)) {
            logger.debug("Switching to status: {} (previous: {})", item.getStatus(), currentStatus);
            progress.clear();
            currentStatus = item.getStatus();
        }*/
        if (item.getId() != null && item.getProgressDetail() != null && item.getProgressDetail().getCurrent() !=null && item.getProgressDetail().getTotal()!=null) {
            progress.put(item.getId()+item.getStatus(), item.getProgressDetail());
            long currentProgress=progress.values().stream().mapToLong(pd -> pd==null || pd.getCurrent()==null ? 0 : pd.getCurrent()/1024).sum()/1024;
            long totalProgress=progress.values().stream().mapToLong(pd -> pd == null || pd.getTotal()==null ? 0 : pd.getTotal()/(1024*1024)).sum();
            if (stepProgress !=null) {
                stepProgress.accept((int)currentProgress, (int)totalProgress);
            }
        }
    }

}
