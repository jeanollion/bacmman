package bacmman.py_dataset;

import bacmman.core.Core;
import bacmman.core.Task;
import bacmman.data_structure.*;
import bacmman.data_structure.dao.MasterDAO;
import bacmman.data_structure.input_image.InputImages;
import bacmman.image.*;
import bacmman.plugins.FeatureExtractor;
import bacmman.plugins.plugins.feature_extractor.RawImage;
import bacmman.utils.HashMapGetCreate;
import bacmman.utils.Triplet;
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
        List<Triplet<String, FeatureExtractor, Integer>> features = t.getExtractDSFeatures();
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
                Map<Integer, Map<SegmentedObject, RegionPopulation>> resampledPops= new HashMapGetCreate.HashMapGetCreateRedirectedSync<>(oc ->  ExtractDatasetUtil.getResampledPopMap(oc, false, dimensions, eraseTouchingContours.test(oc)));
                String outputName = (selName.length() > 0 ? selName + "/" : "") + ds + "/" + position + "/";
                boolean saveLabels = true;
                for (Triplet<String, FeatureExtractor, Integer> feature : features) {
                    logger.debug("feature: {}", feature);
                    Function<SegmentedObject, Image> extractFunction = e -> feature.v2.extractFeature(e, feature.v3, resampledPops.get(feature.v3), dimensions);
                    extractFeature(outputPath, outputName + feature.v1, sel, position, extractFunction, SCALE_MODE.NO_SCALE, feature.v2.interpolation(), null, saveLabels,  saveLabels, dimensions);
                    saveLabels=false;
                    t.incrementProgress();
                }
                resampledPops.clear();
            }
        }
        // write histogram for raw features
        features.stream().filter(f->f.v2 instanceof RawImage).map(f->f.v1).forEach(channelName -> {
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
        Function<Image, Image> crop = image -> {
            if (bounds==null) return image;
            MutableBoundingBox bds = new MutableBoundingBox(bounds);
            if (bds.sizeX()<=0 || bds.xMax()>image.xMax()) bds.setxMax(image.xMax());
            if (bds.sizeY()<=0 || bds.yMax()>image.yMax()) bds.setyMax(image.yMax());
            if (bds.sizeZ()<=0 || bds.zMax()>image.zMax()) bds.setzMax(image.zMax());
            return image.crop(bds);
        };
        for (String position : positionMapFrames.keySet()) {
            logger.debug("position: {}", position);
            InputImages inputImages = mDAO.getExperiment().getPosition(position).getInputImages();
            List<Integer> frames = positionMapFrames.get(position);
            boolean saveLabels = true;
            for (int channel : channels) {
                String outputName = ds + "/" + position + "/" + channelNames[channel];
                List<Image> images = frames.stream().map(fIdx -> inputImages.getImage(channel, fIdx).setName("Frame:"+fIdx)).map(crop).collect(Collectors.toList());
                extractFeature(outputPath, outputName, images, SCALE_MODE.NO_SCALE, null, saveLabels, null);
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
    public static Map<SegmentedObject, RegionPopulation> getResampledPopMap(int objectClassIdx, boolean shortMask, int[] dimensions, boolean eraseTouchingContours) {
        return new HashMapGetCreate.HashMapGetCreateRedirectedSyncKey<>(o -> {
            Image mask = o.getChildRegionPopulation(objectClassIdx).getLabelMap();
            ImageInteger maskR;
            if (shortMask) {
                if (!(mask instanceof ImageShort) || mask.sizeX()!=dimensions[0] || mask.sizeY()!=dimensions[1]) maskR =  TypeConverter.toShort(resample(mask, true, dimensions), null).resetOffset();
                else maskR = (ImageShort) mask.resetOffset();
            } else {
                if (!(mask instanceof ImageByte) || mask.sizeX()!=dimensions[0] || mask.sizeY()!=dimensions[1]) maskR =  TypeConverter.toByte(resample(mask, true, dimensions), null).resetOffset();
                else maskR = (ImageByte)mask.resetOffset();
            }
            RegionPopulation res = new RegionPopulation(maskR, true);
            if (eraseTouchingContours) res.eraseTouchingContours(false);
            return res;
        });
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
    public static void extractFeature(Path outputPath, String dsName, Selection sel, String position, Function<SegmentedObject, Image> feature, SCALE_MODE scaleMode, InterpolatorFactory interpolation, Map<String, Object> metadata, boolean saveLabels, boolean saveDimensions, int... dimensions) {
        Supplier<Stream<SegmentedObject>> streamSupplier = position==null ? () -> sel.getAllElements().stream().parallel() : () -> sel.getElements(position).stream().parallel();

        List<Image> images = streamSupplier.get().map(e -> { //skip(1).
            Image im = feature.apply(e);
            Image out = resample(im, interpolation, dimensions);
            out.setName(getLabel(e));
            return out;
        }).sorted(Comparator.comparing(Image::getName)).collect(Collectors.toList());

        int[][] originalDimensions = saveDimensions ? streamSupplier.get().sorted(Comparator.comparing(e->getLabel(e))).map(o->{
            if (o.is2D()) return new int[]{o.getBounds().sizeX(), o.getBounds().sizeY()};
            else return new int[]{o.getBounds().sizeX(), o.getBounds().sizeY(), o.getBounds().sizeZ()};
        }).toArray(int[][]::new) : null;
        if (ExtractDatasetUtil.display) images.stream().forEach(i -> Core.getCore().showImage(i));

        extractFeature(outputPath, dsName, images, scaleMode, metadata, saveLabels, originalDimensions);
    }
    public static void extractFeature(Path outputPath, String dsName, List<Image> images, SCALE_MODE scaleMode, Map<String, Object> metadata, boolean saveLabels, int[][] originalDimensions) {

        if (scaleMode == SCALE_MODE.NO_SCALE && !images.isEmpty()) { // ensure all images have same bitdepth
            int maxBD = images.stream().mapToInt(Image::getBitDepth).max().getAsInt();
            if (images.stream().anyMatch(i->i.getBitDepth()!=maxBD)) {
                if (maxBD==32) scaleMode = SCALE_MODE.TO_FLOAT;
                else scaleMode = SCALE_MODE.TO_SHORT;
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

        HDF5IO.savePyDataset(images, outputPath.toFile(), true, dsName, 4, saveLabels, originalDimensions, metadata );
    }

    public enum WEIGHT_MAP {NONE, UNET, DELTA, DY}

    public enum SCALE_MODE {NO_SCALE, MAX_MIN_BYTE_SAFE_FLOAT, MAX_MIN_BYTE, MAX_MIN_SHORT, TO_SHORT, TO_FLOAT}
}
