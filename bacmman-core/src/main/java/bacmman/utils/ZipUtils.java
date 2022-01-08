package bacmman.utils;

import java.io.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public class ZipUtils {


    public static void zipFile(File fileToZip, File destination) throws IOException {
        zipFile(fileToZip, destination, false);
    }
    public static void zipFile(File fileToZip, File destination, boolean includeHiddenFiles) throws IOException {
        FileOutputStream fos = new FileOutputStream(destination);
        ZipOutputStream zipOut = new ZipOutputStream(fos);
        if (fileToZip.isDirectory()) {
            File[] children = fileToZip.listFiles();
            for (File childFile : children) {
                zipFile(childFile, childFile.getName(), zipOut, includeHiddenFiles);
            }
        } else zipFile(fileToZip, fileToZip.getName(), zipOut, includeHiddenFiles);
        zipOut.close();
        fos.close();
    }
    public static ByteArrayInOutStream zipFile(File fileToZip) throws IOException {
        return zipFile(fileToZip, false);
    }

    public static ByteArrayInOutStream zipFile(File fileToZip, boolean includeHiddenFiles) throws IOException {
        ByteArrayInOutStream baos = new ByteArrayInOutStream();
        ZipOutputStream zipOut = new ZipOutputStream(baos);
        if (fileToZip.isDirectory()) {
            File[] children = fileToZip.listFiles();
            for (File childFile : children) {
                zipFile(childFile, childFile.getName(), zipOut, includeHiddenFiles);
            }
        } else zipFile(fileToZip, fileToZip.getName(), zipOut, includeHiddenFiles);
        zipOut.close();
        return baos;
    }
    protected static void zipFile(File fileToZip, String fileName, ZipOutputStream zipOut, boolean includeHiddenFiles) throws IOException {
        if (!includeHiddenFiles && fileToZip.isHidden()) return;
        if (fileToZip.isDirectory()) {
            if (fileName.endsWith("/")) {
                zipOut.putNextEntry(new ZipEntry(fileName));
                zipOut.closeEntry();
            } else {
                zipOut.putNextEntry(new ZipEntry(fileName + "/"));
                zipOut.closeEntry();
            }
            File[] children = fileToZip.listFiles();
            for (File childFile : children) {
                zipFile(childFile, fileName + "/" + childFile.getName(), zipOut, includeHiddenFiles);
            }
            return;
        }
        FileInputStream fis = new FileInputStream(fileToZip);
        ZipEntry zipEntry = new ZipEntry(fileName);
        zipOut.putNextEntry(zipEntry);
        byte[] bytes = new byte[1024];
        int length;
        while ((length = fis.read(bytes)) >= 0) {
            zipOut.write(bytes, 0, length);
        }
        fis.close();
    }

    public static void unzipFile(File sourceFile, File destinationDirectory) throws IOException {
        assert destinationDirectory.isDirectory();
        assert !sourceFile.equals(destinationDirectory);
        byte[] buffer = new byte[1024];
        ZipInputStream zis = new ZipInputStream(new FileInputStream(sourceFile));
        ZipEntry zipEntry = zis.getNextEntry();
        while (zipEntry != null) {
            File newFile = newFile(destinationDirectory, zipEntry);
            if (zipEntry.isDirectory()) {
                if (!newFile.isDirectory() && !newFile.mkdirs()) {
                    throw new IOException("Failed to create directory " + newFile);
                }
            } else {
                // fix for Windows-created archives
                File parent = newFile.getParentFile();
                if (!parent.isDirectory() && !parent.mkdirs()) {
                    throw new IOException("Failed to create directory " + parent);
                }

                // write file content
                FileOutputStream fos = new FileOutputStream(newFile);
                int len;
                while ((len = zis.read(buffer)) > 0) {
                    fos.write(buffer, 0, len);
                }
                fos.close();
            }
            zipEntry = zis.getNextEntry();
        }
        zis.closeEntry();
        zis.close();
    }

    protected static File newFile(File destinationDir, ZipEntry zipEntry) throws IOException {
        File destFile = new File(destinationDir, zipEntry.getName());
        String destDirPath = destinationDir.getCanonicalPath();
        String destFilePath = destFile.getCanonicalPath();
        if (!destFilePath.startsWith(destDirPath + File.separator)) {
            throw new IOException("Entry is outside of the target dir: " + zipEntry.getName());
        }
        return destFile;
    }

    public static class ByteArrayInOutStream extends ByteArrayOutputStream {
        /**
         * Creates a new ByteArrayInOutStream. The buffer capacity is
         * initially 32 bytes, though its size increases if necessary.
         */
        public ByteArrayInOutStream() {
            super();
        }

        /**
         * Creates a new ByteArrayInOutStream, with a buffer capacity of
         * the specified size, in bytes.
         *
         * @param   size   the initial size.
         * @exception  IllegalArgumentException if size is negative.
         */
        public ByteArrayInOutStream(int size) {
            super(size);
        }

        /**
         * Creates a new ByteArrayInputStream that uses the internal byte array buffer
         * of this ByteArrayInOutStream instance as its buffer array. The initial value
         * of pos is set to zero and the initial value of count is the number of bytes
         * that can be read from the byte array. The buffer array is not copied. This
         * instance of ByteArrayInOutStream can not be used anymore after calling this
         * method.
         * @return the ByteArrayInputStream instance
         */
        public ByteArrayInputStream getInputStream() {
            // create new ByteArrayInputStream that respects the current count
            ByteArrayInputStream in = new ByteArrayInputStream(this.buf, 0, this.count);

            // set the buffer of the ByteArrayOutputStream
            // to null so it can't be altered anymore
            this.buf = null;

            return in;
        }
    }
}
