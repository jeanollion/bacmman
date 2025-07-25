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
package bacmman.data_structure;

import bacmman.data_structure.region_container.roi.IJRoi3D;
import bacmman.measurement.BasicMeasurements;
import bacmman.processing.EDT;
import com.google.common.collect.Sets;
import bacmman.data_structure.region_container.RegionContainer;
import static bacmman.data_structure.region_container.RegionContainer.MAX_VOX_3D;
import static bacmman.data_structure.region_container.RegionContainer.MAX_VOX_2D;
import bacmman.data_structure.region_container.RegionContainerBlankMask;
import bacmman.data_structure.region_container.RegionContainerIjRoi;
import bacmman.data_structure.region_container.RegionContainerVoxels;
import bacmman.image.BlankMask;
import bacmman.image.BoundingBox;
import bacmman.image.BoundingBox.LoopFunction;

import static bacmman.image.BoundingBox.getIntersection2D;
import static bacmman.image.BoundingBox.intersect2D;
import bacmman.image.MutableBoundingBox;
import bacmman.image.Image;
import bacmman.image.ImageByte;
import bacmman.image.ImageInteger;
import bacmman.processing.ImageLabeller;
import bacmman.image.ImageMask;
import bacmman.image.ImageMask2D;
import bacmman.image.ImageProperties;
import bacmman.image.Offset;
import bacmman.image.SimpleBoundingBox;
import bacmman.image.SimpleImageProperties;
import bacmman.image.SimpleOffset;
import bacmman.image.TypeConverter;

import java.util.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import bacmman.processing.Filters;
import bacmman.processing.RegionFactory;
import bacmman.processing.neighborhood.EllipsoidalNeighborhood;
import bacmman.processing.neighborhood.Neighborhood;
import bacmman.utils.Utils;
import static bacmman.utils.Utils.comparator;

import bacmman.utils.geom.Point;
import bacmman.utils.geom.Vector;

import java.util.function.DoublePredicate;
import java.util.function.Predicate;
import java.util.function.ToDoubleBiFunction;
import java.util.stream.Collectors;
import java.util.stream.DoubleStream;
import java.util.stream.Stream;

/**
 * 
 * @author Jean Ollion
 * 
 */
public class Region {
    public final static Logger logger = LoggerFactory.getLogger(Region.class);
    protected ImageMask mask; //lazy -> use getter // bounds par rapport au root si absoluteLandMark==true, au parent sinon
    protected BoundingBox bounds;
    protected int label;
    protected Set<Voxel> voxels; //lazy -> use getter // coordonnées des voxel = coord dans l'image mask + offset du masque.  
    protected double scaleXY=1, scaleZ=1;
    protected boolean absoluteLandmark=false; // false = coordinates relative to the direct parent
    protected double quality=Double.NaN;
    protected double categoryProbability = Double.NaN;
    protected int category = -1;
    protected Point center;
    protected boolean is2D;
    boolean regionModified;
    IJRoi3D roi; // TODO make an interface to allow other types of ROIs
    /**
     * @param mask : image containing only the object, and whose bounding box is the same as the one of the object
     * @param label
     * @param is2D
     */
    public Region(ImageMask mask, int label, boolean is2D) {
        this.mask=mask;
        this.bounds=new SimpleBoundingBox(mask);
        this.label=label;
        this.scaleXY=mask.getScaleXY();
        this.scaleZ=mask.getScaleZ();
        this.is2D=is2D;
    }
    
    public Region(Set<Voxel> voxels, int label, boolean is2D, double scaleXY, double scaleZ) {
        this.voxels = voxels;
        this.label=label;
        this.scaleXY=scaleXY;
        this.scaleZ=scaleZ;
        this.is2D=is2D;
    }
    public Region(final Voxel voxel, int label, boolean is2D, double scaleXY, double scaleZ) {
        this(new HashSet<Voxel>(){{add(voxel);}}, label, is2D, scaleXY, scaleZ);
    }
    
    public Region(Set<Voxel> voxels, int label, BoundingBox bounds, boolean is2D, double scaleXY, double scaleZ) {
        this(voxels, label, is2D, scaleXY, scaleZ);
        this.bounds=bounds;
    }
    public Region(IJRoi3D roi, int label, BoundingBox bounds, double scaleXY, double scaleZ) {
        this.scaleXY=scaleXY;
        this.scaleZ=scaleZ;
        this.label = label;
        this.is2D=roi.is2D();
        this.roi = roi;
        this.bounds = bounds;
    }
    
    public Region setIsAbsoluteLandmark(boolean absoluteLandmark) {
        this.absoluteLandmark = absoluteLandmark;
        return this;
    }
    public IJRoi3D getRoi() {
        return roi;
    }
    public boolean hasModifications() {
        return regionModified;
    }
    public boolean isAbsoluteLandMark() {
        return absoluteLandmark;
    }
    public boolean is2D() {
        return is2D;
    }
    public Region setIs2D(boolean is2D) {
        if (this.is2D!=is2D) {
            regionModified=true;
            this.is2D=is2D;
        }
        return this;
    }
    public Region setBounds(BoundingBox bounds) {
        if (bounds.sameBounds(getBounds())) return this;
        if (roi==null && mask!=null) createRoi();
        mask = null; // reset mask
        this.bounds = new SimpleBoundingBox(bounds);
        return this;
    }

    public Region setQuality(double quality) {
        this.quality=quality;
        regionModified=true;
        return this;
    }

    public double getQuality() {
        return quality;
    }

    public Region setCategory(int categoryIdx, double probability) {
        this.category=categoryIdx;
        this.categoryProbability = probability;
        regionModified=true;
        return this;
    }

    public double getCategoryProbability() {
        return categoryProbability;
    }
    public int getCategory() {
        return category;
    }

    /**
     *
     * @param z plane to intersect with. Should be in the same landmark as the region
     * @param return2D if true returns a 2D object, otherwise a 3D object located at slice Z
     * @return a new object that correspond to the intersection of this region with the plane, or null if intersection is empty.
     */
    public Region intersectWithZPlane(int z, boolean return2D, boolean forceDuplicate) {
        if (is2D) {
            if (return2D) return forceDuplicate ? duplicate() : this;
            else {
                Point newCenter = center == null ? null : new Point(center.get(0), center.get(1), z);
                if (roi != null) {
                    IJRoi3D newRoi = this.roi.duplicate().setIs2D(is2D).translate(new SimpleOffset(0, 0, z));
                    return new Region(newRoi, label, new MutableBoundingBox(bounds).setzMin(z).setzMax(z), scaleXY, scaleZ).setIsAbsoluteLandmark(absoluteLandmark).setQuality(quality).setCenter(newCenter);
                } else if (voxels!=null) {
                    Set<Voxel> vox = voxels.stream().map(v -> new Voxel(v.x, v.y, z)).collect(Collectors.toSet());
                    return new Region(vox, label, false, scaleXY, scaleZ).setIsAbsoluteLandmark(absoluteLandmark);
                } else {
                    ImageInteger<?> newMask;
                    if (mask instanceof ImageInteger) newMask = ((ImageInteger<?>) mask).duplicate();
                    else newMask = getMaskAsImageInteger();
                    if (newMask.sizeZ()>1) throw new RuntimeException("2D object with 3D mask");
                    newMask.translate(0, 0, z - newMask.zMin());
                    return new Region(newMask, label, false).setIsAbsoluteLandmark(absoluteLandmark);
                }
            }
        } else { // 3D
            BoundingBox bds = getBounds();
            if (z < bds.zMin() || z > bds.zMax()) return null;
            else if (z == bds.zMin() && z == bds.zMax() && !return2D) return forceDuplicate ? duplicate() : this; // already 1-slice object
            Point newCenter = center == null ? null : (return2D ? new Point(center.get(0), center.get(1)) : new Point(center.get(0), center.get(1), z) );
            if (roi != null) {
                IJRoi3D newROI = roi.duplicateZ(z);
                if (!return2D) newROI.setIs2D(false).translate(new SimpleOffset(0, 0, z));
                Region r = new Region(newROI, label, new MutableBoundingBox(bounds).setzMin(0).setzMax(0), scaleXY, scaleZ).setIsAbsoluteLandmark(absoluteLandmark).setQuality(quality).setCenter(newCenter);
                r.clearRoi(); // creates mask
                if (r.size()==0) return null;
                r.resetMask(); // so that bounding box corresponds to mask
                r.clearMask(); // creates ROI
                return r;
            } else if (mask != null) {
                ImageInteger<?> newMask;
                if (mask instanceof ImageInteger) {
                    List<ImageInteger<?>> planes = ((ImageInteger)mask).splitZPlanes(z, z);
                    if (planes.isEmpty()) {
                        logger.error("bounds and mask not consistent. bds:{} mask bounds: {}", getBounds(), ((ImageInteger<?>) mask).getBounds(false));
                    }
                    newMask = planes.get(0).duplicate();
                } else {
                    newMask = TypeConverter.toByteMaskZ(mask, null, 1, z);
                }
                if (return2D) newMask.translate(0, 0, -z);
                Region r = new Region(newMask, label, return2D).setIsAbsoluteLandmark(absoluteLandmark).setQuality(quality).setCenter(newCenter);
                if (r.size() == 0) return null;
                r.resetMask(); // so that bounding box corresponds to mask
                return r;
            } else {
                int newZ = return2D ? 0 : z;
                Set<Voxel> vox = getVoxels().stream().filter(v -> v.z == z).map(v -> new Voxel(v.x, v.y, newZ)).collect(Collectors.toSet());
                if (vox.isEmpty()) return null;
                return new Region(vox, label, return2D, scaleXY, scaleZ).setIsAbsoluteLandmark(absoluteLandmark).setQuality(quality).setCenter(newCenter);
            }
        }
    }

