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
    final Object lock;
    public BackgroundImageLoader(Supplier<Set<ImageCoordinate>> loadedPosition, Consumer<ImageCoordinate> loadFunction, Object lock, int cacheSize, int maxFrames) {
        this.loadedPosition = loadedPosition;
        this.loadFunction = loadFunction;
        this.cacheSize = cacheSize;
        this.maxFrames=maxFrames;
        this.lock=lock;
        daemon = new Thread(this::run);
        daemon.setPriority(1);
        daemon.setDaemon(true);
        daemon.setName("BackgroundImageLoader-"+daemon.getName());
        daemon.start();
    }
    protected void run() {
        while(true) {
            synchronized (lock) {
                ImageCoordinate nextPosition = getNextPosition();
                try {
                    if (nextPosition == null) {
                        lock.wait();
                    } else {
                        loadFunction.accept(nextPosition);
                    }
                } catch (InterruptedException e) {
                    return;
                }
            }
            try {
                Thread.sleep(100); // give priority to main process
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
    public void setPosition(ImageCoordinate fcz) {
        //logger.debug("setting position: {} (was {})", fcz, currentPosition);
        if (fcz.equals(this.currentPosition)) return;
        synchronized (lock) {
            currentPosition = fcz;
            lock.notifyAll();
        }
    }
    public void interrupt() {
        daemon.interrupt();
    }
}
