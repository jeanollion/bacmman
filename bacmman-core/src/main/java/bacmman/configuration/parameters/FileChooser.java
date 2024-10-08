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
package bacmman.configuration.parameters;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import javax.swing.JFileChooser;

import bacmman.configuration.experiment.Experiment;
import net.imagej.ops.Ops;
import org.json.simple.JSONArray;
import bacmman.utils.JSONUtils;
import bacmman.utils.Utils;

/**
 *
 * @author Jean Ollion
 */
public class FileChooser extends ParameterImpl<FileChooser> implements Listenable<FileChooser> {
    protected String[] selectedFiles=new String[0];
    boolean allowNoSelection = true;
    protected FileChooserOption option = FileChooserOption.DIRECTORIES_ONLY;
    boolean relativePath = true, mustExist=true;
    Predicate<String> validPath;
    Path refPath;
    Supplier<Path> getRefPathFunction;
    public FileChooser(String name) {this(name, FileChooserOption.FILE_OR_DIRECTORY, true);}
    public FileChooser(String name, FileChooserOption option) {
        this(name, option, true);
    }
    public FileChooser(String name, FileChooserOption option, boolean allowNoSelection) {
        super(name);
        this.option=option;
        this.allowNoSelection=allowNoSelection;
        setGetRefPathFunction( p -> {
            Experiment xp = ParameterUtils.getExperiment(p);
            if (xp==null) return null;
            else return xp.getPath();
        });
    }
    public FileChooser setOption(FileChooserOption option) {
        this.option = option;
        return this;
    }
    public FileChooser setPathValidation(Predicate<String> validPath) {
        this.validPath = validPath;
        return this;
    }
    public FileChooser setAllowNoSelection(boolean allowNoSelection) {
        this.allowNoSelection = allowNoSelection;
        return this;
    }
    public boolean isAllowNoSelection() {
        return allowNoSelection;
    }
    public FileChooser setRelativePath(boolean relativePath) {
        if (this.relativePath==relativePath) return this;
        if (selectedFiles==null || selectedFiles.length==0) {
            this.relativePath=relativePath;
            return this;
        }
        Path refPath = this.refPath!=null ? this.refPath : (getRefPathFunction !=null ? getRefPathFunction.get() : ParameterUtils.getExperiment(this).getPath());
        if (refPath==null) throw new RuntimeException("Cannot change relative state, no path detected");
        if (this.relativePath) selectedFiles = toAbsolutePath(refPath, selectedFiles);
        else selectedFiles = toRelativePath(refPath, selectedFiles);
        this.relativePath=relativePath;
        return this;
    }
    public boolean isRelativePath() {return this.relativePath;}

    public FileChooser setRefPath(Path refPath) {
        String[] sel = getSelectedFilePath();
        this.refPath = refPath;
        setSelectedFilePath(sel);
        return this;
    }
    public FileChooser mustExist(boolean mustExist) {
        this.mustExist=mustExist;
        return this;
    }
    public String[] getSelectedFilePath() {
        if (selectedFiles == null || selectedFiles.length==0) return new String[0];
        if (relativePath) {
            Path refPath = getRefPath();
            if (refPath != null) return toAbsolutePath(refPath, selectedFiles);
        }
        return Arrays.copyOf(selectedFiles, selectedFiles.length);
    }
    
    public String getFirstSelectedFilePath() {
        if (selectedFiles==null || selectedFiles.length==0) return null;
        if (relativePath) {
            Path refPath = getRefPath();
            if (refPath!=null) return toAbsolutePath(refPath, selectedFiles[0]);
        }
        return selectedFiles[0];
    }
    