    public Region duplicate(boolean duplicateVoxels) {
        if (this.roi!=null) {
            Region res = new Region(roi.duplicate(), label, new SimpleBoundingBox(bounds), scaleXY, scaleZ).setIsAbsoluteLandmark(absoluteLandmark).setQuality(quality).setCenter(center==null ? null : center.duplicate());
            if (!duplicateVoxels && voxelsCreated()) res.voxels = new HashSet<>(voxels);
            return res;
        } else if (this.mask!=null) {
            Region res = new Region(getMask().duplicateMask(), label, is2D).setIsAbsoluteLandmark(absoluteLandmark).setQuality(quality).setCenter(center==null ? null : center.duplicate());
            if (!duplicateVoxels && voxelsCreated()) res.voxels = new HashSet<>(voxels);
            return res;
        } else if (this.voxels!=null) {
            Set<Voxel> vox;
            if (duplicateVoxels) {
                vox = new HashSet<>(voxels.size());
                for (Voxel v : voxels) vox.add(v.duplicate());
            } else vox = new HashSet<>(voxels);
            if (bounds==null) return new Region(vox, label, is2D, scaleXY, scaleZ).setIsAbsoluteLandmark(absoluteLandmark).setQuality(quality).setCenter(center==null ? null: center.duplicate());
            else return new Region(vox, label, new SimpleBoundingBox(bounds), is2D, scaleXY, scaleZ).setIsAbsoluteLandmark(absoluteLandmark).setQuality(quality).setCenter(center==null ? null: center.duplicate());
        }
        return null;
    }

    public Region duplicate() {
        return duplicate(true);
    }

    public int getLabel() {
        return label;
    }

    public double getScaleXY() {
        return scaleXY;
    }

    public double getScaleZ() {
        return scaleZ;
    }
    
    public double size() {
        if (this.voxelsCreated()) return voxels.size();
        else return getMask().count();
    }

    public Region setCenter(Point center) {
        this.center=center;
        regionModified=true;
        return this;
    }
    public double[] getSecondCentralMoments2D(Point center) {
        center = center==null ? (this.center==null ? getGeomCenter(false) : this.center) : center;
        double cx = center.get(0);
        double cy = center.get(1);
        double[] buffer = new double[4];
        LoopFunction fun  = (x, y, z) -> {
            double xx = x - cx;
            double yy = y - cy;
            buffer[0] += xx*xx;
            buffer[1] += yy*yy;
            buffer[2] += xx*yy;
            ++buffer[3];
        };
        if (voxelsCreated()) {
            voxels.forEach(v -> fun.loop(v.x, v.y, v.z));
        } else {
            ImageMask.loopWithOffset(getMask(), fun);
        }
        return new double[]{buffer[0] / buffer[3], buffer[1] / buffer[3], buffer[2] / buffer[3]};
    }

    public Point getCenter() {
        if (center!=null) return center.duplicate();
        else return null;
    }

    public Point getCenterOrGeomCenter() {
        Point center = getCenter();
        if (center!=null) return center;
        return getGeomCenter(false);
    }

    public static Point getGeomCenter(boolean scaled, Region... regions) {
        double[] center = new double[3];
        double[] size = new double[1];
        for (Region r : regions) {
            if (r.mask instanceof BlankMask) {
                int s = r.mask.sizeXYZ();
                size[0]+=s;
                center[0] += (r.mask).xMean() * s;
                center[1] += (r.mask).yMean() * s;
                center[2] += (r.mask).zMean() * s;
            } else if (r instanceof Analytical) {
                double s = r.size();
                size[0] += s;
                Point c = r.getCenter();
                center[0] += c.get(0) * s;
                center[1] += c.get(1) * s;
                center[2] += c.getWithDimCheck(2) * s;
            } else {
                LoopFunction fun = (x, y, z) -> {
                    center[0] += x;
                    center[1] += y;
                    center[2] += z;
                    ++size[0];
                };
                r.loop(fun);
            }
        }
        center[0] /= size[0];
        center[1] /= size[0];
        center[2] /= size[0];
        if (scaled) {
            center[0] *=regions[0].getScaleXY();
            center[1] *=regions[0].getScaleXY();
            center[2] *=regions[0].getScaleZ();
        }
        return new Point(center);
    }

    public Point getGeomCenter(boolean scaled) {
        return getGeomCenter(scaled, this);
    }

    public Point getMassCenter(Image image, boolean scaled) {
        return getMassCenter(image, scaled, null);
    }

    public Point getMassCenter(Image image, boolean scaled, DoublePredicate useValue) {
        double[] center = new double[3];
        double[] count = new double[1];
        DoublePredicate f = useValue==null? v->true:useValue;
        double minValue = BasicMeasurements.getMinValue(this, image);
        LoopFunction fun = (x, y, z)-> {
            double value = absoluteLandmark ? image.getPixelWithOffset(x, y, z) : image.getPixel(x, y, z);
            if (f.test(value)) {
                value -= minValue;
                center[0] += x * value;
                center[1] += y * value;
                center[2] += z * value;
                count[0] += value;
            }
        };
        loop(fun, image);
        if (count[0]==0) return getGeomCenter(scaled); // all values == minimal value
        center[0]/=count[0];
        center[1]/=count[0];
        center[2]/=count[0];
        if (scaled) {
            center[0] *=this.getScaleXY();
            center[1] *=this.getScaleXY();
            center[2] *=this.getScaleZ();
        }
        return new Point(center);
    }
    
