package bacmman.core;

import bacmman.configuration.experiment.Experiment;
import bacmman.data_structure.image_container.MultipleImageContainer;
import bacmman.image.Image;
import bacmman.image.io.ImageReader;
import bacmman.image.io.OmeroImageMetadata;
import bacmman.omero.BACMMANLogger;
import bacmman.image.io.ImageReaderOmero;
import bacmman.ui.gui.ImportFromOmero;
import bacmman.ui.logger.ProgressLogger;
import bacmman.utils.Pair;
import omero.ServerError;
import omero.api.RawPixelsStorePrx;
import omero.gateway.Gateway;
import omero.gateway.LoginCredentials;
import omero.gateway.SecurityContext;
import omero.gateway.exception.DSAccessException;
import omero.gateway.exception.DSOutOfServiceException;
import omero.gateway.facility.BrowseFacility;
import omero.gateway.model.ExperimenterData;
import omero.gateway.model.ImageData;
import omero.gateway.model.PixelsData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static bacmman.image.io.OmeroUtils.convertPlane;

public class OmeroGatewayI implements OmeroGateway {
    public static final Logger logger = LoggerFactory.getLogger(OmeroGatewayI.class);
    Gateway gateway;
    SecurityContext ctx;
    BrowseFacility browse;
    String hostname, username, password;
    final private Map<String, char[]> serverPasswords = new HashMap<>();
    ImportFromOmero importInstance;
    public OmeroGatewayI(ProgressLogger bacmmanLogger) {
        gateway = new Gateway(new BACMMANLogger(logger, bacmmanLogger));
    }
    public Gateway gateway() {
        connectIfNecessary();
        return gateway;
    }
    @Override
    public boolean isConnected() {
        return gateway.isConnected();
    }
    public boolean connectIfNecessary() {
        if (!gateway.isConnected() && validCredentials()) {
            synchronized (gateway) {
                if (!gateway.isConnected() && validCredentials()) connect();
            }
        }
        return gateway.isConnected();
    }
    public boolean validCredentials() {
        if (hostname!=null && hostname.length()>0 && username!=null && username.length()>0) {
            if ((password==null || password.length()==0) && serverPasswords.containsKey(username)) password = String.valueOf(serverPasswords.get(username));
            return password!=null && password.length()>0;
        }
        return false;
    }
    public SecurityContext securityContext() {
        connectIfNecessary();
        return ctx;
    }
    public BrowseFacility browse() {
        connectIfNecessary();
        return browse;
    }
    @Override
    public OmeroGateway setCredentials(String hostname, String username, String password) {
        this.username=username;
        this.hostname=hostname;
        this.password=password;
        return this;
    }

    private static Pair<String, Integer> splitHostName(String hostname) {
        int idx = hostname.indexOf(':');
        if (idx > 0) {
            String portS = hostname.substring(idx);
            try {
                Integer port = Integer.parseInt(portS);
                return new Pair<>(hostname.substring(0, idx), port);
            } catch (NumberFormatException ignored) {

            }

        }
        return new Pair<>(hostname, -1);
    }
    @Override
    public boolean connect() {
        Pair<String, Integer> hp = splitHostName(hostname);
        LoginCredentials cred = new LoginCredentials();
        cred.getServer().setHost(hp.key);
        if (hp.value > 0) {
            cred.getServer().setPort(hp.value);
        }
        cred.getUser().setUsername(username);
        cred.getUser().setPassword(password);
        try {
            ExperimenterData user = gateway.connect(cred);
            ctx = new SecurityContext(user.getGroupId());
            ctx.setExperimenter(user);
            browse = gateway.getFacility(BrowseFacility .class);

            return true;
        } catch (DSOutOfServiceException|ExecutionException e) {
            logger.debug("error while connecting: ", e);
            logger.debug("Could not connect to OMERO Server: " + e.getLocalizedMessage());
            return false;
        }
    }

    public Image[][] openImageCT(long id) {
        try {
            RawPixelsStorePrx rawData = gateway().createPixelsStore(securityContext());
            ImageData imData = browse.getImage(ctx, id);
            PixelsData pixels = imData.getDefaultPixels();
            rawData.setPixelsId(pixels.getId(), false);
            Image[][] imageCT = new Image[pixels.getSizeC()][pixels.getSizeT()];
            for (int c = 0; c< pixels.getSizeC(); ++c) {
                for (int t = 0; t< pixels.getSizeT(); ++t) {
                    imageCT[c][t] = getImage(pixels, rawData, c, t);
                }
            }
            return imageCT;
        } catch (DSOutOfServiceException | DSAccessException | ServerError e) {
            logger.debug("error while retrieving image5D: ", e);
            throw new RuntimeException(e);
        }
    }
    public Image getImage(PixelsData pixels, RawPixelsStorePrx rawData, int c, int t) {
        List<Image> planes = IntStream.range(0, pixels.getSizeZ()).mapToObj(z -> getPlane(pixels, rawData, z, c, t)).collect(Collectors.toList());
        return (Image)Image.mergeImagesInZ(planes);
    }
    public Image getPlane(PixelsData pixels, RawPixelsStorePrx rawData, int z, int c, int t) {
        try {
            byte[] data = rawData.getPlane(z, c, t);
            return convertPlane(data, null, pixels.getSizeX(), pixels.getSizeY(), pixels.getPixelType());
        } catch (ServerError e) {
            logger.debug("error retrieving raw data", e);
            throw new RuntimeException(e);
        }
    }

    @Override
    public boolean close() {
        ctx = null;
        browse = null;
        if (importInstance!=null) {
            importInstance.close();
            importInstance = null;
        }
        try {
            gateway.close();
        } catch (Exception e) {
            return false;
        }
        return true;
    }

    @Override
    public ImageReader createReader(long imageID) {
        connectIfNecessary();
        logger.debug("creating reader for ID: {}, connected ? {}", imageID, isConnected());
        return new ImageReaderOmero(imageID, this);
    }

    @Override
    public void importFiles(Experiment xp, Consumer<List<MultipleImageContainer>> callback, ProgressCallback pcb) {
        // dialog -> omero browser / cannot be a modal window because we want to be able to see images
        if (importInstance!=null) {
            logger.debug("import instance already running");
            importInstance.toFront();
        } else {
            Consumer<List<OmeroImageMetadata>> importCallback = sel -> {
                if (!sel.isEmpty()) {
                    List<MultipleImageContainer> images = OmeroImageFieldFactory.importImages(sel, xp, pcb);
                    if (!images.isEmpty()) callback.accept(images);
                }
                importInstance = null;
            };
            importInstance = new ImportFromOmero(this, serverPasswords, importCallback, pcb);
            importInstance.pack();
            importInstance.setVisible(true);
            importInstance.setAlwaysOnTop(true);
        }
    }

}