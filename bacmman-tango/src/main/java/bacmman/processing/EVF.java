package bacmman.processing;

import bacmman.data_structure.Region;
import bacmman.data_structure.RegionPopulation;
import bacmman.data_structure.SegmentedObject;
import bacmman.image.ImageFloat;
import bacmman.image.ImageMask;
import bacmman.image.SubtractedMask;
import bacmman.image.ThresholdMask;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

public class EVF {
    public static ImageFloat getEVFMap(SegmentedObject container, int[] referenceObjectClasses, boolean negativeInsideRef, double erodeContainer) {
        double zAspectRatio = container.getScaleZ()/container.getScaleXY();
        boolean parentIsPartOfRef = Arrays.stream(referenceObjectClasses).filter(i -> i==container.getStructureIdx()).findAny().isPresent();
        boolean refIsParent = parentIsPartOfRef && referenceObjectClasses.length==1; // only parent
        ImageFloat edt;
        ImageMask mask, erosionMask=null;
        if (refIsParent) {
            mask = container.getMask();
            edt = EDT.transform(mask, true, 1, zAspectRatio, false);
            if (erodeContainer>0) {
                erosionMask = new ThresholdMask(edt, erodeContainer, false, true);
                mask = new SubtractedMask(mask, erosionMask);
            }
        } else {
            ImageMask inside = getInsideMask(container, referenceObjectClasses);
            mask = new SubtractedMask(container.getMask(), inside);
            if (erodeContainer>0) {
                ImageFloat parentEdt = EDT.transform(container.getMask(), true, 1, zAspectRatio, false);
                erosionMask = new ThresholdMask(parentEdt, 0, true, erodeContainer, true);
                mask = new SubtractedMask(mask, erosionMask);
            }
            if (parentIsPartOfRef) edt = EDT.transform(mask, true, 1, zAspectRatio, false);
            else edt = EDT.transform(inside, false, 1, zAspectRatio, false);
            if (negativeInsideRef) {
                ImageFloat edtIn = EDT.transform(inside, true, 1, zAspectRatio, false);
                ImageMask.loop(inside, (x, y, z) -> edt.setPixel(x, y, z, -edtIn.getPixel(x, y, z)));
                mask = erodeContainer>0 ? new SubtractedMask(container.getMask(), erosionMask) : container.getMask();
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
