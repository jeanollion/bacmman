package bacmman.github.gist;

import java.net.HttpURLConnection;

public class NoAuth implements UserAuth {
    final String account;

    public NoAuth() {
        this(null);
    }

    public NoAuth(String account) {
        this.account = account;
    }

    @Override
    public void authenticate(HttpURLConnection urlConnection) {
    }
    @Override
    public String getAccount() {
        return account;
    }
}
