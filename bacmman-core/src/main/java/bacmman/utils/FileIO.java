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
package bacmman.utils;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.io.Reader;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.stream.Collectors;

/**
 *
 * @author Jean Ollion
 */
public class FileIO {
    public static final Logger logger = LoggerFactory.getLogger(FileIO.class);
    
    // random access file
    public static void write(RandomAccessFile raf, String write, boolean append) throws IOException  {
        //clearRAF(raf);
        if (append) {
            long l = raf.length();
            raf.seek(raf.length());
            if (l>0) raf.writeBytes("\n");
            raf.writeBytes(write);
        } else {
            raf.setLength(0);
            raf.writeBytes(write);
            raf.setLength(write.length());
        }
    }
    public static void clearRAF(RandomAccessFile raf) throws IOException {
        //long l = raf.length();
        raf.setLength(0);
        //raf.setLength(l);
    }
    
    
    public static <T> void writeToFile(String outputFile, Collection<T> objects, Function<T, String> converter) {
        try {
            java.io.FileWriter fstream;
            BufferedWriter out;
            File output = new File(outputFile);
            //output.delete();
            output.getParentFile().mkdirs();
            if (objects.isEmpty()) return;
            fstream = new java.io.FileWriter(output);
            out = new BufferedWriter(fstream);
            //out.flush();
            List<String> toWrite = objects.stream().parallel().map(converter).filter(Objects::nonNull).collect(Collectors.toList());
            if (toWrite.size()!=objects.size()) logger.error("#{} objects could not be converted", objects.size()-toWrite.size());
            Iterator<String> it = toWrite.iterator();
            out.write(it.next());
            while(it.hasNext()) {
                out.newLine();
                out.write(it.next());
            }
            out.close();
        } catch (IOException ex) {
            logger.debug("Error while writing list to file", ex);
        }
    }
    public static <T> T readFirstLineFromFile(String path, Function<String, T> converter) {
        TextFile tf = null;
        try {
            tf = new TextFile(path, false, false);
        } catch (IOException e) {
            return null;
        }
        String line = tf.readFirstLine();
        tf.close();
        return converter.apply(line);
    }
    public static <T> List<T> readFromFile(String path, Function<String, T> converter, Consumer<String> runWhenConversionError) {
        return readFromFile(path, converter, null, null, runWhenConversionError);
    }
    public static <T> List<T> readFromFile(String path, Function<String, T> converter, String[] headerContainer, Predicate<String> isHeader, Consumer<String> runWhenConversionError) {
        FileReader input = null;
        List<String> resS = new ArrayList<>();
        try {
            input = new FileReader(path);
            BufferedReader bufRead = new BufferedReader(input);
            String myLine = null;
            while ( (myLine = bufRead.readLine()) != null) resS.add(myLine);
        } catch (IOException ex) {
            logger.debug("an error occurred trying read file: {}, {}", path, ex);
        } finally {
            try {
                if (input!=null) input.close();
            } catch (IOException ex) { }
        }
        if (runWhenConversionError==null) return resS.stream().map(s -> {
            if (headerContainer!=null && headerContainer[0]==null && (isHeader == null || isHeader.test(s))) {
                headerContainer[0] = s;
                return null;
            } else return converter.apply(s);
        }).filter(Objects::nonNull).collect(Collectors.toList()); // throws error
        else {
            return resS.stream().map(s -> {
                if (headerContainer!=null && headerContainer[0]==null && (isHeader == null || isHeader.test(s))) {
                    headerContainer[0] = s;
                    return null;
                } else {
                    try {
                        return converter.apply(s);
                    } catch (Throwable e) {
                        runWhenConversionError.accept(s);
                        return null;
                    }
                }
            }).filter(Objects::nonNull).collect(Collectors.toList());
        }
    }
    
