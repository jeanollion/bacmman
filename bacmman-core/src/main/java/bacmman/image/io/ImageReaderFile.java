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
import ome.units.quantity.Time;
import ome.units.UNITS;
import ome.xml.model.primitives.Timestamp;
import bacmman.image.ImageByte;
import bacmman.image.ImageFloat;
import bacmman.image.ImageInt;
import bacmman.image.ImageShort;
import bacmman.utils.Pair;

import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.DoubleStream;
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
    volatile List<Double> timePoints;
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

    @Override
    public boolean imageExists() {
        return new File(getImagePath()).exists();
    }

    public boolean getInvertTZ() {
        return invertTZ;
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
        if (!imageExists()) {
            logger.error("File: {} was not found", getImagePath());
            return;
        }
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
                logger.error(ex.getMessage(), ex);
            }
        } catch (DependencyException ex) {
            logger.error(ex.getMessage(), ex);
        }
        setId();
    }
    public Map<String, Object> getMetadata() {
        return reader==null ? null : reader.getGlobalMetadata();
    }
    public synchronized Map<String, Object> getSeriesMetadata(int series) {
        int s = reader.getSeries();
        if (series!=s) reader.setSeries(series);
        Map<String, Object> res = reader.getSeriesMetadata();
        if (series!=s) reader.setSeries(s);
        return res;
    }

    private void setId() {
        try {
            //long t0 = System.currentTimeMillis();
            reader.setId(getImagePath());
            //long t1 = System.currentTimeMillis();
            //logger.debug("set id in  {}ms", t1-t0);
        } catch (FormatException | IOException ex) {
            logger.error("An error occurred while setting image id: {}, message: {}", getImagePath(),  ex.getMessage());
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
            logger.error("An error occurred while closing reader for image: "+getImagePath(),  ex);
        }
    }
    
    public Image openChannel() throws IOException {
        return ImageReaderFile.this.openImage(new ImageIOCoordinates());
    }

    public Image openImage(ImageIOCoordinates coords) throws IOException {
        if (reader==null) {
            if (!imageExists()) throw new IOException("Image not found: "+getImagePath());
            else throw new IOException("Reader not initialized");
        }
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
                if (bounds == null || !supportView) {
                    planes.add(openImage(idx, 0, 0, sizeX, sizeY, coords.getRGB()));
                } else {
                    planes.add(openImage(idx, bounds.xMin(), bounds.yMin(), bounds.sizeX(), bounds.sizeY(), coords.getRGB()));
                }
                res = Image.mergeZPlanes(planes);
                if (!supportView && bounds != null) { // crop
                    bounds.setzMin(0).setzMax(res.sizeZ() - 1);
                    res = res.crop(bounds);
                }
                if (bounds != null) res.resetOffset().translate(bounds);
                double[] scaleXYZ = getScaleXYZ(1);
                if (scaleXYZ[0] != 1) res.setCalibration((float) scaleXYZ[0], (float) scaleXYZ[2]);
            } catch (FormatException ex) {
                throw new IOException(ex);
            } catch (IOException ex) {
                logger.error("An error occurred while opening image: {}, c:{}, t:{}, s:{}, message: {}", reader.getCurrentFile() , coords.getChannel() , coords.getTimePoint(), coords.getSerie(), ex.getMessage());
                throw ex;
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
                        timePoints = paseDVLogFile(getDVLogPath(getImagePath()), "Time Point: ");
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
    
    /**
     * Sources of plane time points, by order of priority.
     */
    public enum TIME_SOURCE {
        TIF_TAGS(true, false), // acquisition time written in TIF tags by acquisition software (fast, see TiffTimestamps)
        DV_LOG(false, false), // DeltaVision log file
        BIOFORMATS_ACQUISITION_TIME(true, true), // acquisition date of the series + time offset of the plane
        BIOFORMATS_DELTA_T(false, true), // time offset of the plane relative to the start of the file
        FRAME_INTERVAL(false, true); // frame index x frame interval (Bio-Formats time increment or ImageJ TIF frame interval)
        public final boolean absolute, needsBioFormats;
        TIME_SOURCE(boolean absolute, boolean needsBioFormats) {
            this.absolute = absolute;
            this.needsBioFormats = needsBioFormats;
        }
    }

    /**
     * Time points of planes of a file, in seconds: since epoch if the source is absolute, otherwise relative to the file.
     * Absolute local times without time zone are interpreted as UTC: they are only meaningful as differences.
     */
    public static class TimePoints {
        public final TIME_SOURCE source;
        public final double[] times;
        public final double frameInterval; // in seconds, only for FRAME_INTERVAL source
        TimePoints(TIME_SOURCE source, double[] times, double frameInterval) {
            this.source = source;
            this.times = times;
            this.frameInterval = frameInterval;
        }
    }

    private static String getDVLogPath(String imagePath) {
        int deconvIdx = imagePath.indexOf("_D3D");
        return deconvIdx>0 ? imagePath.substring(0, deconvIdx)+".dv.log" : imagePath+".log";
    }

    private static boolean isDV(String path) {
        return path.toLowerCase().endsWith(ImageFormat.DV.getExtension());
    }

    /**
     * Time points read without Bio-Formats (fast), for files whose planes are stored in order (e.g. one file per frame and channel, planes = z-slices).
     * Sources tried: TIF tags, then DeltaVision log file.
     * @param nPlanes number of planes
     * @param sources allowed sources
     * @return time points of the {@code nPlanes} first planes, or null if not found
     */
    public static TimePoints getTimePointsWithoutBioFormats(String filePath, int nPlanes, Set<TIME_SOURCE> sources) {
        if (sources.contains(TIME_SOURCE.TIF_TAGS) && isIJTif(filePath)) {
            double[] tifTimes = TiffTimestamps.read(filePath);
            if (tifTimes != null) { // if only one time is found for a stack, it is used for all planes
                double[] times = IntStream.range(0, nPlanes).mapToDouble(i -> tifTimes[Math.min(i, tifTimes.length-1)] / 1000d).toArray();
                return new TimePoints(TIME_SOURCE.TIF_TAGS, times, Double.NaN);
            }
        }
        if (sources.contains(TIME_SOURCE.DV_LOG) && isDV(filePath)) {
            List<Double> log = paseDVLogFile(getDVLogPath(filePath), "Time Point: ");
            if (log != null && log.size() >= nPlanes) return new TimePoints(TIME_SOURCE.DV_LOG, log.subList(0, nPlanes).stream().mapToDouble(d->d).toArray(), Double.NaN);
        }
        return null;
    }

    /**
     * Time points of planes of the file. Sources are tried by order of priority (see {@link TIME_SOURCE}), the first source that gives the time of all planes is used.
     * @param series series index
     * @param planesCTZ coordinates (channel, frame, z) of each plane
     * @param sources allowed sources
     * @return time points of each plane, or null if not found
     */
    public synchronized TimePoints getTimePoints(int series, int[][] planesCTZ, Set<TIME_SOURCE> sources) {
        if (reader==null) return null;
        reader.setSeries(series);
        int[] idx = Arrays.stream(planesCTZ).mapToInt(p -> getIndex(p[0], p[1], p[2])).toArray();
        String path = getImagePath();
        if (sources.contains(TIME_SOURCE.TIF_TAGS) && isIJTif(path)) {
            double[] tifTimes = TiffTimestamps.read(path);
            if (tifTimes != null && tifTimes.length == reader.getImageCount() && reader.getSeriesCount() == 1) { // pages are planes
                double[] times = IntStream.of(idx).mapToDouble(i -> tifTimes[i] / 1000d).toArray();
                if (isValid(planesCTZ, times)) return new TimePoints(TIME_SOURCE.TIF_TAGS, times, Double.NaN);
            }
        }
        if (sources.contains(TIME_SOURCE.DV_LOG) && isDV(path)) {
            List<Double> log = paseDVLogFile(getDVLogPath(path), "Time Point: ");
            if (log != null && IntStream.of(idx).allMatch(i -> i < log.size())) {
                double[] times = IntStream.of(idx).mapToDouble(log::get).toArray();
                if (isValid(planesCTZ, times)) return new TimePoints(TIME_SOURCE.DV_LOG, times, Double.NaN);
            }
        }
        if (meta == null) return null;
        try {
            double[] deltaT = IntStream.of(idx).mapToDouble(i -> getDeltaT(series, i)).toArray();
            boolean allDeltaT = DoubleStream.of(deltaT).noneMatch(Double::isNaN);
            if (sources.contains(TIME_SOURCE.BIOFORMATS_ACQUISITION_TIME)) {
                Timestamp date = meta.getImageAcquisitionDate(series);
                boolean singleFrame = Arrays.stream(planesCTZ).mapToInt(p -> p[1]).distinct().count() == 1;
                if (date != null && date.asInstant() != null && (allDeltaT || singleFrame)) { // without time offsets, planes of different frames would have the same time
                    double date_s = date.asInstant().getMillis() / 1000d;
                    double[] times = DoubleStream.of(deltaT).map(dt -> Double.isNaN(dt) ? date_s : date_s + dt).toArray();
                    if (isValid(planesCTZ, times)) return new TimePoints(TIME_SOURCE.BIOFORMATS_ACQUISITION_TIME, times, Double.NaN);
                }
            }
            if (sources.contains(TIME_SOURCE.BIOFORMATS_DELTA_T) && allDeltaT && isValid(planesCTZ, deltaT)) return new TimePoints(TIME_SOURCE.BIOFORMATS_DELTA_T, deltaT, Double.NaN);
            if (sources.contains(TIME_SOURCE.FRAME_INTERVAL)) {
                double interval = toSeconds(meta.getPixelsTimeIncrement(series));
                if (!(interval > 0) && isIJTif(path)) interval = getTIFTimeFrameInterval(path);
                if (interval > 0) {
                    double fi = interval;
                    return new TimePoints(TIME_SOURCE.FRAME_INTERVAL, Arrays.stream(planesCTZ).mapToDouble(p -> p[1] * fi).toArray(), fi);
                }
            }
        } catch (RuntimeException e) {
            logger.debug("error while reading time points of file: {}: {}", path, e.toString());
        }
        return null;
    }

    /**
     * Time points are valid if, for each channel and z-slice, time strictly increases with the frame index.
     * Some readers return invalid values when times are not recorded (e.g. 0 for all planes)
     */
    static boolean isValid(int[][] planesCTZ, double[] times) {
        if (DoubleStream.of(times).anyMatch(Double::isNaN)) return false;
        Map<List<Integer>, TreeMap<Integer, Double>> timeByFrameByCZ = new HashMap<>();
        for (int i = 0; i < planesCTZ.length; ++i) {
            int[] p = planesCTZ[i];
            Double previous = timeByFrameByCZ.computeIfAbsent(Arrays.asList(p[0], p[2]), k -> new TreeMap<>()).put(p[1], times[i]);
            if (previous != null && previous != times[i]) return false; // same plane with different times
        }
        for (TreeMap<Integer, Double> timeByFrame : timeByFrameByCZ.values()) {
            double previous = Double.NEGATIVE_INFINITY;
            for (double t : timeByFrame.values()) {
                if (t <= previous) return false;
                previous = t;
            }
        }
        return true;
    }

    private double getDeltaT(int series, int planeIdx) {
        if (planeIdx >= meta.getPlaneCount(series)) return Double.NaN;
        return toSeconds(meta.getPlaneDeltaT(series, planeIdx));
    }

    private static double toSeconds(Time time) {
        if (time == null) return Double.NaN;
        Number s = time.value(UNITS.SECOND);
        return s == null ? Double.NaN : s.doubleValue();
    }

    public void logTimeAnnotations() {
        if (meta!=null) {
            logger.debug("image count: {}", meta.getImageCount());
            for (int i = 0; i<meta.getImageCount(); ++i) {
                logger.debug("i:{}, time: {}, {} {}", i, meta.getImageAcquisitionDate(i), meta.getImageAcquisitionDate(i)==null? "":meta.getImageAcquisitionDate(i).asDateTime(DateTimeZone.UTC), meta.getImageAcquisitionDate(i)==null? "":meta.getImageAcquisitionDate(i).asInstant());
            }
            int c = meta.getTimestampAnnotationCount();
            for (int i = 0; i<c; ++c) {
                logger.debug("time: i={}, value: {} (as date={}, as instant={}), ns={}, id={}, desc={}, annotator={}", i, meta.getTimestampAnnotationValue(i), meta.getTimestampAnnotationValue(i)==null? "":meta.getTimestampAnnotationValue(i).asDateTime(DateTimeZone.UTC), meta.getTimestampAnnotationValue(i)==null? "":meta.getTimestampAnnotationValue(i).asInstant(), meta.getTimestampAnnotationNamespace(i), meta.getTimestampAnnotationID(i), meta.getTimestampAnnotationDescription(i), meta.getTimestampAnnotationAnnotator(i));
                int cc = meta.getTimestampAnnotationAnnotationCount(i);
                for (int ii = 0; ii<cc; ++ii) {
                    logger.debug("time: i={}, ref.idx={}, ref={}", i, ii, meta.getTimestampAnnotationAnnotationRef(i, ii));
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
    
    public static Image openImage(String filePath) throws IOException {
        return openImage(filePath, new ImageIOCoordinates());
    }
    
    public static Image openImage(String filePath, ImageIOCoordinates ioCoords) throws IOException {
        return openImage(filePath, ioCoords, false, null);
    }

    public static Image openImage(String filePath, ImageIOCoordinates ioCoords, boolean invertTZ, byte[][] buffer) throws IOException {
        if ((filePath.toLowerCase().endsWith(".tif") || filePath.toLowerCase().endsWith(".tiff")) && !filePath.toLowerCase().endsWith(".ome.tiff")) { // try with faster IJ's method (10x to 100x faster than bioformat as of january 2022 : setID method is very slow)
            if (ioCoords.getSerie()==0 && ioCoords.getChannel()==0 && ioCoords.getTimePoint()==0) { // this only works when
                int[] slices = ioCoords.getBounds()==null ? null : ArrayUtil.generateIntegerArray(ioCoords.getBounds().zMin(), ioCoords.getBounds().zMax()+1);
                //long t0 = System.currentTimeMillis();
                //logger.debug("opening IJ TIF: slices {}", slices);
                Image res = openIJTif(filePath, ioCoords.rgb, slices);
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
        ImageReaderFile reader = new ImageReaderFile(filePath).setInvertTZ(invertTZ);
        long t1 = System.currentTimeMillis();
        if (buffer!=null) reader.buffer = buffer[0];
        Image im = reader.openImage(ioCoords);
        if (buffer!=null) buffer[0] = reader.buffer;
        long t2 = System.currentTimeMillis();
        reader.closeReader();
        long t3 = System.currentTimeMillis();
        //logger.debug("opening with image reader : init {}ms, open: {}ms, close: {}ms", t1-t0, t2-t1, t3-t2);
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
    /**
     * @return true if the file is a TIF that is not an OME-TIF: such files are opened with ImageJ's TIF reader, which is much faster than Bio-Formats
     */
    public static boolean isIJTif(String filePath) {
        String lower = filePath.toLowerCase();
        return (lower.endsWith(".tif") || lower.endsWith(".tiff")) && !lower.endsWith(".ome.tif") && !lower.endsWith(".ome.tiff");
    }

    /**
     * Acquisition time of each plane of a TIF file, read directly from TIF tags (fast: no Bio-Formats reader is created). See {@link TiffTimestamps} for supported software.
     * @return acquisition time in milliseconds of each plane (only meaningful as differences), or null if not found
     */
    public static double[] getTIFAcquisitionTimes(String filePath) {
        return TiffTimestamps.read(filePath);
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
            stcxyz[0][4] = Math.max(1, Arrays.stream(info).mapToInt(i->i.nImages).sum());
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
        return openIJTif(filePath, ImageIOCoordinates.RGB.R, slices);
    }
    public static Image openIJTif(String filePath, ImageIOCoordinates.RGB rgb, int... slices) {
        File file = new File(filePath);
        ImagePlus imp;
        Opener o = new Opener();
        o.setSilentMode(true);
        if (slices==null || slices.length==0) {
            imp = o.openTiff(file.getParent(), file.getName());
            if (imp != null) {
                imp.setTitle(file.getName());
                if (imp.isRGB()) imp.updatePosition(rgb.idx + 1, imp.getZ(), imp.getT());
                Image im = IJImageWrapper.wrap(imp);
                return im;
            } else return null;
        } else {
            List<Image> planes = Arrays.stream(slices)
                    .mapToObj(s -> o.openTiff(file.toString(), s+1))
                    .filter(Objects::nonNull)
                    .peek(i -> { if (i.isRGB()) {i.updatePosition(rgb.idx + 1, i.getZ(), i.getT() );} })
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
