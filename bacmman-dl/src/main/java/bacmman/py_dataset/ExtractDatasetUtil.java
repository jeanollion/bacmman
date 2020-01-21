package bacmman.py_dataset;

import bacmman.core.Core;
import bacmman.core.ProgressCallback;
import bacmman.core.Task;
import bacmman.data_structure.*;
import bacmman.data_structure.dao.MasterDAO;
import bacmman.image.*;
import bacmman.processing.EDT;
import bacmman.processing.ImageOperations;
import bacmman.utils.HashMapGetCreate;
import bacmman.utils.Triplet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.function.DoubleToIntFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static bacmman.processing.Resample.resample;

public class ExtractDatasetUtil {
    public static boolean display=false, test=false;
    private final static Logger logger = LoggerFactory.getLogger(ExtractDatasetUtil.class);
    public static void runTask(Task t) {
        String outputFile = t.getExtractDSFile();
        Path outputPath = Paths.get(outputFile);
        int[] dimensions = t.getExtractDSDimensions();
        List<Triplet<String, Task.EXTRACT_TYPE, Integer>> features = t.getExtractDSFeatures();
        List<String> selectionNames = t.getExtractDSSelections();

        MasterDAO mDAO = t.getDB();
        String ds = mDAO.getDBName();
        boolean eraseTouchingContours = features.stream().anyMatch(f->f.v2.equals(Task.EXTRACT_TYPE.UNET_WEIGHT_MAP)||f.v2.equals(Task.EXTRACT_TYPE.DELTA_WEIGHT_MAP)); // if a feature is a weight map ?
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
                Map<Integer, Map<SegmentedObject, RegionPopulation>> resampledPops= new HashMapGetCreate.HashMapGetCreateRedirected<>(oc -> {
                    Map<SegmentedObject, RegionPopulation> res = ExtractDatasetUtil.getResampledPopMap(oc, false, dimensions);
                    if (eraseTouchingContours) res.values().parallelStream().forEach(pop -> pop.eraseTouchingContours(false));
                    return res;
                });
                String outputName = (selName.length() > 0 ? selName + "/" : "") + ds + "/" + position + "/";
                for (Triplet<String, Task.EXTRACT_TYPE, Integer> feature : features) {
                    logger.debug("feature: {}", feature);
                    switch(feature.v2) {
                        case RAW: {
                            ExtractDatasetUtil.extractRaw(outputPath, outputName + feature.v1, sel, position, feature.v3, false, dimensions);
                            break;
                        }
                        case LABEL: {
                            Map<SegmentedObject, RegionPopulation> resampledPop = resampledPops.get(feature.v3);
                            ExtractDatasetUtil.extractLabel(outputPath, outputName + feature.v1, sel, position, resampledPop, dimensions);
                            break;
                        }
                        case PREVIOUS_LABEL: {
                            Map<SegmentedObject, RegionPopulation> resampledPop = resampledPops.get(feature.v3);
                            ExtractDatasetUtil.extractPreviousLabel(outputPath, outputName + feature.v1, sel, position, 1, resampledPop, dimensions);
                            break;
                        }
                        case UNET_WEIGHT_MAP: {
                            Map<SegmentedObject, RegionPopulation> resampledPop = resampledPops.get(feature.v3);
                            ExtractDatasetUtil.extractWeightMap(outputPath, outputName + feature.v1, sel, position, resampledPop, 10, 5, WEIGHT_MAP.UNET, dimensions);
                            break;
                        }
                        case DELTA_WEIGHT_MAP: {
                            Map<SegmentedObject, RegionPopulation> resampledPop = resampledPops.get(feature.v3);
                            ExtractDatasetUtil.extractWeightMap(outputPath, outputName + feature.v1, sel, position, resampledPop, 10, 5, WEIGHT_MAP.DELTA, dimensions);
                            break;
                        }
                    }
                    t.incrementProgress();
                }
                resampledPops.clear();
            }
        }
    }

    public static Map<SegmentedObject, RegionPopulation> getResampledPopMap(int objectClassIdx, boolean shortMask, int[] dimensions) {
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
            return res;
        });
    }

    public static void extractWeightMap(Path outputPath, String dsName, Selection sel, String position, Map<SegmentedObject, RegionPopulation> ressampledMap, final double wo, final double sigma, WEIGHT_MAP weightMap, int... dimensions) {
        Function<SegmentedObject, Image> dis = e -> {
            RegionPopulation curPop = ressampledMap.get(e);
            return computeWeightMap(curPop, wo, sigma, weightMap); // if one is short, all should be converted to short
        };
        extractFeature(outputPath, dsName, sel, position, dis, SCALE_MODE.MAX_MIN_BYTE_SAFE_FLOAT, false, null, false, false, dimensions);
    }

    public static Selection getAllElements(MasterDAO mDAO, int objectClassIdx) {
        Selection sel = new Selection();
        for (String pos : mDAO.getExperiment().getPositionsAsString()) {
            sel.addElements(SegmentedObjectUtils.getAllObjectsAsStream(mDAO.getDao(pos), objectClassIdx).collect(Collectors.toList()));
        }
        return sel;
    }

    @FunctionalInterface
    private interface GetWeight {
        double apply(int x, int y, int z);
    }
    public static Image computeWeightMap(RegionPopulation pop, final double wo, final double sigma, WEIGHT_MAP weightMap) {
        if (pop.getRegions().isEmpty() || WEIGHT_MAP.NONE.equals(weightMap)) {
            Image res = new ImageFloat("", pop.getImageProperties());
            ImageOperations.fill(res, 1, null);
            return res;
        }
        // compute class frequency
        double foreground = pop.getRegions().stream().mapToDouble(r->r.size()).sum();
        int total = pop.getImageProperties().sizeXY();
        double background = total - foreground;
        double[] wc = new double[]{1, background  / foreground};
        ImageInteger allRegions = pop.getLabelMap();
        GetWeight getWeight;
        switch (weightMap) {
            case UNET:
            case DELTA:
            default:
            {   // compute distance maps from each object
                ImageFloat[] edms = pop.getRegions().stream().map(r -> {
                    ImageByte mask = new ImageByte("", pop.getImageProperties());
                    r.draw(mask, 1, mask);
                    return EDT.transform(mask, false, 1, 1, false); // computeWeightMap is already called in MT
                }).toArray(ImageFloat[]::new);
                double s2 = sigma * sigma * 2;
                getWeight = (x, y, z) -> {
                    if (allRegions.insideMask(x, y, z)) return wc[1];
                    if (edms.length>1) {
                        double[] minDists = Arrays.stream(edms).mapToDouble(edm -> edm.getPixel(x, y, z)).sorted().limit(2).toArray();
                        return wc[0] + wo * Math.exp( - Math.pow(minDists[0] + minDists[1], 2) / s2);
                    } else return wc[0];
                };
                break;
            }
            case DY: {
                getWeight = (x, y, z) -> {
                    if (allRegions.insideMask(x, y, z)) return wc[1];
                    else return wc[0];
                };
                break;
            }
        }
        ImageFloat res = new ImageFloat("weight map", pop.getImageProperties());
        BoundingBox.loop(res, (x, y, z) -> res.setPixel(x, y, z, getWeight.apply(x, y, z)));
        if (WEIGHT_MAP.DELTA.equals(weightMap) || WEIGHT_MAP.DY.equals(weightMap)) {
            pop.getRegions().forEach(r -> {
                r.getContour().forEach(v -> res.setPixel(v.x, v.y, v.z, 0));
            });
        }
        return res;
    }

    public static String getLabel(SegmentedObject e) {
        return Selection.indicesString(e.getTrackHead()) + "_f" + String.format("%05d", e.getFrame());
    }

    public static void extractFeature(Path outputPath, String dsName, Selection sel, String position, Function<SegmentedObject, Image> feature, SCALE_MODE scaleMode, boolean binary, Map<String, Object> metadata, boolean saveLabels, boolean saveDimensions, int... dimensions) {
        Supplier<Stream<SegmentedObject>> streamSupplier = position==null ? () -> sel.getAllElements().stream().parallel() : () -> sel.getElements(position).stream().parallel();

        List<Image> images = streamSupplier.get().map(e -> { //skip(1).
            Image im = feature.apply(e);
            Image out = resample(im, binary, dimensions);
            out.setName(getLabel(e));
            return out;
        }).sorted(Comparator.comparing(Image::getName)).collect(Collectors.toList());

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
        }
        metadata.put("scale_xy", images.get(0).getScaleXY());
        metadata.put("scale_z", images.get(0).getScaleZ());
        if (converter!=null) images = images.stream().parallel().map(converter).collect(Collectors.toList());

        int[][] originalDimensions = saveDimensions ? streamSupplier.get().sorted(Comparator.comparing(e->getLabel(e))).map(o->{
            if (o.is2D()) return new int[]{o.getBounds().sizeX(), o.getBounds().sizeY()};
            else return new int[]{o.getBounds().sizeX(), o.getBounds().sizeY(), o.getBounds().sizeZ()};
        }).toArray(int[][]::new) : null;
        if (ExtractDatasetUtil.display) images.stream().forEach(i -> Core.getCore().showImage(i));
        HDF5IO.savePyDataset(images, outputPath.toFile(), true, dsName, 4, saveLabels, originalDimensions, metadata );
    }

    public static void extractLabel(Path outputPath, String dsName, Selection sel, String position, Map<SegmentedObject, RegionPopulation> resampledMap, int... dimensions) {
        Function<SegmentedObject, Image> dis = e -> {
            RegionPopulation curPop = resampledMap.get(e);
            return curPop.getLabelMap(); // if one is short, all should be converted to short
        };
        extractFeature(outputPath, dsName, sel, position, dis, SCALE_MODE.NO_SCALE, true, null, false, false, dimensions);
    }

    public static void extractPreviousLabel(Path outputPath, String dsName, Selection sel, String position, int objectClassIdx, Map<SegmentedObject, RegionPopulation> ressampledMap, int... dimensions) {
        Function<SegmentedObject, Image> dis = e -> {
            RegionPopulation curPop = ressampledMap.get(e);
            Image prevLabel = new ImageByte("", curPop.getImageProperties());
            if (e.getPrevious()!=null) { // if first frame previous image is self: no previous labels
                e.getChildren(objectClassIdx).filter(c->c.getPrevious()!=null).forEach(c -> {
                    Region r = curPop.getRegion(c.getIdx()+1);
                    r.draw(prevLabel, c.getPrevious().getIdx()+1);
                });
            }
            return prevLabel;
        };
        extractFeature(outputPath, dsName, sel, position, dis, SCALE_MODE.NO_SCALE, true, null, false, false, dimensions);
    }

    public static void extractRaw(Path outputPath, String dsName, Selection sel, String position, int objectClassIdx, boolean convertToByte, int... dimensions) {
        Function<SegmentedObject, Image> extractRaw = e -> {
            Image im = e.getRawImage(objectClassIdx);
            if (convertToByte) im = TypeConverter.toFloat(im, null); // so that only one conversion float->int is done during resampling
            return im;
        };
        extractFeature(outputPath, dsName, sel, position, extractRaw, convertToByte ? SCALE_MODE.MAX_MIN_BYTE : SCALE_MODE.NO_SCALE, false, null, true,  true, dimensions);
    }

    public enum WEIGHT_MAP {NONE, UNET, DELTA, DY}

    public enum SCALE_MODE {NO_SCALE, MAX_MIN_BYTE_SAFE_FLOAT, MAX_MIN_BYTE, MAX_MIN_SHORT}
}
