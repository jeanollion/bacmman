package bacmman.ui.image_interaction;

import bacmman.core.Core;
import bacmman.image.BoundingBox;
import bacmman.image.Image;
import bacmman.image.SimpleBoundingBox;
import bacmman.image.wrappers.ImgLib2ImageWrapper;
import bacmman.ui.gui.image_interaction.ImageDisplayer;
import net.imagej.Dataset;
import net.imagej.DatasetService;
import net.imagej.Position;
import net.imagej.display.DataView;
import net.imagej.display.ImageDisplay;
import net.imagej.display.OverlayService;
import net.imagej.lut.LUTService;
import net.imagej.overlay.Overlay;
import net.imglib2.img.Img;
import net.imglib2.type.numeric.RealType;
import org.scijava.display.DisplayService;
import org.scijava.ui.UIService;

import java.util.HashMap;
import java.util.function.Predicate;

public class IJ2ImageDisplayer implements ImageDisplayer<ImageDisplay> {
    private final DisplayService displayService;
    private final OverlayService overlayService;
    private final DatasetService ds;
    private final LUTService lutService;
    private final UIService ui;

    public IJ2ImageDisplayer(DatasetService ds, DisplayService displayService, LUTService lutService, OverlayService overlayService, UIService ui) {
        this.displayService = displayService;
        this.overlayService = overlayService;
        this.ds = ds;
        this.lutService = lutService;
        this.ui = ui;
    }

    protected HashMap<Image, ImageDisplay> displayedImages=new HashMap<>();
    protected HashMap<ImageDisplay, Image> displayedImagesInv=new HashMap<>();


    @Override
    public boolean isDisplayed(ImageDisplay image) {
        return displayedImages.containsKey(image);
    }

    @Override
    public ImageDisplay showImage(Image image, double... displayRange) {
        Img<RealType> img= ImgLib2ImageWrapper.getImage(image);
        Dataset dataset = ds.create(img);
        ImageDisplay disp = (ImageDisplay)displayService.createDisplay(dataset);
        ui.show(disp);
        return disp;
    }

    @Override
    public void close(Image image) {
        ImageDisplay disp = displayedImages.remove(image);
        if (disp!=null) {
            disp.close();
            displayedImagesInv.remove(disp);
        }
    }

    @Override
    public void close(ImageDisplay image) {
        Image im = displayedImagesInv.remove(image);
        if (im!=null) displayedImages.remove(im);
    }

    @Override
    public ImageDisplay getImage(Image image) {
        return displayedImages.get(image);
    }

    @Override
    public Image getImage(ImageDisplay image) {
        return displayedImagesInv.get(image);
    }

    @Override
    public void updateImageDisplay(Image image, double... displayRange) {
        ImageDisplay disp = displayedImages.get(image);
        if (disp!=null) {
            // TODO
        }

    }

    @Override
    public void updateImageRoiDisplay(Image image) {
        ImageDisplay disp = displayedImages.get(image);
        if (disp!=null) {
            Overlay o= overlayService.getActiveOverlay(disp);
            if (o!=null) o.update();
        }
    }

    @Override
    public ImageDisplay showImage5D(String title, Image[][] imageTC) {
        // TODO
        return null;
    }

    @Override
    public BoundingBox getDisplayRange(Image image) {
        ImageDisplay disp = displayedImages.get(image);
        if (disp==null) return null;
        DataView view = disp.getActiveView();
        int h = view.getPreferredHeight();
        int w = view.getPreferredWidth();
        Position p = disp.getActiveView().getPlanePosition();
        int x = p.getIntPosition(0);
        int y = disp.getIntPosition(1);
        int z = disp.getIntPosition(2);
        return new SimpleBoundingBox(x, x+w-1, y, y+h-1, z, z);
    }

    @Override
    public void setDisplayRange(BoundingBox bounds, Image image) {
        ImageDisplay disp = displayedImages.get(image);
        if (disp==null) return;
        DataView view = disp.getActiveView();
        view.setPosition(new int[]{bounds.xMin(), bounds.xMax()});
        view.update();
    }

    @Override
    public ImageDisplay getCurrentImage() {
        return displayService.getActiveDisplay(ImageDisplay.class);
    }

    @Override
    public Image getCurrentImage2() {
        ImageDisplay disp = displayService.getActiveDisplay(ImageDisplay.class);
        return displayedImagesInv.get(disp);
    }

    @Override
    public Image[][] getCurrentImageCT() {
        // TODO
        return new Image[0][];
    }

    @Override
    public void flush() {
        displayedImages.values().forEach(d->d.close());
        displayedImagesInv.clear();
        displayedImages.clear();
    }

    @Override
    public void addMouseWheelListener(Image image, Predicate movementCallBack) {
        // TODO
    }
}
