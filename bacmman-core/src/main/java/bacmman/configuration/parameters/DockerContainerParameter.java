package bacmman.configuration.parameters;

import bacmman.core.Core;
import bacmman.core.DockerGateway;
import bacmman.utils.Utils;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class DockerContainerParameter extends AbstractChoiceParameter<DockerGateway.DockerContainer, DockerContainerParameter> {
    protected List<DockerGateway.DockerContainer> allContainers =null;
    int[] minimalVersion, maximalVersion;
    String imageName, versionPrefix;
    Predicate<DockerGateway.DockerContainer> filter;
    DockerImageParameter imageParameter;
    Consumer<DockerImageParameter> imageParameterListener;
    public DockerContainerParameter(String name) {
        super(name, null, null, DockerGateway.DockerContainer::toString, false);
        setMapper(s->{
            String id = DockerGateway.DockerContainer.parseId(s);
            if (id == null) return null;
            if (allContainers == null) refreshContainerList();
            return allContainers.stream().filter(i -> i.getId().equals(id)).findFirst().orElse(null);
        });
    }

    public DockerContainerParameter setImageParameter(DockerImageParameter imageParameter) {
        if (this.imageParameter != null) {
            this.imageParameter.removeListener(imageParameterListener);
            imageParameterListener = null;
        }
        this.imageParameter=imageParameter;
        if (this.imageParameter != null) {
            imageParameterListener = im -> refreshContainerList();
            this.imageParameter.addListener(imageParameterListener);
        }
        return this;
    }

    public DockerContainerParameter setImageRequirement(String imageName, String versionPrefix, int[] minimalVersion, int[] maximalVersion) {
        boolean change = !Objects.equals(this.imageName, imageName) || !Objects.equals(this.versionPrefix, versionPrefix) || !Objects.equals(this.minimalVersion, minimalVersion) || !Objects.equals(this.maximalVersion, maximalVersion);
        this.imageName = imageName;
        this.versionPrefix = versionPrefix;
        this.minimalVersion = minimalVersion;
        this.maximalVersion = maximalVersion;
        if (allContainers != null) { //  only calls docker if has already been initialized. otherwise block on machine without docker installed
            if (allContainers.isEmpty() || change) {
                refreshContainerList();
            }
        }
        return this;
    }

    public DockerContainerParameter addArchFilter() {
        if (Utils.isARM()) { // discard AMD / X86 tags
            addImageFilter( im -> {
                String tag = im.getImage().getTag().toLowerCase();
                return !tag.contains("amd") && !tag.contains("x86");
            });
        } else { // discard ARM / AARCH tags
            addImageFilter( im -> {
                String tag = im.getImage().getTag().toLowerCase();
                return !tag.contains("arm") && !tag.contains("aarch");
            });
        }
        return this;
    }

    public DockerContainerParameter addImageFilter(Predicate<DockerGateway.DockerContainer> filter) {
        if (filter == null) return this;
        if (this.filter == null) this.filter = filter;
        else this.filter = this.filter.and(filter);
        return this;
    }

    public Stream<DockerGateway.DockerContainer> getAllContainers() {
        if (allContainers == null) refreshContainerList();
        return allContainers.stream();
    }

    public String getImageName() {
        return imageName;
    }

    @Override
    public String[] getChoiceList() {
        refreshContainerList();
        return allContainers.stream().map(DockerGateway.DockerContainer::toString).toArray(String[]::new);
    }

    public void refreshContainerList() {
        DockerGateway gateway = Core.getCore().getDockerGateway();
        boolean notInit = allContainers == null;
        if (allContainers !=null) allContainers.clear();
        else allContainers = new ArrayList<>();
        if (filter == null) filter = im -> true;
        if (gateway != null) {
            gateway.listContainers()
                    .filter(imageParameter == null ? i -> true : i -> imageParameter.getValue() == null || imageParameter.getValue().getTag().equals(i.getImage().getTag()))
                    .filter(imageName == null ? i -> true : i -> i.getImage().getImageName().equals(imageName))
                    .filter(versionPrefix == null ? i -> true : i -> Objects.equals(versionPrefix, i.getImage().getVersionPrefix()))
                    .filter(minimalVersion == null ? i -> true : i -> i.getImage().compareVersionNumber(minimalVersion) >= 0)
                    .filter(maximalVersion == null ? i -> true : i -> i.getImage().compareVersionNumber(maximalVersion) <= 0)
                    .filter(filter)
                    .distinct()
                    .forEach(allContainers::add);
            allContainers.sort(Comparator.comparing(DockerGateway.DockerContainer::getImage));
            if (selectedItem != null) {
                DockerGateway.DockerContainer sel = allContainers.stream().filter(c -> c.getId().equals(selectedItem.getId())).findFirst().orElse(null);
                if (sel != null) selectedItem = sel; // simply replace instance
                else setValue(null);
            } else if (notInit && !allContainers.isEmpty() && !isAllowNoSelection()) {
                setValue(allContainers.stream().filter(DockerGateway.DockerContainer::isRunning).findFirst().orElse(allContainers.get(0)));
            }
        }
    }

    public DockerGateway.DockerContainer getContainer(String containerId) {
        refreshContainerList();
        return allContainers.stream().filter(c -> c.getId().equals(containerId)).findFirst().orElse(null);
    }

    public void setContainer(String containerId) {
        this.setValue(getContainer(containerId));
    }

    public String getSelectedContainerId() {
        DockerGateway.DockerContainer c = getValue();
        if (c == null) return null;
        else return c.getId();
    }

    @Override
    public void setSelectedItem(String value) {
        setValue(mapper.apply(value));
    }

    @Override
    public void setValue(DockerGateway.DockerContainer value) {
        selectedItem = value;
        fireListeners();
        setCondValue();
    }

    @Override
    public DockerGateway.DockerContainer getValue() {
        return selectedItem;
    }

    @Override
    public Object toJSONEntry() {
        if (selectedItem == null) return "";
        else return selectedItem.getId();
    }

}
