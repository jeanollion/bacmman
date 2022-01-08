/* 
 * Copyright (C) 2018 Jean Ollion
 *
 * This File is part of BACMMAN
 *
 * BACMMAN is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * BACMMAN is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with BACMMAN.  If not, see <http://www.gnu.org/licenses/>.
 */
package bacmman.image.io;

import bacmman.image.MutableBoundingBox;
import bacmman.image.wrappers.IJImageWrapper;
import bacmman.image.Image;
import static bacmman.image.io.ImportImageUtils.paseDVLogFile;

import bacmman.utils.ArrayUtil;
import ij.ImagePlus;
import ij.io.FileInfo;
import ij.io.Opener;
import ij.io.TiffDecoder;
import java.io.File;
import java.io.IOException;
import loci.common.services.DependencyException;
import loci.common.services.ServiceException;
import loci.common.services.ServiceFactory;
import loci.formats.FormatException;
import loci.formats.meta.IMetadata;
import loci.formats.services.OMEXMLService;
import ome.units.quantity.Length;
import bacmman.image.ImageByte;
import bacmman.image.ImageFloat;
import bacmman.image.ImageInt;
import bacmman.image.ImageShort;
import bacmman.utils.Pair;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import loci.common.DataTools;
import loci.formats.FormatTools;
import loci.formats.IFormatReader;
import org.joda.time.DateTimeZone;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author Jean Ollion
 */
public class ImageReaderFile implements ImageReader {
    public static final Logger logger = LoggerFactory.getLogger(ImageReaderFile.class);
    ImageFormat extension;
    String path;
    String imageTitle;
    private String fullPath;
    List<Double> timePoints;
    //BioFormats
    IFormatReader reader;
    IMetadata meta;
    boolean invertTZ;
    boolean supportView;
    
    public ImageReaderFile(String path, String imageTitle, ImageFormat extension) {
        this.extension=extension;
        this.path=path;
        this.imageTitle=imageTitle;
        this.invertTZ=extension.getInvertTZ();
        this.supportView=extension.getSupportView();
        initReader();
    }
    
    public ImageReaderFile(String fullPath) {
        setFullPath(fullPath);
        initReader();
    }
    public ImageReaderFile setInvertTZ(boolean invertTZ) {
        this.invertTZ=invertTZ;
        return this;
    }
    private void setFullPath(String fullPath) {
        File f= new File(fullPath);
        path  = f.getParent();
        imageTitle = f.getName();
        int extIdx = imageTitle.indexOf(".");
        if (extIdx<=0) extIdx=imageTitle.length()-1;
        else extension=ImageFormat.getExtension(f.getName().substring(extIdx));
        imageTitle = f.getName().substring(0, extIdx);
        if (extension==null) {
            this.fullPath=fullPath;
            invertTZ=false;
            supportView=true;
        } else {
            invertTZ=extension.getInvertTZ();
            this.supportView=extension.getSupportView();
        }
    }

    public ImageFormat getExtension() {
        return extension;
    }

    public String getPath() {
        return path;
    }

    public String getImageTitle() {
        return imageTitle;
    }
    
    public String getImagePath() {
        if (fullPath!=null) return fullPath;
        else return Paths.get(path, imageTitle+extension).toString();
    }
    //loci.formats.ImageReader ifr;
    private void initReader() {
        if (!new File(getImagePath()).exists()) Image.logger.error("File: {} was not found", getImagePath());
        //logger.debug("init reader: {}", getImagePath());
        //ifr = LociPrefs.makeImageReader();
        //reader = new ImageProcessorReader(new ChannelSeparator(LociPrefs.makeImageReader()));
        reader = new loci.formats.ImageReader();
        ServiceFactory factory;
        try {
            factory = new ServiceFactory();
            OMEXMLService service = factory.getInstance(OMEXMLService.class);
            try {
                meta = service.createOMEXMLMetadata();
                reader.setMetadataStore(meta);
                //logTimeAnnotations();
            } catch (ServiceException ex) {
                Image.logger.error(ex.getMessage(), ex);
            }
        } catch (DependencyException ex) {
            Image.logger.error(ex.getMessage(), ex);
        }
        setId();
    }
    private void setId() {
        try {
            //long t0 = System.currentTimeMillis();
            reader.setId(getImagePath());
            //long t1 = System.currentTimeMillis();
            //logger.debug("set id in  {}ms", t1-t0);
        } catch (FormatException | IOException ex) {
            Image.logger.error("An error occurred while setting image id: {}, message: {}", getImagePath(),  ex.getMessage());
            reader=null;
        }
    }
    private void setFile(String fullPath) {
        setFullPath(fullPath);
        setId();
    }
    
