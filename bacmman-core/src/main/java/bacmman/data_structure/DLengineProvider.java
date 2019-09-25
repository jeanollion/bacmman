package bacmman.data_structure;

import bacmman.configuration.parameters.Parameter;
import bacmman.configuration.parameters.ParameterUtils;
import bacmman.plugins.DLengine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

public class DLengineProvider {
    Logger logger = LoggerFactory.getLogger(DLengineProvider.class);
    List<DLengine> engines = new ArrayList<>();
    //private boolean loadTFFijiAttempt = false;
    public synchronized DLengine getEngine(Class engineClass, List<Parameter> parameters, Supplier<DLengine> engineFactory) {
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
        DLengine engine = engines.stream().filter(e -> e.getClass().equals(engineClass) && (ParameterUtils.sameContent(Arrays.asList(e.getParameters()), parameters))).findFirst().orElse(null);
        if (engine==null) {
            engine = engineFactory.get();
            if (engine!=null) {
                engine.init();
                engines.add(engine);
            }
        }
        return engine;
    }
    public synchronized void closeAllEngines() {
        engines.forEach(e->e.close());
        engines.clear();
    }
}
