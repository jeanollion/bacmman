package bacmman.github.gist;

import java.net.HttpURLConnection;
import java.util.Base64;

public class BasicAuth implements UserAuth {
    private final String username, password;
    public BasicAuth(String username, String password) {
        this.username=username;
        this.password=password;
    }
    public void authenticate(HttpURLConnection urlConnection) {
        String usernameColonPassword = String.format("%s:%s", username, password);
        String basicAuthPayload = "Basic " + Base64.getEncoder().encodeToString(usernameColonPassword.getBytes());
        urlConnection.addRequestProperty("Authorization", basicAuthPayload);
    }
    public String getAccount() {
        return username;
    }
}
