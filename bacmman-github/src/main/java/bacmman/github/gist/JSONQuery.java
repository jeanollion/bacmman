package bacmman.github.gist;

import bacmman.utils.IconUtils;
import bacmman.utils.Utils;
import org.json.simple.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.ProtocolException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public class JSONQuery {
    public static final Logger logger = LoggerFactory.getLogger(JSONQuery.class);
    public enum METHOD {GET, POST, PATCH, DELETE}
    public static String REQUEST_PROPERTY_DEFAULT = "application/json; charset=UTF-8";
    public static String REQUEST_PROPERTY_GITHUB_BASE64 = "application/vnd.github.v3.base64";
    public static String REQUEST_PROPERTY_GITHUB_JSON= "application/vnd.github+json";

    private HttpURLConnection urlConnection;
    UserAuth auth;
    public JSONQuery(String url) {
        this(url, REQUEST_PROPERTY_DEFAULT);
    }
    public JSONQuery(String url, String request_property) {
        try {
            URL serverUrl = new URL(url);
            urlConnection = (HttpURLConnection) serverUrl.openConnection();
            urlConnection.setRequestProperty("Content-Type", request_property);

        } catch (MalformedURLException e) {
            logger.error("query url error", e);
        } catch (IOException e) {
            logger.error("open connection error", e);
        }
    }


    public JSONQuery method(METHOD method) {
        try {
            switch (method) {
                case PATCH:
                    urlConnection.setRequestProperty("X-HTTP-Method-Override", "PATCH");
                    urlConnection.setRequestMethod("POST");
                    break;
                default:
                    urlConnection.setRequestMethod(method.toString());
            }

        } catch (ProtocolException e) {
            logger.error("query method error", e);
        }
        return this;
    }
    public JSONQuery authenticate(UserAuth auth) {
        if (auth instanceof NoAuth) this.auth=null;
        else this.auth = auth;
        auth.authenticate(urlConnection);
        return this;
    }
    public JSONQuery setBody(String jsonString) {
        byte[] out = jsonString.getBytes(StandardCharsets.UTF_8);
        int length = out.length;
        urlConnection.setDoOutput(true);
        urlConnection.setFixedLengthStreamingMode(length);
        try {
            urlConnection.connect();
            OutputStream os = urlConnection.getOutputStream();
            os.write(out);
        } catch (IOException io) {
            logger.error("error setting params", io);
        }
        return this;
    }
    public String fetch() {
        String o_sLineSep = null;
        try {
            o_sLineSep = System.getProperty("line.separator");
        }
        catch (Exception e) {
            o_sLineSep = "\n";
        }
        StringBuilder o_oSb = new StringBuilder();

        try {
            InputStream o_oResponse = urlConnection.getInputStream();
            BufferedReader o_oBufReader = new BufferedReader(new InputStreamReader(o_oResponse));
            String sLine;
            boolean first = true;
            while ((sLine = o_oBufReader.readLine()) != null) {
                if (first) first = false;
                else o_oSb.append(o_sLineSep);
                o_oSb.append(sLine);
            }
        } catch (IOException e) {
            if (e.getMessage().contains("HTTP response code: 401")) {
                logger.error("Authentication error: wrong username/code/token");
                return null;
            } else if (e.getMessage().contains("HTTP response code: 422")) {
                logger.error("Malformed query: HTTP response code: 422");
                return null;
            } else {
                logger.error("error while fetch query", e);
            }
        } finally {
            logger.debug("url disconnected");
            urlConnection.disconnect();
        }
        return o_oSb.toString();
    }
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("Request Method: ").append(urlConnection.getRequestMethod());
        sb.append("header: ").append(Utils.toStringMap(urlConnection.getHeaderFields(), s->s, Utils::toStringList));
        //sb.append("request props: ").append(Utils.toStringMap(urlConnection.getRequestProperties(), s->s, Utils::toStringList));
        return sb.toString();
    }
    public static String encodeJSONBase64(String fileName, byte[] data) {
        return encodeJSONBase64(new HashMap<String, byte[]>(1){{put(fileName, data);}});
    }
    public static String encodeJSONBase64(Map<String, byte[]> chunks) {
        StringBuffer sb = new StringBuffer();
        sb.append('{') // gist
                .append('"').append("files").append('"').append(':').append('{'); // files

        Iterator<Map.Entry<String, byte[]>> iter = chunks.entrySet().iterator();
        while(iter.hasNext()) {
            Map.Entry<String, byte[]> entry = iter.next();
            sb.append('"').append(entry.getKey()).append('"').append(':')
                    .append('{')
                        .append('"').append("content").append('"').append(':')
                        .append('"');
                        if (entry.getValue()!=null && entry.getValue().length>0) sb.append(Base64.getEncoder().encodeToString(entry.getValue()));
                        sb.append('"').append('}');
            if (iter.hasNext()) sb.append(',');
        }
        sb.append('}'); // files
        sb.append('}'); // gist
        return sb.toString();
    }
}
