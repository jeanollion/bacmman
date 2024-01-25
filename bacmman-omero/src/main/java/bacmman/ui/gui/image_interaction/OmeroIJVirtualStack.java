package bacmman.ui.gui.image_interaction;

import bacmman.core.DefaultWorker;
import bacmman.core.OmeroGatewayI;
import bacmman.image.*;
import bacmman.image.wrappers.IJImageWrapper;
import bacmman.processing.ImageOperations;
import bacmman.utils.HashMapGetCreate;
import bacmman.utils.Pair;
import ij.ImagePlus;
import ij.VirtualStack;
import ij.measure.Calibration;
import ij.process.ImageProcessor;
import ome.model.units.BigResult;
import omero.ServerError;
import omero.api.RawPixelsStorePrx;
import omero.gateway.exception.DSOutOfServiceException;
import omero.gateway.model.PixelsData;
import omero.gateway.rnd.DataSink;
import omero.model.enums.UnitsLength;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.event.WindowEvent;
import java.awt.event.WindowListener;
import java.util.*;

import static bacmman.image.io.OmeroUtils.convertPlane;


public class OmeroIJVirtualStack extends VirtualStack {
    public static final Logger logger = LoggerFactory.getLogger(OmeroIJVirtualStack.class);
    RawPixelsStorePrx rawData;
    final int sizeX, sizeY, sizeZ, sizeC, sizeT;
    final String type;
    final Map<Integer, Image> cachedImages = new HashMapGetCreate.HashMapGetCreateRedirectedSyncKey<>(this::openPlane);
    DefaultWorker lazyOpener;
    Map<Integer, double[]> displayRange= new HashMap<>();
    Map<Integer, Boolean> displayRangeManual = new HashMapGetCreate.HashMapGetCreateRedirectedSync<>(i -> false);
    boolean channelWiseDisplayRange = true;
    ImagePlus ip;
    int lastChannel=-1;
    final LinkedList<Integer> cacheQueue = new LinkedList<>();
    public static int N_CACHED_FRAMES = 100;
    public OmeroIJVirtualStack(PixelsData pixels, OmeroGatewayI gateway) throws DSOutOfServiceException, ServerError {
        super(pixels.getSizeX(), pixels.getSizeY(), null, "");
        this.rawData = gateway.gateway().createPixelsStore(gateway.securityContext());
        this.rawData.setPixelsId(pixels.getId(), false);
        this.sizeX = pixels.getSizeX();
        this.sizeY = pixels.getSizeY();
        this.sizeC = pixels.getSizeC();
        this.sizeZ = pixels.getSizeZ();
        this.sizeT = pixels.getSizeT();
        type = pixels.getPixelType();
        logger.debug("created virtual stack: pixel type: {}", type);
        for (int n = 0; n<sizeC*sizeZ*sizeT; ++n) super.addSlice("");
    }

    @Override
    public ImageProcessor getProcessor(int n) {
        Image nextImage = cachedImages.get(n);
        ImageProcessor nextIP = IJImageWrapper.getImagePlus(nextImage).getProcessor();
        int nextChannel = ((n-1)%sizeC);
        setDisplayRange(nextChannel, nextImage, nextIP);

        synchronized (cacheQueue) { // put in front
            cacheQueue.remove((Integer)n);
            cacheQueue.add(n);
            if (N_CACHED_FRAMES>0) {
                List<Integer> toRemove = new ArrayList<>();
                while (cacheQueue.size() > N_CACHED_FRAMES) {
                    toRemove.add(cacheQueue.pollFirst());
                }
                if (!toRemove.isEmpty()) {
                    synchronized (cachedImages) {
                        toRemove.forEach(cachedImages::remove);
                    }
                }
            }
        }
        return nextIP;
    }