    public static <T> List<T> readFromZip(ZipFile file, String relativePath, Function<String, T> converter) {
        List<String> resS = new ArrayList<>();
        try {
            ZipEntry e = file.getEntry(relativePath);
            if (e!=null) {
                Reader r = new InputStreamReader(file.getInputStream(e));
                BufferedReader bufRead = new BufferedReader(r);
                String myLine = null;
                while ( (myLine = bufRead.readLine()) != null) resS.add(myLine);
            }
        } catch (IOException ex) {
            logger.debug("an error occured trying read file: "+relativePath, ex);
        }
        return resS.stream().parallel().map(converter).filter(Objects::nonNull).collect(Collectors.toList());
    }
    public static <T> T readFirstFromZip(ZipFile file, String relativePath, Function<String, T> converter) {
        try {
            ZipEntry e = file.getEntry(relativePath);
            if (e!=null) {
                Reader r = new InputStreamReader(file.getInputStream(e));
                BufferedReader bufRead = new BufferedReader(r);
                String myLine = bufRead.readLine();
                if (myLine!=null) return converter.apply(myLine);
            }
        } catch (IOException ex) {
            logger.debug("an error occured trying read file: {}, {}", relativePath, ex);
        }
        return null;
    }
    public static void writeFile(InputStream in, String filePath) {
        BufferedOutputStream bos = null;
        try {
            bos = new BufferedOutputStream(new FileOutputStream(filePath));
            byte[] bytesIn = new byte[4096];
            int read = 0;
            while ((read = in.read(bytesIn)) != -1) {
                bos.write(bytesIn, 0, read);
            }
        } catch (IOException ex) {
            logger.error("", ex);
        } finally {
            try {
                bos.close();
                in.close();
            } catch (IOException ex) {
                logger.error("", ex);
            }
        }
    }
    public static class TextFile {
        final File f;
        RandomAccessFile raf;
        java.nio.channels.FileLock lock;
        final boolean toLock;
        public TextFile(String file, boolean write, boolean lock) throws IOException {
            f = new File(file);
            if (!f.exists()) {
                if (!f.createNewFile()) throw new IOException("File do not exist and could not be created: "+file);
            }
            raf = new RandomAccessFile(f, write?"rw":"r");
            if (lock) lock();
            toLock = lock;
        }
        public boolean isValid() {
            return f.exists() && raf!=null && (!toLock || (lock!=null && lock.isValid()));
        }
        public void clear() {
            try {
                FileIO.clearRAF(raf);
            } catch (IOException ex) {
                
            }
        }
        public void write(String s, boolean append) {
            try {
                FileIO.write(raf, s, append);
            } catch (IOException ex) {
                
            }
        }
        public String readFirstLine() {
            try {
                raf.seek(0);
                return raf.readLine();
            } catch (IOException ex) {
                return null;
            }
        }
        public List<String> readLines() {
            List<String> res = new ArrayList<>();
            try {
                raf.seek(0);
                String l = raf.readLine();
                while(l!=null) {
                    res.add(l);
                    l = raf.readLine();
                }
            } catch (IOException ex) {

            }
            return res;
        }
        public String read() {
            return readLines().stream().collect(Collectors.joining("\n"));
        }
        public void close() {
            unlock();
            try {
                raf.close();
            } catch (IOException ex) {
                
            }
        }
        private void lock() {
            if (lock!=null) return;
            try {
                lock = raf.getChannel().tryLock();
            } catch (OverlappingFileLockException e) {
                //logger.debug("file already locked", e);
            } catch (IOException ex) {
                
            } 
        }
        public void unlock() {
            if (this.lock!=null) {
                try {
                    lock.release();
                    lock = null;
                } catch (IOException ex) {
                    
                }
            }
        }
        public boolean isEmpty() {
            try {
                return raf.length() == 0;
            } catch (IOException e) {
                return false;
            }
        }
        public boolean delete() {
            close();
            try {
                Files.delete(Paths.get(f.getAbsolutePath()));
                return true;
            } catch (IOException e) {
                return false;
            }
        }
        public File getFile() {
            return f;
        }
        public boolean locked() {
            return lock!=null && lock.isValid();
        }
    }
    public static class ZipWriter {
        File f;
        ZipOutputStream out;
        // append if existing
        //https://stackoverflow.com/questions/3048669/how-can-i-add-entries-to-an-existing-zip-file-in-java
        public ZipWriter(String path) {
            f = new File(path);
            try {
                out  = new ZipOutputStream(new FileOutputStream(f));
                out.setLevel(9);
            } catch (FileNotFoundException ex) {
                
            }
        }
        public boolean isValid() {return out!=null;}
        public <T> void write(String relativePath, List<T> objects, Function<T, String> converter) {
            try {
                ZipEntry e= new ZipEntry(relativePath);
                out.putNextEntry(e);
                List<byte[]> toWrite = objects.stream().parallel().map(o->converter.apply(o)).filter(o->o!=null).map(o->o.getBytes()).collect(Collectors.toList());
                if (toWrite.size()!=objects.size()) logger.error("#{} objects could not be converted", objects.size()-toWrite.size());
                for (byte[] b : toWrite) {
                    out.write(b);
                    out.write('\n');
                }
                out.closeEntry();
            } catch (IOException ex) {
                logger.debug("Error while writing list to file", ex);
            }    
        }
        public void appendFile(String relativePath, InputStream in) { 
            //relativePath=relativePath.replace('\\', '/'); // WARNING : path should be with "/" as separator. 
            try {
                ZipEntry e= new ZipEntry(relativePath);
                out.putNextEntry(e);
                byte[] buffer = new byte[4096];
                int length;
                while((length = in.read(buffer)) > 0) out.write(buffer, 0, length);
                out.closeEntry();
            } catch (IOException ex) {
                logger.error("", ex);
            } finally {
                try {
                    in.close();
                } catch (IOException ex) {
                    logger.error("", ex);
                }
            }
        }
        public void close() {
            if (out==null) return;
            try {
                out.close();
            } catch (IOException ex) {
                logger.error("error while closing zip file", ex);
            }
        }
    }
    public static class ZipReader {
        ZipFile in;
        public ZipReader(String path) {
            try {
                in = new ZipFile(path);
            } catch (IOException ex) {
                logger.error("error while reading zip", ex);
            } 
        }
        public boolean valid() {return in!=null;}
        public <T> List<T> readObjects(String relativePath, Function<String, T> converter) {
            return readFromZip(in, relativePath, converter);
        }
        public <T> T readFirstObject(String relativePath, Function<String, T> converter) {
            return readFirstFromZip(in, relativePath, converter);
        }
        public InputStream readFile(String relativePath) {
            try {
                ZipEntry e = in.getEntry(relativePath);
                if (e!=null) {
                    InputStream is = in.getInputStream(e);
                    return is;
                }
            } catch (IOException ex) {
                logger.debug("an error occured trying read file: {}, {}", relativePath, ex);
            }
            return null;
        }
        public void readFiles(String relativePathDir, String localDir) {
            List<String> files = listsubFiles(relativePathDir);
            for (String f : files) {
                try {
                    ZipEntry e = in.getEntry(f);
                    if (e!=null) {
                        InputStream is = in.getInputStream(e);
                        String fileName = new File(f).getName();
                        String outPath = localDir+"/"+fileName; // OS independent
                        writeFile(is, outPath);
                    }
                } catch (IOException ex) {
                    logger.debug("an error occured trying read file: {}, {}", f, ex);
                }
            }
        }
        public void close() {
            if (in==null) return;
            try {
                in.close();
            } catch (IOException ex) {
                logger.error("error while closing zip", ex);
            }
        }
        public List<String> listsubFiles(String relativeDir) {
            List<String> res = new ArrayList<>();
            Enumeration<? extends ZipEntry> entries = in.entries();
            int l = relativeDir.length();
            while(entries.hasMoreElements()){
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (name.startsWith(relativeDir) && name.length()>l) res.add(name);
            }
            return res;
        }
        public Set<String> listDirectories(Predicate<? super String> excludeDirectory) {
            Set<String> res = listDirectories();
            if (res.isEmpty()) return res;
            res.removeIf(excludeDirectory);
            return res;
        }
        public Set<String> listDirectories(String... excludeKeyWords) {
            Set<String> res = listDirectories();
            if (res.isEmpty()) return res;
            for (String k : excludeKeyWords) res.removeIf(s->s.contains(k));
            return res;
        }
        public Set<String> listRootDirectories() {
            Set<String> dirs = listDirectories();
            Set<String> res = new HashSet<>();
            for (String d : dirs) {
                File f = new File(d);
                while (f.getParentFile()!=null) f=f.getParentFile();
                res.add(f.toString());
            }
            return res;
        }
        public Set<String> listDirectories() {
            Set<String> res = new HashSet<>();
            Enumeration<? extends ZipEntry> entries = in.entries();
            while(entries.hasMoreElements()){
                ZipEntry entry = entries.nextElement();
                String s = new File(entry.getName()).getParent();
                if (s!=null) res.add(s);
            }
            return res;
        }
    }
}