    public synchronized void addVoxels(Collection<Voxel> voxelsToAdd) {
        boolean withinMaskTested = false;
        if (voxels == null && mask == null) {
            boolean within = true;
            ImageMask mask_ = new BlankMask(new SimpleImageProperties(getBounds(), scaleXY, scaleZ));
            for (Voxel v : voxelsToAdd) {if (!mask_.containsWithOffset(v.x, v.y, v.z)); within=false; break;}
            if (within) {
                getMask();
                withinMaskTested = true;
            } else getVoxels();
        }
        if (voxels!=null) voxels.addAll(voxelsToAdd);
        if (mask!=null) {
            // check if all voxels are within mask
            boolean within = true;
            if (!withinMaskTested) {
                for (Voxel v : voxelsToAdd) {if (!mask.containsWithOffset(v.x, v.y, v.z)); within=false; break;}
            }
            if (!within) {
                if (voxels==null) getVoxels().addAll(voxelsToAdd);
                mask = null;
            } else {
                ensureMaskIsImageInteger();
                ImageInteger mask = getMaskAsImageInteger();
                for (Voxel v : voxelsToAdd) mask.setPixelWithOffset(v.x, v.y, v.z, 1);
            }
        }
        this.roi=null;
        this.bounds=null;
        this.center = null;
        regionModified=true;
    }

    public synchronized void add(Region r) {
        if (r.voxels!=null) addVoxels(r.voxels);
        else add(r.getMask());
        regionModified=true;
    }

    public synchronized void remove(Region r) {
        if (this.mask!=null && r.mask!=null) andNot(r.mask);
        else if (this.voxels!=null && r.voxels!=null) removeVoxels(r.voxels);
        else andNot(r.getMask());
        regionModified=true;
    }

    public synchronized void and(Region r) {
        if (r.voxels!=null) retainVoxels(r.voxels);
        else and(r.getMask());
        regionModified=true;
    }

    public synchronized void removeVoxels(Collection<Voxel> voxelsToRemove) {
        if (voxels == null && mask == null) getMask();
        if (voxels!=null) voxels.removeAll(voxelsToRemove);
        if (mask!=null) {
            ensureMaskIsImageInteger();
            ImageInteger mask = getMaskAsImageInteger();
            for (Voxel v : voxelsToRemove) mask.setPixelWithOffset(v.x, v.y, v.z, 0);
        }
        this.roi=null;
        this.bounds=null;
        this.center = null;
        regionModified=true;
    }

    public synchronized void retainVoxels(Collection<Voxel> voxelsToRetain) {
        if (voxels == null && mask == null) getVoxels();
        if (voxels!=null) this.voxels.retainAll(voxelsToRetain);
        else if (mask != null) {
            Set<Voxel> newVoxels = new HashSet<>();
            for (Voxel v : voxelsToRetain) {
                if (mask.containsWithOffset(v.x, v.y, v.z) && mask.insideMaskWithOffset(v.x, v.y, v.z)) newVoxels.add(v.duplicate());
            }
            this.voxels = newVoxels;
        }
        this.roi=null;
        mask = null;
        this.center = null;
        this.bounds=null;
        regionModified=true;
    }

    public synchronized void andNot(ImageMask otherMask) {
        ensureMaskIsImageInteger();
        ImageInteger mask = getMaskAsImageInteger();
        LoopFunction function = (x, y, z)-> {
            if (otherMask.insideMaskWithOffset(x, y, z)) {
                mask.setPixelWithOffset(x, y, z, 0);
                if (voxels!=null) voxels.remove(new Voxel(x, y, z));
            }
        };
        BoundingBox.loop(BoundingBox.getIntersection(otherMask, getBounds()), function);
        this.roi=null;
        this.center = null;
        resetMask();
        regionModified=true;
    }

    public synchronized void and(ImageMask otherMask) {
        ensureMaskIsImageInteger();
        ImageInteger mask = getMaskAsImageInteger();
        LoopFunction function = (x, y, z)-> {
            if (!otherMask.containsWithOffset(x, y, z) || !otherMask.insideMaskWithOffset(x, y, z)) {
                mask.setPixelWithOffset(x, y, z, 0);
                if (voxels!=null) voxels.remove(new Voxel(x, y, z));
            }
        };
        ImageMask.loopWithOffset(mask, function);
        this.roi=null;
        this.center = null;
        resetMask();
        regionModified=true;
    }

    public synchronized void add(ImageMask otherMask) {
        getMask();
        ensureMaskIsImageInteger();
        ImageInteger mask = getMaskAsImageInteger();
        BoundingBox newBounds = new MutableBoundingBox(this.getBounds()).union(otherMask);
        ImageInteger newMask = new ImageByte("union", new SimpleImageProperties(newBounds, getScaleXY(), getScaleZ()));
        LoopFunction function = (x, y, z)-> {
            if (mask.containsWithOffset(x, y, z) && mask.insideMaskWithOffset(x, y, z)) newMask.setPixelWithOffset(x, y, z, 1);
            else if (otherMask.containsWithOffset(x, y, z) && otherMask.insideMaskWithOffset(x, y, z)) {
                newMask.setPixelWithOffset(x, y, z, 1);
                if (voxels!=null) voxels.add(new Voxel(x, y, z));
            }
        };
        BoundingBox.loop(newBounds, function);
        this.mask = newMask;
        this.bounds=newBounds;
        roi = null;
        this.center = null;
        regionModified=true;
    }

    public boolean contains(Voxel v) {
        if (voxels!=null) return voxels.contains(v);
        else return getMask().containsWithOffset(v.x, v.y, v.z) && mask.insideMaskWithOffset(v.x, v.y, v.z);
    }

    public boolean contains(Point p) {
        return contains(p.asVoxel());
    }

    public synchronized void clearVoxels() {
        if (roi == null && mask==null) createMask();
        voxels = null;
    }

    public synchronized void clearMask() {
        if (mask!=null) createBoundsFromMask();
        if (voxels==null && roi == null) createRoi();
        mask = null;
        if (roi==null) this.bounds=null;
    }

    public synchronized void clearRoi() {
        if (voxels==null && mask == null) createMask();
        roi = null;
    }

    public synchronized void createRoi() {
        roi = bacmman.data_structure.region_container.RegionContainerIjRoi.createRoi(getMask(), getBounds(), !is2D());
    }

    public synchronized void resetMask() {
        if (mask!=null) { // do it from mask
            if (mask instanceof BlankMask) return;
            Region other = RegionFactory.getObjectImage(mask); // landmark = mask
            if (other!=null) {
                this.mask = other.mask;
                this.bounds = other.getBounds();
            }
        } else if (voxels!=null) { // mask will be created from voxels
            mask = null;
            bounds = null;
        } else if (roi!=null) {
            mask = roi.toMask(bounds, scaleXY, scaleZ);
        }
    }

    protected void createMask() {
        if (!this.getBounds().isValid()) throw new RuntimeException("Invalid bounds: cannot create mask");
        if (voxels!=null) {
            ImageByte mask_ = new ImageByte("", new SimpleImageProperties(getBounds(), scaleXY, scaleZ));
            for (Voxel v : voxels) {
                if (!mask_.containsWithOffset(v.x, v.y, v.z)) {
                    logger.error("voxel out of bounds: {}, bounds: {}, vox{}", v, mask_.getBoundingBox(), voxels); // can happen if bounds were not updated before the object was saved
                    this.createBoundsFromVoxels();
                    logger.error("bounds after re-create: {}", getBounds());
                }
                mask_.setPixelWithOffset(v.x, v.y, v.z, 1);
            }
            this.mask=mask_;
        } else if (roi!=null) {
            this.mask = roi.toMask(getBounds(), scaleXY, scaleZ);
        } else throw new RuntimeException("Cannot create mask: no voxels and no ROI");

    }

    protected void createVoxels() {
        if (mask==null) getMask(); // in case roi is not null
        //logger.debug("create voxels: mask offset: {}", mask.getBoundingBox());
        HashSet<Voxel> voxels_=new HashSet<>();
        ImageMask.loopWithOffset(mask, (x, y, z)->voxels_.add(new Voxel(x, y, z)));
        voxels=voxels_;
    }
    
