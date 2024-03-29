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
package bacmman.data_structure.region_container;

import bacmman.data_structure.Region;
import bacmman.data_structure.SegmentedObject;
import bacmman.data_structure.region_container.roi.IJRoi3D;
import bacmman.image.*;
import ij.ImagePlus;
import ij.gui.Roi;
import ij.io.RoiDecoder;
import ij.io.RoiEncoder;
import ij.process.ImageProcessor;
import bacmman.image.wrappers.IJImageWrapper;

import static bacmman.image.Image.logger;

import ij.plugin.filter.ThresholdToSelection;

import java.awt.*;
import java.util.*;
import java.util.List;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

/**
 *
 * @author Jean Ollion
 */

public class RegionContainerIjRoi extends RegionContainer {
    List<byte[]> roiZ; // persists
    IJRoi3D roi; // not persistent
    public RegionContainerIjRoi(SegmentedObject structureObject) {
        super(structureObject);
        createRoi(structureObject.getRegion());
    }


    private void createRoi(Region object) {
        if (object.getRoi()==null) object.createRoi(); // calls static method createRoi of this class
        roi = object.getRoi();
    }

    private void encodeRoi() {
        if (roi==null) createRoi(segmentedObject.getRegion());
        roiZ = new ArrayList<>(roi.sizeZ());
        roi.entrySet().stream().filter(e->e.getKey()>=0).sorted(Comparator.comparingInt(Map.Entry::getKey))
                .forEach(e->roiZ.add(RoiEncoder.saveAsByteArray(e.getValue())));
    }
    /**
     * 
     * @return the ROI if existing null if not
     */
    public IJRoi3D getRoi() {
        return roi;
    }
    private void decodeRoi() {
        roi = new IJRoi3D(roiZ.size());
        roi.setIs2D(is2D);
        int z=0;
        for (byte[] b : roiZ) {
            Roi r = RoiDecoder.openFromByteArray(b);
            r.setPosition(z+1+bounds.zMin());
            roi.put(z+bounds.zMin(), r);
            ++z;
        }
        //if (roi.isEmpty()) logger.debug("empty roi for: {}", segmentedObject);
    }
    private synchronized ImageByte getMask() {
        if (roi==null) decodeRoi();
        return roi.toMask(bounds, segmentedObject.getScaleXY(), segmentedObject.getScaleZ());
    }

    @Override
    public void update() {
        super.update();
        createRoi(segmentedObject.getRegion());
        roiZ = null;
    }

    @Override
    public Region getRegion() {
        if (roi==null) decodeRoi();
        return new Region(this.roi, segmentedObject.getIdx() + 1, bounds, segmentedObject.getScaleXY(), segmentedObject.getScaleZ());
        //return new Region(this.getMask(), segmentedObject.getIdx() + 1, is2D);
    }

    @Override
    public void initFromJSON(Map json) {
        super.initFromJSON(json);
        if (json.containsKey("roi")) {
            roiZ = new ArrayList<>(1);
            roiZ.add(Base64.getDecoder().decode((String)json.get("roi")));
        } else if (json.containsKey("roiZ")) {
            JSONArray rois = (JSONArray)json.get(("roiZ"));
            roiZ = new ArrayList<>(rois.size());
            for (int i = 0; i<rois.size(); ++i) roiZ.add(Base64.getDecoder().decode((String)rois.get(i)));
        }
    }
    @Override
    public JSONObject toJSON() {
        JSONObject res = super.toJSON();
        if (roiZ ==null) encodeRoi();
        if (roiZ.size()>1) {
            JSONArray rois = new JSONArray();
            for (byte[] bytes: this.roiZ) {
                rois.add(Base64.getEncoder().encodeToString(bytes));
            }
            res.put("roiZ", rois);
        } else if (roiZ.size()==1) {
            res.put("roi", Base64.getEncoder().encodeToString(roiZ.get(0)));
        }
        return res;
    }
    protected RegionContainerIjRoi() {}
    public RegionContainerIjRoi(SimpleBoundingBox bounds, IJRoi3D roi) {
        super(bounds, roi.is2D());
        this.roi = roi;
    }
    
    /**
     *
     * @param mask
     * @param offset
     * @param is3D
     * @return mapping of Roi to Z-slice (taking into account the provided offset)
     */
    public static IJRoi3D createRoi(ImageMask mask, Offset offset, boolean is3D) {
        if (offset == null) {
            logger.error("ROI creation : offset null for mask: {}", mask.getName());
            return null;
        }
        IJRoi3D res = new IJRoi3D(mask.sizeZ()).setIs2D(!is3D);
        if (mask instanceof BlankMask) {
            for (int z = 0; z < mask.sizeZ(); ++z) {
                Roi rect = new Roi(0, 0, mask.sizeX(), mask.sizeY());
                rect.setLocation(offset.xMin(), offset.yMin());
                rect.setPosition(z +1+ offset.zMin());
                res.put(z + mask.zMin(), rect);
            }
            return res;
        }
        ThresholdToSelection tts = new ThresholdToSelection();
        ImageInteger maskIm = TypeConverter.maskToImageInteger(mask, null); // copy only if necessary
        ImagePlus maskPlus = IJImageWrapper.getImagePlus(maskIm);
        int maxLevel = ImageInteger.getMaxValue(maskIm, true); // TODO necessary ??
        for (int z = 0; z < mask.sizeZ(); ++z) {
            ImageProcessor ip = maskPlus.getStack().getProcessor(z + 1);
            ip.setThreshold(1, maxLevel, ImageProcessor.NO_LUT_UPDATE);
            Roi roi = tts.convert(ip);
            if (roi != null) {
                //roi.setPosition(z+1+mask.getOffsetZ());
                Rectangle bds = roi.getBounds();
                if (bds == null) {
                    continue;
                }
                roi.setLocation(bds.x + offset.xMin(), bds.y + offset.yMin());
                roi.setPosition(z + 1 + offset.zMin()); 
                res.put(z + offset.zMin(), roi);
            }

        }
        return res;
    }
}