    public void closeReader() {
        buffer = null;
        if (reader==null) return;
        try {
            reader.close();
        } catch (IOException ex) {
            Image.logger.error("An error occurred while closing reader for image: "+getImagePath(),  ex);
        }
    }
    
    public Image openChannel() {
        return ImageReaderFile.this.openImage(new ImageIOCoordinates());
    }
    public Image openImage(ImageIOCoordinates coords) {
        if (reader==null) return null;
        Image res = null;
        /*if (reader==null && extension==ImageFormat.TIF) { // try IJ's method
            res = ImageReader.openIJTif(fullPath);
            if (coords.getBounds()!=null) res = res.crop(coords.getBounds());
            return res;
        }*/
        reader.setSeries(coords.getSerie());
        int sizeX = reader.getSizeX();
        int sizeY = reader.getSizeY();
        int sizeZ = invertTZ?reader.getSizeT():reader.getSizeZ();
        //if (coords.getBounds()!=null) coords.getBounds().trimToImage(new BlankMask( sizeX, sizeY, sizeZ));
        int zMin, zMax;
        MutableBoundingBox bounds =coords.getBounds()==null? null: new MutableBoundingBox(coords.getBounds());
        if (bounds!=null) {
            if (bounds.sizeX()<=0) bounds.setxMin(0).setxMax(sizeX-1);
            if (bounds.sizeY()<=0) bounds.setyMin(0).setyMax(sizeY-1);
            zMin=Math.max(bounds.zMin(), 0);
            zMax=Math.min(bounds.zMax(), sizeZ-1);
            if (zMin>zMax) {zMin=0; zMax=sizeZ-1;}
            if (this.supportView) {
                sizeX = bounds.sizeX();
                sizeY = bounds.sizeY();
            }
        } else {
            zMin=0; zMax=sizeZ-1;
        }
        //logger.debug("open image: {}, sizeX: {}, sizeY: {}, sizeZ: {}, zMin: {}, zMax: {}", this.getImagePath(), sizeX, sizeY, sizeZ, zMin, zMax);
        List<Image> planes = new ArrayList<>(zMax - zMin+1);
        for (int z = zMin; z <= zMax; z++) {
            int idx = getIndex(coords.getChannel(), coords.getTimePoint(), z);
            try {
                if (bounds==null || !supportView) {
                    planes.add(openImage(idx, 0, 0, sizeX, sizeY, coords.getRGB()));
                } else {
                    planes.add(openImage(idx, bounds.xMin(), bounds.yMin(), bounds.sizeX(), bounds.sizeY(), coords.getRGB()));
                }
                res = Image.mergeZPlanes(planes);
                if (!supportView && bounds!=null) { // crop
                    bounds.setzMin(0).setzMax(res.sizeZ()-1);
                    res=res.crop(bounds);
                }
                if (bounds!=null) res.resetOffset().translate(bounds);
                double[] scaleXYZ = getScaleXYZ(1);
                if (scaleXYZ[0]!=1) res.setCalibration((float)scaleXYZ[0], (float)scaleXYZ[2]);
            } catch (FormatException | IOException ex) {
                Image.logger.error("An error occurred while opening image: {}, c:{}, t:{}, s:{}, message: {}", reader.getCurrentFile() , coords.getChannel() , coords.getTimePoint(), coords.getSerie(), ex.getMessage());
            }
        }
        return res;
    }
    // code from loci.plugins.uti.ImageProcessorReader
    private byte[] buffer;
    private Image openImage(int no, int x, int y, int w, int h, ImageIOCoordinates.RGB rgb) throws FormatException, IOException {
        // read byte array
        int c = reader.getRGBChannelCount();
        int type = reader.getPixelType();
        int bpp = FormatTools.getBytesPerPixel(type);
        boolean interleave = reader.isInterleaved();
        int bufLength = w * h * c * bpp;
        buffer = (buffer!=null && buffer.length==bufLength) ? reader.openBytes(no, buffer, x, y, w, h) : reader.openBytes(no, x, y, w, h);
        if (buffer.length != w * h * c * bpp && buffer.length != w * h * bpp) throw new FormatException("Invalid byte array length: " + buffer.length + " (expected w=" + w + ", h=" + h + ", c=" + c + ", bpp=" + bpp + ")");
       
        // convert byte array to appropriate primitive array type
        boolean isFloat = FormatTools.isFloatingPoint(type);
        boolean isLittle = reader.isLittleEndian();
        boolean isSigned = FormatTools.isSigned(type);

        // in case of RGB image -> only first channel is opened // TODO argument to choose which or compute luma ?
        int index = 0;
        if (c>1) {
            switch (rgb) {
                case R:
                default:
                    index=0;
                    break;
                case G:
                    index = 1;
                    break;
                case B:
                    index = 2;
                    break;
            }
        }
        byte[] channel = c>1? splitChannels(buffer, null, index, c, bpp, false, interleave) : buffer;
        Object pixels = DataTools.makeDataArray(channel, bpp, isFloat, isLittle);
        if (pixels instanceof byte[]) {
            byte[] q = (byte[]) pixels;
            if (q.length != w * h) {
                byte[] tmp = q;
                q = new byte[w * h];
                System.arraycopy(tmp, 0, q, 0, Math.min(q.length, tmp.length));
            }
            if (isSigned) q = DataTools.makeSigned(q);
            if (q==buffer) buffer = null; // avoid reusing buffer later
            return new ImageByte("", w, q);
        }
        else if (pixels instanceof short[]) {
            short[] q = (short[]) pixels;
            if (q.length != w * h) {
                short[] tmp = q;
                q = new short[w * h];
                System.arraycopy(tmp, 0, q, 0, Math.min(q.length, tmp.length));
            }
            if (isSigned) q = DataTools.makeSigned(q);
            return new ImageShort("", w, q);
        }
        else if (pixels instanceof int[]) {
            int[] q = (int[]) pixels;
            if (q.length != w * h) {
                int[] tmp = q;
                q = new int[w * h];
                System.arraycopy(tmp, 0, q, 0, Math.min(q.length, tmp.length));
            }
            return new ImageInt("", w, q);
        }
        else if (pixels instanceof float[]) {
            float[] q = (float[]) pixels;
            if (q.length != w * h) {
                float[] tmp = q;
                q = new float[w * h];
                System.arraycopy(tmp, 0, q, 0, Math.min(q.length, tmp.length));
            }
            return new ImageFloat("", w, q);
        }
        else if (pixels instanceof double[]) {
            double[] q = (double[]) pixels;
            if (q.length != w * h) {
                double[] tmp = q;
                q = new double[w * h];
                System.arraycopy(tmp, 0, q, 0, Math.min(q.length, tmp.length));
            }
            float[] pix = new float[q.length];
            double[] src = q;
            IntStream.range(0, q.length).forEach(i->pix[i] = (float)src[i]);
            return new ImageFloat("", w, pix);
        } else throw new RuntimeException("Unrecognized pixel type");
    }
    private static byte[] splitChannels(byte[] array, byte[] rtn, int index, int c, int bytes, boolean reverse, boolean interleaved) {
        if (c == 1) {
            return array;
        } else {
            int channelLength = array.length / c;
            if (rtn == null) {
                rtn = new byte[channelLength];
            }

            if (reverse) {
                index = c - index - 1;
            }

            if (!interleaved) {
                System.arraycopy(array, channelLength * index, rtn, 0, channelLength);
            } else {
                int next = 0;

                for(int i = 0; i < array.length; i += c * bytes) {
                    for(int k = 0; k < bytes; ++k) {
                        if (next < rtn.length) {
                            rtn[next] = array[i + index * bytes + k];
                        }

                        ++next;
                    }
                }
            }

            return rtn;
        }
    }
    private int getIndex(int c, int t, int z) {
        return invertTZ ? reader.getIndex(t, c, z) : reader.getIndex(z, c, t);
    }
    
