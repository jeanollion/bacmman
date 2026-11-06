package bacmman.github.gist;

import java.net.HttpURLConnection;

public interface UserAuth {
    void authenticate(HttpURLConnection connection);
    String getAccount();

    /**
     * Authenticates with the header scheme expected by the given repository type. Default: same as {@link #authenticate(HttpURLConnection)}
     */
    default void authenticate(HttpURLConnection connection, GistRepository.TYPE type) {
        authenticate(connection);
    }
}