    private Image openPlane(int n) {
        if (rawData==null) { // disconnected
            switch (type) {
                case DataSink.UINT_8: {
                    return new ImageByte("", sizeX, sizeY, 1);
                } case DataSink.UINT_16: {
                    return new ImageShort("", sizeX, sizeY, 1);
                } case DataSink.UINT_32: {
                    return new ImageInt("", sizeX, sizeY, 1);
                } case DataSink.FLOAT: {
                    return new ImageFloat("", sizeX, sizeY, 1);
                }
            }
        }
        int c = ((n-1)%sizeC);
        int z = (((n-1)/sizeC)%sizeZ);
        int t = (((n-1)/(sizeC*sizeZ))%sizeT);
        try {
            byte[] data = rawData.getPlane(z, c, t);
            Image plane = convertPlane(data, null, this.sizeX, this.sizeY, this.type);
            return plane;

        } catch (Exception e) {
            String p = "("+z+", "+c+", "+t+")";
            throw new RuntimeException("Cannot retrieve the plane "+p, e);
        }
    }
    public void detachFromServer() {
        lazyOpener.cancelSilently();
        rawData = null;
    }
    protected void setDisplayRange(int nextChannel, Image nextImage, ImageProcessor nextIP) {
        if (ip==null || !channelWiseDisplayRange) return;
        if (nextChannel!=lastChannel) {
            if (lastChannel>=0) {
                double[] newDisplayRange = new double[]{ip.getDisplayRangeMin(), ip.getDisplayRangeMax()};
                if (displayRange.containsKey(lastChannel)) {
                    double[] oldDisplayRange = displayRange.get(lastChannel);
                    if (oldDisplayRange[0]!=newDisplayRange[0] || oldDisplayRange[1]!=newDisplayRange[1]) displayRangeManual.put(lastChannel, true);
                    logger.debug("channel: {} has been modified manually", nextChannel);
                }
                displayRange.put(lastChannel, newDisplayRange); // record display for last channel
            }
            if (!displayRange.containsKey(nextChannel)) { // initialize with actual range // TODO initialize with more elaborated algorithm ?
                double[] minAndMax = ImageOperations.getQuantiles(nextImage, null, null, 0.00001, 0.99999);
                displayRange.put(nextChannel, minAndMax);
            } else if (!displayRangeManual.get(nextChannel) && nextImage!=null) {
                double[] minAndMax = ImageOperations.getQuantiles(nextImage, null, null, 0.00001, 0.99999);
                double[] oldDisplayRange = displayRange.get(nextChannel);
                oldDisplayRange[0] = Math.min(oldDisplayRange[0], minAndMax[0]);
                oldDisplayRange[1] = Math.min(oldDisplayRange[1], minAndMax[1]);
                logger.debug("channel: {} updating display range with: {} -> {}",nextChannel, minAndMax, oldDisplayRange);
            }
            double[] curDisp = displayRange.get(nextChannel);
            if (ip.getProcessor()!=null) ip.getProcessor().setMinAndMax(curDisp[0], curDisp[1]); // the image processor stays the same
            else nextIP.setMinAndMax(curDisp[0], curDisp[1]);
            //logger.debug("disp range for channel {} = [{}; {}]", nextChannel, curDisp[0], curDisp[1]);
            lastChannel = nextChannel;
        }
    }
    public void setImagePlus(ImagePlus ip) {
        this.ip = ip;
    }
    public static Pair<OmeroIJVirtualStack, ImagePlus> openVirtual(String name, PixelsData pixels, OmeroGatewayI gateway, boolean show) {
        try {
            OmeroIJVirtualStack stack = new OmeroIJVirtualStack(pixels, gateway);
            ImagePlus ip = new ImagePlus();
            stack.setImagePlus(ip);
            stack.lazyOpener = DefaultWorker.execute(i -> {
                stack.cachedImages.get(i+1);
                return null;
            }, stack.getSize()).appendEndOfWork(() -> ip.setTitle(name));

            ip.setTitle(name+" (downloading...)");
            ip.setStack(stack, stack.sizeC,stack.sizeZ, stack.sizeT);
            ip.setOpenAsHyperStack(true);

            try {
                Calibration cal = new Calibration();
                if (pixels.getPixelSizeX(UnitsLength.MICROMETER)!=null) cal.pixelWidth=pixels.getPixelSizeX(UnitsLength.MICROMETER).getValue();
                if (pixels.getPixelSizeY(UnitsLength.MICROMETER)!=null) cal.pixelHeight=pixels.getPixelSizeY(UnitsLength.MICROMETER).getValue();
                if (pixels.getPixelSizeZ(UnitsLength.MICROMETER)!=null) cal.pixelDepth=pixels.getPixelSizeZ(UnitsLength.MICROMETER).getValue();
                ip.setCalibration(cal);
            } catch (BigResult e) {
                logger.debug("error while reading calibration", e);
            }
            if (show) {
                ip.show();
                ip.getWindow().addWindowListener(new WindowListener() {
                    @Override public void windowOpened(WindowEvent windowEvent) { }
                    @Override public void windowClosing(WindowEvent windowEvent) { }
                    @Override
                    public void windowClosed(WindowEvent windowEvent) {
                        logger.debug("canceling lazy opener");
                        stack.lazyOpener.cancelSilently();
                    }
                    @Override public void windowIconified(WindowEvent windowEvent) {}
                    @Override public void windowDeiconified(WindowEvent windowEvent) { }
                    @Override public void windowActivated(WindowEvent windowEvent) { }
                    @Override public void windowDeactivated(WindowEvent windowEvent) { }
                });
            }

            return new Pair<>(stack, ip);
        } catch (DSOutOfServiceException | ServerError e) {
            logger.debug("error while reading image", e);
            return null;
        }
    }

}
