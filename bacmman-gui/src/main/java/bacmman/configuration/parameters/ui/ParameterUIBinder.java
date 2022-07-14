package bacmman.configuration.parameters.ui;

import bacmman.configuration.experiment.*;
import bacmman.configuration.parameters.*;
import bacmman.configuration.parameters.ParameterUtils;
import bacmman.core.ProgressCallback;
import bacmman.plugins.*;
import bacmman.ui.PluginConfigurationUtils;
import bacmman.ui.gui.configuration.ConfigurationTreeModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.util.List;

public class ParameterUIBinder {
    public static final Logger logger = LoggerFactory.getLogger(ParameterUIBinder.class);
    public static ParameterUI getUI(Parameter p) {
        return getUI(p, null, null);
    }
    public static ParameterUI getUI(Parameter p, ConfigurationTreeModel model, ProgressCallback pcb) {
        boolean expertMode = model==null || model.isExpertMode();
        if (p instanceof NumberParameter) return new NumberParameterUI((NumberParameter)p, model);
        if (p instanceof IndexChoiceParameter) {
            IndexChoiceParameter icp = (IndexChoiceParameter)p;
            if (icp.isMultipleSelection()) return new MultipleChoiceParameterUI(icp, model);
            else return new ChoiceParameterUI(icp, model);
        }
        if (p instanceof PluginParameter) {
            PluginParameter pp = (PluginParameter)p;
            ChoiceParameterUI ui =  new ChoiceParameterUI(pp, "Modules", model);
            if (pp.isOnePluginSet()) {
                // get structureIdx
                Structure s = bacmman.configuration.parameters.ParameterUtils.getFirstParameterFromParents(Structure.class, pp, false);
                if (s!=null) {
                    Plugin pl = pp.instantiatePlugin();
                    boolean pf = pl instanceof PreFilter || pl instanceof TrackPreFilter;
                    if (pl instanceof Segmenter || pl instanceof Tracker || pl instanceof PreFilter || pl instanceof TrackPreFilter || pl instanceof PostFilter || pl instanceof TrackPostFilter) { //
                        int idx = 0;
                        if (pf) {
                            idx = pp.getParent().getIndex(pp);
                            if (pl instanceof TrackPreFilter) { // also add pre-filters count to index
                                try {
                                    Parameter preFilters = ((PluginParameter<ProcessingPipeline>)pp.getParent().getParent()).getParameters().stream().filter(pre-> (pre instanceof PluginParameter) && ((PluginParameter)pre).getPluginType().equals(PreFilter.class)).findFirst().get();
                                    logger.debug("track preFilter idx: {} : preFilters: {} , final index: {}", idx, preFilters.getChildCount(), idx+preFilters.getChildCount());
                                    idx += preFilters.getChildCount();

                                } catch(Exception|Error e) {}
                            }
                        }
                        if (pl instanceof PostFilter || pl instanceof TrackPostFilter) idx = pp.getParent().getIndex(pp);
                        List<JMenuItem> testCommands = PluginConfigurationUtils.getTestCommand((ImageProcessingPlugin)pl, idx, ParameterUtils.getExperiment(pp), s.getIndex(), expertMode);
                        for (int i = 0; i<testCommands.size(); ++i) ui.addActions(testCommands.get(i), i==0);
                    }
                }
            }
            if (p instanceof TransformationPluginParameter && pp.isOnePluginSet()) {

                Position f = ParameterUtils.getFirstParameterFromParents(Position.class, pp, false);
                if (f!=null) {
                    int idx = pp.getParent().getIndex(pp);
                    ui.addActions(PluginConfigurationUtils.getTransformationTest("Test Transformation", f, idx, false, pcb, expertMode), true);
                    //ui.addActions(PluginConfigurationUtils.getTransformationTest("Test Transformation (show all steps)", f, idx, true), false);
                    ui.addActions(PluginConfigurationUtils.getTransformationTestOnCurrentImage("Test Transformation on current Image", f, idx, expertMode), false);
                }
            }
            return ui;
        }
        if (p instanceof CustomParameter) {
            CustomParameter u = (CustomParameter)p;
            ChoiceParameterUI ui =  new ChoiceParameterUI(u, "Parameters", model);
            return ui;
        }
        if (p instanceof AbstractChoiceParameter) return new ChoiceParameterUI((AbstractChoiceParameter)p, model);
        if (p instanceof ListParameter) return new SimpleListParameterUI((ListParameter)p, model);
        if (p instanceof NoteParameter) return new NoteEditorUI((NoteParameter)p, model);
        if (p instanceof TextParameter) return new TextEditorUI((TextParameter)p, model);
        if (p instanceof FileChooser) return new FileChooserUI((FileChooser)p, model);
        if (p instanceof ConditionalParameterAbstract) return getUI(((ConditionalParameterAbstract)p).getActionableParameter(), model, pcb);

        // experiment parameters
        if (p instanceof ChannelImage || p instanceof ChannelImageDuplicated || p instanceof Structure) return new NameEditorUI(p, false ,model);
        if (p instanceof Position) return new PositionUI((Position)p, pcb);
        if (p instanceof PreProcessingChain) return new PreProcessingChainUI((PreProcessingChain)p, model);
        if (p instanceof IntervalParameter) return new IntervalParameterUI((IntervalParameter)p, model);

        // other parameters
        if (p instanceof MLModelFileParameter) return new MLModelFileParameterUI((MLModelFileParameter)p, model);

        // choice parameter interface
        if (p instanceof ChoosableParameterMultiple) return new MultipleChoiceParameterUI((ChoosableParameterMultiple)p, model);
        if (p instanceof ChoosableParameter) return new ChoiceParameterUI((ChoosableParameter)p, model);

        return null;
    }
}
