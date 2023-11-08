package bacmman.core;

import bacmman.configuration.experiment.Experiment;
import bacmman.data_structure.image_container.MultipleImageContainer;
import bacmman.image.io.ImageReader;
import bacmman.image.io.OmeroImageMetadata;
import bacmman.ui.PropertyUtils;
import bacmman.ui.logger.ProgressLogger;
import bacmman.utils.PasswordUtils;

import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;

public interface OmeroGateway {
    void setLogger(ProgressLogger logger);
    OmeroGateway setCredentials(String hostname, String userName, String password);
    boolean isConnected();
    boolean connect();
    boolean close();
    ImageReader createReader(long fileID) throws IOException;
    void importFiles(Experiment xp, Consumer<List<MultipleImageContainer>> importCallback, ProgressCallback pcb);

    static String decryptPassword(String server, String username, char[] password) {
        String encryptedToken = PropertyUtils.get(server+"_"+username+"_o_e");
        String salt = PropertyUtils.get(server+"_"+username+"_o_s");
        if (salt==null || encryptedToken==null) return null;
        return PasswordUtils.decryptFromPassphrase(password, encryptedToken, salt);
    }
    static void encryptPassword(String server, String username, char[] localPassword, char[] remotePassword) {
        String remotePW = String.copyValueOf(remotePassword);
        String[] encryptedPWAndSalt = PasswordUtils.encryptFromPassphrase(remotePW, localPassword);
        PropertyUtils.set(server+"_"+username+"_o_e", encryptedPWAndSalt[0]);
        PropertyUtils.set(server+"_"+username+"_o_s", encryptedPWAndSalt[1]);
    }

}