    public double[] getScaleXYZ(double defaultValue) {
        double[] res = new double[3];
        Arrays.fill(res, defaultValue);
        if (meta != null) {
            try {
                Length lx = meta.getPixelsPhysicalSizeX(0);
                Length ly = meta.getPixelsPhysicalSizeY(0);
                Length lz = meta.getPixelsPhysicalSizeZ(0);
                if (lx!=null) res[0] = lx.value().doubleValue();
                if (ly!=null) res[1] = ly.value().doubleValue();
                if (lz!=null) res[2] = lz.value().doubleValue();
                
            } catch(Exception e) {}
        } 
        //logger.debug("image: {} calibration: {}", this.fullPath, res);
        return res;
    }
    //static boolean logeed = false;
    public double getTimePoint(int c, int t, int z) {
        if (timePoints==null) {
            synchronized(this) {
                if (timePoints==null) {
                    if (this.extension == ImageFormat.DV) { // look for log file
                        int deconvIdx = getImagePath().indexOf("_D3D");
                        String logPath = deconvIdx>0 ? getImagePath().substring(0, deconvIdx)+".dv.log" : getImagePath()+".log";
                        timePoints = paseDVLogFile(logPath, "Time Point: ");
                        //logger.debug("timePoints: {}", timePoints);
                    } else {
                        // not supported
                    }
                }
            }
        }
        if (timePoints!=null) {
            //logger.debug("timePoints: {}", timePoints);
            int idx = getIndex(c, t, z);
            if (idx<timePoints.size()) return timePoints.get(idx);
            else return Double.NaN;
        } else return Double.NaN;
    }
    
