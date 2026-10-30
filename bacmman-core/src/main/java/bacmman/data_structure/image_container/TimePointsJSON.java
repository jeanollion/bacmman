package bacmman.data_structure.image_container;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.*;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Stores the time points of a position (map "c;z;t" -> time) in the configuration file in a compact form.
 * Time points of each channel and z-plane are converted to integers (time x 10^decimals, where decimals is the smallest number of decimals that represents all values) and stored as differences between successive frames.
 * Two formats are written:
 * - compressed (default): {"decimals": d, "sizeZT": [[sizeZ, sizeT], ...], "data": base64 of the zlib compressed integers, with bytes grouped by significance}
 * - readable (editable by hand, see {@link #READABLE}): {"decimals": d, "values": [[[t0, t1-t0, t2-t1, ...], ...], ...]} indexed by channel, then z, then frame
 * The time unit is not modified. Precision is limited to {@link #RELATIVE_PRECISION} x the median interval between successive frames (and to 12 significant digits): values with more decimals are rounded.
 * Legacy format (map "c;z;t" -> time) is still read, and written when the map is not complete (missing planes).
 */
public class TimePointsJSON {
    /**
     * if true, time points are written in readable format, otherwise in compressed format
     */
    public static boolean READABLE = false;
    public static final String KEY = "timePoints", LEGACY_KEY = "timePointCZT";
    static final int MAX_DECIMALS = 15;
    static final double MAX_SCALED_VALUE = 1e12; // values are stored with at most 12 significant digits
    /**
     * time points are not stored with a precision finer than this fraction of the median interval between successive frames
     */
    public static final double RELATIVE_PRECISION = 1e-6;

    /**
     * Writes time points to json, if not null
     */
    public static void write(Map<String, Double> timePoints, JSONObject json) {
        if (timePoints == null) return;
        JSONObject encoded = encode(timePoints, READABLE);
        if (encoded != null) json.put(KEY, encoded);
        else {
            JSONObject legacy = new JSONObject();
            legacy.putAll(timePoints);
            json.put(LEGACY_KEY, legacy);
        }
    }

    /**
     * @return time points read from json in any format, null if there are no time points
     */
    public static Map<String, Double> read(JSONObject json) {
        if (json.containsKey(KEY)) return decode((JSONObject)json.get(KEY));
        else if (json.containsKey(LEGACY_KEY)) {
            Map<String, Double> res = new HashMap<>();
            ((Map<String, Number>)json.get(LEGACY_KEY)).forEach((k, v) -> res.put(k, v.doubleValue()));
            return res;
        } else return null;
    }

    /**
     * @return encoded time points, or null if the map is empty, incomplete, or contains invalid keys or non-finite values
     */
    public static JSONObject encode(Map<String, Double> timePoints, boolean readable) {
        if (timePoints.isEmpty()) return null;
        // get dimensions
        Map<Integer, int[]> sizeZT = new HashMap<>();
        Map<int[], Double> czt = new HashMap<>();
        for (Map.Entry<String, Double> e : timePoints.entrySet()) {
            int[] k = parseKey(e.getKey());
            if (k == null || e.getValue() == null || !Double.isFinite(e.getValue())) return null;
            int[] s = sizeZT.computeIfAbsent(k[0], c -> new int[2]);
            s[0] = Math.max(s[0], k[1] + 1);
            s[1] = Math.max(s[1], k[2] + 1);
            czt.put(k, e.getValue());
        }
        int nC = sizeZT.keySet().stream().mapToInt(i -> i).max().getAsInt() + 1;
        int[][] sizes = new int[nC][];
        long count = 0;
        for (int c = 0; c < nC; ++c) {
            sizes[c] = sizeZT.getOrDefault(c, new int[2]);
            count += (long) sizes[c][0] * sizes[c][1];
        }
        if (count != timePoints.size()) return null; // missing planes
        // values ordered by c, z, t
        double[] values = new double[(int) count];
        int[] offsetC = new int[nC];
        for (int c = 1; c < nC; ++c) offsetC[c] = offsetC[c - 1] + sizes[c - 1][0] * sizes[c - 1][1];
        for (Map.Entry<int[], Double> e : czt.entrySet()) {
            int[] k = e.getKey();
            values[offsetC[k[0]] + k[1] * sizes[k[0]][1] + k[2]] = e.getValue();
        }
        // to integers
        int decimals = getDecimals(values, getMaxDecimals(values, sizes));
        if (decimals < 0) return null;
        double scale = Math.pow(10, decimals);
        long[] deltas = new long[values.length];
        int i = 0;
        for (int c = 0; c < nC; ++c) {
            for (int z = 0; z < sizes[c][0]; ++z) {
                long prev = 0;
                for (int t = 0; t < sizes[c][1]; ++t) {
                    long v = Math.round(values[i] * scale);
                    deltas[i++] = v - prev;
                    prev = v;
                }
            }
        }
        JSONObject res = new JSONObject();
        res.put("decimals", decimals);
        if (readable) {
            JSONArray valuesC = new JSONArray();
            i = 0;
            for (int c = 0; c < nC; ++c) {
                JSONArray valuesZ = new JSONArray();
                for (int z = 0; z < sizes[c][0]; ++z) {
                    JSONArray valuesT = new JSONArray();
                    for (int t = 0; t < sizes[c][1]; ++t) valuesT.add(deltas[i++]);
                    valuesZ.add(valuesT);
                }
                valuesC.add(valuesZ);
            }
            res.put("values", valuesC);
        } else {
            JSONArray sizeArray = new JSONArray();
            for (int[] s : sizes) {
                JSONArray a = new JSONArray();
                a.add(s[0]);
                a.add(s[1]);
                sizeArray.add(a);
            }
            res.put("sizeZT", sizeArray);
            res.put("data", Base64.getEncoder().encodeToString(compress(shuffle(deltas))));
        }
        return res;
    }

    public static Map<String, Double> decode(JSONObject json) {
        int decimals = ((Number) json.get("decimals")).intValue();
        double scale = Math.pow(10, decimals);
        Map<String, Double> res = new HashMap<>();
        if (json.containsKey("values")) {
            List<List<List<Number>>> valuesC = (List<List<List<Number>>>) json.get("values");
            for (int c = 0; c < valuesC.size(); ++c) {
                for (int z = 0; z < valuesC.get(c).size(); ++z) {
                    long v = 0;
                    List<Number> valuesT = valuesC.get(c).get(z);
                    for (int t = 0; t < valuesT.size(); ++t) {
                        v += valuesT.get(t).longValue();
                        res.put(MultipleImageContainer.getKey(c, z, t), v / scale);
                    }
                }
            }
        } else {
            List<List<Number>> sizes = (List<List<Number>>) json.get("sizeZT");
            int count = sizes.stream().mapToInt(s -> s.get(0).intValue() * s.get(1).intValue()).sum();
            long[] deltas = unshuffle(decompress(Base64.getDecoder().decode((String) json.get("data")), count * 8), count);
            int i = 0;
            for (int c = 0; c < sizes.size(); ++c) {
                int sizeZ = sizes.get(c).get(0).intValue(), sizeT = sizes.get(c).get(1).intValue();
                for (int z = 0; z < sizeZ; ++z) {
                    long v = 0;
                    for (int t = 0; t < sizeT; ++t) {
                        v += deltas[i++];
                        res.put(MultipleImageContainer.getKey(c, z, t), v / scale);
                    }
                }
            }
        }
        return res;
    }

    /**
     * @return smallest number of decimals that represents all values, limited so that scaled values remain small enough to be exact. -1 if values are too large
     */
    static int getDecimals(double[] values, int maxDecimals) {
        double maxAbs = Arrays.stream(values).map(Math::abs).max().orElse(0);
        if (maxAbs >= Long.MAX_VALUE / 4d) return -1;
        int d = 0;
        while (!areIntegers(values, Math.pow(10, d)) && d < maxDecimals && maxAbs * Math.pow(10, d + 1) < MAX_SCALED_VALUE) ++d;
        return d;
    }

    /**
     * @return number of decimals so that precision is {@link #RELATIVE_PRECISION} x median interval between successive frames, or {@link #MAX_DECIMALS} if there is no interval
     */
    static int getMaxDecimals(double[] values, int[][] sizes) {
        List<Double> intervals = new ArrayList<>();
        int i = 0;
        for (int[] s : sizes) {
            for (int z = 0; z < s[0]; ++z) {
                for (int t = 0; t < s[1]; ++t) {
                    if (t > 0) {
                        double d = Math.abs(values[i] - values[i - 1]);
                        if (d > 0) intervals.add(d);
                    }
                    ++i;
                }
            }
        }
        if (intervals.isEmpty()) return MAX_DECIMALS;
        Collections.sort(intervals);
        double median = intervals.get(intervals.size() / 2);
        int d = (int) Math.ceil(-Math.log10(median * RELATIVE_PRECISION) - 1e-9); // smallest d such that 10^-d <= median x precision
        return Math.max(0, Math.min(MAX_DECIMALS, d));
    }

    static boolean areIntegers(double[] values, double scale) {
        for (double v : values) {
            double s = v * scale;
            if (Math.abs(s - Math.rint(s)) > 1e-12 + Math.abs(s) * 1e-14) return false; // tolerance: rounding errors of double
        }
        return true;
    }

    static int[] parseKey(String key) {
        String[] s = key.split(";");
        if (s.length != 3) return null;
        try {
            int[] res = new int[]{Integer.parseInt(s[0]), Integer.parseInt(s[1]), Integer.parseInt(s[2])};
            for (int i : res) if (i < 0) return null;
            return res;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Little endian bytes of values, grouped by significance (byte 0 of all values, then byte 1...): high bytes of small values are mostly constant and compress well
     */
    static byte[] shuffle(long[] values) {
        int n = values.length;
        byte[] res = new byte[8 * n];
        for (int i = 0; i < n; ++i) {
            for (int b = 0; b < 8; ++b) res[b * n + i] = (byte) (values[i] >>> (8 * b));
        }
        return res;
    }

    static long[] unshuffle(byte[] bytes, int n) {
        if (bytes.length != 8 * n) throw new IllegalArgumentException("Invalid time point data: expected " + (8 * n) + " bytes, found " + bytes.length);
        long[] res = new long[n];
        for (int i = 0; i < n; ++i) {
            long v = 0;
            for (int b = 0; b < 8; ++b) v |= (bytes[b * n + i] & 0xFFL) << (8 * b);
            res[i] = v;
        }
        return res;
    }

    static byte[] compress(byte[] data) {
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
        try {
            deflater.setInput(data);
            deflater.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream(data.length / 4 + 16);
            byte[] buffer = new byte[4096];
            while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer));
            return out.toByteArray();
        } finally {
            deflater.end();
        }
    }

    static byte[] decompress(byte[] data, int expectedLength) {
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(data);
            byte[] res = new byte[expectedLength];
            int length = 0;
            while (!inflater.finished() && length < expectedLength) {
                int n = inflater.inflate(res, length, expectedLength - length);
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break;
                length += n;
            }
            if (length != expectedLength || !inflater.finished()) throw new IllegalArgumentException("Invalid time point data: could not decompress " + expectedLength + " bytes");
            return res;
        } catch (DataFormatException e) {
            throw new IllegalArgumentException("Invalid time point data", e);
        } finally {
            inflater.end();
        }
    }
}