    public ImageProperties getImageProperties() {
        if (mask!=null) return new SimpleImageProperties(mask, scaleXY, scaleZ);
        return new SimpleImageProperties(getBounds(), scaleXY, scaleZ);
    }

    /**
     * 
     * @return an image containing only the object: its bounds are the one of the object and pixel values >0 where the objects has a voxel. The offset of the image is this offset of the object.
     */
    public ImageMask<? extends ImageMask> getMask() {
        if (mask==null) {
            synchronized(this) {
                if (mask==null) {
                    createMask();
                }
            }
        }
        return mask;
    }

    public ImageInteger<? extends ImageInteger> getMaskAsImageInteger() {
        return TypeConverter.maskToImageInteger(getMask(), null);
    }

    public void ensureMaskIsImageInteger() {
        if (!(getMask() instanceof ImageInteger)) {
            synchronized(this) {
                if (!(getMask() instanceof ImageInteger)) mask = getMaskAsImageInteger();
            }
        }
    }
    /**
     * 
     * @param properties 
     * @return a mask image of the object, with same dimensions as {@param properties}, and the object located within the image according to its offset
     */
    public ImageByte getMask(ImageProperties properties) {
        ImageByte res = new ImageByte("mask", properties);
        draw(res, label);
        return res;
    }
    
    public Set<Voxel> getVoxels() {
        if (voxels==null) {
            synchronized(this) { // "Double-Checked Locking"
                if (voxels==null) {
                    createVoxels();
                }
            }
        }
        return voxels;
    }

    public CoordCollection getCoords() {
        getBounds();
        CoordCollection coll = CoordCollection.create(bounds.sizeX(), bounds.sizeY(), bounds.sizeZ());
        loop((x, y, z) -> coll.add(coll.toCoord(x, y, z)));
        return coll;
    }

    public void loop(LoopFunction fun, BoundingBox area) {
        if (area == null) {
            loop(fun);
            return;
        }
        if (voxelsCreated()) {
            if (absoluteLandmark) {
                for (Voxel v : voxels) {
                    if (area.containsWithOffset(v.x, v.y, v.z)) fun.loop(v.x, v.y, v.z);
                }
            } else {
                for (Voxel v : voxels) {
                    if (area.contains(v.x, v.y, v.z)) fun.loop(v.x, v.y, v.z);
                }
            }
        } else {
            ImageMask mask = getMask();
            BoundingBox inter = BoundingBox.getIntersection(mask, absoluteLandmark ? area : new SimpleBoundingBox(area).resetOffset());
            BoundingBox.loop(inter, fun, mask::insideMaskWithOffset);
        }
    }

    public void loop(LoopFunction fun) {
        if (voxelsCreated()) {
            for (Voxel v : voxels) fun.loop(v.x, v.y, v.z);
        } else ImageMask.loopWithOffset(getMask(), fun);
    }

    /**
     * loop over the region area and apply @param fun to each coordinate
     * @param fun function called with coordinates x, y, z
     * @param off additional offset added to coordinates (x, y, z) before calling fun
     */
    public void loop(LoopFunction fun, Offset off) {
        if (off==null || Offset.offsetNull(off)) {
            loop(fun);
        } else {
            if (voxelsCreated()) {
                for (Voxel v : voxels) fun.loop(v.x+off.xMin(), v.y+ off.yMin(), v.z+off.zMin());
            } else ImageMask.loopWithOffset(getMask(), fun, off);
        }
    }

    public void loopContours(LoopFunction fun) {
        EllipsoidalNeighborhood neigh = !is2D() ? new EllipsoidalNeighborhood(1, 1, true) : new EllipsoidalNeighborhood(1, true); // 1 and not 1.5 -> diagonal
        if (voxels!=null) {
            for (Voxel v: voxels) if (touchBorderVox(v, neigh)) fun.loop(v.x, v.y, v.z);
        } else {
            getMask();
            ImageMask.loop(mask, (x, y, z)->{
                if (touchBorder(x, y, z, neigh, mask)) fun.loop(x+mask.xMin(), y+mask.yMin(), z+mask.zMin());
            });
        }
    }

    /**
     * loop over the region contour and apply @param fun to each coordinate
     * @param fun function called with coordinates x, y, z
     * @param off additional offset added to coordinates (x, y, z) before calling fun
     */
    public void loopContours(LoopFunction fun, Offset off) {
        if (off==null || Offset.offsetNull(off)) {
            loopContours(fun);
        } else {
            EllipsoidalNeighborhood neigh = !is2D() ? new EllipsoidalNeighborhood(1, 1, true) : new EllipsoidalNeighborhood(1, true); // 1 and not 1.5 -> diagonal
            if (voxelsCreated()) {
                for (Voxel v: voxels) if (touchBorderVox(v, neigh)) fun.loop(v.x+off.xMin(), v.y+ off.yMin(), v.z+off.zMin());
            } else {
                ImageMask.loop(mask, (x, y, z)->{
                    if (touchBorder(x, y, z, neigh, mask)) fun.loop(x+mask.xMin()+off.xMin(), y+mask.yMin()+ off.yMin(), z+mask.zMin()+off.zMin());
                });
            }
        }
    }



    public DoubleStream streamValues(Image image, boolean checkInsideImage) {
        if (voxelsCreated()) {
            Stream<Voxel> stream = voxels.stream();
            if (isAbsoluteLandMark()) {
                if (checkInsideImage) stream = stream.filter(v -> image.containsWithOffset(v.x, v.y, v.z));
                return stream.mapToDouble(v->image.getPixelWithOffset(v.x, v.y, v.z));
            }
            else {
                if (checkInsideImage) stream = stream.filter(v -> image.contains(v.x, v.y, v.z));
                return stream.mapToDouble(v->image.getPixel(v.x, v.y, v.z));
            }
        } else return image.stream(getMask(), isAbsoluteLandMark());
    }

    public DoubleStream streamValues(Image image) {
        return streamValues(image, false);
    }

    public boolean voxelsCreated() {
        return voxels!=null;
    }
    /**
     * 
     * @return subset of object's voxels that are in contact with background, edge or other object
     */
    public Set<Voxel> getContour() {
        EllipsoidalNeighborhood neigh = !is2D() ? new EllipsoidalNeighborhood(1, 1, true) : new EllipsoidalNeighborhood(1, true); // 1 and not 1.5 -> diagonal
        Set<Voxel> res = new HashSet<>();
        if (voxels!=null) {
            for (Voxel v: voxels) if (touchBorderVox(v, neigh)) res.add(v);
        } else if ( false && roi!=null && is2D()) { // contour do not correspond: it is shifted 1 pixel towards high y & x values.
            return roi.getContour(getBounds());
        } else {
            getMask();
            ImageMask.loop(mask, (x, y, z)->{ if (touchBorder(x, y, z, neigh, mask)) res.add(new Voxel(x+mask.xMin(), y+mask.yMin(), z+mask.zMin()));});
        }
        return res;
    }

    public boolean contact(Region other) {
        if (is2D || other.is2D) {
            if (!BoundingBox.intersect2D(getBounds(), other.getBounds(), 1)) return false;
        } else {
            if (!BoundingBox.intersect(getBounds(), other.getBounds(), 1)) return false;
        }
        Set<Voxel> otherContours=other.getContour();
        for (Voxel v : getContour()) {
            for (Voxel w : otherContours) {
                if (Math.abs(v.x - w.x )<=1 && Math.abs(v.y - w.y )<=1 && Math.abs(v.z - w.z )<=1) return true;
            }
        }
        return false;
    }

