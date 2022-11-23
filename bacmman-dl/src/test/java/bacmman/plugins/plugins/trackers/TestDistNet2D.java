package bacmman.plugins.plugins.trackers;

import bacmman.configuration.experiment.Experiment;
import bacmman.configuration.experiment.Structure;
import bacmman.data_structure.*;
import bacmman.data_structure.dao.BasicObjectDAO;
import bacmman.data_structure.dao.MasterDAO;
import bacmman.image.BlankMask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class TestDistNet2D {
    public final static Logger logger = LoggerFactory.getLogger(TestDistNet2D.class);
    public static void main(String[] args) {
        Experiment xp = new Experiment();
        Structure bactS = xp.getStructures().createChildInstance("bact");
        xp.getStructures().insert(bactS);
        xp.getPositionParameter().insert(xp.getPositionParameter().createChildInstance("p0"));
        SegmentedObjectFactory factory = getFactory(0);
        SegmentedObjectAccessor accessor = getAccessor();
        TrackLinkEditor editor = getEditor(0);
        MasterDAO mDAO = MasterDAOFactory.createDAO("test", "/storage/test", MasterDAOFactory.DAOType.Basic);
        mDAO.setExperiment(xp);
        BasicObjectDAO dao = new BasicObjectDAO(mDAO, "p0");

        List<SegmentedObject> rootTrack = IntStream.range(0, 3).mapToObj(i -> accessor.createRoot(i, new BlankMask(2, 4, 1), dao)).collect(Collectors.toList());
        Map<Integer, List<SegmentedObject>> bacts = new HashMap<>();
        bacts.put(0, new ArrayList<SegmentedObject>(){{
            add(new SegmentedObject(0, 0, 0, new Region(new Voxel(0, 0, 0), 1, true, 1, 1), rootTrack.get(0)));
            add( new SegmentedObject(0, 0, 1, new Region(of(new Voxel(0, 2, 0), new Voxel(1, 2, 0)), 2, true, 1, 1), rootTrack.get(0)) );
        }});
        bacts.put(1, new ArrayList<SegmentedObject>(){{
            add(new SegmentedObject(1, 0, 0, new Region(new Voxel(0, 2, 0), 1, true, 1, 1), rootTrack.get(1)));
            add( new SegmentedObject(1, 0, 1, new Region(new Voxel(1, 2, 0), 2, true, 1, 1), rootTrack.get(1)) );
            add( new SegmentedObject(1, 0, 2, new Region(new Voxel(2, 1, 0), 3, true, 1, 1), rootTrack.get(1)) );
        }});
        bacts.put(2, new ArrayList<SegmentedObject>(){{
            add(new SegmentedObject(2, 0, 0, new Region(new Voxel(0, 0, 0), 1, true, 1, 1), rootTrack.get(2)));
            add( new SegmentedObject(2, 0, 1, new Region(new Voxel(1, 1, 0), 2, true, 1, 1), rootTrack.get(2)) );
        }});
        rootTrack.forEach( r -> factory.setChildren(r, bacts.get(r.getFrame())));
        logger.debug("position name: {}", rootTrack.get(0).getPositionIdx());
        DistNet2D tracker = new DistNet2D();
        tracker.mergeDistThld.setValue(1);
        tracker.sizePenaltyFactor.setValue(10);
        tracker.contactCriterion.setValue(DistNet2D.CONTACT_CRITERION.CONTOUR_DISTANCE);
        tracker.solveMerge.setSelected(false);
        tracker.track(0, rootTrack, editor);
        bacts.forEach( (f, bs) -> {
            bs.forEach( b -> logger.debug("{} <- {} -> {}", SegmentedObjectEditor.getPrevious(b), b, SegmentedObjectEditor.getNext(b)) );
        });
    }

    private static Set<Voxel> of(Voxel... voxels) {
        return new HashSet<>(Arrays.asList(voxels));
    }
    private static SegmentedObjectFactory getFactory(int objectClassIdx) {
        try {
            Constructor<SegmentedObjectFactory> constructor = SegmentedObjectFactory.class.getDeclaredConstructor(int.class);
            constructor.setAccessible(true);
            return constructor.newInstance(objectClassIdx);
        } catch (InstantiationException | IllegalAccessException | NoSuchMethodException | InvocationTargetException e) {
            throw new RuntimeException("Could not create track link editor", e);
        }
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
    private static TrackLinkEditor getEditor(int objectClassIdx) {
        try {
            Constructor<TrackLinkEditor> constructor = TrackLinkEditor.class.getDeclaredConstructor(int.class, Set.class, boolean.class);
            constructor.setAccessible(true);
            return constructor.newInstance(objectClassIdx, new HashSet<SegmentedObject>(), true);
        } catch (InstantiationException | IllegalAccessException | NoSuchMethodException | InvocationTargetException e) {
            throw new RuntimeException("Could not create track link editor", e);
        }
    }
}
