package bacmman.image.io;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fast reading of plane acquisition times written in TIF tags by acquisition software, without creating a Bio-Formats reader.
 * There is no standard tag for plane acquisition time, so each software needs its own parser. Supported:
 * <ul>
 *     <li>MetaMorph / MetaSeries: "acquisition-time-local" property of the XML image description (tag 270)</li>
 *     <li>Micro-Manager: "ReceivedTime" or "Time" (absolute) or "ElapsedTime-ms" (relative to the start of the acquisition) of the JSON metadata of each plane (tag 51123)</li>
 * </ul>
 * Other files (or BigTIFF files) are not supported: Bio-Formats should be used instead.
 */
public class TiffTimestamps {
    public static final Logger logger = LoggerFactory.getLogger(TiffTimestamps.class);
    static final int IMAGE_DESCRIPTION = 270, MICRO_MANAGER_METADATA = 51123;
    static final int MAX_PLANES = 100000;

    static final Pattern METAMORPH_TIME = Pattern.compile("id=\"acquisition-time-local\"[^>]*value=\"([^\"]+)\"");
    static final DateTimeFormatter METAMORPH_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd HH:mm:ss[.SSS]");
    static final Pattern MM_RECEIVED_TIME = Pattern.compile("\"ReceivedTime\"\\s*:\\s*\"([^\"]+)\"");
    static final Pattern MM_TIME = Pattern.compile("\"Time\"\\s*:\\s*\"([^\"]+)\"");
    static final Pattern MM_ELAPSED_TIME = Pattern.compile("\"ElapsedTime-ms\"\\s*:\\s*\"?(-?[0-9.]+)");
    static final DateTimeFormatter MM_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss[.SSS][ Z]");

    /**
     * Parsers of the metadata of one plane: return the acquisition time in milliseconds or null if not found.
     * Absolute local times without time zone are interpreted as UTC: values are only meaningful as differences between planes of a same acquisition.
     */
    enum Parser {
        METAMORPH(IMAGE_DESCRIPTION, s -> parseLocal(find(METAMORPH_TIME, s), METAMORPH_FORMAT)),
        MICRO_MANAGER_ABSOLUTE(MICRO_MANAGER_METADATA, s -> {
            Double t = parseMM(find(MM_RECEIVED_TIME, s));
            return t != null ? t : parseMM(find(MM_TIME, s));
        }),
        MICRO_MANAGER_ELAPSED(MICRO_MANAGER_METADATA, s -> {
            String t = find(MM_ELAPSED_TIME, s);
            try {
                return t == null ? null : Double.parseDouble(t);
            } catch (NumberFormatException e) {
                return null;
            }
        });
        final int tag;
        final Function<String, Double> parser;
        Parser(int tag, Function<String, Double> parser) {
            this.tag = tag;
            this.parser = parser;
        }
        Double parse(String[] tagValues) {
            String value = tag == IMAGE_DESCRIPTION ? tagValues[0] : tagValues[1];
            return value == null ? null : parser.apply(value);
        }
    }

    /**
     * @return acquisition time in milliseconds of each plane of the file (in the order of the pages), or null if the file is not supported or no acquisition time is found.
     * For files where some pages have no acquisition time, only the times of the first pages are returned.
     */
    public static double[] read(String filePath) {
        try {
            List<String[]> tags = readTags(filePath);
            if (tags == null || tags.isEmpty()) return null;
            for (Parser p : Parser.values()) { // the parser that works for the first plane is used for all planes
                if (p.parse(tags.get(0)) == null) continue;
                List<Double> times = new ArrayList<>(tags.size());
                for (String[] t : tags) {
                    Double time = p.parse(t);
                    if (time == null) break;
                    times.add(time);
                }
                return times.stream().mapToDouble(d -> d).toArray();
            }
            return null;
        } catch (IOException | RuntimeException e) {
            logger.debug("could not read acquisition time from TIF tags of file: {}: {}", filePath, e.toString());
            return null;
        }
    }

    /**
     * Minimal reader of classic TIF files (not BigTIFF)
     * @return for each page: values of the image description and Micro-Manager metadata tags (null if absent)
     */
    static List<String[]> readTags(String filePath) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(filePath, "r")) {
            byte[] header = new byte[8];
            raf.readFully(header);
            ByteOrder order;
            if (header[0] == 'I' && header[1] == 'I') order = ByteOrder.LITTLE_ENDIAN;
            else if (header[0] == 'M' && header[1] == 'M') order = ByteOrder.BIG_ENDIAN;
            else return null;
            ByteBuffer hb = ByteBuffer.wrap(header).order(order);
            if (hb.getShort(2) != 42) return null; // 43 = BigTIFF: not supported
            long ifdOffset = Integer.toUnsignedLong(hb.getInt(4));
            List<String[]> res = new ArrayList<>();
            long length = raf.length();
            while (ifdOffset > 0 && ifdOffset < length && res.size() < MAX_PLANES) {
                raf.seek(ifdOffset);
                byte[] countB = new byte[2];
                raf.readFully(countB);
                int nEntries = Short.toUnsignedInt(ByteBuffer.wrap(countB).order(order).getShort());
                byte[] entriesB = new byte[nEntries * 12 + 4];
                raf.readFully(entriesB);
                ByteBuffer entries = ByteBuffer.wrap(entriesB).order(order);
                String[] values = new String[2];
                for (int i = 0; i < nEntries; ++i) {
                    int pos = i * 12;
                    int tag = Short.toUnsignedInt(entries.getShort(pos));
                    if (tag != IMAGE_DESCRIPTION && tag != MICRO_MANAGER_METADATA) continue;
                    int type = Short.toUnsignedInt(entries.getShort(pos + 2));
                    if (type != 1 && type != 2 && type != 7) continue; // BYTE, ASCII or UNDEFINED
                    long count = Integer.toUnsignedLong(entries.getInt(pos + 4));
                    if (count == 0 || count > Integer.MAX_VALUE) continue;
                    byte[] value = new byte[(int) count];
                    if (count <= 4) System.arraycopy(entriesB, pos + 8, value, 0, (int) count);
                    else {
                        long valueOffset = Integer.toUnsignedLong(entries.getInt(pos + 8));
                        if (valueOffset + count > length) continue;
                        long current = raf.getFilePointer();
                        raf.seek(valueOffset);
                        raf.readFully(value);
                        raf.seek(current);
                    }
                    values[tag == IMAGE_DESCRIPTION ? 0 : 1] = new String(value, StandardCharsets.UTF_8);
                }
                res.add(values);
                ifdOffset = Integer.toUnsignedLong(entries.getInt(nEntries * 12));
            }
            return res;
        }
    }

    static String find(Pattern p, String s) {
        Matcher m = p.matcher(s);
        return m.find() ? m.group(1).trim() : null;
    }

    static Double parseLocal(String time, DateTimeFormatter format) {
        if (time == null) return null;
        try {
            return (double) LocalDateTime.parse(time, format).toInstant(ZoneOffset.UTC).toEpochMilli();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    static Double parseMM(String time) {
        if (time == null) return null;
        try {
            return (double) OffsetDateTime.parse(time, MM_FORMAT).toInstant().toEpochMilli();
        } catch (DateTimeParseException e) { // no time zone
            return parseLocal(time, MM_FORMAT);
        }
    }
}
