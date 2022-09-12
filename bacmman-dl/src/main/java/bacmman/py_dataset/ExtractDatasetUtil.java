package bacmman.py_dataset;

import bacmman.core.Core;
import bacmman.core.Task;
import bacmman.data_structure.*;
import bacmman.data_structure.dao.MasterDAO;
import bacmman.data_structure.input_image.InputImages;
import bacmman.image.*;
import bacmman.plugins.FeatureExtractor;
import bacmman.plugins.FeatureExtractorOneEntryPerInstance;
import bacmman.plugins.plugins.feature_extractor.RawImage;
import bacmman.ui.GUI;
import bacmman.ui.gui.selection.SelectionUtils;
import bacmman.utils.HashMapGetCreate;
import ch.systemsx.cisd.hdf5.IHDF5Writer;
import net.imglib2.interpolation.InterpolatorFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.function.DoubleToIntFunction;
import java.util.function.Function;
import java.util.function.IntPredicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static bacmman.processing.Resize.resample;

public class ExtractDatasetUtil {
    public static boolean display=false, test=false;
    private final static Logger logger = LoggerFactory.getLogger(ExtractDatasetUtil.class);
    public static void runTask(Task t) {
        String outputFile = t.getExtractDSFile();
        Path outputPath = Paths.get(outputFile);
        int[] dimensions = t.getExtractDSDimensions();
        List<FeatureExtractor.Feature> features = t.getExtractDSFeatures();
        List<String> selectionNames = t.getExtractDSSelections();
        int[] eraseTouchingContoursOC = t.getExtractDSEraseTouchingContoursOC();
        IntPredicate eraseTouchingContours = oc -> Arrays.stream(eraseTouchingContoursOC).anyMatch(i->i==oc);
        MasterDAO mDAO = t.getDB();
        String ds = mDAO.getDBName();
        for (String selName : selectionNames) {
            logger.debug("Selection: {}", selName);
            Selection sel = mDAO.getSelectionDAO().getOrCreate(selName, false);
            if (test) {
                Selection selT = new Selection("", sel.getStructureIdx(), mDAO);
                Selection selF = sel;
                String pos = sel.getAllPositions().stream().filter(p -> selF.count(p) > 0).findAny().orElse(null);
                if (pos == null) continue;
                selT.addElements(pos, sel.getElementStrings(pos).stream().limit(display?1:100).collect(Collectors.toList()));
                sel = selT;
                logger.debug("sel size: {}", sel.count());
            }
            for (String position : sel.getAllPositions()) {
                logger.debug("position: {}", position);
                Map<Integer, Map<SegmentedObject, RegionPopulation>> resampledPops = new HashMapGetCreate.HashMapGetCreateRedirectedSync<>(oc -> new HashMapGetCreate.HashMapGetCreateRedirectedSyncKey<>(parent -> {
                    if (parent.getStructureIdx() == oc) return null; // this case is handled separately
                    RegionPopulation pop = parent.getChildRegionPopulation(oc, false);
                    return resamplePopulation(pop, dimensions, eraseTouchingContours.test(oc));
                }));
                String outputName = (selName.length() > 0 ? selName + "/" : "") + ds + "/" + position + "/";
                boolean saveLabels = true;
                for (FeatureExtractor.Feature feature : features) {
                    boolean oneEntryPerInstance = feature.getFeatureExtractor() instanceof FeatureExtractorOneEntryPerInstance;
                    logger.debug("feature: {} ({}), selection filter: {}", feature.getName(), feature.getFeatureExtractor().getClass().getSimpleName(), feature.getSelectionFilter());
                    Function<SegmentedObject, Image> extractFunction;
                    Selection parentSelection;
                    Map<Integer, Map<SegmentedObject, RegionPopulation>> curResamplePops;
                    Selection selFilter = feature.getSelectionFilter()==null?null:mDAO.getSelectionDAO().getOrCreate(feature.getSelectionFilter(), false);
                    if (selFilter != null) {
                        Set<SegmentedObject> allElements = selFilter.hasElementsAt(position) ? selFilter.getElements(position) : Collections.emptySet();
                        Map<SegmentedObject, RegionPopulation> resampledPop = new HashMapGetCreate.HashMapGetCreateRedirectedSyncKey<>(parent -> {
                            List<Region> childrenFiltered = allElements.stream().filter(o -> o.getParent(parent.getStructureIdx()).equals(parent)).map(SegmentedObject::getRegion).collect(Collectors.toList());
                            logger.debug("extract: parent: {} children: {}", parent, childrenFiltered.stream().mapToInt(r -> r.getLabel()-1).toArray());
                            RegionPopulation p = new RegionPopulation(childrenFiltered, parent.getMaskProperties());
                            return resamplePopulation(p, dimensions, eraseTouchingContours.test(feature.getObjectClass()));
                        });
                        curResamplePops = new HashMapGetCreate.HashMapGetCreateRedirectedSync<>(oc -> {
                            if (oc == feature.getObjectClass()) return resampledPop;
                            else return resampledPops.get(oc);
                        });
                        if (oneEntryPerInstance) {
                            Set<SegmentedObject> allParents = sel.hasElementsAt(position) ? sel.getElements(position) : Collections.emptySet();
                            int parentSO = sel.getStructureIdx();
                            parentSelection = Selection.generateSelection(sel.getName(), mDAO, new HashMap<String, List<SegmentedObject>>(1) {{
                                put(position, allElements.stream().filter(o -> allParents.contains(o.getParent(parentSO))).collect(Collectors.toList()));
                            }});
                        } else {
                            int parentOC = sel.getStructureIdx();
                            List<SegmentedObject> allParents = allElements.stream().map(o -> o.getParent(parentOC)).distinct().collect(Collectors.toList());

                            parentSelection = Selection.generateSelection(sel.getName(), mDAO, new HashMap<String, List<SegmentedObject>>(1) {{
                                put(position, allParents);
                            }});
                            //for (String p : parentSelection.getAllPositions()) logger.debug("parent selection before intersect @{}: {}", p, parentSelection.getElementStrings(p));
                            SelectionUtils.intersection(sel.getName(), parentSelection, sel);
                            parentSelection.setMasterDAO(mDAO);
                            //for (String p : parentSelection.getAllPositions()) logger.debug("parent selection @{}: {}", p, parentSelection.getElementStrings(p));
                        }
                    }
                    else {
                        curResamplePops = resampledPops;
                        parentSelection = sel;
                    }
                    if (!parentSelection.isEmpty()) {
                        extractFunction = e -> feature.getFeatureExtractor().extractFeature(e, feature.getObjectClass(), curResamplePops, dimensions);
                        boolean ZtoBatch = feature.getFeatureExtractor().getExtractZDim() == Task.ExtractZAxis.BATCH;
                        extractFeature(outputPath, outputName + feature.getName(), parentSelection, position, extractFunction, ZtoBatch, SCALE_MODE.NO_SCALE, feature.getFeatureExtractor().interpolation(), null, oneEntryPerInstance, saveLabels, saveLabels, dimensions);
                        saveLabels = false;
                    }
                    t.incrementProgress();
                }
                resampledPops.clear();
            }
        }
        // write histogram for raw features
        features.stream().filter(f->f.getFeatureExtractor() instanceof RawImage).map(FeatureExtractor.Feature::getName).forEach(channelName -> {
            write_histogram(outputPath.toFile(), selectionNames, ds, channelName, dimensions);
        });
    }
    public static void runTaskRaw(Task t) {
        logger.debug("extracting raw dataset 2...");
        String outputFile = t.getExtractRawDSFile();
        Path outputPath = Paths.get(outputFile);
        BoundingBox bounds = t.getExtractRawDSBounds();
        Map<String, List<Integer>> positionMapFrames = t.getExtractRawDSFrames();
        int[] channels = t.getExtractRawDSChannels();
        MasterDAO mDAO = t.getDB();
        String ds = mDAO.getDBName();
        String[] channelNames = mDAO.getExperiment().getChannelImagesAsString(false);
        Function<Image, Image> extract = image -> {
            if (bounds!=null) {
                MutableBoundingBox bds = new MutableBoundingBox(bounds);
                logger.debug("bounds before adjust: {}, image: {}", bds, image.getBoundingBox());
                if (bds.sizeX() <= 0 || bds.xMax() > image.xMax()) bds.setxMax(image.xMax());
                if (bds.sizeY() <= 0 || bds.yMax() > image.yMax()) bds.setyMax(image.yMax());
                if (bds.sizeZ() <= 0 || bds.zMax() > image.zMax()) bds.setzMax(image.zMax());
                image = image.crop(bds);
                logger.debug("bounds after adjust: {}, image: {}", bds, image.getBoundingBox());
            }
            return RawImage.handleZ(image, t.getExtractRawZAxis(), t.getExtractRawZAxisPlaneIdx());
        };
        for (String position : positionMapFrames.keySet()) {
            logger.debug("position: {}", position);
            InputImages inputImages = mDAO.getExperiment().getPosition(position).getInputImages();
            List<Integer> frames = positionMapFrames.get(position);
            boolean saveLabels = true;
            for (int channel : channels) {
                String outputName = ds + "/" + position + "/" + channelNames[channel];
                List<Image> images = frames.stream().map(fIdx -> inputImages.getImage(channel, fIdx).setName(""+fIdx)).map(extract).collect(Collectors.toList());
                if (t.getExtractRawZAxis().equals(Task.ExtractZAxis.BATCH)) {
                    logger.debug("before ZToBatch: {}", images.size());
                    Stream<Image> s = images.stream().flatMap(i -> i.splitZPlanes().stream());
                    images = s.collect(Collectors.toList());
                    logger.debug("after ZToBatch: {}", images.size());
                }
                extractFeature(outputPath, outputName, images, SCALE_MODE.NO_SCALE, null, saveLabels, null, false);
                saveLabels = false;
            }
            inputImages.flush();
            t.incrementProgress();
        }
    }

