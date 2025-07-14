package bacmman.data_structure;

import bacmman.configuration.experiment.Experiment;
import bacmman.configuration.experiment.Structure;
import bacmman.utils.Utils;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static bacmman.utils.Utils.toArray;

public class ExperimentStructure {
    final Experiment xp;
    public ExperimentStructure(Experiment xp) {
        this.xp=xp;
    }
    public String getDatasetName() {
        return xp.getName();
    }
    public int getParentObjectClassIdx(int objectClassIdx) {
        if (objectClassIdx<0) return objectClassIdx;
        return xp.getStructure(objectClassIdx).getParentStructure();
    }
    public int getSegmentationParentObjectClassIdx(int objectClassIdx) {
        if (objectClassIdx<0) return objectClassIdx;
        return xp.getStructure(objectClassIdx).getSegmentationParentStructure();
    }
    public int getFirstCommonParentObjectClassIdx(int structureIdx1, int structureIdx2) {
        while (structureIdx1>=0 && structureIdx2>=0) {
            if (structureIdx1>structureIdx2) structureIdx1 = xp.getStructure(structureIdx1).getParentStructure();
            else if (structureIdx1<structureIdx2) structureIdx2 = xp.getStructure(structureIdx2).getParentStructure();
            else return structureIdx1;
        }
        return -1;
    }

    public boolean isDirectChildOf(int parentStructureIdx, int childStructureIdx) {
        if (childStructureIdx<=parentStructureIdx) return false;
        return xp.getStructure(childStructureIdx).getParentStructure()==parentStructureIdx;
    }

    public boolean isChildOf(int parentStructureIdx, int childStructureIdx) {
        if (childStructureIdx<=parentStructureIdx) return false;
        else if (parentStructureIdx==-1) return true; // all object classes are child classes of root
        Structure child = xp.getStructure(childStructureIdx);
        while(true) {
            int p = child.getParentStructure();
            if (p==parentStructureIdx) return true;
            if (p<parentStructureIdx) return false;
            child = xp.getStructure(p);
        }
    }

    public List<Integer> getAllDirectChildStructures(int parentStructureIdx) {
        int max = xp.getStructureCount();
        ArrayList<Integer> res = new ArrayList<Integer>(max);
        for (int s = parentStructureIdx+1; s<max; ++s) {
            if (isDirectChildOf(parentStructureIdx, s)) res.add(s);
        }
        return res;
    }

    public String[] getChildObjectClassesAsString(int structureIdx) {
        int[] childIdx = getAllChildStructures(structureIdx);
        return getObjectClassesNames(childIdx);
    }

    public String[] getObjectClassesNames(int... structureIndicies) {
        String[] res = new String[structureIndicies.length];
        String[] names = xp.experimentStructure.getObjectClassesAsString();
        for (int i = 0; i<res.length; ++i) {
            if (structureIndicies[i]<0) res[i]="Viewfield";
            else if (structureIndicies[i]<names.length) res[i] = xp.getStructure(structureIndicies[i]).getName();
        }
        return res;
    }

    public String[] getObjectClassesAsString() {return xp.getStructures().getChildrenString();}

    /**
     *
     * @param structureIdx
     * @return indexes of structures that are direct children of the structure of index {@param structureIdx}
     */
    public int[] getAllDirectChildStructuresAsArray(int structureIdx) {
        return Utils.toArray(getAllDirectChildStructures(structureIdx), false);
    }
    /**
     *
     * @param structureIdx
     * @return return the direct or indirect children of the structure of index: {@param structureIdx}
     */
    public int[] getAllChildStructures(int structureIdx) {
        if (structureIdx==-1) { // all structures
            int[] res = new int[xp.getStructureCount()];
            for (int i = 1; i<res.length; ++i) res[i] = i;
            return res;
        }
        return IntStream.range(structureIdx+1, xp.getStructureCount()).filter(s->isChildOf(structureIdx, s)).toArray();
    }

