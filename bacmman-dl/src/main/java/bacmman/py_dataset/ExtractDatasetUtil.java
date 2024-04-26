package bacmman.py_dataset;

import bacmman.core.Core;
import bacmman.core.Task;
import bacmman.data_structure.*;
import bacmman.data_structure.dao.MasterDAO;
import bacmman.data_structure.input_image.InputImages;
import bacmman.image.*;
import bacmman.plugins.FeatureExtractor;
import bacmman.plugins.FeatureExtractorConfigurable;
import bacmman.plugins.FeatureExtractorOneEntryPerInstance;
import bacmman.plugins.FeatureExtractorTemporal;
import bacmman.plugins.plugins.feature_extractor.Labels;
import bacmman.plugins.plugins.feature_extractor.MultiClass;
import bacmman.plugins.plugins.feature_extractor.PreviousLinks;
import bacmman.plugins.plugins.feature_extractor.RawImage;
import bacmman.utils.ArrayUtil;
import bacmman.utils.HashMapGetCreate;
import bacmman.utils.Utils;
import ch.systemsx.cisd.hdf5.IHDF5Writer;
import net.imglib2.interpolation.InterpolatorFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
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
    public static  void runTask(Task t) {
        String outputFile = t.getExtractDSFile();
        Path outputPath = Paths.get(outputFile);
        int[] dimensions = t.getExtractDSDimensions();
        List<FeatureExtractor.Feature> features = t.getExtractDSFeatures();
        List<String> selectionNames = t.getExtractDSSelections();
        int subsamplingFactor = t.getExtractDSSubsamplingFactor();
        int subsamplingNumber = t.getExtractDSSubsamplingNumber();
        int[] subsamplingOffsets = ArrayUtil.generateIntegerArray(0, subsamplingFactor, subsamplingNumber);
        int spatialDownsamplingFactor = t.getExtractDSSpatialDownsamplingFactor();
        int compression = t.getExtractDSCompression();
        int[] eraseTouchingContoursOC = t.getExtractDSEraseTouchingContoursOC();
        boolean trackingDataset = t.isExtractDSTracking();
        IntPredicate eraseTouchingContours = oc -> Arrays.stream(eraseTouchingContoursOC).anyMatch(i->i==oc);
        MasterDAO mDAO = t.getDB();
        String ds = mDAO.getDBName();
        for (String selName : selectionNames) {
            logger.debug("Selection: {}", selName);
            Selection mainSel = mDAO.getSelectionDAO().getOrCreate(selName, false);
            List<Selection> trackSels;
            if (trackingDataset) { // split selection by contiguous track segment
                trackSels = new ArrayList<>();
                for (String pos : mainSel.getAllPositions()) {
                    Map<SegmentedObject, List<SegmentedObject>> tracks = SegmentedObjectUtils.splitByContiguousTrackSegment(mainSel.getElements(pos));
                    tracks.forEach( (th, els) -> {
                        Selection subSel = new Selection(mainSel.getName()+"/"+th.toStringShort(), mainSel.getStructureIdx(), mainSel.getMasterDAO());
                        subSel.addElements(els);
                        trackSels.add(subSel);
                    } );
                }
                if (trackSels.size() > 1) t.incrementTaskNumber(trackSels.size() * features.size() - mainSel.getAllPositions().size() * features.size());
            } else trackSels = Collections.singletonList(mainSel);
            for (Selection sel : trackSels) {
                if (test) {
                    Selection selT = new Selection("", sel.getStructureIdx(), mDAO);
                    Selection selF = sel;
                    String pos = sel.getAllPositions().stream().filter(p -> selF.count(p) > 0).findAny().orElse(null);
                    if (pos == null) continue;
                    selT.addElements(pos, sel.getElementStrings(pos).stream().limit(display ? 1 : 100).collect(Collectors.toList()));
                    sel = selT;
                    logger.debug("sel size: {}", sel.count());
                }
                for (String position : sel.getAllPositions()) {
                    logger.debug("position: {}", position);
                    Map<Integer, Map<SegmentedObject, RegionPopulation>> resampledPops = new HashMapGetCreate.HashMapGetCreateRedirectedSync<>(oc -> new HashMapGetCreate.HashMapGetCreateRedirectedSyncKey<>(parent -> {
                        if (parent.getStructureIdx() == oc) return null; // this case is handled separately
                        RegionPopulation pop = parent.getChildRegionPopulation(oc, false);
                        return resamplePopulation(pop, dimensions, spatialDownsamplingFactor, eraseTouchingContours.test(oc));
                    }));
                    String curSelName = sel.getName();
                    String thName = null;
                    if (curSelName.contains("/")) {
                        String[] split = curSelName.split("/");
                        curSelName = split[0];
                        thName = split[1];
                    }
                    String baseOutputName = (!curSelName.isEmpty() ? curSelName + "/" : "") + ds + "/" + position + "/";
                    if (thName != null) baseOutputName += thName + "/";
                    boolean saveLabels = subsamplingFactor==1; // HAS BEEN DISABLED
                    boolean filterParentSelection = Utils.objectsAllHaveSameProperty(features, FeatureExtractor.Feature::getSelectionFilter);
                    for (FeatureExtractor.Feature feature : features) {
                        boolean oneEntryPerInstance = feature.getFeatureExtractor() instanceof FeatureExtractorOneEntryPerInstance;
                        boolean temporal = feature.getFeatureExtractor() instanceof FeatureExtractorTemporal;
                        boolean configurable = feature.getFeatureExtractor() instanceof FeatureExtractorConfigurable;
                        logger.debug("feature: {} ({}), selection filter: {}", feature.getName(), feature.getFeatureExtractor().getClass().getSimpleName(), feature.getSelectionFilter());
                        Function<SegmentedObject, Image> extractFunction;
                        Selection parentSelection;
                        Map<Integer, Map<SegmentedObject, RegionPopulation>> curResamplePops;
                        Selection selFilter = feature.getSelectionFilter() == null ? null : mDAO.getSelectionDAO().getOrCreate(feature.getSelectionFilter(), false);
                        if (selFilter != null) {
                            Set<SegmentedObject> allElements = selFilter.hasElementsAt(position) ? selFilter.getElements(position) : Collections.emptySet();
                            Map<SegmentedObject, RegionPopulation> resampledPop = new HashMapGetCreate.HashMapGetCreateRedirectedSyncKey<>(parent -> {
                                List<Region> childrenFiltered = allElements.stream().filter(o -> o.getParent(parent.getStructureIdx()).equals(parent)).map(SegmentedObject::getRegion).collect(Collectors.toList());
                                logger.debug("extract: parent: {} children: {}", parent, childrenFiltered.stream().mapToInt(r -> r.getLabel() - 1).toArray());
                                RegionPopulation p = new RegionPopulation(childrenFiltered, parent.getMaskProperties());
                                return resamplePopulation(p, dimensions, spatialDownsamplingFactor, eraseTouchingContours.test(feature.getObjectClass()));
                            });
                            curResamplePops = new HashMapGetCreate.HashMapGetCreateRedirectedSync<>(oc -> {
                                if (oc == feature.getObjectClass()) return resampledPop;
                                else return resampledPops.get(oc);
                            });
                            if (filterParentSelection) {
                                if (oneEntryPerInstance) {
                                    Set<SegmentedObject> allParents = sel.hasElementsAt(position) ? sel.getElements(position) : Collections.emptySet();
                                    int parentSO = sel.getStructureIdx();
                                    parentSelection = Selection.generateSelection(sel.getName(), mDAO, new HashMap<String, List<SegmentedObject>>(1) {{
                                        put(position, allElements.stream().filter(o -> allParents.contains(o.getParent(parentSO))).collect(Collectors.toList()));
                                    }});
                                    parentSelection.setMasterDAO(mDAO);
                                } else {
                                    int parentOC = sel.getStructureIdx();
                                    List<SegmentedObject> allParents = allElements.stream().map(o -> o.getParent(parentOC)).distinct().collect(Collectors.toList());
                                    parentSelection = Selection.generateSelection(sel.getName(), mDAO, new HashMap<String, List<SegmentedObject>>(1) {{
                                        put(position, allParents);
                                    }});
                                    SelectionOperations.intersection(sel.getName(), parentSelection, sel);
                                    parentSelection.setMasterDAO(mDAO);
                                }
                                logger.debug("filter parent selection {} / {}", parentSelection.count(), sel.count());
                            } else parentSelection = sel;
                        } else {
                            curResamplePops = resampledPops;
                            parentSelection = sel;
                        }
                        if (!parentSelection.isEmpty()) {
                            for (int offset : subsamplingOffsets) {
                                Selection parentSubSelection;
                                if (subsamplingFactor <= 1) parentSubSelection = parentSelection;
                                else {
                                    parentSubSelection = new Selection(parentSelection.getName() + "sub", parentSelection.getMasterDAO());
                                    int off = offset;
                                    parentSubSelection.addElements(parentSelection.getElements(position).stream().filter(o -> o.getFrame() % subsamplingFactor == off).collect(Collectors.toList()));
                                }
                                String outputName = subsamplingFactor <= 1 ? baseOutputName : baseOutputName + "sub" + subsamplingFactor + "/" + "off" + Utils.formatInteger(subsamplingFactor - 1, 1, offset) + "/";
                                if (temporal)
                                    ((FeatureExtractorTemporal) feature.getFeatureExtractor()).setSubsampling(subsamplingFactor, offset);
                                if (configurable)
                                    ((FeatureExtractorConfigurable) feature.getFeatureExtractor()).configure(parentSubSelection.getAllElementsAsStream(), feature.getObjectClass());
                                extractFunction = e -> feature.getFeatureExtractor().extractFeature(e, feature.getObjectClass(), curResamplePops, spatialDownsamplingFactor, dimensions);
                                boolean ZtoBatch = feature.getFeatureExtractor().getExtractZDim() == Task.ExtractZAxis.BATCH;
                                extractFeature(outputPath, outputName + feature.getName(), parentSubSelection, position, extractFunction, ZtoBatch, SCALE_MODE.NO_SCALE, feature.getFeatureExtractor().interpolation(), null, oneEntryPerInstance, compression, saveLabels, saveLabels, spatialDownsamplingFactor, dimensions);
                            }
                            saveLabels = false;
                        }
                        t.incrementProgress();
                    }
                    resampledPops.clear();
                }
            }
        }
        // write histogram for raw features // DISABLED
        /*features.stream().filter(f->f.getFeatureExtractor() instanceof RawImage).map(FeatureExtractor.Feature::getName).forEach(channelName -> {
            for (int offset : subsamplingOffsets) write_histogram(outputPath.toFile(), selectionNames, ds, channelName, dimensions, subsamplingFactor, offset);
        });*/
    }
    public static void runTaskRaw(Task t) throws IOException  {
        logger.debug("extracting raw dataset 2...");
        String outputFile = t.getExtractRawDSFile();
        Path outputPath = Paths.get(outputFile);
        BoundingBox bounds = t.getExtractRawDSBounds();
        Map<String, List<Integer>> positionMapFrames = t.getExtractRawDSFrames();
        int[] channels = t.getExtractRawDSChannels();
        int compression = t.getExtractDSCompression();
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
            boolean saveLabels = false;
            for (int channel : channels) {
                String outputName = ds + "/" + position + "/" + channelNames[channel];
                List<Image> images= new ArrayList<>(frames.size());
                for (int fIdx : frames) {
                    images.add(extract.apply(inputImages.getImage(channel, fIdx).setName(getLabel(fIdx+inputImages.getMinFrame()))));
                }
                if (t.getExtractRawZAxis().equals(Task.ExtractZAxis.BATCH)) {
                    logger.debug("before ZToBatch: {}", images.size());
                    Stream<Image> s = images.stream().flatMap(i -> i.splitZPlanes().stream());
                    images = s.collect(Collectors.toList());
                    logger.debug("after ZToBatch: {}", images.size());
                }
                extractFeature(outputPath, outputName, images, SCALE_MODE.NO_SCALE, null, saveLabels, null, false, compression);
                saveLabels = false;
            }
            inputImages.flush();
            t.incrementProgress();
        }
    }

    public static void write_histogram(File outputPath, List<String> selectionNames, String dbName, String channelName, int[] dimensions, int subsamplingFactor, int subsamplingOffset) {
        PyDatasetReader reader = new PyDatasetReader(outputPath);
        Map<String, Histogram> histos = new HashMap<>(selectionNames.size());
        for (String selName : selectionNames) {
            PyDatasetReader.DatasetAccess dsA = reader.getDatasetAccess(selName, dbName, subsamplingFactor, subsamplingOffset);
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

    private static RegionPopulation resamplePopulation(RegionPopulation pop, int[] dimensions, int downsamplingFactor, boolean eraseTouchingContours) {
        Image mask = pop.getLabelMap();
        ImageInteger maskR;
        dimensions = getDimensions(mask.dimensions(), dimensions, downsamplingFactor);
        if (mask instanceof ImageShort) maskR =  TypeConverter.toShort(resample(mask, true, dimensions), null).resetOffset();
        else maskR =  TypeConverter.toByte(resample(mask, true, dimensions), null).resetOffset();
        RegionPopulation res = new RegionPopulation(maskR, true);
        if (eraseTouchingContours) res.eraseTouchingContours(false);
        return res;
    }

    public static int[] getDimensions(int[] originalDimensions, int[] targetDimensions, int downsamplingFactor) {
        return IntStream.range(0, originalDimensions.length).map(i -> targetDimensions==null || targetDimensions.length<=i || targetDimensions[i]<=0 ? originalDimensions[i] : targetDimensions[i]).map(d -> d / downsamplingFactor).toArray();
    }

    public static Selection getAllElements(MasterDAO mDAO, int objectClassIdx) {
        Selection sel = new Selection();
        for (String pos : mDAO.getExperiment().getPositionsAsString()) {
            sel.addElements(SegmentedObjectUtils.getAllObjectsAsStream(mDAO.getDao(pos), objectClassIdx).collect(Collectors.toList()));
        }
        return sel;
    }

    public static String getLabel(SegmentedObject e) {
        return Selection.indicesString(e.getTrackHead()) + "_" + getLabel(e.getFrame());
    }
    public static String getLabel(int frame) {
        return "f" + String.format("%05d", frame);
    }
    public static void extractFeature(Path outputPath, String dsName, Selection parentSel, String position, Function<SegmentedObject, Image> feature, boolean zToBatch, SCALE_MODE scaleMode, InterpolatorFactory interpolation, Map<String, Object> metadata, boolean oneEntryPerInstance, int compression, boolean saveLabels, boolean saveDimensions, int downsamplingFactor, int[] dimensions) {
        Supplier<Stream<SegmentedObject>> streamSupplier = position==null ? () -> parentSel.getAllElementsAsStream().parallel() : () -> parentSel.getElementsAsStream(Stream.of(position)).parallel();
        logger.debug("resampling..");
        List<Image> images = streamSupplier.get().map(e -> { //skip(1).
            Image im = feature.apply(e);
            int[] dimensions_ = getDimensions(e.is2D() ? new int[]{e.getBounds().sizeX(), e.getBounds().sizeY()} : new int[]{e.getBounds().sizeX(), e.getBounds().sizeY(), e.getBounds().sizeZ()}, dimensions, downsamplingFactor);
            Image out = resample(im, interpolation, dimensions_);
            out.setName(getLabel(e));
            return out;
        }).sorted(Comparator.comparing(Image::getName)).collect(Collectors.toList());
        logger.debug("resampling done");
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
        extractFeature(outputPath, dsName, images, scaleMode, metadata, saveLabels, originalDimensions, oneEntryPerInstance, compression);
    }
    public static void extractFeature(Path outputPath, String dsName, List<Image> images, SCALE_MODE scaleMode, Map<String, Object> metadata, boolean saveLabels, int[][] originalDimensions, boolean oneEntryPerInstance, int compression) {
        Image type = Image.copyType(images.stream().max(PrimitiveType.typeComparator()).get());
        int originalBitDepth = TypeConverter.toCommonImageType(type).byteCount() * 8;
        boolean originalIsFloat = type.floatingPoint();
        if (scaleMode == SCALE_MODE.NO_SCALE && !images.isEmpty()) { // ensure all images have same type
            if (images.stream().anyMatch(i->!type.getClass().equals(i.getClass()))) { // if there are different types -> need to convert
                if (type.floatingPoint()) scaleMode = SCALE_MODE.TO_FLOAT;
                else scaleMode = SCALE_MODE.TO_SHORT;
                logger.debug("Scale mode changed to : {} (type: {})", scaleMode, type);
            }
        }

        if (metadata==null) metadata= new HashMap<>();
        Function<Image, Image> converter = null;
        switch (scaleMode) {
            case MAX_MIN_BYTE:
            case MAX_MIN_BYTE_SAFE_FLOAT:
                if ( !(images.get(0) instanceof ImageByte) ) { // do not apply conversion in case images are already byte
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
                        metadata.put("original_bitDepth", originalBitDepth);
                        metadata.put("original_is_float", originalIsFloat);
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
                if ( !(images.get(0) instanceof ImageByte) && !(images.get(0) instanceof ImageShort)  ) { // do not apply conversion in case images are already byte or short
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
                        metadata.put("original_bitDepth", originalBitDepth);
                        metadata.put("original_is_float", originalIsFloat);
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
        if (converter!=null) {
            logger.debug("converting type...");
            images = images.stream().parallel().map(converter).collect(Collectors.toList());
            logger.debug("converting done.");
        }
        logger.debug("saving h5 file...");
        if (oneEntryPerInstance) {
            for (int i = 0; i<images.size(); ++i) HDF5IO.savePyDataset(images.subList(i, i+1), outputPath.toFile(), true, dsName+"/"+images.get(i).getName(), compression, saveLabels, new int[][]{originalDimensions[i]}, metadata );
        } else HDF5IO.savePyDataset(images, outputPath.toFile(), true, dsName, compression, saveLabels, originalDimensions, metadata ); // TODO : compression level as option
        logger.debug("saving done.");
    }

    public static Task getPixMClassDatasetTask(MasterDAO mDAO, int[] channelIndices, int[] objectClasses, List<String> selections, String outputFile, int compression) throws IllegalArgumentException {
        if (objectClasses.length!=2 && objectClasses.length!=3) throw new IllegalArgumentException("Select 2 or 3 object classes: background, foreground (and contours)");
        if (!Utils.objectsAllHaveSameProperty(Arrays.stream(objectClasses).boxed().collect(Collectors.toList()), mDAO.getExperiment().experimentStructure::getParentObjectClassIdx)) {
            throw new IllegalArgumentException("All selected object classes must have same parent object class");
        }


        Task resultingTask = new Task(mDAO);
        List<FeatureExtractor.Feature> features = new ArrayList<>();
        ExperimentStructure xp = mDAO.getExperiment().experimentStructure;
        List<String> channelNames = IntStream.of(channelIndices).mapToObj(c -> xp.getChannelNames()[c]).collect(Collectors.toList());
        for (int c : channelIndices) {
            int oc = xp.getObjectClassIdx(c);
            if (oc<0) throw new RuntimeException("Channel: "+c+ " has not associated object class");
            features.add(new FeatureExtractor.Feature( channelIndices.length == 1 ? new RawImage().defaultName() : channelNames.get(c), new RawImage(), oc));
        }
        features.add(new FeatureExtractor.Feature( new MultiClass(objectClasses), objectClasses[0] ));

        int[] dims = new int[]{0, 0};
        int[] eraseContoursOC = new int[0];
        resultingTask.setExtractDS(outputFile, selections, features, dims, eraseContoursOC, false, 1, 1, 1, compression);
        return resultingTask;
    }

    public static Task getDiSTNetDatasetTask(MasterDAO mDAO, int objectClass, int[] outputDimensions, List<String> selections, String selectionFilter, String outputFile, int spatialDownSampling, int subSamplingFactor, int subSamplingNumber, int compression) throws IllegalArgumentException {
        Task resultingTask = new Task(mDAO);
        List<FeatureExtractor.Feature> features = new ArrayList<>(3);
        features.add(new FeatureExtractor.Feature( new RawImage(), objectClass ));
        features.add(new FeatureExtractor.Feature( new Labels(), objectClass, selectionFilter ));
        features.add(new FeatureExtractor.Feature( new PreviousLinks(), objectClass, selectionFilter ));

        int[] eraseContoursOC = new int[0];
        resultingTask.setExtractDS(outputFile, selections, features, outputDimensions, eraseContoursOC, true, spatialDownSampling, subSamplingFactor, subSamplingNumber, compression);
        return resultingTask;
    }

    public static Task getDiSTNetSegDatasetTask(MasterDAO mDAO, int objectClass, int channelImage, Task.ExtractZAxis extractZMode, int extractZPlane, String selection, String filterSelection, String outputFile, int spatialDownSampling, int compression) throws IllegalArgumentException {
        Task resultingTask = new Task(mDAO);
        int rawOC = mDAO.getExperiment().experimentStructure.getObjectClassIdx(channelImage);
        if (rawOC<0) throw new RuntimeException("Channel: "+channelImage+ " has not associated object class");
        List<FeatureExtractor.Feature> features = new ArrayList<>(3);
        features.add(new FeatureExtractor.Feature( new RawImage().setExtractZ(extractZMode, extractZPlane), rawOC ));
        features.add(new FeatureExtractor.Feature( new Labels(), objectClass, filterSelection ));
        int[] dims = new int[]{0, 0};
        int[] eraseContoursOC = new int[0];
        resultingTask.setExtractDS(outputFile, Collections.singletonList(selection), features, dims, eraseContoursOC, false, spatialDownSampling, 1, 1, compression);
        return resultingTask;
    }

    public static Task getDenoisingDatasetTask(MasterDAO mDAO, int[] objectClasses, List<String> position, String outputFile, int compression) throws IllegalArgumentException {
        if (objectClasses.length!=1) throw new IllegalArgumentException("Select a single object classes");
        int channelIdx = mDAO.getExperiment().experimentStructure.getChannelIdx(objectClasses[0]);
        if (position.isEmpty()) throw new IllegalArgumentException("Select at least one position");
        Map<String, List<Integer>> positionMapFrames = position.stream().collect(Collectors.toMap(p -> p, p -> IntStream.range(0, mDAO.getExperiment().getPosition(p).getInputImages().getFrameNumber()).boxed().collect(Collectors.toList()) ));

        Task resultingTask = new Task(mDAO);
        List<FeatureExtractor.Feature> features = new ArrayList<>(3);
        features.add(new FeatureExtractor.Feature( new RawImage(), objectClasses[0] ));
        features.add(new FeatureExtractor.Feature( new MultiClass(objectClasses), objectClasses[0] ));

        resultingTask.setExtractRawDS(outputFile, new int[]{channelIdx}, new SimpleBoundingBox(0, 0,0, 0, 0, 0), Task.ExtractZAxis.BATCH, 0,  positionMapFrames, compression);
        return resultingTask;
    }

    public enum SCALE_MODE {NO_SCALE, MAX_MIN_BYTE_SAFE_FLOAT, MAX_MIN_BYTE, MAX_MIN_SHORT, TO_SHORT, TO_FLOAT}

    // templates


}