    public static void write_histogram(File outputPath, List<String> selectionNames, String dbName, String channelName, int[] dimensions) {
        PyDatasetReader reader = new PyDatasetReader(outputPath);
        Map<String, Histogram> histos = new HashMap<>(selectionNames.size());
        for (String selName : selectionNames) {
            PyDatasetReader.DatasetAccess dsA = reader.getDatasetAccess(selName, dbName);
            Supplier<Stream<Image>> imageStream = () -> dsA.extractImagesForPositions(channelName, null, false, false, dimensions);
            double[] minAndMax = HistogramFactory.getMinAndMax(imageStream.get());
            double binSize = HistogramFactory.getBinSize(minAndMax[0], minAndMax[1], 256);
            Histogram h = HistogramFactory.getHistogram(imageStream.get(), binSize, 256, minAndMax[0]);
            histos.put(dsA.dsName(), h);
        }
        reader.close();
        IHDF5Writer writer = HDF5IO.getWriter(outputPath, true);
        histos.forEach((s, h) -> {
            writer.int64().setArrayAttr(s, "raw_histogram", h.getData());
            writer.float64().setAttr(s, "raw_histogram_bin_size", h.getBinSize());
            writer.float64().setArrayAttr(s, "raw_percentiles", h.getQuantiles(IntStream.range(0, 101).mapToDouble(i -> i / 100d).toArray()));
        });
        writer.close();
    }