    public void logTimeAnnotations() {
        if (meta!=null) {
            Image.logger.debug("image count: {}", meta.getImageCount());
            for (int i = 0; i<meta.getImageCount(); ++i) {
                Image.logger.debug("i:{}, time: {}, {} {}", i, meta.getImageAcquisitionDate(i), meta.getImageAcquisitionDate(i)==null? "":meta.getImageAcquisitionDate(i).asDateTime(DateTimeZone.UTC), meta.getImageAcquisitionDate(i)==null? "":meta.getImageAcquisitionDate(i).asInstant());
            }
            int c = meta.getTimestampAnnotationCount();
            for (int i = 0; i<c; ++c) {
                Image.logger.debug("time: i={}, time: {}({}/{}), ns={}, id={}, desc={}, annotator={}", i, meta.getTimestampAnnotationValue(i), meta.getTimestampAnnotationValue(i)==null? "":meta.getTimestampAnnotationValue(i).asDateTime(DateTimeZone.UTC), meta.getTimestampAnnotationValue(i), meta.getTimestampAnnotationValue(i)==null? "":meta.getTimestampAnnotationValue(i).asInstant(), meta.getTimestampAnnotationNamespace(i), meta.getTimestampAnnotationID(i), meta.getTimestampAnnotationDescription(i), meta.getTimestampAnnotationAnnotator(i));
                
                int cc = meta.getTimestampAnnotationAnnotationCount(i);
                for (int ii = 0; ii<cc; ++ii) {
                    Image.logger.debug("time: i={}, ref.idx={}, ref={}", i, ii, meta.getTimestampAnnotationAnnotationRef(i, ii));
                }
            }
        }
        
    }
    
    /*private float[] getTifCalibrationIJ() {
        try {
            TiffDecoder td = new TiffDecoder(path, this.imageTitle + extension);
            FileInfo[] info = td.getTiffInfo();
            if (info[0].pixelWidth > 0) {
                new FileOpener(info[0]).decodeDescriptionString(info[0]);
                float[] res = new float[]{(float) info[0].pixelWidth, (float)info[0].pixelDepth};
                System.out.println("calibration IJ: xy:" + res[0] + " z:" + res[1]);
                return res;
            } else {
                return new float[]{1, 1};
            }
        } catch (IOException ex) {
            Logger.getLogger(ImageReader.class.getName()).log(Level.SEVERE, null, ex);
            return new float[]{1, 1};
        }
    }*/

    /**
     * 
     * @return dimensions of the image: first dimension of the matrix: series, second dimension: dimensions of the images of the serie: 0=timePoint number, 1 = channel number, 2=sizeX, 3=sizeY, 4=sizeZ
     */
    public int[][] getSTCXYZNumbers() {
        if (reader==null) return new int[0][5];
        int[][] res = new int[reader.getSeriesCount()][5];
        for (int i = 0; i<res.length; i++) {
            reader.setSeries(i);
            res[i][0] = invertTZ?reader.getSizeZ():reader.getSizeT();
            res[i][1] = reader.getSizeC();
            res[i][2]=reader.getSizeX();
            res[i][3]=reader.getSizeY();
            res[i][4]=invertTZ?reader.getSizeT():reader.getSizeZ();
        }
        return res;
    }
    
    public static Image openImage(String filePath) {
        return openImage(filePath, new ImageIOCoordinates());
    }
    
