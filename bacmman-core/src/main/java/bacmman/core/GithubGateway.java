package bacmman.core;

import bacmman.github.gist.LargeFileGist;
import bacmman.github.gist.NoAuth;
import bacmman.github.gist.TokenAuth;
import bacmman.github.gist.UserAuth;
import bacmman.ui.logger.ProgressLogger;
import bacmman.utils.Pair;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Consumer;

import static bacmman.github.gist.JSONQuery.GIST_BASE_URL;
import static bacmman.github.gist.JSONQuery.logger;

public class GithubGateway {
    final protected Map<String, char[]> passwords = new HashMap<>();
    protected char[] password;
    protected String username;
    ProgressLogger bacmmanLogger;
    private BiFunction<GithubGateway, String, Pair<String, char[]>> promptCredientialFunction;

    public GithubGateway setLogger(ProgressLogger logger) {
        bacmmanLogger = logger;
        return this;
    }
    public File downloadFile(String id, File destFile) {
        if (id.startsWith(GIST_BASE_URL)) id = id.replace(GIST_BASE_URL, "");
        try {
            UserAuth auth = getAuthentication(false);
            LargeFileGist lf = new LargeFileGist(id, auth);
            return lf.retrieveFile(destFile, false, true, auth, null, bacmmanLogger);
        }  catch (IOException ex) {
            if (bacmmanLogger!=null) bacmmanLogger.setMessage("Error trying to download model: "+ex.getMessage());
            logger.debug("error trying to download model", ex);
            return null;
        }
    }
    public void clear() {
        passwords.clear();
    }
    public char[] getPassword(String username) {
        if (passwords.containsKey(username)) return passwords.get(username);
        return null;
    }
    public String getUsername() {
        return username;
    }
    public void setCredentials(String username, char[] password) {
        this.username=username;
        this.password = password;
        if (username!=null && username.length()>0) passwords.put(username, password);
    }
    public UserAuth getAuthentication(boolean promptIfNecessary) {
        return getAuthentication(promptIfNecessary, null);
    }
    public UserAuth getAuthentication(boolean promptIfNecessary, String message) {
        if (password==null || password.length == 0 || username==null || username.isEmpty()) {
            if (promptIfNecessary && promptCredientialFunction!=null) {
                Pair<String, char[]> cred = promptCredientialFunction.apply(this, message);
                if (cred != null) {
                    username = cred.key;
                    password = cred.value;
                    if (password!=null && password.length>0 && username!=null && !username.isEmpty()) return getAuthentication(false);
                }
            }
            return new NoAuth();
        }
        else {
            try {
                TokenAuth auth = new TokenAuth(username, password);
                passwords.put(username, password);
                return auth;
            } catch (IllegalArgumentException e) {
                if (bacmmanLogger!=null && !promptIfNecessary) bacmmanLogger.setMessage("No token associated with this username found");
                if (promptIfNecessary) {
                    Pair<String, char[]> cred = promptCredientialFunction.apply(this, message);
                    if (cred != null) {
                        username = cred.key;
                        password = cred.value;
                        if (password!=null && password.length>0 && username!=null && !username.isEmpty()) return getAuthentication(false);
                    }
                }
                return new NoAuth();
            } catch (Throwable t) {
                if (bacmmanLogger!=null && !promptIfNecessary) bacmmanLogger.setMessage("Token could not be retrieved. Wrong password ?");
                if (promptIfNecessary) {
                    Pair<String, char[]> cred = promptCredientialFunction.apply(this, message);
                    if (cred != null) {
                        username = cred.key;
                        password = cred.value;
                        if (password!=null && password.length>0 && username!=null && !username.isEmpty()) return getAuthentication(false);
                    }
                }
                return new NoAuth();
            }
        }
    }
    public void setPromptGithubCredientials(BiFunction<GithubGateway, String, Pair<String, char[]>> prompt) {
        promptCredientialFunction = prompt;
    }
    public UserAuth promptCredentials(Consumer<String> error, String message) {
        Pair<String, char[]> cred = promptCredientialFunction.apply(this, message);
        if (cred == null || cred.key.isEmpty() || cred.value.length==0) return new NoAuth();
        else {
            try {
                TokenAuth auth = new TokenAuth(cred.key, cred.value);
                passwords.put(cred.key, cred.value);
                return auth;
            } catch (RuntimeException e) {
                if (error!=null) error.accept("Could not retried Token. Has Token been stored ?");
                logger.info("Authentication error", e);
                return new NoAuth();
            }
        }
    }
}