    /**
     * 
     * @param x
     * @param z
     * @param y
     * @param neigh with offset
     * @param mask
     * @return 
     */
    private static boolean touchBorder(int x, int y, int z, EllipsoidalNeighborhood neigh, ImageMask mask) {
        int xx, yy, zz;
        for (int i = 0; i<neigh.dx.length; ++i) {
            xx=x+neigh.dx[i];
            yy=y+neigh.dy[i];
            zz=z+neigh.dz[i];
            if (!mask.contains(xx, yy, zz) || !mask.insideMask(xx, yy, zz)) return true;
        }
        return false;
    }
    private boolean touchBorderVox(Voxel v, EllipsoidalNeighborhood neigh) {
        Voxel next = new Voxel(v.x, v.y, v.z);
        for (int i = 0; i<neigh.dx.length; ++i) {
            next.x=v.x+neigh.dx[i];
            next.y=v.y+neigh.dy[i];
            next.z=v.z+neigh.dz[i];
            if (!getBounds().containsWithOffset(next.x, next.y, next.z)) return true;
            if (!voxels.contains(next)) return true;
        }
        return false;
    }
    public void erode(Neighborhood neigh) {
        mask = Filters.min(getMaskAsImageInteger(), null, neigh, false);
        voxels = null; // reset voxels
        // TODO reset bounds?
    }
    /**
     * 
     * @param image
     * @param threshold
     * @param removeIfLowerThanThreshold
     * @param keepOnlyBiggestObject
     * @param contour will be modified if a set
     * @return if any change was made
     */
    public boolean erodeContours(Image image, double threshold, boolean removeIfLowerThanThreshold, boolean keepOnlyBiggestObject, Collection<Voxel> contour, Predicate<Voxel> stopPropagation) {
        boolean changes = false;
        TreeSet<Voxel> heap = new TreeSet<>(Voxel.getComparator());
        heap.addAll( contour==null ? getContour() : contour);
        EllipsoidalNeighborhood neigh = !this.is2D() ? new EllipsoidalNeighborhood(1, 1, true) : new EllipsoidalNeighborhood(1, true);
        ensureMaskIsImageInteger();
        ImageInteger mask = getMaskAsImageInteger();
        Voxel n = new Voxel(0, 0, 0);
        while(!heap.isEmpty()) {
            Voxel v = heap.pollFirst();
            if (removeIfLowerThanThreshold ? image.getPixel(v.x, v.y, v.z)<=threshold : image.getPixel(v.x, v.y, v.z)>=threshold) {
                changes = true;
                mask.setPixel(v.x-mask.xMin(), v.y-mask.yMin(), v.z-mask.zMin(), 0);
                for (int i = 0; i<neigh.dx.length; ++i) {
                    n.x=v.x+neigh.dx[i];
                    n.y=v.y+neigh.dy[i];
                    n.z=v.z+neigh.dz[i];
                    if (mask.contains(n.x-mask.xMin(), n.y-mask.yMin(), n.z-mask.zMin()) && mask.insideMask(n.x-mask.xMin(), n.y-mask.yMin(), n.z-mask.zMin()) && (stopPropagation==null || !stopPropagation.test(n))) {
                        heap.add(n.duplicate());
                    }
                }
            }
        }
        if (changes) {
            List<Region> objects = ImageLabeller.labelImageListLowConnectivity(mask);
            if (objects.size() > 1) {
                if (keepOnlyBiggestObject) { // check if 2 objects and erase all but smallest
                    objects.remove(Collections.max(objects, Comparator.comparingDouble(o -> o.size())));
                } else { // erase single pixels
                    objects.removeIf(r->r.size()>1);
                }
                for (Region toErase : objects) toErase.draw(mask, 0);
            }
            voxels = null; // reset voxels
            this.roi=null;
            regionModified=true;
        }
        return changes;
    }
    protected boolean erodeContoursEdge(Image edgeMap, Image intensityMap, boolean keepOnlyBiggestObject) {
        boolean changes = false;
        TreeSet<Voxel> heap = new TreeSet<>(Voxel.getComparator());
        Image distanceMap = EDT.transform(getMask(), true, scaleXY, scaleZ, false);
        Set<Voxel> contour = getContour();
        contour.forEach(v -> v.value = distanceMap.getPixelWithOffset(v.x, v.y, v.z));
        heap.addAll(contour);
        EllipsoidalNeighborhood neigh = !this.is2D() ? new EllipsoidalNeighborhood(1.5, 1.5, true) : new EllipsoidalNeighborhood(1.5, true);
        ensureMaskIsImageInteger();
        ImageInteger mask = getMaskAsImageInteger();
        while(!heap.isEmpty()) {
            Voxel v = heap.pollFirst();
            // get distance of nearest voxel that is further away from center
            double dist = neigh.stream(v, getMask(), true).mapToDouble(n->distanceMap.getPixelWithOffset(n.x, n.y, n.z)).filter(d->d>v.value).min().orElse(Double.NaN);
            if (Double.isNaN(dist)) continue;
            double curValue = intensityMap.getPixel(v.x, v.y, v.z);
            double curEdge = edgeMap.getPixel(v.x, v.y, v.z);
            List<Voxel> nexts =  neigh.stream(v, getMask(), true).filter(n->distanceMap.getPixelWithOffset(n.x, n.y, n.z)==dist).filter(n->edgeMap.getPixel(n.x, n.y, n.z)>curEdge && intensityMap.getPixel(n.x, n.y, n.z)>curValue).collect(Collectors.toList());
            if (nexts.isEmpty()) continue;
            changes = true;
            mask.setPixelWithOffset(v.x, v.y, v.z, 0);
            nexts.forEach(next -> {
                contour.remove(v);
                next.value = (float)dist;
                contour.add(next);
                heap.add(next);
            });
        }
        if (changes) {
            List<Region> objects = ImageLabeller.labelImageListLowConnectivity(mask);
            if (objects.size() > 1) {
                if (keepOnlyBiggestObject) { // check if 2 objects and erase all but smallest
                    objects.remove(Collections.max(objects, Comparator.comparingDouble(o -> o.size())));
                } else { // erase single pixels
                    objects.removeIf(r->r.size()>1);
                }
                for (Region toErase : objects) toErase.draw(mask, 0);
            }
            voxels = null; // reset voxels
            this.roi=null;
            regionModified=true;
        }
        return changes;
    }

    public void dilateContours(Image image, double threshold, boolean addIfHigherThanThreshold, Collection<Voxel> contour, ImageInteger labelMap) {
        //if (labelMap==null) labelMap = Collections.EMPTY_SET;
        TreeSet<Voxel> heap = new TreeSet<>(Voxel.getComparator());
        heap.addAll( contour==null ? getContour() : contour);
        heap.removeIf(v->{
            int l = labelMap.getPixelInt(v.x, v.y, v.z);
            return l>0 && l!=label;
        });
        //heap.removeAll(labelMap);
        EllipsoidalNeighborhood neigh = !this.is2D() ? new EllipsoidalNeighborhood(1.5, 1.5, true) : new EllipsoidalNeighborhood(1.5, true);
        //logger.debug("start heap: {},  voxels : {}", heap.size(), voxels.size());
        while(!heap.isEmpty()) {
            Voxel v = heap.pollFirst();
            if (addIfHigherThanThreshold ? image.getPixel(v.x, v.y, v.z)>=threshold : image.getPixel(v.x, v.y, v.z)<=threshold) {
                voxels.add(v);
                for (int i = 0; i<neigh.dx.length; ++i) {
                    Voxel next = new Voxel(v.x+neigh.dx[i], v.y+neigh.dy[i], v.z+neigh.dz[i]);
                    if (image.contains(next.x, next.y, next.z) && !voxels.contains(next)) {
                        int l = labelMap.getPixelInt(next.x, next.y, next.z);
                        if (l==0 || l == label) heap.add(next);
                    }
                }
            }
        }
        bounds = null; // reset bounds
        this.mask=null; // reset voxels
        this.roi=null;
        regionModified=true;
    }

