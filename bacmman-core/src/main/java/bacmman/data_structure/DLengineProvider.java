package bacmman.data_structure;

import bacmman.configuration.parameters.Parameter;
import bacmman.configuration.parameters.ParameterUtils;
import bacmman.plugins.DLengine;
import bacmman.utils.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.IntStream;

public class DLengineProvider {
    Logger logger = LoggerFactory.getLogger(DLengineProvider.class);
    List<DLengine> engines = new ArrayList<>();
    //private boolean loadTFFijiAttempt = false;
    public synchronized <T extends DLengine> T getEngine(T defaultEngine) {
        /*if (!loadTFFijiAttempt) { // using reflexion here because we don't want to add a dependency
            try {
                Service tensorflowService = Core.imagej2().get("net.imagej.tensorflow.TensorFlowService");
                Method loadLibrary = tensorflowService.getClass().getMethod("loadLibrary");
                loadLibrary.invoke(tensorflowService);
                logger.info("tensorflow lib loading attempt");
            } catch (IllegalArgumentException|NoClassDefFoundError|NoSuchMethodException|IllegalAccessException|InvocationTargetException e) {
                logger.error("error while loading tensorflow library using imagej-tensorflow", e);
            }
            loadTFFijiAttempt =true;
        }*/
        DLengine engine = engines.stream().filter(e -> e.getClass().equals(defaultEngine.getClass()) && (ParameterUtils.sameContent(e.getParameters(), defaultEngine.getParameters()))).findFirst().orElse(null);
        if (engine==null) {
            logger.debug("Engine of class: {}, and parameters: {} not found among opened engines:", defaultEngine.getClass(), defaultEngine.getParameters());
            engines.forEach(e->logger.debug("Opened Engine: {}-> parameters:{}", e.getClass(), IntStream.range(0, defaultEngine.getParameters().length).mapToObj(i->e.getParameters()[i].toStringFull()+(e.getParameters()[i].sameContent(defaultEngine.getParameters()[i]) ? "==" : "!=")+defaultEngine.getParameters()[i].toStringFull()).toArray()));
            engines.add(defaultEngine);
            engine = defaultEngine;
        }
        return (T)engine;
    }

    public void closeAllEngines() {
        for (DLengine e : engines) {
            logger.debug("closing dlengine: {}->{}", e.getClass(), e.getParameters());
            e.close();
            logger.debug("engine closed successfully");
        }
        engines.clear();
    }
}
