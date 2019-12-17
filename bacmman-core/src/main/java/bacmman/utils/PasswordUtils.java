package bacmman.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.*;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.InvalidKeyException;
import java.security.Key;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.KeySpec;
import java.util.Base64;

public class PasswordUtils {
    private static final Logger logger = LoggerFactory.getLogger(PasswordUtils.class);
    public static String[] encryptFromPassphrase(String text, char[] passphrase) {
        SecureRandom random = new SecureRandom();
        byte[] salt = new byte[16];
        random.nextBytes(salt);
        KeySpec spec = new PBEKeySpec(passphrase, salt, 65536, 256); // AES-256
        try {
            SecretKeyFactory f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            byte[] key = f.generateSecret(spec).getEncoded();
            String enc = encrypt(text, key);
            String saltString = Base64.getEncoder().encodeToString(salt);
            return new String[]{enc, saltString};
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
           throw new RuntimeException(e);
        }
    }
    public static String decryptFromPassphrase(char[] passphrase, String enc, String salt) {
        byte[] saltB = Base64.getDecoder().decode(salt);
        KeySpec spec = new PBEKeySpec(passphrase, saltB, 65536, 256); // AES-256
        try {
            SecretKeyFactory f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1");
            byte[] key = f.generateSecret(spec).getEncoded();
            return decrypt(enc, key);
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new RuntimeException(e);
        }
    }
    public static String encrypt(String text, byte[] key) {
        Key aesKey = new SecretKeySpec(key, "AES");
        try {
            Cipher cipher = Cipher.getInstance("AES");
            // encrypt the text
            cipher.init(Cipher.ENCRYPT_MODE, aesKey);
            byte[] encrypted = cipher.doFinal(text.getBytes());
            return Base64.getEncoder().encodeToString(encrypted);
            //StringBuilder sb = new StringBuilder();
            //for (byte b: encrypted) sb.append((char)b);
            //return sb.toString();
        } catch (NoSuchAlgorithmException|NoSuchPaddingException|BadPaddingException|InvalidKeyException|IllegalBlockSizeException e) {
            throw new RuntimeException(e);
        }
    }

   public static String decrypt(String enc, byte[] key) {
       try {
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
       } catch (NoSuchAlgorithmException|NoSuchPaddingException|BadPaddingException|InvalidKeyException|IllegalBlockSizeException e) {
           throw new RuntimeException(e);
       }
   }
}
