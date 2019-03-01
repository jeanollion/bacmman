package bacmman.github.gist;

import java.net.HttpURLConnection;

public class NoAuth implements UserAuth {
    @Override
    public void autenticate(HttpURLConnection connection) {

    }
}