    private static RegionPopulation resamplePopulation(RegionPopulation pop, int[] dimensions, boolean eraseTouchingContours) {
        Image mask = pop.getLabelMap();
        ImageInteger maskR;
        if (mask instanceof ImageShort) maskR =  TypeConverter.toShort(resample(mask, true, dimensions), null).resetOffset();
        else maskR =  TypeConverter.toByte(resample(mask, true, dimensions), null).resetOffset();
        RegionPopulation res = new RegionPopulation(maskR, true);
        if (eraseTouchingContours) res.eraseTouchingContours(false);
        return res;
    }

    public static Selection getAllElements(MasterDAO mDAO, int objectClassIdx) {
        Selection sel = new Selection();
        for (String pos : mDAO.getExperiment().getPositionsAsString()) {
            sel.addElements(SegmentedObjectUtils.getAllObjectsAsStream(mDAO.getDao(pos), objectClassIdx).collect(Collectors.toList()));
        }
        return sel;
    }

    public static String getLabel(SegmentedObject e) {
        return Selection.indicesString(e.getTrackHead()) + "_f" + String.format("%05d", e.getFrame());
    }
    public static void extractFeature(Path outputPath, String dsName, Selection parentSel, String position, Function<SegmentedObject, Image> feature, boolean zToBatch, SCALE_MODE scaleMode, InterpolatorFactory interpolation, Map<String, Object> metadata, boolean oneEntryPerInstance, boolean saveLabels, boolean saveDimensions, int... dimensions) {
        Supplier<Stream<SegmentedObject>> streamSupplier = position==null ? () -> parentSel.getAllElements().stream().parallel() : () -> parentSel.getElements(position).stream().parallel();

        List<Image> images = streamSupplier.get().map(e -> { //skip(1).
            Image im = feature.apply(e);
            Image out = resample(im, interpolation, dimensions);
            out.setName(getLabel(e));
            return out;
        }).sorted(Comparator.comparing(Image::getName)).collect(Collectors.toList());

        int[][] originalDimensions = saveDimensions ? streamSupplier.get().sorted(Comparator.comparing(ExtractDatasetUtil::getLabel)).map(o->{
            if (o.is2D()) return new int[]{o.getBounds().sizeX(), o.getBounds().sizeY()};
            else return new int[]{o.getBounds().sizeX(), o.getBounds().sizeY(), o.getBounds().sizeZ()};
        }).toArray(int[][]::new) : null;
        if (zToBatch) {
            logger.debug("before ZToBatch: {}", images.size());
            Stream<Image> s = images.stream().flatMap(i -> i.splitZPlanes().stream());
            images = s.collect(Collectors.toList());
            logger.debug("after ZToBatch: {}", images.size());
            if (saveDimensions) {
                originalDimensions = streamSupplier.get().sorted(Comparator.comparing(ExtractDatasetUtil::getLabel)).flatMap(o -> Collections.nCopies(o.getBounds().sizeZ(), o).stream() ).map(o-> new int[]{o.getBounds().sizeX(), o.getBounds().sizeY()}).toArray(int[][]::new);
                logger.debug("after ZToBatch: dimensions {}", originalDimensions.length);
            }
        }
        if (ExtractDatasetUtil.display) images.stream().forEach(i -> Core.getCore().showImage(i));

        extractFeature(outputPath, dsName, images, scaleMode, metadata, saveLabels, originalDimensions, oneEntryPerInstance);
    }
    public static void extractFeature(Path outputPath, String dsName, List<Image> images, SCALE_MODE scaleMode, Map<String, Object> metadata, boolean saveLabels, int[][] originalDimensions, boolean oneEntryPerInstance) {
        if (scaleMode == SCALE_MODE.NO_SCALE && !images.isEmpty()) { // ensure all images have same bitdepth
            int maxBD = images.stream().mapToInt(Image::getBitDepth).max().getAsInt();
            if (images.stream().anyMatch(i->i.getBitDepth()!=maxBD)) {
                if (maxBD==32) scaleMode = SCALE_MODE.TO_FLOAT;
                else scaleMode = SCALE_MODE.TO_SHORT;
                logger.debug("Scale mode changed to : {} (max BD: {})", scaleMode, maxBD);
            }
        }

        if (metadata==null) metadata= new HashMap<>();
        Function<Image, Image> converter = null;
        switch (scaleMode) {
            case MAX_MIN_BYTE:
            case MAX_MIN_BYTE_SAFE_FLOAT:
                if (images.get(0).getBitDepth()>8) { // do not apply conversion in case images are already byte
                    DoubleSummaryStatistics stats = Image.stream(images).summaryStatistics();
                    double off;
                    if (stats.getMax() == stats.getMin() ) {
                        converter = im -> TypeConverter.toByte(im, null, d->(int)(0.5 + stats.getMax()));
                    } else {
                        double scale = 255d / (stats.getMax() - stats.getMin());
                        if (SCALE_MODE.MAX_MIN_BYTE_SAFE_FLOAT.equals(scaleMode) && stats.getMin() < 0) { // regularize so that 0 maps to an integer
                            double z = -stats.getMin() * scale;
                            int zi = (int) (z + 0.5);
                            double d = z - zi;
                            off = stats.getMin() + d / scale;
                            //logger.debug("regularize offset: min {}, z: {}, zi: {}, new off: {}", stats.getMin(), z, zi, off);
                        } else off = stats.getMin();
                        if (Double.isNaN(off) || Double.isInfinite(off) || Double.isNaN(scale) || Double.isInfinite(scale)) {
                            throw new RuntimeException("Invalid scaling factor: "+scale+ " or offset:"+off);
                        }
                        metadata.put("scaling_center", -off * scale);
                        metadata.put("scaling_factor", scale);
                        metadata.put("original_bitDepth", images.get(0).getBitDepth());
                        int zero = (int) (0.5 - off * scale);
                        DoubleToIntFunction scaler = SCALE_MODE.MAX_MIN_BYTE_SAFE_FLOAT.equals(scaleMode) ?
                                d -> {
                                    int res = (int) (0.5 + scale * (d - off));
                                    if (res == zero) { // avoid small values being mapped to zero during compression
                                        if (d > 0) ++res;
                                        else if (d < 0) --res;
                                    }
                                    return res;
                                } : d -> (int) (0.5 + scale * (d - off));
                        converter = im -> TypeConverter.toByte(im, null, scaler);
                    }
                }
                break;
            case MAX_MIN_SHORT:
                if (images.get(0).getBitDepth()>16) { // do not apply conversion in case images are already byte or short
                    DoubleSummaryStatistics stats = Image.stream(images).summaryStatistics();
                    double off;
                    if (stats.getMax() == stats.getMin()) {
                        converter = im -> TypeConverter.toShort(im, null, d->(int)(0.5 + stats.getMax()));
                    } else {
                        double scale =  65535d / (stats.getMax() - stats.getMin());
                        if (stats.getMin() < 0) { // regularize so that 0 maps to an integer
                            double z = -stats.getMin() * scale;
                            int zi = (int) (z + 0.5);
                            double d = z - zi;
                            off = stats.getMin() - d / scale;
                        } else off = stats.getMin();
                        if (Double.isNaN(off) || Double.isInfinite(off) || Double.isNaN(scale) || Double.isInfinite(scale)) {
                            throw new RuntimeException("Invalid scaling factor: "+scale+ " or offset:"+off);
                        }
                        metadata.put("scaling_center", -off * scale);
                        metadata.put("scaling_factor", scale);
                        metadata.put("original_bitDepth", images.get(0).getBitDepth());
                        DoubleToIntFunction scaler = d -> (int) (0.5 + scale * (d - off));
                        converter = im -> TypeConverter.toShort(im, null, scaler);
                    }
                }
                break;
            case TO_SHORT:
                converter = im -> im instanceof ImageShort ? im : TypeConverter.toShort(im, null);
                break;
            case TO_FLOAT:
                converter = im -> im instanceof ImageFloat ? im : TypeConverter.toFloat(im, null);
                break;
        }
        if (images.isEmpty()) return;
        metadata.put("scale_xy", images.get(0).getScaleXY());
        metadata.put("scale_z", images.get(0).getScaleZ());
        if (converter!=null) images = images.stream().parallel().map(converter).collect(Collectors.toList());
        int compression = GUI.getInstance()==null? 4 : GUI.getInstance().getExtractedDSCompressionFactor();
        if (oneEntryPerInstance) {
            for (int i = 0; i<images.size(); ++i) HDF5IO.savePyDataset(images.subList(i, i+1), outputPath.toFile(), true, dsName+"/"+images.get(i).getName(), compression, saveLabels, new int[][]{originalDimensions[i]}, metadata );
        } else HDF5IO.savePyDataset(images, outputPath.toFile(), true, dsName, compression, saveLabels, originalDimensions, metadata ); // TODO : compression level as option
    }

    public enum SCALE_MODE {NO_SCALE, MAX_MIN_BYTE_SAFE_FLOAT, MAX_MIN_BYTE, MAX_MIN_SHORT, TO_SHORT, TO_FLOAT}
}
