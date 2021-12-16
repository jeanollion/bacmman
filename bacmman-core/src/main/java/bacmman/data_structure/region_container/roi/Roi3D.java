package bacmman.data_structure.region_container.roi;

import bacmman.image.BoundingBox;
import bacmman.image.MutableBoundingBox;
import bacmman.image.Offset;
import bacmman.image.SimpleBoundingBox;
import ij.gui.Overlay;
import ij.gui.Roi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.util.HashMap;

public class Roi3D extends HashMap<Integer, Roi> {
    public static final Logger logger = LoggerFactory.getLogger(Roi3D.class);
    boolean is2D;
    int locdx, locdy; // in case of EllipseRoi -> 0.5 is added to coordinate, this can create inconsistencies in localization as IJ.ROIs use a integer reference. this is a fix when calling set location
    public Roi3D(int bucketSize) {
        super(bucketSize);
    }
    public Roi3D setLocDelta(int locdx, int locdy) {
        this.locdx = locdx;
        this.locdy = locdy;
        return this;
    }
    public Roi3D setIs2D(boolean is2D) {this.is2D=is2D; return this;}
    public boolean contained(Overlay o) {
        for (Roi r : values()) if (o.contains(r)) return true;
        return false;
    }
    public Roi3D setFrame(int frame) {
        for (Roi r : values()) r.setPosition(r.getCPosition(), r.getZPosition(), frame+1);
        return this;
    }

    public Roi3D setZToPosition() {
        for (Roi r: values()) r.setPosition(r.getZPosition());
        return this;
    }
    public Roi3D setTToPosition() {
        for (Roi r: values()) r.setPosition(r.getTPosition());
        return this;
    }
    public boolean is2D() {
        return is2D;
    }

    public MutableBoundingBox getBounds() {
        int xMin = this.entrySet().stream().filter(e -> e.getKey()>=0).mapToInt(e -> e.getValue().getBounds().x).min().getAsInt();
        int xMax = this.entrySet().stream().filter(e -> e.getKey()>=0).mapToInt(e -> e.getValue().getBounds().x + e.getValue().getBounds().width).max().getAsInt();
        int yMin = this.entrySet().stream().filter(e -> e.getKey()>=0).mapToInt(e -> e.getValue().getBounds().y).min().getAsInt();
        int yMax = this.entrySet().stream().filter(e -> e.getKey()>=0).mapToInt(e -> e.getValue().getBounds().y + e.getValue().getBounds().height).max().getAsInt();
        int zMin = this.entrySet().stream().filter(e -> e.getKey()>=0).mapToInt(e->e.getKey()).min().getAsInt();
        int zMax = this.entrySet().stream().filter(e -> e.getKey()>=0).mapToInt(e->e.getKey()).max().getAsInt();
        return new MutableBoundingBox(xMin, xMax, yMin, yMax, zMin, zMax);
    }
    public Roi3D setLocation(Offset off) {
        return translate(getBounds().reverseOffset().translate(off).setzMin(0));
    }

    public Roi3D translate(Offset off) {
        if (off.zMin()!=0) { // need to clear map to update z-mapping
            synchronized(this) {
                HashMap<Integer, Roi> temp = new HashMap<>(this);
                this.clear();
                temp.forEach((z, r)->put(z+off.zMin(), r));
            }
        }
        forEach((z, r)-> {
            r.setLocation((int)r.getXBase()+off.xMin()+locdx, (int)r.getYBase()+off.yMin()+locdy);
            r.setPosition(r.getCPosition(), r.getZPosition()+off.zMin(), r.getTPosition());
        });
        return this;
    }
    public Roi3D duplicate() {
        Roi3D res = new Roi3D(this.size()).setIs2D(is2D).setLocDelta(locdx, locdy);
        super.forEach((z, r)->res.put(z, (Roi)r.clone()));
        return res;
    }
    public int size() {
        return (int)keySet().stream().filter(z->z>=0).count();
    }
    public void duplicateROIUntilZ(int zMax) {
        if (size()>1 || !containsKey(0)) return;
        Roi r = this.get(0);
        for (int z = 1; z<zMax; ++z) {
            Roi dup = (Roi)r.clone();
            dup.setPosition(r.getCPosition(), z+1, r.getTPosition());
            this.put(z, dup);
        }
        if (this.containsKey(-1)) { // segmentation correction arrow
            r = this.get(-1);
            for (int z = 1; z<zMax; ++z) {
                Roi dup = (Roi)r.clone();
                dup.setPosition(r.getCPosition(), z+1, r.getTPosition());
                this.put(-z-1, dup);
            }
        }
    }
}