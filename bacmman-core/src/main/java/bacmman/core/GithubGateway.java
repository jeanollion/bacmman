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
import java.util.function.Consumer;
import java.util.function.Function;

import static bacmman.github.gist.JSONQuery.GIST_BASE_URL;
import static bacmman.github.gist.JSONQuery.logger;

public class GithubGateway {
    final protected Map<String, char[]> passwords = new HashMap<>();
    protected char[] password;
    protected String username;
    ProgressLogger bacmmanLogger;
    private Consumer<GithubGateway> promptCredientialFunction;

    public GithubGateway setLogger(ProgressLogger logger) {
        bacmmanLogger = logger;
        return this;
    }
    public File downloadModel(String id, File destFile) {
        if (id.startsWith(GIST_BASE_URL)) id = id.replace(GIST_BASE_URL, "");
        try {
            LargeFileGist lf = new LargeFileGist(id);
            return lf.retrieveFile(destFile, false, true, null, bacmmanLogger);
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
        if (password==null || password.length == 0 || username==null || username.length()==0) {
            if (promptIfNecessary && promptCredientialFunction!=null) {
                promptCredientialFunction.accept(this);
                if (password!=null && password.length>0 && username!=null && username.length()>0) return getAuthentication(false);
            }
            return new NoAuth();
        }
        else {
            try {
                UserAuth auth = new TokenAuth(username, password);
                return auth;
            } catch (IllegalArgumentException e) {
                if (bacmmanLogger!=null && !promptIfNecessary) bacmmanLogger.setMessage("No token associated with this username found");
                if (promptIfNecessary) {
                    promptCredientialFunction.accept(this);
                    if (password!=null && password.length>0 && username!=null && username.length()>0) return getAuthentication(false);
                }
                return new NoAuth();
            } catch (Throwable t) {
                if (bacmmanLogger!=null && !promptIfNecessary) bacmmanLogger.setMessage("Token could not be retrieved. Wrong password ?");
                if (promptIfNecessary) {
                    promptCredientialFunction.accept(this);
                    if (password!=null && password.length>0 && username!=null && username.length()>0) return getAuthentication(false);
                }
                return new NoAuth();
            }
        }
    }
    public void setPromptGithubCredientials(Consumer<GithubGateway> prompt) {
        promptCredientialFunction = prompt;
    }
}