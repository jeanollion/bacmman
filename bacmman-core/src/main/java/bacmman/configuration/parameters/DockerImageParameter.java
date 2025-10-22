package bacmman.configuration.parameters;

import bacmman.core.DockerGateway;
import bacmman.core.Core;
import bacmman.utils.Utils;

import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class DockerImageParameter extends AbstractChoiceParameter<DockerGateway.DockerImage, DockerImageParameter> {
    protected List<DockerGateway.DockerImage> allImages=null;
    int[] minimalVersion, maximalVersion;
    String imageName, versionPrefix;
    Predicate<DockerGateway.DockerImage> imageFilter;
    final List<String> dockerImageFileNames;
    public DockerImageParameter(String name) {
        super(name, null, null, DockerGateway.DockerImage::toString, false);
        setMapper(s->{
            String tag = DockerGateway.DockerImage.parseTagString(s);
            if (tag == null) return null;
            if (allImages == null) refreshImageList();
            return allImages.stream().filter(i -> i.getTag().equals(tag)).findFirst().orElse(null);
        });
        dockerImageFileNames = Utils.getResourcesForPath("dockerfiles/").collect(Collectors.toList());
    }

    public void selectLatestImageIfNoSelection() {
        if (getValue() == null) {
            DockerGateway.DockerImage im = getAllImages().sorted(Comparator.reverseOrder()).findFirst().orElse(null);
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

    public DockerImageParameter addImageFilter(Predicate<DockerGateway.DockerImage> imageFilter) {
        if (imageFilter == null) return this;
        if (this.imageFilter == null) this.imageFilter = imageFilter;
        else this.imageFilter = this.imageFilter.and(imageFilter);
        return this;
    }

    public Stream<DockerGateway.DockerImage> getAllImages() {
        if (allImages == null) refreshImageList();
        return allImages.stream();
    }

    public String getImageName() {
        return imageName;
    }

    @Override
    public String[] getChoiceList() {
        refreshImageList();
        return allImages.stream().map(DockerGateway.DockerImage::toString).toArray(String[]::new);
    }

    public String getDockerFileForTag(String tag) {
        return dockerImageFileNames.stream().filter(fn -> DockerGateway.formatDockerTag(fn).equals(tag)).findFirst().orElse(null);
    }

    public void refreshImageList() {
        DockerGateway gateway = Core.getCore().getDockerGateway();
        List<String[]> installedImages;
        if (gateway==null) installedImages = Collections.EMPTY_LIST;
        else installedImages = gateway.listImages().collect(Collectors.toList());
        boolean notInit = allImages == null;
        if (allImages !=null) allImages.clear();
        else allImages = new ArrayList<>();
        if (imageFilter == null) imageFilter = im -> true;
        Stream<DockerGateway.DockerImage> installedIms = installedImages.stream().map(s -> new DockerGateway.DockerImage(s[0], s[1], getDockerFileForTag(s[0])));
        Set<String> installedImTags = installedImages.stream().map(s->s[0]).collect(Collectors.toSet());
        Stream<DockerGateway.DockerImage> ressourceIms = dockerImageFileNames.stream().map(fn -> new String[]{fn, DockerGateway.formatDockerTag(fn)})
                .filter(s -> !installedImTags.contains(s[0])).map(s -> new DockerGateway.DockerImage(s[1], null, s[0]));
        Stream.concat(installedIms, ressourceIms)
            .filter(imageName == null ? i->true : i -> i.getImageName().equals(imageName))
            .filter(versionPrefix==null ? i -> true : i -> Objects.equals(versionPrefix, i.getVersionPrefix()))
            .filter(minimalVersion == null ? i->true: i->i.compareVersionNumber(minimalVersion)>=0 )
            .filter(maximalVersion == null ? i->true: i->i.compareVersionNumber(maximalVersion)<=0 )
            .filter(imageFilter)
            .distinct()
            .forEach(allImages::add);
        Collections.sort(allImages);
        if (selectedItem != null) {
            DockerGateway.DockerImage sel = allImages.stream().filter(c -> c.getTag().equals(selectedItem.getTag())).findFirst().orElse(null);
            if (sel != null) selectedItem = sel; // simply replace instance
            else setValue(null);
        } else if (notInit && !allImages.isEmpty() && !isAllowNoSelection()) {
            setValue(allImages.stream().filter(DockerGateway.DockerImage::isInstalled).findFirst().orElse(allImages.get(0)));
        }
    }

    @Override
    public void setSelectedItem(String value) {
        setValue(mapper.apply(value));
    }

    @Override
    public void setValue(DockerGateway.DockerImage value) {
        selectedItem = value;
        fireListeners();
        setCondValue();
    }

    @Override
    public DockerGateway.DockerImage getValue() {
        return selectedItem;
    }

    @Override
    public Object toJSONEntry() {
        if (selectedItem == null) return "";
        else return selectedItem.getTag();
    }

}