    protected void createBoundsFromMask() {
        MutableBoundingBox bounds_  = new MutableBoundingBox();
        ImageMask.loopWithOffset(mask, bounds_::union);
        bounds= bounds_;
    }

    protected void createBoundsFromVoxels() {
        MutableBoundingBox bounds_  = new MutableBoundingBox();
        for (Voxel v : voxels) bounds_.union(v);
        bounds= bounds_;
    }

    protected boolean maskHasVoidEdges() {
        boolean left=false, right=false, up=false, down=false, top=false, bottom=false;
        for (int z = 0; z<mask.sizeZ(); ++z) {
            for (int x = 0; x<mask.sizeX(); ++x) {
                if (mask.insideMask(x, 0, z)) up=true;
                if (mask.insideMask(x, mask.sizeY()-1, z)) down=true;
            }
            for (int y = 0; y<mask.sizeY(); ++y) {
                if (mask.insideMask(0, y, z)) left=true;
                if (mask.insideMask(mask.sizeX()-1, y, z)) right=true;
            }
        }
        if (!left || !right || !up || !down) return false; // at least one edge is void
        if (mask.sizeZ()>1) {
            for (int y = 0; y<mask.sizeY(); ++y) {
                for (int x = 0; x<mask.sizeX(); ++x) {
                    if (mask.insideMask(x, y, 0)) bottom=true;
                    if (mask.insideMask(x, y, mask.sizeZ()-1)) top=true;
                }
            }
        }
        if (!top || !bottom) return false;
        return true;
    }

    public <T extends BoundingBox<T>> BoundingBox<T> getBounds() {
        if (bounds==null) {
            synchronized(this) { // "Double-Checked Locking"
                if (bounds==null) {
                    if (mask!=null) {
                        bounds=new SimpleBoundingBox(mask); // needs to be mask bb, even if mask has black borders
                    } else if (voxels!=null) createBoundsFromVoxels();
                }
            }
        }
        return bounds;
    }
    
    public void setMask(ImageMask mask) {
        synchronized(this) {
            this.mask= mask;
            this.bounds=null;
            this.voxels=null;
            this.roi=null;
            this.center = null;
            regionModified=true;
        }
    }
    
    public Set<Voxel> getIntersectionVoxelSet(Region other) { // TODO: version without voxels
        if (other instanceof Analytical) return other.getIntersectionVoxelSet(this); // spot version is more efficient
        if (!boundsIntersect(other)) return Collections.emptySet();
        if (other.is2D()!=is2D()) { // should not take into account z for intersection -> cast to voxel2D (even for the 2D object to enshure voxel2D), and return voxels from the 3D objects
            Set s1 = Sets.newHashSet(Utils.transform(getVoxels(), Voxel::toVoxel2D));
            Set s2 = Sets.newHashSet(Utils.transform(other.getVoxels(), Voxel::toVoxel2D));
            if (is2D()) {
                s2.retainAll(s1);
                return Sets.newHashSet(Utils.transform(s2, v->((Voxel2D)v).toVoxel()));
            } else {
                s1.retainAll(s2);
                return Sets.newHashSet(Utils.transform(s1, v->((Voxel2D)v).toVoxel()));
            }
        } else {
            if (voxelsCreated() && other.voxelsCreated()) return Sets.intersection(Sets.newHashSet(voxels), Sets.newHashSet(other.voxels));
            else {
                BoundingBox inter = BoundingBox.getIntersection(getBounds(), other.getBounds());
                Set<Voxel> res = new HashSet<>();
                getMask();
                other.getMask();
                BoundingBox.loop(inter, (x, y, z)-> res.add(new Voxel(x, y, z)), (x, y, z) -> mask.containsWithOffset(x, y, z) && other.mask.containsWithOffset(x, y, z));
                return res;
            }
        }
    }

    public ImageMask getIntersectionMask(Region other) {
        BoundingBox otherBounds =  new SimpleBoundingBox(other.getBounds());
        BoundingBox thisBounds = new SimpleBoundingBox(getBounds());
        final boolean inter2D = is2D() || other.is2D();
        if (inter2D) {
            if (!intersect2D(thisBounds, otherBounds)) return null;
        } else {
            if (!BoundingBox.intersect(thisBounds, otherBounds)) return null;
        }

        final ImageMask mask = is2D() && !other.is2D() ? new ImageMask2D(getMask()) : getMask();
        final ImageMask otherMask = other.is2D() && !is2D() ? new ImageMask2D(other.getMask()) : other.getMask();
        BoundingBox inter = inter2D ? (is2D() ? getIntersection2D(otherBounds, thisBounds):getIntersection2D(thisBounds, otherBounds)) : BoundingBox.getIntersection(thisBounds, otherBounds);
        ImageByte resMask = new ImageByte("inter", new SimpleImageProperties(inter, getScaleXY(), getScaleZ()));
        //logger.debug("off: {}, otherOff: {}, is2D: {} other Is2D: {}, inter: {}", thisBounds, otherBounds, is2D(), other.is2D(), inter);

        final int offX = thisBounds.xMin();
        final int offY = thisBounds.yMin();
        final int offZ = thisBounds.zMin();
        final int otherOffX = otherBounds.xMin();
        final int otherOffY = otherBounds.yMin();
        final int otherOffZ = otherBounds.zMin();
        BoundingBox.LoopPredicate intersect = (x, y, z) -> mask.insideMask(x - offX, y - offY, z - offZ) && otherMask.insideMask(x - otherOffX, y - otherOffY, z - otherOffZ);
        BoundingBox.loop(inter, (x, y, z) -> resMask.setPixelWithOffset(x, y, z, 1), intersect);
        return resMask;
    }

    public Region getIntersection(Region other) {
        ImageMask mask = getIntersectionMask(other);
        if (mask == null) return null;
        return new Region(mask, 1, is2D() && other.is2D()).setIsAbsoluteLandmark(isAbsoluteLandMark());
    }

    public boolean boundsIntersect(Region other) {
        if (is2D()||other.is2D()) return BoundingBox.intersect2D(getBounds(), other.getBounds());
        return BoundingBox.intersect(getBounds(), other.getBounds());
    }

    public boolean intersect(Region other) {
        return getOverlapArea(other, null, null, 1)>0;
    }

    public double getOverlapArea(Region other) {
        return getOverlapArea(other, null, null);
    }


