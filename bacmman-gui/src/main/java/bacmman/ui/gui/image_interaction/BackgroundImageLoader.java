package bacmman.ui.gui.image_interaction;

import bacmman.image.ImageCoordinate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class BackgroundImageLoader {
    static Logger logger = LoggerFactory.getLogger(BackgroundImageLoader.class);
    final Supplier<Set<ImageCoordinate>> loadedPosition;
    final Consumer<ImageCoordinate> loadFunction;
    Thread daemon;
    ImageCoordinate currentPosition;
    final int cacheSize, maxFrames;
    public BackgroundImageLoader(Supplier<Set<ImageCoordinate>> loadedPosition, Consumer<ImageCoordinate> loadFunction, int cacheSize, int maxFrames) {
        this.loadedPosition = loadedPosition;
        this.loadFunction = loadFunction;
        this.cacheSize = cacheSize;
        this.maxFrames=maxFrames;
        daemon = new Thread(this::run);
        daemon.setDaemon(true);
        daemon.setName("BackgroundImageLoader-"+daemon.getName());
        daemon.start();
    }
    protected synchronized void run() {
        while(true) {
            ImageCoordinate nextPosition = getNextPosition();
            try {
                if (nextPosition == null) {
                    logger.debug("no next position: waiting");
                    wait();
                }
                else {
                    //logger.debug("current position: {} will load: {}", currentPosition, nextPosition);
                    loadFunction.accept(nextPosition);
                }
            } catch (InterruptedException e) {
                return;
            }
        }
    }
    protected ImageCoordinate getNextPosition() {
        if (currentPosition == null) return null;
        Set<ImageCoordinate> loadedPositions = loadedPosition.get();
        ImageCoordinate res = currentPosition.duplicate();
        for (int i = 0; i<=cacheSize/2; ++i) {
            res.setFrame(currentPosition.getFrame()+i+1);
            if (res.getFrame()<maxFrames && !loadedPositions.contains(res)) return res;
            res.setFrame(currentPosition.getFrame()-i-1);
            if (res.getFrame()>=0 && !loadedPositions.contains(res)) return res;
        }
        return null;
    }
    public synchronized void setPosition(ImageCoordinate fcz) {
        //logger.debug("setting position: {} (was {})", fcz, currentPosition);
        currentPosition = fcz;
        notifyAll();
    }
    public void interrupt() {
        daemon.interrupt();
    }
}
