package bacmman.py_dataset;

import bacmman.core.ProgressCallback;
import bacmman.image.*;
import bacmman.processing.ImageOperations;
import bacmman.utils.ArrayUtil;
import bacmman.utils.Utils;
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
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public class HDF5IO {
    public final static Logger logger = LoggerFactory.getLogger(HDF5IO.class);
    public static IHDF5Writer getWriter(File outFile, boolean append) {
        try {
            if (!outFile.exists()) outFile.createNewFile();
        } catch (IOException e) {
            logger.error("error creating file:", e);
            throw new RuntimeException(e);
        }
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
        DTYPE type = getType(imp);
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
        DTYPE type = getType(imp);
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

    public static void saveImage(Image image, IHDF5Writer writer, String dsName, boolean channelLast, int compressionLevel) {
        int C = (image instanceof LazyImage5D) ? ((LazyImage5D)image).getSizeC() : 1;
        int T = (image instanceof LazyImage5D) ? ((LazyImage5D)image).getSizeF() : 1;
        int Z = image.sizeZ();
        int W = image.sizeX();
        int H = image.sizeY();

        long[] dims;
        int[] blockDims;
        long[] blockIdx;
        if (Z > 1) {
            dims = channelLast ? new long[]{ T, Z, H, W, C } : new long[]{ T, C, Z, H, W };
            blockDims = channelLast ? new int[]{ 1, 1, H, W, 1 } : new int[]{ 1, 1, 1, H, W };
            blockIdx = new long[]{ 0, 0, 0, 0, 0 };
        } else {
            dims = channelLast ? new long[]{ T, H, W, C } : new long[]{ T, C, H, W };
            blockDims = channelLast ? new int[]{ 1, H, W, 1 } : new int[]{ 1, 1, H, W };
            blockIdx = new long[]{ 0, 0, 0, 0 };
        }
        DTYPE type = getType(image);
        MDAbstractArray data = getArray(writer, dsName, dims, blockDims, type, compressionLevel);
        Object dataFlat = data.getAsFlatArray();
        for (int t = 0; t < T; ++t) {
            blockIdx[0] = t;
            for (int c = 0; c < C; ++c) {
                blockIdx[channelLast ? blockIdx.length -1 : 1] = c;
                if (image instanceof LazyImage5D) ((LazyImage5D)image).setPosition(t, c);
                for (int z = 0; z < Z; ++z) {
                    if (Z>1) blockIdx[channelLast ? 1 : 2] = z;
                    writeSlice(writer, image, data, dataFlat, dsName, type, blockIdx, z);
                }
            }
        }
    }

    public enum DTYPE {
        BYTE(8, false), SHORT(16, false), FLOAT32(32, true), FLOAT64(64, true), INT32(32, false);
        final int bitDepth;
        final boolean floatingPoint;
        private DTYPE(int bitDepth, boolean floatingPoint) {
            this.bitDepth = bitDepth;
            this.floatingPoint = floatingPoint;
        }
    }
    public static DTYPE getType(ImagePlus imp) {
        switch (imp.getBitDepth()) {
            case 8: return DTYPE.BYTE;
            case 16: return DTYPE.SHORT;
            case 32: return DTYPE.FLOAT32;
            default: throw new IllegalArgumentException("Bit depth not supported");
        }
    }
    public static DTYPE getType(Image image) {
        if (image.floatingPoint()) {
            if (image.byteCount()==8) return DTYPE.FLOAT64;
            return DTYPE.FLOAT32;
        }
        else if (image instanceof ImageByte) return DTYPE.BYTE;
        else if (image instanceof ImageShort) return DTYPE.SHORT;
        else if (image instanceof ImageInt) return DTYPE.INT32;
        else throw new IllegalArgumentException("Bit depth not supported");
    }
    public static Image getImageType(DTYPE type) {
        switch (type) {
            case BYTE:
                return new ImageByte("",0,0,0);
            case SHORT:
                return new ImageShort("", 0, 0, 0);
            case FLOAT32:
            default:
                return new ImageFloat("", 0, 0, 0);
            case FLOAT64:
                return new ImageDouble("", 0, 0, 0);
            case INT32:
                return new ImageInt("", 0, 0, 0);
        }
    }
    public static DTYPE getType(int bitDepth, boolean floatingPoint) {
        switch (bitDepth) {
            case 8:
                return DTYPE.BYTE;
            case 16:
                return DTYPE.SHORT;
            case 32:
                if (floatingPoint) return DTYPE.FLOAT32;
                else return DTYPE.INT32;
            case 64:
                if (floatingPoint) return DTYPE.FLOAT64;
            default:
                throw new IllegalArgumentException("Unsupported bitdepth : "+bitDepth+" floating: "+floatingPoint);
        }
    }

    private static MDAbstractArray getArray(IHDF5Writer writer, String dsName, long[] dims, int[] blockDims, DTYPE type, int compressionLevel) {
        switch (type) {
            case FLOAT32:
            default:
                writer.float32().createMDArray(dsName, dims, blockDims, HDF5FloatStorageFeatures.createDeflationDelete(compressionLevel));
                return new MDFloatArray(blockDims);
            case FLOAT64:
                writer.float64().createMDArray(dsName, dims, blockDims, HDF5FloatStorageFeatures.createDeflationDelete(compressionLevel));
                return new MDDoubleArray(blockDims);
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

    private static void writeSlice(IHDF5Writer writer, ImagePlus imp, MDAbstractArray data, Object dataFlat, String dsName, DTYPE type, long[] blockIdx, int sliceSize, int c, int z, int t) {
        int stackIndex = imp.getStackIndex(c + 1, z + 1, t + 1);
        System.arraycopy(imp.getImageStack().getPixels(stackIndex), 0, dataFlat, 0, sliceSize);
        switch (type){
            case FLOAT32:
                writer.float32().writeMDArrayBlock(dsName, (MDFloatArray)data, blockIdx);
                break;
            case FLOAT64:
                writer.float64().writeMDArrayBlock(dsName, (MDDoubleArray)data, blockIdx);
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
            case FLOAT32:
                writer.float32().writeMDArrayBlock(dsName, (MDFloatArray)data, blockIdx);
                break;
            case FLOAT64:
                writer.float64().writeMDArrayBlock(dsName, (MDDoubleArray)data, blockIdx);
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
    public static DTYPE getType(HDF5DataSetInformation dsInfo) {
        int elementSize = dsInfo.getTypeInformation().getElementSize();
        switch (elementSize) {
            case 8:
                if (dsInfo.getTypeInformation().getRawDataClass().equals(HDF5DataClass.INTEGER)) throw new IllegalArgumentException("Long type not supported");
                else return DTYPE.FLOAT64;
            case 4:
                if (dsInfo.getTypeInformation().getRawDataClass().equals(HDF5DataClass.INTEGER)) return DTYPE.INT32;
                else return DTYPE.FLOAT32;
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
        DTYPE type = getType(dsInfo);
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

    public static LazyImage5D readDatasetLazy(IHDF5Reader reader, String dsName, boolean channelLast) {
        HDF5DataSetInformation dsInfo = reader.object().getDataSetInformation(dsName);
        DTYPE type = getType(dsInfo);
        int nDims    = dsInfo.getDimensions().length - 2;
        int channelAxis = channelLast ? dsInfo.getDimensions().length - 1 : 1;
        int zAxis = channelLast ? 1 : 2;
        int nFrames  = (int)dsInfo.getDimensions()[0];
        int nChannels = (int)dsInfo.getDimensions()[channelAxis];
        int nZ    = (nDims == 2) ? 1 : (int)dsInfo.getDimensions()[zAxis];
        int nRows    = (int)dsInfo.getDimensions()[zAxis + ((nDims == 2) ? 0 : 1)];
        int nCols    = (int)dsInfo.getDimensions()[zAxis + ((nDims == 2) ? 1 : 2)];
        int[] blockDims;
        if (nDims == 2) {
            blockDims = channelLast ? new int[] { 1, nRows, nCols, 1 } : new int[] { 1, 1, nRows, nCols };
        } else {
            blockDims = channelLast ? new int[] { 1, 1, nRows, nCols, 1 } : new int[] { 1, 1, 1, nRows, nCols };
        }

        Function<int[], Image> generatorFCZ = fcz -> {
            long[] blockIdx = (nDims == 2) ? (new long[] { 0, 0, 0, 0 }) : (new long[] { 0, 0, 0, 0, 0 });
            blockIdx[0] = fcz[0];
            if (nDims == 3) blockIdx[zAxis] = fcz[2];
            blockIdx[channelAxis] = fcz[1];
            Object slice = readSlice(reader, dsName, blockDims, blockIdx, type);
            return toImage(Utils.toStringArray(fcz), nCols, slice, type);
        };
        return new LazyImage5DPlane(dsName, getImageType(type), generatorFCZ, new int[]{nFrames, nChannels, nZ} );
    }

    private static Image toImage(String name, int sizeX, Object slice, DTYPE type) {
        switch (type) {
            case FLOAT32:
                return new ImageFloat(name, sizeX, (float[])slice);
            case FLOAT64:
                return new ImageDouble(name, sizeX, (double[])slice);
            case SHORT:
                return new ImageShort(name, sizeX, (short[])slice);
            case BYTE:
                return new ImageByte(name, sizeX, (byte[])slice);
            case INT32:
                return new ImageInt(name, sizeX, (int[])slice);
            default:
                throw new IllegalArgumentException("Unsupported datatype");
        }
    }
    private static Object readSlice(IHDF5Reader reader, String dsName, int[] blockDims, long[] blockIdx, DTYPE type) {
        switch (type) {
            case FLOAT32:
                return reader.float32().readMDArrayBlock( dsName, blockDims, blockIdx).getAsFlatArray();
            case FLOAT64:
                return reader.float64().readMDArrayBlock( dsName, blockDims, blockIdx).getAsFlatArray();
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
        if (!Utils.objectsAllHaveSameProperty(images, Image::sameDimensions)) {
            List<Image> distinctImages =  images.stream().filter(Utils.distinctByKey(Image::getBoundingBox)).collect(Collectors.toList());
            logger.error("Dimensions differ: {}", Utils.toStringList(distinctImages, i -> i.getName()+"->"+i.getBoundingBox()));
            throw new IllegalArgumentException("At least 2 images have different dimensions");
        }
        if (!Utils.objectsAllHaveSameProperty(images, i->getType(i))) {
            List<Image> distinctImages =  images.stream().filter(Utils.distinctByKey(HDF5IO::getType)).collect(Collectors.toList());
            logger.error("data type differ: {}", Utils.toStringList(distinctImages, i->i.getName()+"->"+i.getClass()+"->"+getType(i)));
            throw new IllegalArgumentException("At least 2 images have different data type");
        }
        IHDF5Writer writer = getWriter(outFile, append);
        Image sample = images.get(0);
        DTYPE type = getType(sample);
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
        DTYPE type = getType(dsInfo);
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
            boolean floatingPoint = reader.object().getAllAttributeNames(dsName).contains("original_is_float") ? reader.bool().getAttr(dsName, "original_is_float") : bitDepth>=32;
            Image imType = bitDepth>0 ? getImageType(getType(bitDepth, floatingPoint)) : null;
            IntStream.range(0, res.length)
                    .filter(i->res[i]!=null)
                    .parallel()
                    .forEach(i -> res[i] = ImageOperations.affineOperation2(res[i], imType, scale_factor, -scale_center ));
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