    public double getOverlapArea(Region other, Offset offset, Offset offsetOther) {
        return getOverlapArea(other, offset, offsetOther, Double.POSITIVE_INFINITY);
    }
    /**
     * Counts the overlap (in voxels) between this region and {@param other}, using masks of both region (no creation of voxels)
     * @param other other region
     * @param offset offset to add to this region so that it would be in absolute landmark
     * @param offsetOther offset to add to {@param other} so that it would be in absolute landmark
     * @param overlapLimit limit the count to this value. Set 1 to test overlap, POSITIVE_INFINITY to compute the overlap
     * @return overlap (in voxels) between this region and {@param other}
     */
    public double getOverlapArea(Region other, Offset offset, Offset offsetOther, double overlapLimit) {
        if (other == null) return 0;
        if (other instanceof Analytical) return other.getOverlapArea(this, offsetOther, offset); // spot version is more efficient
        BoundingBox otherBounds = offsetOther==null? new SimpleBoundingBox(other.getBounds()) : new SimpleBoundingBox(other.getBounds()).translate(offsetOther);
        BoundingBox thisBounds = offset==null? new SimpleBoundingBox(getBounds()) : new SimpleBoundingBox(getBounds()).translate(offset);
        final boolean inter2D = is2D() || other.is2D();
        if (inter2D) {
            if (!intersect2D(thisBounds, otherBounds)) return 0;
        } else {
            if (!BoundingBox.intersect(thisBounds, otherBounds)) return 0;
        }
        
        final ImageMask mask = is2D() ? new ImageMask2D(getMask()) : getMask();
        final ImageMask otherMask = other.is2D() ? new ImageMask2D(other.getMask()) : other.getMask();
        BoundingBox inter = inter2D ? (is2D() ? getIntersection2D(otherBounds, thisBounds):getIntersection2D(thisBounds, otherBounds)) : BoundingBox.getIntersection(thisBounds, otherBounds);
        //logger.debug("off: {}, otherOff: {}, is2D: {} other Is2D: {}, inter: {}", thisBounds, otherBounds, is2D(), other.is2D(), inter);

        final int offX = thisBounds.xMin();
        final int offY = thisBounds.yMin();
        final int offZ = thisBounds.zMin();
        final int otherOffX = otherBounds.xMin();
        final int otherOffY = otherBounds.yMin();
        final int otherOffZ = otherBounds.zMin();
        BoundingBox.LoopPredicate intersect = (x, y, z) -> mask.insideMask(x - offX, y - offY, z - offZ) && otherMask.insideMask(x - otherOffX, y - otherOffY, z - otherOffZ);
        if (overlapLimit == 1) {
            return BoundingBox.test(inter, intersect) ? 1:0;
        } else {
            final int[] count = new int[1];
            BoundingBox.loop(inter, (x, y, z) -> ++count[0], intersect, Double.isFinite(overlapLimit) ? (x, y, z) -> count[0]>=overlapLimit : null);
            return count[0];
        }
    }
    
    public List<Region> getIncludedObjects(List<Region> candidates) {
        ArrayList<Region> res = new ArrayList<>();
        for (Region c : candidates) if (c.intersect(this)) res.add(c); // strict inclusion?
        return res;
    }
    /**
     * 
     * @param otherRegions
     * @param offset offset to add to this region so that it would be in absolute landmark
     * @param otherOffset  offset to add to the container regions so that they would be in absolute landmark
     * @return the container with the most intersection
     */
    public Region getMostOverlappingRegion(Collection<Region> otherRegions, Offset offset, Offset otherOffset) {
        if (otherRegions==null || otherRegions.isEmpty()) return null;
        Region currentParent=null;
        double currentIntersection=-1;
        for (Region p : otherRegions) {
            double inter = getOverlapArea(p, offset, otherOffset);
            if (inter>0) {
                if (currentParent==null) {
                    currentParent = p;
                    currentIntersection = inter;
                } else if (inter>currentIntersection) { // in case of conflict: keep parent that intersect most
                    currentIntersection=inter;
                    currentParent=p;
                }
            }
        }
        return currentParent;
    }

    public Set<Region> getOverlappingRegions(Collection<Region> candidates, Offset offset, Offset containerOffset) {
        return getOverlappingRegions(candidates, offset, containerOffset, 1);
    }

    public Set<Region> getOverlappingRegions(Collection<Region> candidates, Offset offset, Offset containerOffset, double minOverlap) {
        return getOverlappingRegions(candidates, offset, containerOffset, (r1, r2)->minOverlap);
    }
    public Set<Region> getOverlappingRegions(Collection<Region> candidates, Offset offset, Offset containerOffset, ToDoubleBiFunction<Region, Region> minOverlap) {
        if (candidates.isEmpty()) return Collections.emptySet();
        return candidates.stream().filter(p -> {
            double thld = minOverlap.applyAsDouble(this, p);
            return getOverlapArea(p, offset, containerOffset, thld) >= thld;
        }).collect(Collectors.toSet());
    }
    
    public void merge(Region other) {
        if (voxelsCreated() && other.voxelsCreated()) {
            this.getVoxels().addAll(other.getVoxels());
            this.mask=null; // reset mask
            this.bounds=null; // reset bounds
        } else {
            Region res = merge(false, this, other);
            this.mask = res.mask;
            this.voxels = null;
            this.bounds = res.bounds;
        }
        this.roi = null;
        this.center = null;
        regionModified=true;
    }
    public static Region merge(boolean close, Region... regions) {
        return merge(Arrays.asList(regions), close);
    }
    public static Region merge(Collection<Region> regions) {
        return merge(regions, false);
    }
    public static Region merge(Collection<Region> regions, boolean close) {
        if (regions==null || regions.isEmpty()) return null;
        if (!Utils.objectsAllHaveSameProperty(regions, Region::isAbsoluteLandMark)) throw new IllegalArgumentException("Trying to merge regions with different landmarks");
        if (!Utils.objectsAllHaveSameProperty(regions, r->r.is2D)) throw new IllegalArgumentException("Trying to merge 2D with 3D regions");
        double closeDistSq = 0;
        if (close) {
            List<Set<Voxel>> contours = regions.stream().map(Region::getContour).collect(Collectors.toList());
            ToDoubleBiFunction<Set<Voxel>, Set<Voxel>> getMinDist = (c1, c2) -> c1.stream().mapToDouble(v1 -> c2.stream().mapToDouble(v1::getDistanceSquare).min().orElse(0)).min().orElse(0);
            for (int i = 0; i<regions.size()-1; ++i) {
                for (int j = i+1; j<regions.size(); ++j) {
                    double d = getMinDist.applyAsDouble(contours.get(i), contours.get(j));
                    if (d>closeDistSq) closeDistSq = d;
                }
            }
        }
        Iterator<Region> it = regions.iterator();
        Region ref = it.next();
        MutableBoundingBox bounds = new MutableBoundingBox(ref.getBounds());
        while (it.hasNext()) bounds.union(it.next().getBounds());
        ImageByte mask = new ImageByte("", new SimpleImageProperties(bounds, ref.getScaleXY(), ref.getScaleZ()));
        for (Region r : regions) {
            if (r.voxelsCreated()) for (Voxel v:r.voxels) mask.setPixelWithOffset(v.x, v.y, v.z, 1);
            else ImageMask.loopWithOffset(r.getMask(), (x, y, z)-> mask.setPixelWithOffset(x, y, z, 1));
        }
        Region merge = new Region(mask, 1, ref.is2D).setIsAbsoluteLandmark(ref.isAbsoluteLandMark());
        if (closeDistSq > 1) merge.binaryClose(Math.sqrt(closeDistSq+1)); // in case objects do not touch : perform binary close
        return merge;
    }
    public void binaryClose(double radius) {
        binaryClose(radius, radius * scaleXY / scaleZ);
    }
    public void binaryClose(double radius, double radiusZ) {
        Neighborhood n = is2D() ? new EllipsoidalNeighborhood(radius, false):new EllipsoidalNeighborhood(radius, radiusZ, false);
        ImageInteger closed = Filters.binaryCloseExtend(getMaskAsImageInteger(), n, false);
        setMask(closed);
    }
    
    public RegionContainer createRegionContainer(SegmentedObject structureObject) {
        if (roi!=null) return new RegionContainerIjRoi(structureObject);
        else if (mask instanceof BlankMask) return new RegionContainerBlankMask(structureObject);
        else if (!overVoxelSizeLimit()) return new RegionContainerVoxels(structureObject);
        else return new RegionContainerIjRoi(structureObject);
    }
    