    public FileChooser setSelectedFilePath(String... filePath) {
        String[] currentPath = getSelectedFilePath();
        if (filePath==null) selectedFiles = new String[0];
        else selectedFiles=Arrays.stream(filePath).filter(Objects::nonNull).toArray(String[]::new);
        Path refPath = getRefPath();
        if (refPath!=null) {
            for (int i = 0; i < selectedFiles.length; ++i) {
                boolean abs = Paths.get(selectedFiles[i]).isAbsolute();
                if (abs && this.relativePath) selectedFiles[i] = toRelativePath(refPath, selectedFiles[i]);
                else if (!abs && !this.relativePath) selectedFiles[i] = toAbsolutePath(refPath, selectedFiles[i]);
            }
        }
        String[] newPath = getSelectedFilePath();
        if (!Arrays.equals(newPath, currentPath)) fireListeners();
        return this;
    }
    public FileChooser setSelectedFiles(File... filePath) {
        if (filePath==null || filePath.length==0) {
            logger.debug("{} set selected file: {}", name, filePath);
            setSelectedFilePath();
        }
        else {
            setSelectedFilePath(Arrays.stream(filePath).map(File::toString).toArray(String[]::new));
        }
        return this;
    }
    @Override 
    public boolean isValid() {
        if (!super.isValid()) return false;
        if ((selectedFiles==null || selectedFiles.length==0) && !allowNoSelection) return false;
        else if (selectedFiles!=null && selectedFiles.length>0 && mustExist) { // check that all files exist
            if (relativePath) {
                Path ref = getRefPath();
                if (ref!=null) {
                    return Arrays.stream(toAbsolutePath(getRefPath(), selectedFiles)).noneMatch(f -> !Files.exists(Paths.get(f)) || (validPath != null && !validPath.test(f)));
                }
            } else return Arrays.stream(selectedFiles).noneMatch(f -> !Files.exists(Paths.get(f)) || (validPath != null && !validPath.test(f)));
        }
        return true;
    }

    @Override
    public boolean sameContent(Parameter other) {
        if (other instanceof FileChooser) {
            FileChooser otherFC = (FileChooser)other;
            if (otherFC.selectedFiles==null && selectedFiles==null) return true;
            else if ((otherFC.selectedFiles==null) != (selectedFiles==null)) return false;
            if (otherFC.selectedFiles.length==selectedFiles.length) {
                if (selectedFiles.length==0) return true;
                boolean convert = this.relativePath != otherFC.relativePath;
                Path refPath = convert ? getRefPath() : null;
                Path otherRefPath = null;
                boolean convertThis = convert && refPath!=null;
                boolean convertOther = false;
                if (convert && refPath==null) {
                    otherRefPath = otherFC.getRefPath();
                    if (otherRefPath==null) throw new IllegalArgumentException("cannot compare two file chooser, one of them in relative state without reference path");
                    convertOther = true;
                }
                String[] compareThis = convertThis ? (this.relativePath ? toAbsolutePath(refPath, selectedFiles) : toRelativePath(refPath, selectedFiles)) : selectedFiles;
                String[] compareOther = convertOther ? (otherFC.relativePath ? toAbsolutePath(otherRefPath, otherFC.selectedFiles): toRelativePath(otherRefPath, otherFC.selectedFiles)) : otherFC.selectedFiles;
                for (int i =0; i<selectedFiles.length; i++) {
                    if ((compareThis[i] ==null && compareOther[i]!=null) || ( compareThis[i] !=null && !compareThis[i].equals(compareOther[i]))) {
                        logger.trace("FileChoose {}!={}: difference in selected files : {} vs {}", this, other, selectedFiles, ((FileChooser)other).selectedFiles);
                        return false;
                    }
                }
                return true;
            } else {
                logger.trace("FileChoose {}!={}: # of files : {} vs {}", this, other, selectedFiles.length, ((FileChooser)other).selectedFiles.length);
                return false;
            }
        } else return false;
    }
    @Override
    public void setContentFrom(Parameter other) {
        if (other instanceof FileChooser) {
            FileChooser otherFC = (FileChooser)other;
            if (this.relativePath!=otherFC.relativePath) {
                if (this.relativePath) selectedFiles = toRelativePath(otherFC.getRefPath(), otherFC.selectedFiles);
                else selectedFiles = toAbsolutePath(otherFC.getRefPath(), otherFC.selectedFiles);
            } else this.selectedFiles=Arrays.copyOf(((FileChooser)other).selectedFiles, ((FileChooser)other).selectedFiles.length);
        } else throw new IllegalArgumentException("wrong parameter type");
    }
    
    @Override public String toString() {
        return name+": "+Utils.getStringArrayAsString(selectedFiles);
    }
    
    public FileChooserOption getOption() {return option;}
    
    @Override public FileChooser duplicate() {
        FileChooser res = new FileChooser(name, option, allowNoSelection).setRelativePath(relativePath);
        res.selectedFiles = Arrays.copyOf(selectedFiles, selectedFiles.length);
        res.setRefPathFunction(getRefPathFunction);
        res.setRefPath(refPath);
        res.setListeners(listeners);
        res.addValidationFunction(additionalValidation);
        res.setHint(toolTipText);
        res.setSimpleHint(toolTipTextSimple);
        res.setEmphasized(isEmphasized);
        return res;
    }

    @Override
    public Object toJSONEntry() {
        if (selectedFiles==null) selectedFiles=new String[0];
        return JSONUtils.toJSONArray(selectedFiles);
    }

