package bacmman.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.*;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.*;
import java.security.spec.KeySpec;
import java.util.Base64;

public class PasswordUtils {
    private static final Logger logger = LoggerFactory.getLogger(PasswordUtils.class);
    public static String[] encryptFromPassphrase(String text, char[] passphrase) throws GeneralSecurityException {
        SecureRandom random = new SecureRandom();
        byte[] salt = new byte[16];
        random.nextBytes(salt);
        KeySpec spec = new PBEKeySpec(passphrase, salt, 65536, 256); // AES-256
        SecretKeyFactory f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        byte[] key = f.generateSecret(spec).getEncoded();
        String enc = encrypt(text, key);
        String saltString = Base64.getEncoder().encodeToString(salt);
        return new String[]{enc, saltString};
    }
    public static String decryptFromPassphrase(char[] passphrase, String enc, String salt) throws GeneralSecurityException {
        byte[] saltB = Base64.getDecoder().decode(salt);
        KeySpec spec = new PBEKeySpec(passphrase, saltB, 65536, 256); // AES-256
        SecretKeyFactory f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        byte[] key = f.generateSecret(spec).getEncoded();
        return decrypt(enc, key);
    }
    public static String encrypt(String text, byte[] key) throws NoSuchPaddingException, NoSuchAlgorithmException, InvalidKeyException, IllegalBlockSizeException, BadPaddingException {
        Key aesKey = new SecretKeySpec(key, "AES");
        Cipher cipher = Cipher.getInstance("AES");
        // encrypt the text
        cipher.init(Cipher.ENCRYPT_MODE, aesKey);
        byte[] encrypted = cipher.doFinal(text.getBytes());
        return Base64.getEncoder().encodeToString(encrypted);
    }

   public static String decrypt(String enc, byte[] key) throws GeneralSecurityException {
       Key aesKey = new SecretKeySpec(key, "AES");
       Cipher cipher = Cipher.getInstance("AES");
       /*byte[] bb = new byte[enc.length()];
       for (int i=0; i<enc.length(); i++) {
           bb[i] = (byte) enc.charAt(i);
       }*/
       byte[] encB = Base64.getDecoder().decode(enc);
       // decrypt the text
       cipher.init(Cipher.DECRYPT_MODE, aesKey);
       return  new String(cipher.doFinal(encB));
   }
}
