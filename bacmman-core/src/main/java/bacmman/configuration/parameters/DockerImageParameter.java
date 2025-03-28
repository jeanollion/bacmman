package bacmman.configuration.parameters;

import bacmman.core.DockerGateway;
import bacmman.core.Core;
import bacmman.utils.Pair;
import bacmman.utils.Utils;

import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class DockerImageParameter extends AbstractChoiceParameter<DockerImageParameter.DockerImage, DockerImageParameter> {
    protected List<DockerImage> allImages=null;
    int[] minimalVersion, maximalVersion;
    String imageName, versionPrefix;
    Predicate<DockerImage> imageFilter;
    final List<String> dockerImageResources;
    public DockerImageParameter(String name) {
        super(name, null, null, DockerImage::toString, false);
        setMapper(s->{
            if (allImages == null) refreshImageList();
            return allImages.stream().filter(i -> i.equals(s)).findFirst().orElse(null);
        });
        dockerImageResources = Utils.getResourcesForPath("dockerfiles/").collect(Collectors.toList());
    }

    public void selectLatestImageIfNoSelection() {
        if (getValue() == null) {
            DockerImage im = getAllImages().sorted(Comparator.reverseOrder()).findFirst().orElse(null);
            if (im != null) setValue(im);
        }
    }

    public DockerImageParameter setImageRequirement(String imageName, String versionPrefix, int[] minimalVersion, int[] maximalVersion) {
        boolean change = !Objects.equals(this.imageName, imageName) || !Objects.equals(this.versionPrefix, versionPrefix) || !Objects.equals(this.minimalVersion, minimalVersion) || !Objects.equals(this.maximalVersion, maximalVersion);
        this.imageName = imageName;
        this.versionPrefix = versionPrefix;
        this.minimalVersion = minimalVersion;
        this.maximalVersion = maximalVersion;
        if (allImages != null) { //  only calls docker if has already been initialized. otherwise block on machine without docker installed
            if (allImages.isEmpty() || change) {
                refreshImageList();
            }
        }
        return this;
    }

    public DockerImageParameter addArchFilter() {
        if (Utils.isARM()) { // discard AMD / X86 tags
            addImageFilter( im -> {
                String tag = im.getTag().toLowerCase();
                return !tag.contains("amd") && !tag.contains("x86");
            });
        } else { // discard ARM / AARCH tags
            addImageFilter( im -> {
                String tag = im.getTag().toLowerCase();
                return !tag.contains("arm") && !tag.contains("aarch");
            });
        }
        return this;
    }

    public DockerImageParameter addImageFilter(Predicate<DockerImage> imageFilter) {
        if (imageFilter == null) return this;
        if (this.imageFilter == null) this.imageFilter = imageFilter;
        else this.imageFilter = this.imageFilter.and(imageFilter);
        return this;
    }

    public Stream<DockerImage> getAllImages() {
        if (allImages == null) refreshImageList();
        return allImages.stream();
    }

    public String getImageName() {
        return imageName;
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
        boolean notInit = allImages == null;
        if (allImages !=null) allImages.clear();
        else allImages = new ArrayList<>();
        if (imageFilter == null) imageFilter = im -> true;
        Stream.concat(dockerImageResources.stream(), installedImages.stream())
            .map(n -> new DockerImage(n, installedImages))
            .filter(imageName == null ? i->true : i -> i.imageName.equals(imageName))
            .filter(versionPrefix==null ? i -> true : i -> Objects.equals(versionPrefix, i.versionPrefix))
            .filter(minimalVersion == null ? i->true: i->i.compareVersionNumber(minimalVersion)>=0 )
            .filter(maximalVersion == null ? i->true: i->i.compareVersionNumber(maximalVersion)<=0 )
            .filter(imageFilter)
            .distinct()
            .forEach(allImages::add);
        Collections.sort(allImages);
        if (selectedItem != null) {
            int idx = allImages.indexOf(selectedItem);
            if (idx>=0) selectedItem = allImages.get(idx);
        } else if (notInit && !allImages.isEmpty() && !isAllowNoSelection()) {
            setValue(allImages.stream().filter(DockerImage::isInstalled).findFirst().orElse(allImages.get(0)));
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
        return selectedItem;
    }

    @Override
    public Object toJSONEntry() {
        if (selectedItem == null) return "";
        else return selectedItem.getTag();
    }


    public static class DockerImage implements Comparable<DockerImage> {
        String imageName, version, versionPrefix, fileName;
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
                    Pair<String, int[]> t = DockerGateway.parseVersion(tag);
                    versionPrefix = t.key;
                    versionNumber = t.value;
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

        public int compareVersionNumber(int[] version) {
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
