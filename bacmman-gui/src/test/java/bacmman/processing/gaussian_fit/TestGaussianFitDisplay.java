package bacmman.processing.gaussian_fit;

import bacmman.configuration.experiment.Experiment;
import bacmman.configuration.experiment.Structure;
import bacmman.data_structure.*;
import bacmman.data_structure.dao.MemoryMasterDAO;
import bacmman.data_structure.dao.MasterDAO;
import bacmman.data_structure.dao.UUID;
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

        List<Region> result = fit(im, 100000, true, true);
        displayObjects(im, result);
        /*Image im = new ImageFloat("test display3D", 100, 100, 5);
        Point center  = new Point(50, 40, 2);
        im.setPixel(center.xMin(), center.yMin(), center.zMin(), 1);
        Region e = new Ellipse2D(center, 10, 5, 0.5, 100, 1, false, 1, 1);
        //Spot e = new Spot(center, 2, 2, 1, 1, false, 1, 1);
        e.setIsAbsoluteLandmark(true);
        e = new Region(e.getMaskAsImageInteger(), 1, false);
        List<Region> regions=  new ArrayList<>();
        regions.add(e);
        RegionPopulation pop = new RegionPopulation(regions, im);
        displayObjects(pop.getLabelMap(), regions);*/
    }

    private static void displayObjects(Image im, List<Region> result) {
        result.forEach(r -> r.setIsAbsoluteLandmark(true));
        SegmentedObjectAccessor accessor = getAccessor();
        MasterDAO<?,?> dao = new MemoryMasterDAO<>(accessor, UUID.generator());
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
