package bacmman.github;

/*import bacmman.utils.JSONUtils;
import bacmman.utils.Pair;
import ch.systemsx.cisd.base.annotation.JsonObject;
import com.jcabi.github.*;
import com.jcabi.http.Request;
import com.jcabi.http.Response;
import com.jcabi.http.response.JsonResponse;
import com.jcabi.http.response.RestResponse;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;*/


public class TestGists {
    //public static final Logger logger = LoggerFactory.getLogger(TestGists.class);
    public static void main(String[] args) {
        //Github github = new RtGithub();
        // list all public gists from a repo
        //listAllGists(github).forEach(p->logger.debug("{} -> {}", p.key, p.value));

        // create gist
        //testCreateGist(github); // needs credentials
    }
    /*public static void testCreateGist(Github github) {
        Map<String, String> files = new HashMap<String, String>(){{put("Test3.json", "{\"content\":\"Test3\"}");}};
        try {
            Gist g = github.gists().create(files, true);
            logger.debug("new gist content : {}", g.read("Test3.json"));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }*/
    /*public static List<Pair<String, String>> listAllGists(Github github) {
        try {
            Response response = github.entry()
                    .uri().path("/users/jeanollion/gists").back()
                    .method(Request.GET).fetch();
            String jsonArray = response.body();
            //.headers().forEach((k,v) -> logger.debug("{} -> {}", k,v));
            JSONArray gistsRequest = (JSONArray)new JSONParser().parse(jsonArray);
            return ((Stream<JSONObject>)gistsRequest.stream()).map(
                gist -> {
                    String desc = (String)gist.get("description");
                    String fileUrl = (String) ((JSONObject) ((JSONObject) gist.get("files")).values().iterator().next()).get("raw_url");
                    try {
                        String content = response
                                .as(RestResponse.class)
                                .jump(URI.create(fileUrl))
                                .fetch()
                                .as(RestResponse.class)
                                .assertStatus(HttpURLConnection.HTTP_OK)
                                .body();
                        return new Pair<>(desc, content);
                    } catch (IOException e) {
                        return new Pair<>(desc, "");
                    }
                }).collect(Collectors.toList());

        } catch (Exception e) {
            return Collections.emptyList();
        }
    }*/
}
