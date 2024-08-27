package bacmman.processing.matching;

import bacmman.data_structure.GraphObject;
import bacmman.data_structure.Region;
import bacmman.data_structure.SegmentedObject;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Set;

public interface GraphObjectMapper<S extends GraphObject<S>> {
    boolean contains(S go);
    S getGraphObject(Region r);
    Region getRegion(S go);

    S remove(Region r);
    Region remove(S go);
    void add(Region r, S go);
    Collection<Region> regions();
    Collection<S> graphObjects();
    boolean isEmpty();
    class GraphObjectMapperImpl<S extends GraphObject<S>> implements GraphObjectMapper<S> {
        public final HashMap<Region, S> regionObjectMap = new HashMap<>();
        public final HashMap<S, Region> objectRegionMap = new HashMap<>();
        @Override
        public boolean contains(S go) {
            return objectRegionMap.containsKey(go);
        }
        @Override
        public S getGraphObject(Region r) {
            return regionObjectMap.get(r);
        }

        @Override
        public Region getRegion(S go) {
            return objectRegionMap.get(go);
        }

        @Override
        public S remove(Region r) {
            S s = regionObjectMap.remove(r);
            if (s!=null) objectRegionMap.remove(s);
            return s;
        }

        @Override
        public Region remove(S go) {
            Region r = objectRegionMap.remove(go);
            if (go!=null) regionObjectMap.remove(r);
            return r;
        }

        @Override
        public void add(Region r, S go) {
            regionObjectMap.put(r, go);
            objectRegionMap.put(go, r);
        }

        @Override
        public Collection<Region> regions() {
            return Collections.unmodifiableSet(regionObjectMap.keySet());
        }

        @Override
        public Collection<S> graphObjects() {
            return Collections.unmodifiableSet(objectRegionMap.keySet());
        }

        @Override
        public boolean isEmpty() {
            return objectRegionMap.isEmpty();
        }
    }

    class SegmentedObjectMapper implements GraphObjectMapper<SegmentedObject> {
        public final HashMap<Region, SegmentedObject> regionObjectMap = new HashMap<>();

        @Override
        public boolean contains(SegmentedObject go) {
            return regionObjectMap.containsKey(go.getRegion());
        }
        @Override
        public SegmentedObject getGraphObject(Region r) {
            return regionObjectMap.get(r);
        }

        @Override
        public Region getRegion(SegmentedObject go) {
            return go.getRegion();
        }

        @Override
        public SegmentedObject remove(Region r) {
            return regionObjectMap.remove(r);
        }

        @Override
        public Region remove(SegmentedObject go) {
            Region r = go.getRegion();
            regionObjectMap.remove(r);
            return r;
        }

        @Override
        public void add(Region r, SegmentedObject go) {
            regionObjectMap.put(r, go);
            if (!go.getRegion().equals(r)) throw new IllegalArgumentException("Region must belong to segmented object");
        }

        @Override
        public Collection<Region> regions() {
            return Collections.unmodifiableSet(regionObjectMap.keySet());
        }

        @Override
        public Collection<SegmentedObject> graphObjects() {
            return Collections.unmodifiableCollection(regionObjectMap.values());
        }

        @Override
        public boolean isEmpty() {
            return regionObjectMap.isEmpty();
        }
    }

}
