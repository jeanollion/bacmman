package bacmman.configuration.parameters;

import bacmman.core.DockerGateway;
import bacmman.core.Core;
import bacmman.utils.Utils;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class DockerImageParameter extends AbstractChoiceParameter<DockerImageParameter.DockerImage, DockerImageParameter> {
    protected List<DockerImage> allImages=new ArrayList<>();
    int[] minimalVersion, maximalVersion;
    String imageName;
    public DockerImageParameter(String name) {
        super(name, null, null, DockerImage::toString, false);
        setMapper(s->allImages.stream().filter(i -> i.equals(s)).findFirst().orElse(null));
    }
    public DockerImageParameter setImageRequirement(String imageName, int[] minimalVersion, int[] maximalVersion) {
        this.imageName = imageName;
        this.minimalVersion = minimalVersion;
        this.maximalVersion = maximalVersion;
        refreshImageList();
        if (selectedItem == null && !allImages.isEmpty() && !isAllowNoSelection()) {
            // set first installed value
            setValue(allImages.stream().filter(DockerImage::isInstalled).findFirst().orElse(allImages.get(0)));
        }
        return this;
    }
    @Override
    public void setSelectedItem(String item) {
        setValue(this.mapper.apply(item));
    }

    @Override
    public String[] getChoiceList() {
        refreshImageList();
        return allImages.stream().map(DockerImage::toString).toArray(String[]::new);
    }

    public void refreshImageList() {
        DockerGateway gateway = Core.getCore().getDockerGateway();
        Set<String> installedImages;
        if (gateway==null) installedImages = Collections.EMPTY_SET;
        else installedImages = gateway.listImages().collect(Collectors.toSet());
        allImages.clear();
        Stream.concat(Utils.getResourcesForPath("dockerfiles/"), installedImages.stream())
            .map(n -> new DockerImage(n, installedImages))
            .filter(imageName == null ? i->true : i -> i.imageName.equals(imageName))
            .filter(minimalVersion == null ? i->true: i->i.compareVersion(minimalVersion)>=0 )
            .filter(maximalVersion == null ? i->true: i->i.compareVersion(maximalVersion)<=0 )
            .distinct()
            .forEach(allImages::add);
        Collections.sort(allImages);
        if (selectedItem != null) {
            int idx = allImages.indexOf(selectedItem);
            if (idx>=0) selectedItem = allImages.get(idx);
        }
    }

    @Override
    public void setValue(DockerImage value) {
        this.selectedItem = value;
        fireListeners();
        setCondValue();
    }

    @Override
    public DockerImage getValue() {
        if (selectedItem != null && !selectedItem.isInstalled()) refreshImageList(); // in case image has been installed but not refreshed
        return selectedItem;
    }

    @Override
    public Object toJSONEntry() {
        if (selectedItem == null) return "";
        else return selectedItem.getTag();
    }


    public static class DockerImage implements Comparable<DockerImage> {
        String imageName, version, fileName;
        int[] versionNumber;
        boolean installed;
        public DockerImage(String fileName, Set<String> installed) {
            this.fileName = fileName;
            String tag = DockerGateway.formatDockerTag(fileName);
            int i = tag.indexOf(':');
            if (i>=0) {
                imageName = tag.substring(0, i);
                version = tag.substring(i+1);
                try {
                    versionNumber = DockerGateway.parseVersion(tag);
                } catch (NumberFormatException e) {
                    versionNumber = new int[0];
                }
            } else {
                imageName = tag;
                version = "";
                versionNumber = new int[0];
            }
            this.installed = installed!=null && installed.contains(getTag());
        }

        public boolean isInstalled() {
            return installed;
        }

        public String getImageName() {
            return imageName;
        }

        public String getFileName() {
            return fileName;
        }

        public String getVersion() {
            return version;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o instanceof DockerImage) {
                DockerImage that = (DockerImage) o;
                return Objects.equals(imageName, that.imageName) && Objects.equals(version, that.version);
            } else if (o instanceof String) {
                return ((String) o).replace(" (not installed)", "").equals(getTag());
            } else return false;
        }

        @Override
        public int hashCode() {
            return Objects.hash(imageName, version);
        }
        public String getTag() {
            return imageName+":"+version;
        }
        @Override
        public String toString() {
            return getTag() + (isInstalled() ? "" : " (not installed)");
        }

        public int compareVersion(int[] version) {
            return DockerGateway.versionComparator().compare(versionNumber, version);
        }

        @Override
        public int compareTo(DockerImage o) {
            int c = imageName.compareTo(o.imageName);
            if (c==0) {
                return -DockerGateway.versionComparator().compare(versionNumber, o.versionNumber);
            } else return c;
        }
    }
}
