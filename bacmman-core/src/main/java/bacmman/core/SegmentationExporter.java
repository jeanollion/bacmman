package bacmman.core;

import bacmman.data_structure.Selection;
import bacmman.data_structure.dao.MasterDAO;
import bacmman.plugins.FeatureExtractor;
import bacmman.plugins.plugins.feature_extractor.Contours;
import bacmman.plugins.plugins.feature_extractor.Labels;
import bacmman.utils.MultipleException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

public class SegmentationExporter {
    private static final Logger logger = LoggerFactory.getLogger(SegmentationExporter.class);

    public static void exportMasks(MasterDAO mDAO, String outputFile, Selection sel, int objectClass, int compression) {
        int parentOC = -1; // mDAO.getExperiment().experimentStructure.getParentObjectClassIdx(objectClass); will not work if parent track has bounds that differ from one frame to another
        String ocName = mDAO.getExperiment().getStructure(objectClass).getName();
        Selection selFilter = null;
        if (sel.getObjectClassIdx() >=0 && sel.getObjectClassIdx() != parentOC) {
            selFilter = new Selection("exportMaskSelFilter", mDAO);
            selFilter.addElements(sel.getAllElementsAsStream().flatMap(o -> o.getChildren(objectClass)).distinct().sorted().collect(Collectors.toList()));
        }
        if (sel.getObjectClassIdx() != parentOC) { // generate parent OC
            Selection pSel = new Selection(ocName, mDAO);
            for (String p : sel.getAllPositions()) {
                pSel.addElements(sel.getElements(p).stream().flatMap(o -> o.getChildren(objectClass)).distinct().sorted().collect(Collectors.toList()));
            }
            sel = pSel;
        }
        Task resultingTask = new Task(mDAO).setPositions(sel.getAllPositions().toArray(new String[0]));;
        List<FeatureExtractor.Feature> features = new ArrayList<>(1);
        features.add(new FeatureExtractor.Feature("masks",  new Labels(), objectClass, selFilter ));
        resultingTask.setExtractDSWithSelection(outputFile, Collections.singletonList(sel), features, null, null, -1, new int[0], true, 1, 1, 1, compression);
        resultingTask.runTask();
        if (!resultingTask.getErrors().isEmpty()) throw new MultipleException(resultingTask.getErrors());
    }

    public static void exportContours(MasterDAO mDAO, String outputFile, Selection sel, int objectClass, int compression) {
        String ocName = mDAO.getExperiment().getStructure(objectClass).getName();
        if (sel.getObjectClassIdx() != objectClass) { // generate selection
            Selection pSel = new Selection(ocName, mDAO);
            for (String p : sel.getAllPositions()) {
                pSel.addElements(sel.getElements(p).stream().flatMap(o -> o.getChildren(objectClass)).distinct().sorted().collect(Collectors.toList()));
            }
            sel = pSel;
        }
        Task resultingTask = new Task(mDAO).setPositions(sel.getAllPositions().toArray(new String[0]));
        List<FeatureExtractor.Feature> features = new ArrayList<>(1);
        features.add(new FeatureExtractor.Feature("Contours", new Contours(), objectClass, (String)null ));
        resultingTask.setExtractDSWithSelection(outputFile, Collections.singletonList(sel), features, null, null, -1, new int[0], false, 1, 1, 1, compression);
        resultingTask.runTask();
        if (!resultingTask.getErrors().isEmpty()) throw new MultipleException(resultingTask.getErrors());
    }
}
