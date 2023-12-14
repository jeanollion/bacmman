package bacmman.utils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterOutputStream;

public class CompressionUtils {
    @SuppressWarnings("deprecation")
    public static String decompressToString(byte[] compressedTxt, boolean isASCII) throws IOException {
        byte[] decompressedBArray = decompress(compressedTxt);
        if (isASCII) return new String(decompressedBArray, 0); // supposes that chars are encoded only on 1st byte
        else return new String(decompressedBArray, StandardCharsets.UTF_8);
    }
    public static byte[] decompress(byte[] compressedTxt) throws IOException {
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        try (OutputStream ios = new InflaterOutputStream(os)) {
            ios.write(compressedTxt);
        }
        return os.toByteArray();
    }

    @SuppressWarnings("deprecation")
    public static byte[] compress(String text, boolean isASCII) throws IOException {
        byte[] bytes;
        if (isASCII) {
            bytes = new byte[text.length()];
            text.getBytes(0, bytes.length, bytes, 0); // supposes that chars are encoded only on 1st byte
        } else {
            bytes = text.getBytes(StandardCharsets.UTF_8);
        }
        return compress(bytes);
    }

    public static byte[] compress(byte[] bArray) throws IOException {
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        try (DeflaterOutputStream dos = new DeflaterOutputStream(os)) {
            dos.write(bArray);
        }
        return os.toByteArray();
    }

    private static byte[] getBytesUTF8(String str) {
        final char buffer[] = new char[str.length()];
        final int length = str.length();
        str.getChars(0, length, buffer, 0);
        final byte b[] = new byte[length];
        for (int j = 0; j < length; j++)
            b[j] = (byte) buffer[j];
        return b;
    }

    private static byte[] getBytesUTF16LE(String str) {
        final int length = str.length();
        final char buffer[] = new char[length];
        str.getChars(0, length, buffer, 0);
        final byte b[] = new byte[length*2];
        for (int j = 0; j < length; j++) {
            b[j*2] = (byte) (buffer[j] & 0xFF);
            b[j*2+1] = (byte) (buffer[j] >> 8);
        }
        return b;
    }

}
