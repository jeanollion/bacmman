package bacmman.processing;

import bacmman.data_structure.Region;
import bacmman.data_structure.RegionPopulation;
import bacmman.data_structure.SegmentedObject;
import bacmman.image.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

public class EVF {
    public static final Logger logger = LoggerFactory.getLogger(EVF.class);
    public static ImageFloat getEVFMap(SegmentedObject container, int[] referenceObjectClasses, boolean negativeInsideRef, double erodeContainer, boolean resampleZ) {
        final double zAR = container.getScaleZ()/container.getScaleXY();
        double zAspectRatio = resampleZ ? 1 : zAR;

        boolean parentIsPartOfRef = Arrays.stream(referenceObjectClasses).filter(i -> i==container.getStructureIdx()).findAny().isPresent();
        boolean refIsParent = parentIsPartOfRef && referenceObjectClasses.length==1; // only parent
        UnaryOperator<ImageMask> resampleZFun = m -> {
            if (zAR==1 || m.sizeZ()==1) return m;
            ImageInteger im = TypeConverter.maskToImageInteger(m, null);
            return Resize.resample(im, true, im.sizeX(), im.sizeY(), (int)Math.round(im.sizeZ() * zAR));
        };
        ImageFloat edt;
        ImageMask mask, erosionMask=null;
        if (refIsParent) {
            mask = container.getMask();
            if (resampleZ) {
                mask = resampleZFun.apply(mask);
            }
            edt = EDT.transform(mask, true, 1, zAspectRatio, false);
            if (erodeContainer>0) {
                erosionMask = new PredicateMask(edt, erodeContainer, false, false);
                erosionMask = TypeConverter.toByteMask(erosionMask, null, 1); // erosion mask must be flattened because it depends on EDT that will be modified
                mask = new SubtractedMask(mask, erosionMask);
            }
        } else {
            ImageMask inside = getInsideMask(container, referenceObjectClasses);
            ImageMask containerMask = container.getMask();
            if (resampleZ) {
                containerMask = resampleZFun.apply(containerMask);
                inside = resampleZFun.apply(inside);
            }
            mask = new SubtractedMask(containerMask, inside);
            if (erodeContainer>0) {
                ImageFloat parentEdt = EDT.transform(containerMask, true, 1, zAspectRatio, false);
                erosionMask = new PredicateMask(parentEdt, 0, true, erodeContainer, false);
                mask = new SubtractedMask(mask, erosionMask);
            }
            if (parentIsPartOfRef) edt = EDT.transform(mask, true, 1, zAspectRatio, false);
            else {
                edt = EDT.transform(inside, false, 1, zAspectRatio, false);
                for (int z = 0; z<edt.sizeZ(); z++) { // set NaN outside container, as distance will not be transformed to index
                    for (int xy=0; xy<edt.getSizeXY(); xy++) {
                        if (!containerMask.insideMask(xy, z)) {
                            edt.setPixel(xy, z, Double.NaN);
                        }
                    }
                }
            }
            if (negativeInsideRef) {
                ImageFloat edtIn = EDT.transform(inside, true, 1, zAspectRatio, false);
                ImageMask.loop(inside, (x, y, z) -> edt.setPixel(x, y, z, -edtIn.getPixel(x, y, z)));
                mask = erodeContainer>0 ? new SubtractedMask(containerMask, erosionMask) : containerMask;
            }
        }
        normalizeDistanceMap(edt, mask, erosionMask, parentIsPartOfRef);
        edt.resetOffset().translate(container.getBounds());
        return edt;
    }
    private static ImageMask getInsideMask(SegmentedObject container, int[] oc) {
        if (oc.length==1) return container.getChildRegionPopulation(oc[0], false).getLabelMap();
        else {
            List<Region> regions = Arrays.stream(oc).filter(o -> o!=container.getStructureIdx()).mapToObj(o -> container.getChildRegionPopulation(o, false).getRegions()).flatMap(Collection::stream).collect(Collectors.toList());
            return new RegionPopulation(regions, container.getMaskProperties()).getLabelMap();
        }
    }

    public static void normalizeDistanceMap(ImageFloat distanceMap, ImageMask mask, ImageMask excludedVolume, boolean setErodedVolumeToZero) {
        int count = 0;
        Vox[] indices = new Vox[mask.count()];
        double volume = indices.length;
        for (int z = 0; z<distanceMap.sizeZ(); z++) {
            for (int xy=0; xy<distanceMap.getSizeXY(); xy++) {
                if (mask.insideMask(xy, z)) {
                    indices[count++]=new Vox(distanceMap.getPixel(xy, z), xy, z);
                }
            }
        }
        Arrays.sort(indices);
        for (int i = 0;i<indices.length-1;i++) {
            // gestion des repetitions : valeur médiane
            if (indices[i+1].distance==indices[i].distance) {
                int j = i+1;
                while (j<(indices.length-1) && indices[i].distance==indices[j].distance) j++;
                double median = (i+j)/2d;
                for (int k = i; k<=j;k++) indices[k].index=median;
                i=j;
            } else {
                indices[i].index=i;
            }
        }
        if (indices.length>=1 && indices[indices.length-1].index==0) indices[indices.length-1].index = indices.length-1;
        for (Vox v : indices ) v.index/=volume;
        for (Vox v : indices) distanceMap.setPixel(v.xy, v.z, v.index);
        if (excludedVolume!=null) correctDistanceMap(excludedVolume, distanceMap, indices, setErodedVolumeToZero);
    }

    private static void correctDistanceMap(ImageMask excludedVolume, ImageFloat distanceMap, Vox[] indicies, boolean setErodedVolumeToZero) {
        if (!setErodedVolumeToZero) {
            for (int z = 0; z<distanceMap.sizeZ(); z++) {
                for (int xy=0; xy<distanceMap.getSizeXY(); xy++) {
                    if (excludedVolume.insideMask(xy, z)) {
                        Vox v = new Vox(distanceMap.getPixel(xy, z), xy, z);
                        int i=Arrays.binarySearch(indicies, v);
                        if (i>=0) distanceMap.setPixel(xy, z, (float)indicies[i].index);
                        else {
                            int ins = -i-1;
                            if (ins==indicies.length) distanceMap.setPixel(xy, z, 1);
                            else if (ins==0) distanceMap.setPixel(xy, z, 0);
                            else distanceMap.setPixel(xy, z, (float)(indicies[ins].index+indicies[ins-1].index)/2);
                        }
                    }
                }
            }
        } else {
            for (int z = 0; z<distanceMap.sizeZ(); z++) {
                for (int xy=0; xy<distanceMap.getSizeXY(); xy++) {
                    if (excludedVolume.insideMask(xy, z)) {
                        distanceMap.setPixel(xy, z, 0);
                    }
                }
            }
        }
    }

    protected static class Vox implements Comparable<Vox>{
        float distance;
        double index;
        int xy, z;
        public Vox(float distance, int xy, int z) {
            this.distance=distance;
            this.xy=xy;
            this.z=z;
        }
        public Vox(float distance, double index, int xy, int z) {
            this.distance=distance;
            this.index=index;
            this.xy=xy;
            this.z=z;
        }
        @Override
        public int compareTo(Vox v) {
            if (distance > v.distance) return 1;
            else if (distance<v.distance) return -1;
            else return 0;
        }
    }
}
