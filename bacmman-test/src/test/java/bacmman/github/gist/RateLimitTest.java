package bacmman.github.gist;

import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/**
 * Request limit of GitHub / GitLab: clear message, no long retries. A local server simulates the responses
 */
public class RateLimitTest {
    HttpServer server;
    String base;
    final AtomicInteger calls = new AtomicInteger();

    static final UserAuth ACCOUNT = new UserAuth() {
        @Override public void authenticate(HttpURLConnection connection) {connection.setRequestProperty("Authorization", "token test");}
        @Override public String getAccount() {return "user";}
    };

    static void respond(com.sun.net.httpserver.HttpExchange ex, int code, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream os = ex.getResponseBody()) {os.write(b);}
    }

    @Before
    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        long reset = System.currentTimeMillis() / 1000 + 1800;
        // GitHub style: 403 with remaining = 0 and reset time
        server.createContext("/github", ex -> {
            calls.incrementAndGet();
            ex.getResponseHeaders().add("x-ratelimit-remaining", "0");
            ex.getResponseHeaders().add("x-ratelimit-reset", String.valueOf(reset));
            respond(ex, 403, "{\"message\":\"API rate limit exceeded for 1.2.3.4.\"}");
        });
        // GitLab style: 429 with a short Retry-After, then success
        server.createContext("/short", ex -> {
            if (calls.incrementAndGet() == 1) {
                ex.getResponseHeaders().add("Retry-After", "1");
                respond(ex, 429, "Retry later");
            } else respond(ex, 200, "{\"ok\":true}");
        });
        // GitLab style: 429 with a long wait
        server.createContext("/long", ex -> {
            calls.incrementAndGet();
            ex.getResponseHeaders().add("Retry-After", "3600");
            ex.getResponseHeaders().add("RateLimit-Reset", String.valueOf(reset));
            respond(ex, 429, "Retry later");
        });
        // forbidden, not a rate limit
        server.createContext("/forbidden", ex -> {
            calls.incrementAndGet();
            respond(ex, 403, "{\"message\":\"Forbidden\"}");
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @After
    public void stop() {
        server.stop(0);
    }

    @Test
    public void withoutAccount() {
        long t0 = System.currentTimeMillis();
        try {
            JSONQuery.fetchRaw(() -> new JSONQuery(base + "/github").method(JSONQuery.METHOD.GET).authenticate(new NoAuth()), JSONQuery.MAX_TRYOUTS);
            fail("rate limit expected");
        } catch (JSONQuery.RateLimitException e) {
            assertFalse(e.authenticated);
            assertTrue(e.getMessage(), e.getMessage().contains("request limit reached for connections without account"));
            assertTrue(e.getMessage(), e.getMessage().contains("Connect to your account"));
            assertTrue(e.getMessage(), e.getMessage().contains("wait until "));
        } catch (IOException e) {
            fail("rate limit exception expected: " + e);
        }
        assertEquals("not retried", 1, calls.get());
        assertTrue("no wait", System.currentTimeMillis() - t0 < 5000);
    }

    @Test
    public void withAccount() throws Exception {
        try {
            new JSONQuery(base + "/github").method(JSONQuery.METHOD.GET).authenticate(ACCOUNT).fetch(); // other read method
            fail("rate limit expected");
        } catch (JSONQuery.RateLimitException e) {
            assertTrue(e.authenticated);
            assertTrue(e.getMessage(), e.getMessage().contains("request limit of your account reached"));
            assertFalse(e.getMessage(), e.getMessage().contains("Connect"));
        }
        try {
            JSONQuery.fetch(() -> new JSONQuery(base + "/long").method(JSONQuery.METHOD.GET).authenticate(ACCOUNT), JSONQuery.MAX_TRYOUTS);
            fail("rate limit expected");
        } catch (JSONQuery.RateLimitException e) {
            assertTrue(e.getMessage(), e.getMessage().startsWith("GitLab (127.0.0.1): request limit of your account reached"));
        }
        assertEquals("long wait: not retried", 2, calls.get());
    }

    @Test
    public void shortWaitRetried() throws Exception {
        long t0 = System.currentTimeMillis();
        String res = JSONQuery.fetchRaw(() -> new JSONQuery(base + "/short").method(JSONQuery.METHOD.GET).authenticate(ACCOUNT), 3);
        assertEquals("{\"ok\":true}", res);
        assertEquals(2, calls.get());
        assertTrue(System.currentTimeMillis() - t0 >= 900);
    }

    @Test
    public void forbiddenIsNotRateLimit() {
        try {
            JSONQuery.fetchRaw(() -> new JSONQuery(base + "/forbidden").method(JSONQuery.METHOD.GET).authenticate(ACCOUNT), 3);
            fail("error expected");
        } catch (JSONQuery.RateLimitException e) {
            fail("not a rate limit: " + e.getMessage());
        } catch (IOException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("403"));
        }
    }

    @Test
    public void githubMessage() {
        String m = JSONQuery.rateLimitMessage("GitHub", true, false, -1, -1, 0);
        assertEquals("GitHub: request limit reached for connections without account (60 requests per hour). Connect to your account (enter your password and click Connect) to continue, or wait a few minutes.", m);
    }
}
