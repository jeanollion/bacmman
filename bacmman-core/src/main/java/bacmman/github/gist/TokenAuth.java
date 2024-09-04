package bacmman.github.gist;

import bacmman.utils.PasswordUtils;
import bacmman.ui.PropertyUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.net.HttpURLConnection;
import java.security.GeneralSecurityException;
import java.security.KeyException;

public class TokenAuth implements UserAuth {
    private static final Logger logger = LoggerFactory.getLogger(TokenAuth.class);
    private final String token;
    private final String account;
    public TokenAuth(String username, char[] passphrase) throws GeneralSecurityException, KeyException {
        String encryptedToken = PropertyUtils.get(username+"_g_t_e");
        String salt = PropertyUtils.get(username+"_g_t_s");
        if (salt==null || encryptedToken==null) throw new KeyException("No token stored");
        this.token = PasswordUtils.decryptFromPassphrase(passphrase, encryptedToken, salt);
        this.account=username;
    }

    public TokenAuth(String token) {
        this.token=token;
        this.account = null;
    }
    public static void encryptAndStore(String username, char[] passphrase, String token) throws GeneralSecurityException {
        String[] encryptedTokenAndSalt = PasswordUtils.encryptFromPassphrase(token, passphrase);
        PropertyUtils.set(username+"_g_t_e", encryptedTokenAndSalt[0]);
        PropertyUtils.set(username+"_g_t_s", encryptedTokenAndSalt[1]);
    }
    public void authenticate(HttpURLConnection urlConnection) {
       urlConnection.addRequestProperty("Authorization", "token "+token);
    }
    public String getAccount() {
        return account;
    }
}
