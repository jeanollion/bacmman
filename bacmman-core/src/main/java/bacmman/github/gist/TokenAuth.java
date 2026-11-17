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
        this(username, passphrase, GistRepository.TYPE.GITHUB);
    }

    /**
     * Decrypts a token stored for a given service. GitHub tokens and GitLab tokens are stored under different keys, so that a user can store both
     */
    public TokenAuth(String username, char[] passphrase, GistRepository.TYPE service) throws GeneralSecurityException, KeyException {
        this(username, passphrase, service, null);
    }

    /**
     * Decrypts a token stored for a given service and server
     * @param server server of GitLab repositories (tokens are stored per server). null: gitlab.com. Ignored for GitHub
     */
    public TokenAuth(String username, char[] passphrase, GistRepository.TYPE service, String server) throws GeneralSecurityException, KeyException {
        String encryptedToken = PropertyUtils.get(key(username, service, server)+"_t_e");
        String salt = PropertyUtils.get(key(username, service, server)+"_t_s");
        if (salt==null || encryptedToken==null) throw new KeyException("No token stored");
        this.token = PasswordUtils.decryptFromPassphrase(passphrase, encryptedToken, salt);
        this.account=username;
    }

    public TokenAuth(String token) {
        this.token=token;
        this.account = null;
    }
    public static void encryptAndStore(String username, char[] passphrase, String token) throws GeneralSecurityException {
        encryptAndStore(username, passphrase, token, GistRepository.TYPE.GITHUB);
    }
    public static void encryptAndStore(String username, char[] passphrase, String token, GistRepository.TYPE service) throws GeneralSecurityException {
        encryptAndStore(username, passphrase, token, service, null);
    }

    /**
     * Stores a token encrypted with a passphrase
     * @param server server of GitLab repositories (tokens are stored per server, so that accounts with the same name on different servers can be used). null: gitlab.com. Ignored for GitHub
     */
    public static void encryptAndStore(String username, char[] passphrase, String token, GistRepository.TYPE service, String server) throws GeneralSecurityException {
        String[] encryptedTokenAndSalt = PasswordUtils.encryptFromPassphrase(token, passphrase);
        PropertyUtils.set(key(username, service, server)+"_t_e", encryptedTokenAndSalt[0]);
        PropertyUtils.set(key(username, service, server)+"_t_s", encryptedTokenAndSalt[1]);
    }

    /**
     * @return whether a token is stored for this account
     */
    public static boolean hasToken(String username, GistRepository.TYPE service, String server) {
        return PropertyUtils.get(key(username, service, server)+"_t_e") != null;
    }

    private static String key(String username, GistRepository.TYPE service, String server) {
        if (service == null || GistRepository.TYPE.GITHUB.equals(service)) return username + "_g"; // legacy key
        String host = GitLabRepository.normalizeServerURL(server).replaceFirst("^[a-z]+://", "").replaceAll("[^A-Za-z0-9.\\-]", "_");
        return username + "_gl_" + host;
    }
    public void authenticate(HttpURLConnection urlConnection) {
       urlConnection.addRequestProperty("Authorization", "token "+token);
    }
    @Override
    public void authenticate(HttpURLConnection urlConnection, GistRepository.TYPE type) {
        if (GistRepository.TYPE.GITLAB.equals(type)) urlConnection.addRequestProperty("PRIVATE-TOKEN", token);
        else authenticate(urlConnection);
    }
    public String getAccount() {
        return account;
    }
}
