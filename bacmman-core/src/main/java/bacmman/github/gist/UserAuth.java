package bacmman.github.gist;

import java.io.IOException;
import java.net.HttpURLConnection;

public interface UserAuth {
    void authenticate(HttpURLConnection connection);
}
