package bacmman.py_dataset;

import bacmman.data_structure.SegmentedObject;
import bacmman.data_structure.SegmentedObjectUtils;
import bacmman.data_structure.Selection;
import bacmman.image.BoundingBox;
import bacmman.image.Image;
import bacmman.image.SimpleBoundingBox;
import bacmman.image.SimpleImageProperties;
import bacmman.utils.ArrayUtil;
import bacmman.utils.HashMapGetCreate;
import bacmman.utils.Pair;
import ch.systemsx.cisd.hdf5.IHDF5Reader;
import com.google.common.collect.Sets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public class PyDatasetReader {
    public final static Logger logger = LoggerFactory.getLogger(PyDatasetReader.class);
    private IHDF5Reader reader;
    private Set<String> groupLevel1;
    private Set<Pair<String, String>> groupDB;
    private Map<Pair<String, String>, DatasetAccess> dataAccess = new HashMap<>();
    public PyDatasetReader(File file) {
        reader = HDF5IO.getReader(file);
        HashSet<String> g= new HashSet<>(reader.getGroupMembers("/"));
        g.removeIf(s->!reader.isGroup(s));
        Set<Pair<String, String>> gDB=new HashSet<>();
        for (String group : g) {
            List<String> dbs = reader.getGroupMembers(group+"/");
            dbs.removeIf(s->!reader.isGroup(group+"/"+s));
            for (String db : dbs) gDB.add(new Pair<>(group, db));
        }
        if (gDB.isEmpty()) {
            // only one group level = root
            HashSet<String> gg = new HashSet<>();
            gg.add("");
            for (String db : g) gDB.add(new Pair<>("", db));
            groupLevel1 = Collections.unmodifiableSet(gg);
        } else {
            groupLevel1 = Collections.unmodifiableSet(g);
        }
        groupDB = Collections.unmodifiableSet(gDB);
    }
    public Set<String> getDBForGroup(String group) {
        return groupDB.stream().filter(p->p.key.equals(group)).map(p->p.value).collect(Collectors.toSet());
    }
    public Set<String> getGroups() {
        return groupLevel1;
    }
    public Set<Pair<String, String>> getDBs() {
        return groupDB;
    }
    public DatasetAccess getDatasetAccess(String group, String dbName) {
        if (group==null) group="";
        Pair<String, String> p = new Pair<>(group, dbName);
        if (!groupDB.contains(p)) {
            logger.warn("no entry with group: {} and dataset: {}. all entries: {}", group, dbName, groupDB);
            return null;
        }
        if (!dataAccess.containsKey(p)) {
            synchronized (dataAccess) {
                if (!dataAccess.containsKey(p)) {
                    dataAccess.put(p, new DatasetAccess(group, dbName));
                }
            }
        }
        return dataAccess.get(p);
    }
    public void close() {
        reader.close();
        dataAccess.values().forEach(da -> da.flush());
        dataAccess.clear();
    }
    public void flush(String dbName) {
        synchronized (dataAccess) {
            Iterator<Map.Entry<Pair<String, String>, DatasetAccess>> it = dataAccess.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<Pair<String, String>, DatasetAccess> e = it.next();
                if (e.getKey().key.equals(dbName)) {
                    it.remove();
                    e.getValue().flush();
                }
            }
        }
    }
    public class DatasetAccess {
        final String group;
        final String dbName;
        final Map<String, ObjectCoordinates[]> coords;
        final Map<String, int[][]> originalDims;
        final Set<String> positions;
        public DatasetAccess(String group, String dbName) {
            this.group = group;
            this.dbName = dbName;
            // get all positions for dataset
            positions = reader.getGroupMembers(dsName()).stream().filter(p->reader.isGroup(dsName(p))).collect(Collectors.toSet());
            coords = new HashMapGetCreate.HashMapGetCreateRedirectedSync<>(position -> {
                if (!reader.getGroupMembers(dsName(position)).contains("labels")) {
                    logger.error("no label array found for group: {}, dataset: {}, position: {}", group, dbName, position);
                    return null;
                }
                String[] labels = reader.string().readArray(dsName(position) + "/labels");
                logger.debug("dataset: {}, labels: #{} -> {}", dsName(position), labels.length, labels);
                return Arrays.stream(labels).map(l -> new ObjectCoordinates(l)).toArray(l -> new ObjectCoordinates[l]);
            });
            originalDims = new HashMapGetCreate.HashMapGetCreateRedirectedSync<>(position -> {
                if (!reader.getGroupMembers(dsName(position)).contains("originalDimensions")) {
                    logger.error("no original dim matrix found for group: {}, dataset: {}, position: {}", group, dbName, position);
                    return null;
                }
                return reader.int32().readMatrix(dsName(position) + "/originalDimensions");
            });
        }

        private void flush() {
            new ArrayList<>(coords.keySet()).forEach(p -> flushPosition(p));
        }
        private void flushPosition(String posName) {
            this.coords.remove(posName);
            this.originalDims.remove(posName);
        }
        public String dsName() {
            return group.length()==0 ? dbName : group +"/"+dbName;
        }
        public String dsName(String position) {
            return group.length()==0 ? dbName : group +"/"+dbName+"/"+position;
        }

        public List<String> getDatasetNames(String position) {
            return reader.getGroupMembers(dsName(position));
        }
        public Map<SegmentedObject, Image> extractImagesForTrack(String dsName, String posName, List<SegmentedObject> track, boolean binary, int... resampleDims) {
            if (track.isEmpty()) return Collections.emptyMap();
            ObjectCoordinates[] cds = coords.get(posName);
            Comparator comp = getComparator();
            long t1 = System.currentTimeMillis();
            int[] idxA = track.stream().parallel().mapToInt(o -> Arrays.binarySearch(cds, o, comp)).toArray();
            long t2 = System.currentTimeMillis();
            logger.debug("get {} indices: in {}ms", idxA.length, t2-t1);
            Image[] images = getImages(dsName, posName, idxA, true, binary, resampleDims);
            Image ref = Arrays.stream(images).filter(i->i!=null).findAny().get();
            return IntStream.range(0, idxA.length).mapToObj(i->i).collect(Collectors.toMap(i->track.get(i), i -> {
                if (images[i]!=null) {
                    images[i].setCalibration(track.get(i).getScaleXY(), track.get(i).getScaleZ());
                    images[i].translate(track.get(i).getBounds());
                    return images[i];
                }
                else return Image.createEmptyImage("", ref, new SimpleImageProperties(track.get(i).getBounds(), track.get(i).getScaleXY(), track.get(i).getScaleZ()));
            }));
        }
        public Stream<Image> extractImagesForPositions(String dsName, String[] positions, boolean resampleBack, boolean binary, int... resampleDims) {
            Stream<String> positionStream;
            if (positions==null) positionStream=this.positions.stream();
            else positionStream = Sets.intersection(Arrays.stream(positions).collect(Collectors.toSet()), this.positions).stream();
            return positionStream.flatMap(p -> Arrays.stream(getImages(dsName, p, null, resampleBack, binary, resampleDims)));
        }
        public String[] getLabelsForPosition(String posName) {
            return Arrays.stream(coords.get(posName)).map(c->c.label).toArray(String[]::new);
        }
        private Image[] getImages(String dsName, String posName, int[] idx, boolean resampleBack, boolean binary, int... resampleDims) {
            Image[] res;
            long t1 = System.currentTimeMillis();
            //synchronized (reader) { // TODO check if concurrent access on different datasets is possible
            res = HDF5IO.readPyDataset(reader, dsName(posName) + "/" + dsName, false, idx);
            //}
            ObjectCoordinates[] coords = this.coords.get(posName);
            for (int i = 0; i<idx.length; ++i) if (res[i]!=null) res[i].setName(coords[idx[i]].label);

            long t2 = System.currentTimeMillis();
            logger.debug("extracted: {} images (#null={}) in {}ms", res.length, Arrays.stream(res).filter(i->i==null).count(), t2-t1);
            if (resampleBack) {
                if (idx == null) idx = ArrayUtil.generateIntegerArray(res.length);
                int[][] originalDims = this.originalDims.get(posName);
                logger.debug("original dims: 0={}, 1={} #={}, dim={}", originalDims[0], originalDims[1], originalDims.length, res[0].getBoundingBox());
                IntStream.range(0, idx.length).filter(i -> res[i] != null)
                        .forEach(i -> res[i] = resampleBack(res[i], new SimpleBoundingBox(0, originalDims[i][0]-1, 0, originalDims[i][1]-1, 0, originalDims[i].length > 2 ? originalDims[i][2]-1 : 0), binary, resampleDims));
                long t3 = System.currentTimeMillis();
                logger.debug("resampled: {} images in {}ms", res.length, t3-t2);
            }
            return res;
            /*
            SegmentedObject[] objects = this.objects.get(posName);
            return IntStream.range(0, idx.length).parallel()
                .mapToObj(i -> resampleBack(res.get(i), objects[idx[i]].getBounds(), binary, resampleDims)).collect(Collectors.toList());
           */
        }


    }
    static Comparator getComparator() {
        Map<SegmentedObject, String> trackHeadIndices = new HashMapGetCreate.HashMapGetCreateRedirected<>(o -> Selection.indicesString(o));
        return (o, t1) -> {
            if (o instanceof SegmentedObject) {
                if (t1 instanceof ObjectCoordinates) {
                    int comp = trackHeadIndices.get(((SegmentedObject) o).getTrackHead()).compareTo(((ObjectCoordinates) t1).trackHeadIndices);
                    if (comp!=0) return comp;
                    return Integer.compare(((SegmentedObject) o).getFrame(), ((ObjectCoordinates) t1).frame);
                } else {
                    int comp = trackHeadIndices.get(((SegmentedObject) o).getTrackHead()).compareTo(trackHeadIndices.get(((SegmentedObject) t1).getTrackHead()));
                    if (comp!=0) return comp;
                    return Integer.compare(((SegmentedObject) o).getFrame(), ((SegmentedObject) t1).getFrame());
                }
            } else {
                if (t1 instanceof SegmentedObject) {
                    int comp = ((ObjectCoordinates)o).trackHeadIndices.compareTo(trackHeadIndices.get(((SegmentedObject) t1).getTrackHead()));
                    if (comp!=0) return comp;
                    return Integer.compare(((ObjectCoordinates) o).frame, ((SegmentedObject) t1).getFrame());
                } else {
                    int comp = ((ObjectCoordinates) o).trackHeadIndices.compareTo(((ObjectCoordinates) t1).trackHeadIndices);
                    if (comp!=0) return comp;
                    return Integer.compare(((ObjectCoordinates) o).frame, ((ObjectCoordinates) t1).frame);
                }
            }
        };
    }
    static class ObjectCoordinates {
        final String label, trackHeadIndices;
        final int frame;
        //final int[] trackHeadIndices;
        public ObjectCoordinates(String label) {
            this.label=label;
            // label is: position _ track head _f + frame number
            int i = label.lastIndexOf("_f");
            frame = Integer.parseInt(label.substring(i+2));
            trackHeadIndices = label.substring(0, i);
            //i = label.indexOf('_');
            //position = Integer.parseInt(label.substring(0, i));
            //trackHeadIndices = Selection.parseIndices(label.substring(i+1));
            //trackHeadIndices = Selection.parseIndices(label);
        }
    }

    public static Image resampleBack(Image im, BoundingBox target, boolean binary, int... dimensions) {
        if (im.sameDimensions(target)) return im;
        // if resampled dim negative: need to crop to size -> will set zeros!
        if (Arrays.stream(dimensions).anyMatch(i->i<0)) { // needs cropping
            BoundingBox cropBB = new SimpleBoundingBox(0, dimensions[0]<0 && im.sizeX()<target.sizeX() ? target.sizeX()-1 : im.sizeX()-1, 0, dimensions.length>1 && dimensions[1]<0 && im.sizeY()<target.sizeY() ? target.sizeY()-1 : im.sizeY()-1, 0, dimensions.length>2 && dimensions[2]<0 && im.sizeZ()<target.sizeZ() ? target.sizeZ()-1 : im.sizeZ()-1);
            im = im.crop(cropBB);
        }
        return ExtractDataset.resampleImage(im, binary, target.sizeX(), target.sizeY(), target.sizeZ());
    }
}
