package bacmman.image.io;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.junit.Assert.*;

public class TiffTimestampsTest {
    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    /**
     * Writes a TIF file without pixel data: one page per element of tagValues, each page containing the given (tag, ascii value) pairs
     */
    File writeTif(ByteOrder order, Object[][] tagValuesPerPage) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(1 << 16).order(order);
        b.put(order == ByteOrder.LITTLE_ENDIAN ? new byte[]{'I', 'I'} : new byte[]{'M', 'M'});
        b.putShort((short) 42);
        int nextIFDPointer = 4;
        b.putInt(0);
        for (Object[] page : tagValuesPerPage) {
            int nTags = page.length / 2;
            // values are written before the IFD
            int[] valueOffsets = new int[nTags];
            byte[][] values = new byte[nTags][];
            for (int i = 0; i < nTags; ++i) {
                values[i] = (page[2 * i + 1] + "\0").getBytes(StandardCharsets.UTF_8);
                valueOffsets[i] = b.position();
                b.put(values[i]);
            }
            if (b.position() % 2 == 1) b.put((byte) 0);
            int ifd = b.position();
            b.putInt(nextIFDPointer, ifd);
            b.putShort((short) nTags);
            for (int i = 0; i < nTags; ++i) {
                b.putShort((short) (int) (Integer) page[2 * i]);
                b.putShort((short) 2);
                b.putInt(values[i].length);
                b.putInt(valueOffsets[i]);
            }
            nextIFDPointer = b.position();
            b.putInt(0);
        }
        File f = folder.newFile();
        Files.write(f.toPath(), java.util.Arrays.copyOf(b.array(), b.position()));
        return f;
    }

    static String metamorph(String time) {
        return "<MetaData>\n<PlaneInfo>\n<prop id=\"acquisition-time-local\" type=\"time\" value=\"" + time + "\"/>\n</PlaneInfo>\n</MetaData>";
    }

    @Test
    public void metamorph() throws IOException {
        File f = writeTif(ByteOrder.LITTLE_ENDIAN, new Object[][]{
                {270, metamorph("20261015 15:39:35.183"), 306, "20261015 15:39:35.183"},
                {270, metamorph("20261015 15:44:36.296")}
        });
        double[] t = TiffTimestamps.read(f.getPath());
        assertNotNull(t);
        assertEquals(2, t.length);
        assertEquals(LocalDateTime.of(2026, 10, 15, 15, 39, 35, 183_000_000).toInstant(ZoneOffset.UTC).toEpochMilli(), t[0], 0);
        assertEquals(301113, t[1] - t[0], 0);
    }

    @Test
    public void microManagerAbsoluteTime() throws IOException {
        File f = writeTif(ByteOrder.BIG_ENDIAN, new Object[][]{
                {51123, "{\"ElapsedTime-ms\": 0.0, \"ReceivedTime\": \"2019-03-12 16:48:24.718 +0100\"}"},
                {51123, "{\"ElapsedTime-ms\": 1500.0, \"ReceivedTime\": \"2019-03-12 16:48:26.218 +0100\"}"}
        });
        double[] t = TiffTimestamps.read(f.getPath());
        assertNotNull(t);
        assertEquals(OffsetDateTime.of(2019, 3, 12, 16, 48, 24, 718_000_000, ZoneOffset.ofHours(1)).toInstant().toEpochMilli(), t[0], 0);
        assertEquals(1500, t[1] - t[0], 0);
    }

    @Test
    public void microManagerElapsedTime() throws IOException {
        File f = writeTif(ByteOrder.LITTLE_ENDIAN, new Object[][]{
                {270, "ImageJ=1.54\nimages=2", 51123, "{\"ElapsedTime-ms\": \"120.5\"}"},
                {51123, "{\"ElapsedTime-ms\": \"620.5\"}"}
        });
        assertArrayEquals(new double[]{120.5, 620.5}, TiffTimestamps.read(f.getPath()), 0);
    }

    @Test
    public void onlyLeadingPagesWithTime() throws IOException {
        File f = writeTif(ByteOrder.LITTLE_ENDIAN, new Object[][]{
                {270, metamorph("20261015 15:39:35")},
                {270, "no time"}
        });
        assertEquals(1, TiffTimestamps.read(f.getPath()).length);
    }

    @Test
    public void unsupported() throws IOException {
        File noTime = writeTif(ByteOrder.LITTLE_ENDIAN, new Object[][]{{270, "ImageJ=1.54\nfinterval=300"}});
        assertNull(TiffTimestamps.read(noTime.getPath()));
        File notTif = folder.newFile();
        Files.write(notTif.toPath(), "not a tif file".getBytes(StandardCharsets.UTF_8));
        assertNull(TiffTimestamps.read(notTif.getPath()));
        assertNull(TiffTimestamps.read(new File(folder.getRoot(), "missing.tif").getPath()));
    }
}