    @Override
    public void initFromJSONEntry(Object jsonEntry) {
        if (jsonEntry instanceof String) {
            selectedFiles = new String[]{(String)jsonEntry};
        } else if (jsonEntry instanceof JSONArray) {
            selectedFiles = JSONUtils.fromStringArray((JSONArray) jsonEntry);
        } else selectedFiles = new String[0];
        Utils.transformInPlace(selectedFiles, FileChooser::fixWindowsPath);
        // check absolute...
        if (selectedFiles.length>0) {
            boolean rel = !currentPathsAreAbsolute();
            if (this.relativePath != rel) {
                if (this.relativePath) selectedFiles = toRelativePath(getRefPath(), selectedFiles);
                else selectedFiles = toAbsolutePath(getRefPath(), selectedFiles);
            }
        }
    }
    protected static String[] toRelativePath(Path refPath, String[] files) {
        if (refPath == null) return null;
        if (files ==null) return null;
        if (files.length==0) return new String[0];
        return Arrays.stream(files).map(p -> toRelativePath(refPath, p)).toArray(String[]::new);
    }
    protected static String toRelativePath(Path ref, String toConvert) {
        try {
            return ref.relativize(Paths.get(toConvert)).toString();
        } catch(Exception e) {
            logger.error("toRelativePath error: ref: {}, toConvert: {}", ref, toConvert);
            throw e;
        }

    }
    protected static String fixWindowsPath(String path) {
        if (File.separatorChar=='\\') { // current system is windows convert path from Unix to windows
            return path.replace('/', File.separatorChar);
        } else { // current system is Unix: convert path from Windows to Unix
            return path.replace('\\', File.separatorChar);
        }
    }
    protected  static String toAbsolutePath(Path ref, String toConvert) {
        return ref.resolve(Paths.get(toConvert)).normalize().toFile().getAbsolutePath();
    }
    public Path getRefPath() {
        if (refPath !=null) return refPath;
        else if (getRefPathFunction != null) return getRefPathFunction.get();
        else return null;
    }
    public FileChooser setRefPathFunction(Supplier<Path> refPathFun) {
        this.getRefPathFunction = refPathFun;
        return this;
    }

    public FileChooser setGetRefPathFunction(Function<Parameter, Path> getRefPathFunction) {
        this.getRefPathFunction = () -> getRefPathFunction.apply(this);
        return this;
    }
    protected static String[] toAbsolutePath(Path referencePath, String[] files) {
        if (referencePath == null) return null;
        if (files ==null) return null;
        if (files.length==0) return new String[0];
        return Arrays.stream(files).map(p -> {
            String pp = toAbsolutePath(referencePath, p);
            if (pp==null) return p;
            else return pp;
        }).toArray(String[]::new);
    }
    protected boolean currentPathsAreAbsolute() {
        if (selectedFiles==null || selectedFiles.length==0) return false;
        boolean abs = Paths.get(selectedFiles[0]).isAbsolute();
        if (selectedFiles.length>1) {
            boolean anyDif = IntStream.range(1, selectedFiles.length).mapToObj(i -> selectedFiles[i]).anyMatch(f -> Paths.get(f).isAbsolute()!=abs);
            if (anyDif) throw new IllegalArgumentException("Some paths are relative other absolute");
            return abs;
        } else return abs;
    }
    
    public enum FileChooserOption {
        DIRECTORIES_ONLY(JFileChooser.DIRECTORIES_ONLY, false), 
        FILES_ONLY(JFileChooser.FILES_ONLY, true),
        FILE_ONLY(JFileChooser.FILES_ONLY, false),
        FILES_AND_DIRECTORIES(JFileChooser.FILES_AND_DIRECTORIES, true),
        FILE_OR_DIRECTORY(JFileChooser.FILES_AND_DIRECTORIES, false);
        private final int option;
        private final boolean multipleSelection;
        FileChooserOption(int option, boolean multipleSelection) {
            this.option=option;
            this.multipleSelection=multipleSelection;
        }
        public int getOption() {return option;}
        public boolean getMultipleSelectionEnabled(){return multipleSelection;}
    }

    public static Predicate<String> fileExists = p -> {
        if (p==null) return false;
        File f = new File(p);
        return f.isFile();
    };
    public static Predicate<String> dirExists = p -> {
        if (p==null) return false;
        File f = new File(p);
        return f.isDirectory();
    };
    public static Predicate<String> exists = p -> {
        if (p==null) return false;
        File f = new File(p);
        return f.exists();
    };
}
