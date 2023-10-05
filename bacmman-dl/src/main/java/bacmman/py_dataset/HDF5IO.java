package bacmman.py_dataset;

import bacmman.core.ProgressCallback;
import bacmman.image.Image;
import bacmman.image.ImageInteger;
import bacmman.processing.ImageOperations;
import bacmman.utils.ArrayUtil;
import ch.systemsx.cisd.base.mdarray.*;
import ch.systemsx.cisd.hdf5.*;
import ij.ImagePlus;
import ij.ImageStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntFunction;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public class HDF5IO {
    public final static Logger logger = LoggerFactory.getLogger(HDF5IO.class);
    public static IHDF5Writer getWriter(File outFile, boolean append) {
        IHDF5WriterConfigurator conf = HDF5Factory.configure(outFile.getAbsolutePath()).syncMode(
                IHDF5WriterConfigurator.SyncMode.SYNC_BLOCK)
                .useSimpleDataSpaceForAttributes();
        if (!append) conf = conf.overwrite();
        return conf.writer();
    }

    public static void saveImage(ImagePlus imp, IHDF5Writer writer, String dsName, int compressionLevel, ProgressCallback pr)  {
        if (imp.getNSlices() == 1) save2DImage(imp, writer, dsName, compressionLevel, pr);
        else save3DImage(imp, writer, dsName, compressionLevel, pr);
    }

    public static void save2DImage(ImagePlus imp, IHDF5Writer writer, String dsName, int compressionLevel, ProgressCallback pr) {
        int T = imp.getNFrames();
        int Z = imp.getNSlices();
        int N = T * Z;
        int C = imp.getNChannels();
        int W = imp.getWidth();
        int H = imp.getHeight();
        int sliceSize = H*W;
        long[] dims = { N, C, H, W };
        int[] blockDims = { 1, 1, H, W };
        long[] blockIdx = { 0, 0, 0, 0 };

        //double[] elSize = getElementSizeUm(imp);
        DTYPE type = getBitDepth(imp);
        MDAbstractArray data = getArray(writer, dsName, dims, blockDims, type, compressionLevel);
        Object dataFlat = data.getAsFlatArray();

        for (int t = 0; t < T; ++t) {
            for (int z = 0; z < Z; ++z, ++blockIdx[0]) {
                for (int c = 0; c < C; ++c) {
                    //if (pr != null) pr.log("Saving " + dsName + " t=" + t + ", z=" + z + ", c=" + c);
                    blockIdx[1] = c;
                    writeSlice(writer, imp, data, dataFlat, dsName, type, blockIdx, sliceSize, c, z, t);
                }
            }
        }
        //writer.float64().setArrayAttr(dsName, "element_size_um", elSize);
    }
    public static void saveImage(Image image, IHDF5Writer writer, String dsName, int compressionLevel) {
        int Z = image.sizeZ();
        int W = image.sizeX();
        int H = image.sizeY();
        long[] dims = { Z, H, W };
        int[] blockDims = { 1, H, W };
        long[] blockIdx = { 0, 0, 0 };

        DTYPE type = getBitDepth(image);
        MDAbstractArray data = getArray(writer, dsName, dims, blockDims, type, compressionLevel);
        Object dataFlat = data.getAsFlatArray();
        for (int z = 0; z < Z; ++z, ++blockIdx[0]) {
            blockIdx[0] = z;
            writeSlice(writer, image, data, dataFlat, dsName, type, blockIdx, z);
        }
    }
    public enum DTYPE {BYTE, SHORT, FLOAT, INT32}
    public static DTYPE getBitDepth(ImagePlus imp) {
        switch (imp.getBitDepth()) {
            case 8: return DTYPE.BYTE;
            case 16: return DTYPE.SHORT;
            case 32: return DTYPE.FLOAT;
            default: throw new IllegalArgumentException("Bit depth not supported");
        }
    }
    public static DTYPE getBitDepth(Image image) {
        switch (image.getBitDepth()) {
            case 8: return DTYPE.BYTE;
            case 16: return DTYPE.SHORT;
            case 32: {
                if (image instanceof ImageInteger) return DTYPE.INT32;
                else return DTYPE.FLOAT;
            }
            default: throw new IllegalArgumentException("Bit depth not supported");
        }
    }
    private static MDAbstractArray getArray(IHDF5Writer writer, String dsName, long[] dims, int[] blockDims, DTYPE type, int compressionLevel) {
        switch (type) {
            case FLOAT:
            default:
                writer.float32().createMDArray(dsName, dims, blockDims, HDF5FloatStorageFeatures.createDeflationDelete(compressionLevel));
                return new MDFloatArray(blockDims);
            case SHORT:
                writer.uint16().createMDArray(dsName, dims, blockDims, HDF5IntStorageFeatures.createDeflationUnsignedDelete(compressionLevel));
                return new MDShortArray(blockDims);
            case BYTE:
                writer.uint8().createMDArray(dsName, dims, blockDims, HDF5IntStorageFeatures.createDeflationUnsignedDelete(compressionLevel));
                return new MDByteArray(blockDims);
            case INT32:
                writer.int32().createMDArray(dsName, dims, blockDims, HDF5IntStorageFeatures.createDeflationUnsignedDelete(compressionLevel));
                return new MDIntArray(blockDims);
        }
    }
    public static void save3DImage(ImagePlus imp, IHDF5Writer writer, String dsName, int compressionLevel, ProgressCallback pr) {
        int T = imp.getNFrames();
        int Z = imp.getNSlices();
        int C = imp.getNChannels();
        int W = imp.getWidth();
        int H = imp.getHeight();
        long[] dims = { T, C, Z, H, W };
        int[] blockDims = { 1, 1, 1, H, W };
        long[] blockIdx = { 0, 0, 0, 0, 0 };
        int sliceSize = H*W;
        //double[] elSize = getElementSizeUm(imp);

        //if (pr != null) pr.init(imp.getImageStackSize());
        DTYPE type = getBitDepth(imp);
        MDAbstractArray data = getArray(writer, dsName, dims, blockDims, type, compressionLevel);
        Object dataFlat = data.getAsFlatArray();
        for (int t = 0; t < T; ++t) {
            blockIdx[0] = t;
            for (int z = 0; z < Z; ++z) {
                blockIdx[2] = z;
                for (int c = 0; c < C; ++c) {
                    blockIdx[1] = c;
                    //if (pr != null && !pr.count( "Saving " + dsName + " t=" + t + ", z=" + z + ", c=" + c, 1)) throw new InterruptedException();
                    writeSlice(writer, imp, data, dataFlat, dsName, type, blockIdx, sliceSize, c, z, t);
                }
            }
        }
        //writer.float64().setArrayAttr(dsName, "element_size_um", elSize);
    }
    private static void writeSlice(IHDF5Writer writer, ImagePlus imp, MDAbstractArray data, Object dataFlat, String dsName, DTYPE type, long[] blockIdx, int sliceSize, int c, int z, int t) {
        int stackIndex = imp.getStackIndex(c + 1, z + 1, t + 1);
        System.arraycopy(imp.getImageStack().getPixels(stackIndex), 0, dataFlat, 0, sliceSize);
        switch (type){
            case FLOAT:
                writer.float32().writeMDArrayBlock(dsName, (MDFloatArray)data, blockIdx);
                break;
            case SHORT:
                writer.uint16().writeMDArrayBlock(dsName, (MDShortArray)data, blockIdx);
                break;
            case BYTE:
                writer.uint8().writeMDArrayBlock(dsName, (MDByteArray)data, blockIdx);
                break;
            case INT32:
                writer.int32().writeMDArrayBlock(dsName, (MDIntArray)data, blockIdx);
                break;
        }
    }
    private static void writeSlice(IHDF5Writer writer, Image image, MDAbstractArray data, Object dataFlat, String dsName, DTYPE type, long[] blockIdx, int z) {
        System.arraycopy(image.getPixelArray()[z], 0, dataFlat, 0, image.getSizeXY());
        switch (type){
            case FLOAT:
                writer.float32().writeMDArrayBlock(dsName, (MDFloatArray)data, blockIdx);
                break;
            case SHORT:
                writer.uint16().writeMDArrayBlock(dsName, (MDShortArray)data, blockIdx);
                break;
            case BYTE:
                writer.uint8().writeMDArrayBlock(dsName, (MDByteArray)data, blockIdx);
                break;
            case INT32:
                writer.int32().writeMDArrayBlock(dsName, (MDIntArray)data, blockIdx);
                break;
        }
    }
    public static IHDF5Reader getReader(File file) {
        return HDF5Factory.configureForReading(file.getAbsolutePath()).reader();
    }

    public static Set<String> getAllDatasets(IHDF5Reader reader, String start) {
        Set<String> oldList = getAllGroupMembers(reader, Stream.of(start)).collect(Collectors.toSet());
        Set<String> newList = getAllGroupMembers(reader, oldList.stream()).collect(Collectors.toSet());
        while (!oldList.equals(newList)) {
            oldList = newList;
            newList = getAllGroupMembers(reader, oldList.stream()).collect(Collectors.toSet());
        }
        return newList;
    }
    protected static Stream<String> getAllGroupMembers(IHDF5Reader reader, Stream<String> groups) {
        return groups.flatMap( g -> {
            if (g.equals("/") || reader.isGroup(g)) return reader.getGroupMembers( g ).stream().map(s -> g + (g.equals("/") ? "" : "/") + s);
            else return Stream.of(g);
        });
    }
    public static DTYPE getBitDepth(HDF5DataSetInformation dsInfo) {
        int elementSize = dsInfo.getTypeInformation().getElementSize();
        switch (elementSize) {
            case 4:
                if (dsInfo.getTypeInformation().getRawDataClass().equals(HDF5DataClass.INTEGER)) return DTYPE.INT32;
                else return DTYPE.FLOAT;
            case 2:
                if (!dsInfo.getTypeInformation().isSigned()) return DTYPE.SHORT;
                else throw new IllegalArgumentException("Signed 16bit not supported");
            case 1:
                if (!dsInfo.getTypeInformation().isSigned()) return DTYPE.BYTE;
                else throw new IllegalArgumentException("Signed 8bit not supported");
            default: {
                logger.error("Data not supported: {}", dsInfo);
                throw new IllegalArgumentException("Data type not supported: "+dsInfo);
            }
        }
    }
    public static ImagePlus readDataset(IHDF5Reader reader, String dsName) {
        HDF5DataSetInformation dsInfo = reader.object().getDataSetInformation(dsName);
        DTYPE type = getBitDepth(dsInfo);
        int nDims    = dsInfo.getDimensions().length - 2;
        int nFrames  = (int)dsInfo.getDimensions()[0];
        int nChannels = (int)dsInfo.getDimensions()[1];
        int nZ    = (nDims == 2) ? 1 : (int)dsInfo.getDimensions()[2];
        int nRows    = (int)dsInfo.getDimensions()[2 + ((nDims == 2) ? 0 : 1)];
        int nCols    = (int)dsInfo.getDimensions()[3 + ((nDims == 2) ? 0 : 1)];

        //ImagePlus output = IJ.createHyperStack(dsName, nCols, nRows, nChannels, nZ, nFrames, 32);
        ImageStack stack = new ImageStack(nCols, nRows, null);
        //impScores.setDisplayMode(IJ.GRAYSCALE);
        //impScores.setCalibration(_imp.getCalibration().copy());

        int[] blockDims = (nDims == 2) ?
                (new int[] { 1, 1, nRows, nCols }) :
                (new int[] { 1, 1, 1, nRows, nCols });
        long[] blockIdx = (nDims == 2) ?
                (new long[] { 0, 0, 0, 0 }) : (new long[] { 0, 0, 0, 0, 0 });

        for (int t = 0; t < nFrames; ++t) {
            blockIdx[0] = t;
            for (int z = 0; z < nZ; ++z) {
                if (nDims == 3) blockIdx[2] = z;
                for (int c = 0; c < nChannels; ++c) {
                    blockIdx[1] = c;
                    Object slice = readSlice(reader, dsName, blockDims, blockIdx, type);
                    stack.addSlice("", slice);
                }
            }
        }
        ImagePlus imp = new ImagePlus(dsName, stack);
        imp.setDimensions(nChannels, nZ, nFrames);
        imp.setOpenAsHyperStack(true);
        return imp;
    }
    private static Object readSlice(IHDF5Reader reader, String dsName, int[] blockDims, long[] blockIdx, DTYPE type) {
        switch (type) {
            case FLOAT:
                return reader.float32().readMDArrayBlock( dsName, blockDims, blockIdx).getAsFlatArray();
            case SHORT:
                return reader.uint16().readMDArrayBlock( dsName, blockDims, blockIdx).getAsFlatArray();
            case BYTE:
                return reader.uint8().readMDArrayBlock( dsName, blockDims, blockIdx).getAsFlatArray();
            case INT32:
                return reader.int32().readMDArrayBlock( dsName, blockDims, blockIdx).getAsFlatArray();
            default:
                throw new IllegalArgumentException("Unsupported datatype");
        }
    }
    public static void savePyDataset(List<Image> images, File outFile, boolean append, String dsName, int compressionLevel, boolean saveLabels, int[][] originalDimensions, Map<String, Object> metadata) {
        if (images.isEmpty()) return;
        try {
            if (!outFile.exists()) outFile.createNewFile();
        } catch (IOException e) {
            logger.error("error creating file:", e);
            throw new RuntimeException(e);
        }
        IHDF5Writer writer = getWriter(outFile, append);
        Image sample = images.get(0);
        if (images.stream().anyMatch(i -> !i.sameDimensions(sample) || i.getBitDepth()!=sample.getBitDepth())) throw new IllegalArgumentException("At least 2 images have different dimensions or bitdepth");
        DTYPE type = getBitDepth(sample);
        long[] dims, blockIdx;
        int[] blockDims;
        if (sample.sizeZ()>1) {
            dims=new long[]{images.size(), sample.sizeZ(), sample.sizeY(), sample.sizeX()};
            blockDims = new int[]{1, 1, sample.sizeY(), sample.sizeX()};
            blockIdx = new long[4];
        } else {
            dims=new long[]{images.size(), sample.sizeY(), sample.sizeX()};
            blockDims = new int[]{1, sample.sizeY(), sample.sizeX()};
            blockIdx = new long[3];
        }
        int sizeZ = sample.sizeZ();

        MDAbstractArray data = getArray(writer, dsName, dims, blockDims, type, compressionLevel);
        Object dataFlat = data.getAsFlatArray();

        for (int idx = 0; idx < images.size(); ++idx) {
            blockIdx[0] = idx;
            for (int z = 0; z < sizeZ; ++z) {
                if (sizeZ>1) blockIdx[1] = z;
                writeSlice(writer, images.get(idx), data, dataFlat, dsName, type, blockIdx, z);
            }
        }

        if (saveLabels) {
            String[] labels = images.stream().map(i->i.getName()).toArray(s->new String[s]);
            writer.string().writeArray(getLabelKey(dsName), labels);
        }
        //writer.string().setArrayAttr(dsName, "labels", labels); // not compatible with python
        saveMetadata(writer, dsName, metadata);
        if (originalDimensions!=null) writer.int32().writeMatrix(getDimensionsKey(dsName), originalDimensions);

        writer.close();
    }
    private static void saveMetadata(IHDF5Writer writer, String s, Map<String, Object> metadata) {
        if (metadata==null || metadata.isEmpty()) return;
        metadata.forEach((k, v)-> {
            if (v instanceof Integer) writer.int32().setAttr(s, k, (Integer)v);
            else if (v instanceof Float) writer.float32().setAttr(s, k, (Float)v);
            else if (v instanceof Double) writer.float64().setAttr(s, k, (Double)v);
            else if (v instanceof Boolean) writer.bool().setAttr(s, k, (Boolean)v);
            else if (v instanceof int[]) writer.int32().setArrayAttr(s, k, (int[])v);
            else if (v instanceof float[]) writer.float32().setArrayAttr(s, k, (float[])v);
            else if (v instanceof double[]) writer.float64().setArrayAttr(s, k, (double[])v);
            else throw new IllegalArgumentException("metadata: "+k+"->"+v+" of type: "+v.getClass().toString()+" not supported");
        });
    }
    public static long[] getDimensions(IHDF5Reader reader, String dsName) {
        HDF5DataSetInformation dsInfo = reader.object().getDataSetInformation(dsName);
        return dsInfo.getDimensions();
    }
    public static Image[] readPyDataset(IHDF5Reader reader, String dsName, boolean extractLabels, int... imageIdx) {
        HDF5DataSetInformation dsInfo = reader.object().getDataSetInformation(dsName);
        DTYPE type = getBitDepth(dsInfo);
        int nDims    = dsInfo.getDimensions().length - 1;
        int nImages  = (int)dsInfo.getDimensions()[0];
        int nZ    = (nDims == 2) ? 1 : (int)dsInfo.getDimensions()[1];
        int nRows    = (int)dsInfo.getDimensions()[((nDims == 2) ? 1 : 2)];
        int nCols    = (int)dsInfo.getDimensions()[((nDims == 2) ? 2 : 3)];

        int[] blockDims = (nDims == 2) ?
                (new int[] { 1, nRows, nCols }) :
                (new int[] { 1, 1, nRows, nCols });
        long[] blockIdx = (nDims == 2) ?
                (new long[] { 0, 0, 0}) : (new long[] { 0, 0, 0, 0 });
        int[] iIdx = (imageIdx==null || imageIdx.length==0) ? ArrayUtil.generateIntegerArray(nImages) : imageIdx;
        final Image[] res = new Image[iIdx.length];
        String labelKey = getLabelKey(dsName);
        String[] labels = extractLabels && reader.getGroupMembers(getGroupName(dsName)).contains("labels") ?
                reader.string().readArray(labelKey) : null;
        if (labels!=null && labels.length!=nImages) throw new IllegalArgumentException("# of labels does not match dataset dimension");

        IntFunction<Image> retrieveImage = nDims==3 ?  i -> {
            blockIdx[0] = i;
            Image[] planes = new Image[nZ];
            for (int z = 0; z < nZ; ++z) {
                blockIdx[1] = z;
                Object slice = readSlice(reader, dsName, blockDims, blockIdx, type);
                planes[z] = Image.createImageFrom2DPixelArray("", slice, nCols);
            }
            return Image.mergeZPlanes(planes).setName(labels!=null ? labels[i] : "");
        } : i -> {
            blockIdx[0] = i;
            Object slice = readSlice(reader, dsName, blockDims, blockIdx, type);
            return Image.createImageFrom2DPixelArray(labels!=null ? labels[i] : "", slice, nCols);
        };
        for (int i = 0; i<iIdx.length; ++i) {
            if (iIdx[i]>=0) res[i] = retrieveImage.apply(iIdx[i]);
        }

        // apply scaling in metadata
        // TODO are they stored as array or scalar ?
        double scale_center = reader.object().getAllAttributeNames(dsName).contains("scaling_center") ? reader.float64().getAttr(dsName, "scaling_center") : 0;
        double scale_factor = reader.object().getAllAttributeNames(dsName).contains("scaling_center") ? reader.float64().getAttr(dsName, "scaling_factor") : 1;
        if (scale_center!=0 || scale_factor!=1) {
            int bitDepth = reader.object().getAllAttributeNames(dsName).contains("original_bitDepth") ? reader.int32().getAttr(dsName, "original_bitDepth") : -1;
            IntStream.range(0, res.length)
                    .filter(i->res[i]!=null)
                    .parallel()
                    .forEach(i -> res[i] = ImageOperations.affineOperation2(res[i], bitDepth>0 ? (Image)Image.createEmptyImage(bitDepth) : null, scale_factor, -scale_center ));
        }
        return res;
    }
    private static String getLabelKey(String dsName) {
        int lastGrpIdx = dsName.lastIndexOf('/');
        if (lastGrpIdx>0) return dsName.substring(0, lastGrpIdx+1)+"labels";
        else return "labels";
    }
    private static String getDimensionsKey(String dsName) {
        int lastGrpIdx = dsName.lastIndexOf('/');
        if (lastGrpIdx>0) return dsName.substring(0, lastGrpIdx+1)+"originalDimensions";
        else return "originalDimensions";
    }
    private static String getGroupName(String dsName) {
        int lastGrpIdx = dsName.lastIndexOf('/');
        if (lastGrpIdx>0) return dsName.substring(0, lastGrpIdx);
        else return "/";
    }
}
