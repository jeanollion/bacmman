package bacmman.processing.gaussian_fit;

import bacmman.configuration.experiment.Experiment;
import bacmman.configuration.experiment.Structure;
import bacmman.core.Core;
import bacmman.data_structure.MasterDAOFactory;
import bacmman.data_structure.Region;
import bacmman.data_structure.SegmentedObject;
import bacmman.data_structure.SegmentedObjectAccessor;
import bacmman.data_structure.dao.BasicMasterDAO;
import bacmman.data_structure.dao.MasterDAO;
import bacmman.image.BlankMask;
import bacmman.image.Image;
import bacmman.ui.gui.image_interaction.*;
import ij.ImageJ;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.stream.Collectors;

import static bacmman.processing.gaussian_fit.TestGaussianFit.*;

public class TestGaussianFitDisplay {
    public static void main(String[] args) {
        new ImageJ();
        //IJImageDisplayer disp = new IJImageDisplayer();
        Image im = generateImage(true, true);
        //disp.showImage(im);

        //Image labels = fitAndDrawLabels(im, 60, true, true);
        //disp.showImage(labels.setName("fit"));

        List<Region> result = fit(im, 60, true, false);
        displayObjects(im, result);

    }

    private static void displayObjects(Image im, List<Region> result) {
        SegmentedObjectAccessor accessor = getAccessor();
        MasterDAO dao = new BasicMasterDAO(accessor);
        dao.setExperiment(new Experiment("xp", new Structure()));
        dao.getExperiment().createPosition("pos1");
        SegmentedObject parent = accessor.createRoot(0, new BlankMask(im), dao.getDao("pos1"));
        List<SegmentedObject> children = result.stream().map(r -> new SegmentedObject(0, 0, r.getLabel()-1, r, parent)).collect(Collectors.toList());
        accessor.setChildren(parent, children, 0);
        InteractiveImage i = new SimpleInteractiveImage(parent, 0);
        ImageWindowManager iwm = ImageWindowManagerFactory.getImageManager();
        iwm.addImage(im, i, 0, true);
    }

    private static SegmentedObjectAccessor getAccessor() {
        try {
            Constructor<SegmentedObjectAccessor> constructor = SegmentedObjectAccessor.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (InstantiationException | IllegalAccessException | NoSuchMethodException | InvocationTargetException e) {
            throw new RuntimeException("Could not create track link editor", e);
        }
    }
}