    /**
     *
     * @param structureIdx
     * @return the number of parent before the root (0 if the parent is the root)
     */
    public int getHierachicalOrder(int structureIdx) {
        int order=0;
        int p = xp.getStructure(structureIdx).getParentStructure();
        while(p>=0) {
            p=xp.getStructure(p).getParentStructure();
            ++order;
        }
        return order;
    }
    /**
     *
     * @param structureIdx
     * @return an array of structure index, starting from the first structure after the root structure, ending at the structure index (included)
     */
    public int[] getPathToRoot(int structureIdx) {
        return getPathToStructure(-1, structureIdx);
    }
    /**
     *
     * @param startStructureIdx start structure (excluded), must be anterior to {@param stopStructureIdx} in the structure experimentStructure
     * @param stopStructureIdx stop structure (included), must be posterior to {@param stopStructureIdx} in the structure experimentStructure
     * @return
     */
    public int[] getPathToStructure(int startStructureIdx, int stopStructureIdx) {
        if (startStructureIdx==-1 && startStructureIdx==stopStructureIdx) return new int[0];
        ArrayList<Integer> pathToStructure = new ArrayList<>(xp.getStructureCount());
        pathToStructure.add(stopStructureIdx);
        if (startStructureIdx!=stopStructureIdx) {
            int p = xp.getStructure(stopStructureIdx).getParentStructure();
            while(p!=startStructureIdx) {
                if (p<0) return new int[0]; // no path found between structures
                pathToStructure.add(p);
                p=xp.getStructure(p).getParentStructure();
            }
        }
        return toArray(pathToStructure, true);
    }

    /**
     *
     * @return a matrix of structure indexes. the first dimension represent the hierarchical orders, the second dimension the structures at the given hierarchical order, sorted by the index of the structure
     */
    public int[][] getStructuresInHierarchicalOrder() {
        int[] orders = new int[xp.getStructureCount()];
        int maxOrder=0;
        for (int i = 0; i<orders.length;++i) {
            orders[i]=getHierachicalOrder(i);
            if (orders[i]>maxOrder) maxOrder=orders[i];
        }
        int[] resCount = new int[maxOrder+1];
        for (int i = 0; i<orders.length;++i) resCount[orders[i]]++;
        int[][] res = new int[maxOrder+1][];
        int[] idx = new int[maxOrder+1];
        for (int i = 0; i<resCount.length;++i) res[i]=new int[resCount[i]];
        for (int i = 0; i<orders.length;++i) {
            res[orders[i]][idx[orders[i]]]=i;
            idx[orders[i]]++;
        }
        return res;
    }

    public int[] getStructuresInHierarchicalOrderAsArray() {
        int[][] so=getStructuresInHierarchicalOrder();
        int[] res = new int[xp.getStructureCount()];
        int idx=0;
        for (int[] o : so) for (int s:o) res[idx++]=s;
        return res;
    }
    public String getObjectClassName(int objectClassIdx) {
        if (objectClassIdx==-1) return "Viewfield";
        return xp.getStructure(objectClassIdx).getName();
    }
    public boolean singleFrame(String positionName, int objectClassIdx) {
        return xp.getPosition(positionName).singleFrame(objectClassIdx);
    }
    public boolean singleFrameChannel(String positionName, int channelIdx) {
        return xp.getPosition(positionName).singleFrameChannel(channelIdx);
    }
    public int sizeZ(String positionName, int channelIdx) {
        return xp.getPosition(positionName).getSizeZ(channelIdx);
    }
    public int getChannelIdx(int objectClassIdx) {
        if (objectClassIdx<0) return -1;
        if (xp.getStructureCount()<=objectClassIdx) return -1;
        return xp.getStructure(objectClassIdx).getChannelImage();
    }
    public int getObjectClassIdx(int channelIdx) {
        for (Structure s : xp.getStructures().getChildren()) {
            if (s.getChannelImage() == channelIdx) return s.getIndex();
        }
        return -1;
    }
    public int getObjectClassNumber() {
        return xp.getStructureCount();
    }

    public int getChannelNumber() {
        return xp.getChannelImageCount(true);
    }
    public String[] getChannelNames() {
        return xp.getChannelImagesAsString(true);
    }
    public String[] getObjectClassNames() {
        return xp.getStructures().getChildrenString();
    }
    public Stream<Color> getChannelColors() {
        return xp.getChannelColors(true);
    }
    public String[] getChannelColorNames() {
        return xp.getChannelColorAsString(true).toArray(String[]::new);
    }

    public Stream<Color> getObjectColors() {
        return xp.getObjectColors();
    }

    public boolean is2D(int objectClassIdx, String positionName) {
        return xp.getStructure(objectClassIdx).is2D(positionName);
    }

    public boolean allowMerge(int objectClassIdx) {
        return xp.getStructure(objectClassIdx).allowMerge();
    }
    public boolean allowSplit(int objectClassIdx) {
        return xp.getStructure(objectClassIdx).allowSplit();
    }

    public boolean allowOverlap(int objectClassIdx) {
        return xp.getStructure(objectClassIdx).allowOverlap();
    }

    public Structure.TRACK_DISPLAY getTrackDisplay(int objectClassIdx) {
        return xp.getStructure(objectClassIdx).getTrackDisplay();
    }
}
