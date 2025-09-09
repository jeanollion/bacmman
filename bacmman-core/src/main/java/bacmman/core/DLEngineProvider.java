package bacmman.core;

import bacmman.configuration.experiment.Experiment;
import bacmman.configuration.parameters.Parameter;
import bacmman.configuration.parameters.ParameterUtils;
import bacmman.configuration.parameters.PluginParameter;
import bacmman.plugins.DLEngine;
import bacmman.ui.PropertyUtils;
import bacmman.utils.JSONUtils;
import org.json.simple.parser.ParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static bacmman.configuration.parameters.ParameterUtils.sameClassAndParameters;

public class DLEngineProvider {
    static Logger logger = LoggerFactory.getLogger(DLEngineProvider.class);
    List<DLEngine> engines = new ArrayList<>();
    //private boolean loadTFFijiAttempt = false;
    public synchronized <T extends DLEngine> T getEngine(T defaultEngine) {
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
        DLEngine engine = engines.stream().filter(e -> sameClassAndParameters(e, defaultEngine)).findFirst().orElse(null);
        if (engine==null) {
            logger.debug("Engine of class: {}, and parameters: {} not found among opened engines:", defaultEngine.getClass(), defaultEngine.getParameters());
            engines.forEach(e->logger.debug("Opened Engine: {}-> parameters:{}", e.getClass(), IntStream.range(0, defaultEngine.getParameters().length).mapToObj(i->e.getParameters()[i].toStringFull()+(e.getParameters()[i].sameContent(defaultEngine.getParameters()[i]) ? "==" : "!=")+defaultEngine.getParameters()[i].toStringFull()).toArray()));
            Set<Integer> currentGPUSet = Arrays.stream(defaultEngine.getGPUs()).filter(gpu -> gpu >=0 ).boxed().collect(Collectors.toSet());
            if (!currentGPUSet.isEmpty()) {
                Iterator<DLEngine> it = engines.iterator();
                while (it.hasNext()) {
                    DLEngine e = it.next();
                    if (Arrays.stream(e.getGPUs()).filter(gpu -> gpu>=0).anyMatch(currentGPUSet::contains)) {
                        e.close();
                        it.remove();
                        logger.debug("GPU conflict for GPU={} closing Engine: {}", currentGPUSet, Arrays.toString(e.getParameters()));
                    }
                }
            }
            engines.add(defaultEngine);
            engine = defaultEngine;
            // close engines that RUN on same GPU.

        }
        return (T)engine;
    }

    public void closeAllEngines() {
        for (DLEngine e : engines) {
            logger.debug("closing dlengine: {}->{}", e.getClass(), e.getParameters());
            e.close();
            logger.debug("engine closed successfully");
        }
        engines.clear();
    }

    public static DLEngine getDefaultEngine(Experiment xp, List<Parameter> parameters) {
        PluginParameter<DLEngine> defaultDLEngine = new PluginParameter<>("Default DLEngine", DLEngine.class, false);
        defaultDLEngine.setParent(xp);
        String params = PropertyUtils.get(PropertyUtils.DEFAULT_DL_ENGINE, null);
        logger.debug("default DL Engine parameters: {}", params);
        if (params == null) return null;
        try {
            defaultDLEngine.initFromJSONEntry(JSONUtils.parseJSON(params));
            if (parameters != null && !parameters.isEmpty()) {
                ParameterUtils.setContent(defaultDLEngine.getParameters(), parameters);
            }
            return defaultDLEngine.instantiatePlugin();
        } catch (ParseException e) {
            return null;
        }
    }
}
