package bacmman.github.gist;

import java.net.HttpURLConnection;

public interface UserAuth {
    void authenticate(HttpURLConnection connection);
}
