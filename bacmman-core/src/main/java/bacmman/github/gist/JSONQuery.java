package bacmman.github.gist;

import bacmman.utils.Pair;
import bacmman.utils.Utils;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static bacmman.utils.ThreadRunner.sleep;

public class JSONQuery {
    public static final Logger logger = LoggerFactory.getLogger(JSONQuery.class);
    public enum METHOD {GET, POST, PATCH, DELETE}
    public static String REQUEST_PROPERTY_DEFAULT = "application/json; charset=UTF-8";
    public static String REQUEST_PROPERTY_GITHUB_BASE64 = "application/vnd.github.v3.base64";
    public static String REQUEST_PROPERTY_GITHUB_JSON= "application/vnd.github+json";
    public static int MAX_PER_PAGE = 100;
    public static int MAX_TRYOUTS = 50;
    public static int TRYOUT_SLEEP = 7500; //ms
    public static int TRYOUT_SLEEP_INC = 100;
    private HttpURLConnection urlConnection;
    UserAuth auth;
    public static String GIST_BASE_URL = "https://gist.github.com/";

    public JSONQuery(String url) {
        this(url, REQUEST_PROPERTY_GITHUB_JSON);
    }
    public JSONQuery(String url, String request_property) {
        this(url, request_property, getDefaultParameters(1));
    }
    public JSONQuery(String url, String request_property, List<Pair<String, String>> parameters) {
        try {
            URL serverUrl = new URL(url + getQuery(parameters));
            urlConnection = (HttpURLConnection) serverUrl.openConnection();
            //urlConnection.setRequestProperty("Content-Type", request_property);
            header("Accept", request_property);
        } catch (MalformedURLException e) {
            logger.error("query url error", e);
        } catch (IOException e) {
            logger.error("open connection error", e);
        }
    }
    public JSONQuery header(String key, String value) {
        urlConnection.setRequestProperty(key, value);
        return this;
    }
    public JSONQuery headerAcceptJSON() {
        header("Accept", "application/json");
        return this;
    }
    private String getQuery(List<Pair<String, String>> parameters) {
        if (parameters.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append('?');
        Iterator<Pair<String, String>> it = parameters.iterator();
        while(it.hasNext()) {
            Pair<String, String> param = it.next();
            sb.append(param.key).append('=').append(param.value);
            if (it.hasNext()) sb.append('&');
        }
        return sb.toString();
    }
    public static List<Pair<String, String>> getDefaultParameters(int page) {
        List<Pair<String, String>> res = new ArrayList<>(2);
        res.add(new Pair<>("per_page", String.valueOf(MAX_PER_PAGE)));
        if (page>1) res.add(new Pair<>("page", String.valueOf(page)));
        return res;
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

    public String fetchSilently() {
        try {
            String res = fetch();
            return res;
        } catch (IOException e) {
            if (e.getMessage().contains("HTTP response code: 401")) {
                logger.error("Authentication error: wrong username/code/token");
                return null;
            } else if (e.getMessage().contains("HTTP response code: 422")) {
                logger.error("Malformed query: HTTP response code: 422");
                return null;
            } else {
                logger.error("error while fetch query", e);
                return null;
            }
        }
    }

    public String fetch() throws IOException {
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
                else {
                    o_oSb.append(o_sLineSep);
                    logger.info("APPEND SEP LINE CHAR");
                }
                o_oSb.append(sLine);
            }
        } catch(FileNotFoundException e) {
            throw new IOException("Authentication Error for "+e.getMessage());
        } catch (IOException e) {
            throw e;
        }  finally {
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
    public static String encodeJSONBase64(Map<String, byte[]> fileNameMapChunks) {
        StringBuffer sb = new StringBuffer();
        sb.append('{') // gist
                .append('"').append("files").append('"').append(':').append('{'); // files

        Iterator<Map.Entry<String, byte[]>> iter = fileNameMapChunks.entrySet().iterator();
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

    public static List<JSONObject> fetchAllPages(IntFunction<JSONQuery> queryPerPage) throws IOException, ParseException {
        int currentPage = 1;
        List<JSONObject> currentPageResult = fetchAndParse(() -> queryPerPage.apply(1), MAX_TRYOUTS);
        List<JSONObject> res = new ArrayList<>(currentPageResult);
        while(currentPageResult.size()==MAX_PER_PAGE) {
            int cp = ++currentPage;
            currentPageResult = fetchAndParse(() -> queryPerPage.apply(cp), MAX_TRYOUTS);
            res.addAll(currentPageResult);
            logger.debug("retrieved page {} -> {} entries total: {}", currentPage-1, currentPageResult.size(), res.size());
        }
        return res;
    }
    public static String fetch(Supplier<JSONQuery> querySupplier, int tryouts) throws IOException{
        if (tryouts==0) tryouts=1;
        int sleep = TRYOUT_SLEEP;
        while (tryouts-->0) {
            try {
                return querySupplier.get().fetch();
            } catch (IOException e) {
                if (tryouts==0 || !e.getMessage().contains("HTTP response code: 50")) throw e;
                else {
                    logger.debug("error {} -> try again, remaining tryouts: {}", e.getMessage(), tryouts);
                    sleep(sleep);
                    sleep+=TRYOUT_SLEEP_INC;
                }
            }
        }
        return null;
    }
    public static List<JSONObject> fetchAndParse(Supplier<JSONQuery> querySupplier, int tryouts) throws IOException, ParseException {
        String answer = fetch(querySupplier, tryouts);
        return parseJSON(answer);
    }
    public static List<JSONObject> parseJSON(String response) throws ParseException {
        Object json = new JSONParser().parse(response);
        if (json instanceof JSONArray) {
            JSONArray gistsRequest = (JSONArray)json;
            return ((Stream<JSONObject>) gistsRequest.stream()).collect(Collectors.toList());
        } else {
            return new ArrayList<JSONObject>(){{add((JSONObject)json);}};
        }
    }
    public static List<String> parseJSONStringArray(String response) throws ParseException {
        if (response.charAt(0)=='[') {
            Object json = new JSONParser().parse(response);
            JSONArray gistsRequest = (JSONArray)json;
            return ((Stream<String>) gistsRequest.stream()).collect(Collectors.toList());
        } else {
            return new ArrayList<String>(){{add(response);}};
        }
    }
    public static boolean delete(String url, UserAuth auth) {
        if (auth instanceof NoAuth) return false;
        try {
            new JSONQuery(url).method(JSONQuery.METHOD.DELETE).authenticate(auth).fetch();
        } catch (Exception e) {
            logger.debug("Error deleting file", e);
            return false;
        }
        return true;
    }

    //https://docs.github.com/en/developers/apps/building-oauth-apps/authorizing-oauth-apps#device-flow
    public static JSONObject authorizeAppStep1() throws IOException, ParseException{
        List<Pair<String, String>> params = new ArrayList<>(2);
        params.add(new Pair<>("client_id", "3c8f978d2a0423b4b003"));
        params.add(new Pair<>("scope", "gist"));
        JSONQuery q = new JSONQuery("https://github.com/login/device/code", JSONQuery.REQUEST_PROPERTY_DEFAULT, params).headerAcceptJSON().method(METHOD.POST);
        //Accept: application/json
        String answer = q.fetch();
        logger.info("authorize step 1: {}", answer);
        JSONObject json = parseJSON(answer).get(0);
        logger.info("authorize step 1: {}", json);
        return json;
        //String deviceCode = (String)json.get("device_code");
        //String userCode = json.get("user_code");
        //String verificationURI = json.get("verification_uri");
    }
    public static String authorizeAppStep2(String deviceCode) throws IOException, ParseException {
        List<Pair<String, String>> params = new ArrayList<>(2);
        params.add(new Pair<>("client_id", "3c8f978d2a0423b4b003"));
        params.add(new Pair<>("device_code", deviceCode));
        params.add(new Pair<>("grant_type", "urn:ietf:params:oauth:grant-type:device_code"));
        JSONQuery q = new JSONQuery("https://github.com/login/oauth/access_token", JSONQuery.REQUEST_PROPERTY_DEFAULT, params).headerAcceptJSON().method(METHOD.POST);
        //Accept: application/json
        String answer = q.fetch();
        JSONObject json = parseJSON(answer).get(0);
        logger.info("authorize step 2: {}", json);
        return (String)json.get("access_token");
    }

}
