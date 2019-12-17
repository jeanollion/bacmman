package bacmman.github.gist;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.ProtocolException;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class JSONQuery {
    public static final Logger logger = LoggerFactory.getLogger(JSONQuery.class);
    public enum METHOD {GET, POST, PATCH, DELETE}
    private HttpURLConnection urlConnection;

    public JSONQuery(String url) {
        URL serverUrl = null;
        try {
            serverUrl = new URL(url);
            urlConnection = (HttpURLConnection) serverUrl.openConnection();
            //urlConnection.setRequestProperty("Content-Type", "application/json");
            urlConnection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
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
            while ((sLine = o_oBufReader.readLine()) != null) {
                o_oSb.append(sLine);
                o_oSb.append(o_sLineSep);
            }
        } catch (IOException e) {
            logger.error("error while fetch query", e);
        } finally {
            urlConnection.disconnect();
        }
        return o_oSb.toString();
    }

}