    public void setVoxelValues(Image image, boolean useOffset) {
        if (useOffset) {
            for (Voxel v : getVoxels()) v.value=image.getPixelWithOffset(v.x, v.y, v.z);
        } else {
            for (Voxel v : getVoxels()) v.value=image.getPixel(v.x, v.y, v.z);
        }
    }
    /**
     * Draws with the offset of the object, using the offset of the image if the object is in absolute landmark
     * @param image
     * @param value
     */
    public void draw(Image image, double value) {
        if (is2D() && image.sizeZ()>1) {
            List<Image> planes = image.splitZPlanes();
            for (Image p : planes) draw(p, value);
        }
        boolean intersect = isAbsoluteLandMark() ? ( is2D() ? BoundingBox.intersect2D(this.getBounds(), image.getBoundingBox()): BoundingBox.intersect(this.getBounds(), image.getBoundingBox())) : (is2D() ? BoundingBox.intersect2D(this.getBounds(), image.getBoundingBox().resetOffset()) : BoundingBox.intersect(this.getBounds(), image.getBoundingBox().resetOffset()));
        if (!intersect) return;
        boolean included = isAbsoluteLandMark() ? ( is2D() ? BoundingBox.isIncluded2D(this.getBounds(), image.getBoundingBox()): BoundingBox.isIncluded(this.getBounds(), image.getBoundingBox())) : (is2D() ? BoundingBox.isIncluded2D(this.getBounds(), image.getBoundingBox().resetOffset()) : BoundingBox.isIncluded(this.getBounds(), image.getBoundingBox().resetOffset()));
        if (voxels !=null) {
            //logger.trace("drawing from VOXELS of object: {} with label: {} on image: {} ", this, label, image);
            if (isAbsoluteLandMark()) {
                if (included) for (Voxel v : voxels) image.setPixelWithOffset(v.x, v.y, v.z, value);
                else for (Voxel v : voxels) if (image.containsWithOffset(v.x, v.y, v.z)) image.setPixelWithOffset(v.x, v.y, v.z, value);
            }
            else {
                if (included) for (Voxel v : voxels) image.setPixel(v.x, v.y, v.z, value);
                else for (Voxel v : voxels) if (image.contains(v.x, v.y, v.z)) image.setPixel(v.x, v.y, v.z, value);
            }
        }
        else {
            getMask();
            if (mask == null) throw new RuntimeException("Both voxels and mask are null: cannot draw region of class: "+getClass());
            //logger.debug("drawing from IMAGE of object: {} with label: {} on image: {} mask: {}, absolute landmark: {}", this, label, image, mask, isAbsoluteLandMark());
            if (isAbsoluteLandMark()) {
                if (included) ImageMask.loopWithOffset(mask, (x, y, z)-> image.setPixelWithOffset(x, y, z, value));
                else ImageMask.loopWithOffset(mask, (x, y, z)-> {if (image.containsWithOffset(x, y, z)) image.setPixelWithOffset(x, y, z, value);});
            }
            else {
                if (included) ImageMask.loopWithOffset(mask, (x, y, z)-> image.setPixel(x, y, z, value));
                else ImageMask.loopWithOffset(mask, (x, y, z)-> { if (image.contains(x, y, z)) image.setPixel(x, y, z, value);} );
            }
        }
    }
    /**
     * Draws with a custom offset 
     * @param image its offset will be taken into account
     * @param value 
     * @param offset will be added to the object absolute position
     */
    public void draw(Image image, double value, Offset offset) {
        if (offset==null) offset = new SimpleOffset(0, 0, 0);
        if (is2D() && image.sizeZ()>1) {
            List<Image> planes = image.splitZPlanes();
            Offset curO = offset.duplicate();
            for (Image p : planes) {
                draw(p, value, curO);
                curO.translate(new SimpleOffset(0, 0, 1));
            }
        }
        if (voxels !=null) {
            //logger.trace("drawing from VOXELS of object: {} with value: {} on image: {} ", this, value, image);
            int offX = offset.xMin()-image.xMin();
            int offY = offset.yMin()-image.yMin();
            int offZ = offset.zMin()-image.zMin();
            for (Voxel v : voxels) if (image.contains(v.x+offX, v.y+offY, v.z+offZ)) image.setPixel(v.x+offX, v.y+offY, v.z+offZ, value);
        }
        else {
            getMask();
            int offX = offset.xMin()+mask.xMin()-image.xMin();
            int offY = offset.yMin()+mask.yMin()-image.yMin();
            int offZ = offset.zMin()+mask.zMin()-image.zMin();
            //logger.trace("drawing from IMAGE of object: {} with value: {} on image: {} mask offsetX: {} mask offsetY: {} mask offsetZ: {}", this, value, mask, offX, offY, offZ);
            for (int z = 0; z < mask.sizeZ(); ++z) {
                for (int y = 0; y < mask.sizeY(); ++y) {
                    for (int x = 0; x < mask.sizeX(); ++x) {
                        if (mask.insideMask(x, y, z)) {
                            if (image.contains(x+offX, y+offY, z+offZ)) image.setPixel(x+offX, y+offY, z+offZ, value);
                        }
                    }
                }
            }
        }
    }
    
    /**
     * Draws with a custom offset (the offset of the object and the image is not taken into account)
     * @param image
     * @param value 
     */
    public void drawWithoutObjectOffset(Image image, double value, Offset offset) {
        if (offset==null) {
            draw(image, value);
            return;
        }
        if (is2D() && image.sizeZ()>1) {
            List<Image> planes = image.splitZPlanes();
            Offset curO = offset.duplicate();
            for (Image p : planes) {
                draw(p, value, curO);
                curO.translate(new SimpleOffset(0, 0, 1));
            }
        }
        if (voxels !=null) {
            //logger.trace("drawing from VOXELS of object: {} with value: {} on image: {} ", this, value, image);
            int offX = -getBounds().xMin()+offset.xMin();
            int offY = -getBounds().yMin()+offset.yMin();
            int offZ = -getBounds().zMin()+offset.zMin();
            for (Voxel v : voxels) image.setPixel(v.x+offX, v.y+offY, v.z+offZ, value);
        }
        else {
            getMask();
            int offX = offset.xMin();
            int offY = offset.yMin();
            int offZ = offset.zMin();
            //logger.trace("drawing from IMAGE of object: {} with value: {} on image: {} mask offsetX: {} mask offsetY: {} mask offsetZ: {}", this, value, mask, offX, offY, offZ);
            for (int z = 0; z < mask.sizeZ(); ++z) {
                for (int y = 0; y < mask.sizeY(); ++y) {
                    for (int x = 0; x < mask.sizeX(); ++x) {
                        if (mask.insideMask(x, y, z)) {
                            image.setPixel(x+offX, y+offY, z+offZ, value);
                        }
                    }
                }
            }
        }
    }
    
    private boolean overVoxelSizeLimit() {
        int limit =  (!is2D() ? MAX_VOX_3D :MAX_VOX_2D);
        if (voxels!=null) return voxels.size()>limit;
        if (mask instanceof BlankMask) return true;
        if (mask==null) getMask();
        int count =0;
        for (int z = 0; z < mask.sizeZ(); ++z) {
            for (int xy = 0; xy < mask.sizeXY(); ++xy) {
                if (mask.insideMask(xy, z)) {
                    if (++count==limit) return true;
                }
            }
        }
        return false;
    }
    
    public Region translate(Offset offset) {
        if (Offset.offsetNull(offset)) return this;
        else {
            if (mask!=null) mask.translate(offset);
            if (bounds!=null) bounds.translate(offset);
            if (voxels!=null) {
                for (Voxel v : voxels) v.translate(offset);
                this.voxels = new HashSet<>(voxels); // hash of voxel changed
            }
            if (center!=null) center.translate(offset);
            if (roi!=null) roi.translate(offset);
            regionModified=true;
        }
        return this;
    }
    
    public void translateToFirstPointOutsideRegionInDir(Point start, Vector normedDir) {
        Voxel v = start.asVoxel();
        if (!contains(v)) return;
        start.translate(normedDir);
        start.copyLocationToVoxel(v);
        while(contains(v)) {
            start.translate(normedDir);
            start.copyLocationToVoxel(v);
        }
    }

    public Region setLabel(int label) {
        this.label=label;
        return this;
    }
    @Override
    public String toString() {
        return ""+this.label;
    }
}