    public static Image openImage(String filePath, ImageIOCoordinates ioCoords) {
        return openImage(filePath, ioCoords, null);
    }
    public static Image openImage(String filePath, ImageIOCoordinates ioCoords, byte[][] buffer) {
        if (filePath.toLowerCase().endsWith(".tif")) { // try with faster IJ's method (10x to 100x faster than bioformat as of january 2022 : setID method is very slow)
            if (ioCoords.getSerie()==0 && ioCoords.getChannel()==0 && ioCoords.getTimePoint()==0) { // this only works when
                int[] slices = ioCoords.getBounds()==null ? null : ArrayUtil.generateIntegerArray(ioCoords.getBounds().zMin(), ioCoords.getBounds().zMax()+1);
                //long t0 = System.currentTimeMillis();
                //logger.debug("opening IJ TIF: slices {}", slices);
                Image res = openIJTif(filePath, slices);
                //long t1 = System.currentTimeMillis();
                //logger.debug("opening IJ TIF: slices {} in {}ms", slices, t1-t0);
                if (res!=null) {
                    if (ioCoords.getBounds()!=null && ioCoords.getBounds().sizeX()>0 && ioCoords.getBounds().sizeY()>0) {
                        return res.crop(new MutableBoundingBox(ioCoords.getBounds()).setzMin(0).setzMax(res.sizeZ()-1));
                    } else return res;
                }
            }
        }
        long t0 = System.currentTimeMillis();
        ImageReaderFile reader = new ImageReaderFile(filePath);
        long t1 = System.currentTimeMillis();
        if (buffer!=null) reader.buffer = buffer[0];
        Image im = reader.openImage(ioCoords);
        if (buffer!=null) buffer[0] = reader.buffer;
        long t2 = System.currentTimeMillis();
        reader.closeReader();
        long t3 = System.currentTimeMillis();
        logger.debug("opening with image reader : init {}ms, open: {}ms, close: {}ms", t1-t0, t2-t1, t3-t2);
        return im;
    }
    public static Pair<int[][], double[]> getImageInfo(String filePath) {
        if (filePath.endsWith(".tif")) {
            Pair<int[][], double[]> res = getTIFInfo(filePath);
            if (res!=null) return res;
        }
        ImageReaderFile reader = new ImageReaderFile(filePath);
        int[][] stcxyz = reader.getSTCXYZNumbers();
        double[] sXYZ = reader.getScaleXYZ(1);
        reader.closeReader();
        return new Pair(stcxyz, sXYZ);
    }

    public static double getTIFTimeFrameInterval(String filePath) {
        File file = new File(filePath);
        TiffDecoder td = new TiffDecoder(file.getParent(), file.getName());
        try {
            FileInfo[] info = td.getTiffInfo();
            return info[0].frameInterval;
        } catch (IOException ex) {
            return Double.NaN;
        }
    }
    public static Pair<int[][], double[]> getTIFInfo(String filePath) {
        File file = new File(filePath);
        TiffDecoder td = new TiffDecoder(file.getParent(), file.getName());
        try {
            FileInfo[] info = td.getTiffInfo();
            int[][] stcxyz = new int[1][5];
            stcxyz[0][0] = 1; // currently no multiframe supported...
            stcxyz[0][1] = 1; // currently no multichannel ....
            stcxyz[0][2] = info[0].width;
            stcxyz[0][3] = info[0].height;
            stcxyz[0][4] = Arrays.stream(info).mapToInt(i->i.nImages).sum();
            double[] scale = new double[3];
            scale[0] = info[0].pixelWidth;
            scale[1] = info[0].pixelHeight;
            scale[2] = info[0].pixelDepth;
            return new Pair(stcxyz, scale);
        } catch (IOException ex) {
            return null;
        }
    }
    public static Image openIJTif(String filePath, int... slices) {
        File file = new File(filePath);
        ImagePlus imp;
        Opener o = new Opener();
        o.setSilentMode(true);
        if (slices==null || slices.length==0) {
            imp = o.openTiff(file.getParent(), file.getName());
            if (imp != null) {
                imp.setTitle(file.getName());
                Image im = IJImageWrapper.wrap(imp);
                return im;
            } else return null;
        } else {
            List<Image> planes = Arrays.stream(slices)
                    .mapToObj(s -> o.openTiff(file.toString(), s+1))
                    .filter(Objects::nonNull)
                    .map(IJImageWrapper::wrap)
                    .collect(Collectors.toList());
            if (planes.isEmpty()) return null;
            Image res = Image.mergeZPlanes(planes);
            res.setName(file.getName());
            return res;
            //logger.debug("opening with opener: slice: {} cal: {}", slice, imp.getCalibration());
        }
    }
}
