package bacmman.github.gist;

import java.net.HttpURLConnection;

public class NoAuth implements UserAuth {
    @Override
    public void authenticate(HttpURLConnection urlConnection) {
    }
}
