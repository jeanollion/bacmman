package bacmman.data_structure.region_container.roi;

import bacmman.image.Offset;
import ij.gui.Overlay;
import ij.gui.Roi;

import java.awt.*;
import java.util.HashMap;

public class Roi3D extends HashMap<Integer, Roi> {
    boolean is2D;
    public Roi3D(int bucketSize) {
        super(bucketSize);
    }
    public Roi3D setIs2D(boolean is2D) {this.is2D=is2D; return this;}
    public boolean contained(Overlay o) {
        for (Roi r : values()) if (o.contains(r)) return true;
        return false;
    }

    public boolean is2D() {
        return is2D;
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
            Rectangle bds = r.getBounds();
            r.setLocation(bds.x+off.xMin(), bds.y+off.yMin());
            r.setPosition(r.getPosition()+off.zMin());
        });
        return this;
    }
    public Roi3D duplicate() {
        Roi3D res = new Roi3D(this.size()).setIs2D(is2D);
        super.forEach((z, r)->res.put(z, (Roi)r.clone()));
        return res;
    }
    public void duplicateROIUntilZ(int zMax) {
        if (size()>1 || !containsKey(0)) return;
        Roi r = this.get(0);
        for (int z = 1; z<zMax; ++z) {
            Roi dup = (Roi)r.clone();
            dup.setPosition(z+1);
            this.put(z, dup);
        }
        if (this.containsKey(-1)) { // segmentation correction arrow
            r = this.get(-1);
            for (int z = 1; z<zMax; ++z) {
                Roi dup = (Roi)r.clone();
                dup.setPosition(z+1);
                this.put(-z-1, dup);
            }
        }
    }
}