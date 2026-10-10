package dev.nofocus.folderplayer;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Reverse audio v3: receiver challenge, session-key confirmation, then encrypted PCM. */
final class PhoneAudioProtocol {
    private static final int MAGIC = 0x4e465033;
    private PhoneAudioProtocol() { }
    private static byte[] packet(int size, int type, long session) {
        byte[] bytes = new byte[size];
        ByteBuffer.wrap(bytes).putInt(MAGIC).put((byte)3).put((byte)type).putShort((short)size).putLong(session);
        return bytes;
    }
    static boolean isType(byte[] bytes, int length, int type, int header) {
        if (bytes == null || length < header || length > bytes.length) return false;
        ByteBuffer b = ByteBuffer.wrap(bytes);
        return b.getInt() == MAGIC && b.get() == 3 && b.get() == type && (b.getShort() & 0xffff) == header;
    }
    private static byte[] hmac(byte[] key, byte[] bytes, int length) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key,"HmacSHA256")); mac.update(bytes,0,length); return mac.doFinal();
    }
    private static void sign(byte[] bytes, byte[] key) throws GeneralSecurityException {
        System.arraycopy(hmac(key,bytes,bytes.length - 16),0,bytes,bytes.length - 16,16);
    }
    private static boolean signed(byte[] bytes, int length, byte[] key) throws GeneralSecurityException {
        return MessageDigest.isEqual(Arrays.copyOf(hmac(key,bytes,length - 16),16),Arrays.copyOfRange(bytes,length - 16,length));
    }
    static byte[] hello(byte[] root, long session, String name) throws GeneralSecurityException {
        byte[] bytes = packet(64,1,session);
        ByteBuffer format = ByteBuffer.wrap(bytes); format.position(16);
        format.putInt(48000).put((byte)2).put((byte)2).putShort((short)240)
                .put(Arrays.copyOf(name.getBytes(StandardCharsets.UTF_8),24));
        sign(bytes,root); return bytes;
    }
    static long parseHello(byte[] bytes, int length, byte[] root) throws GeneralSecurityException {
        if (length != 64 || !isType(bytes,length,1,64) || !signed(bytes,length,root)) throw new GeneralSecurityException("Invalid v3 hello");
        ByteBuffer b = ByteBuffer.wrap(bytes); long session = b.getLong(8);
        if (b.getInt(16) != 48000 || bytes[20] != 2 || bytes[21] != 2 || b.getShort(22) != 240) throw new GeneralSecurityException("Unsupported PCM format");
        return session;
    }
    static byte[] sessionKey(byte[] root, long session, byte[] nonce) throws GeneralSecurityException {
        if (nonce.length != 16) throw new GeneralSecurityException("Invalid receiver nonce");
        byte[] label = "NoFocus phone-to-PC v3 session".getBytes(StandardCharsets.US_ASCII);
        byte[] input = ByteBuffer.allocate(label.length + 24).put(label).putLong(session).put(nonce).array();
        return hmac(root,input,input.length);
    }
    private static byte[] proof(byte[] key, long session, byte[] nonce, int type) throws GeneralSecurityException {
        if (nonce.length != 16) throw new GeneralSecurityException("Invalid receiver nonce");
        byte[] bytes = packet(48,type,session); System.arraycopy(nonce,0,bytes,16,16); sign(bytes,key); return bytes;
    }
    static byte[] challenge(byte[] root, long session, byte[] nonce) throws GeneralSecurityException { return proof(root,session,nonce,3); }
    static byte[] confirm(byte[] key, long session, byte[] nonce) throws GeneralSecurityException { return proof(key,session,nonce,4); }
    static byte[] challengeNonce(byte[] bytes, int length, byte[] root, long session) throws GeneralSecurityException {
        if (length != 48 || !isType(bytes,length,3,48) || ByteBuffer.wrap(bytes).getLong(8) != session || !signed(bytes,length,root)) return null;
        return Arrays.copyOfRange(bytes,16,32);
    }
    static boolean isConfirmation(byte[] bytes, int length, byte[] key, long session, byte[] nonce) throws GeneralSecurityException {
        return length == 48 && isType(bytes,length,4,48) && ByteBuffer.wrap(bytes).getLong(8) == session
                && MessageDigest.isEqual(nonce,Arrays.copyOfRange(bytes,16,32)) && signed(bytes,length,key);
    }
    static byte[] acknowledge(byte[] key, long session) throws GeneralSecurityException {
        byte[] bytes = packet(32,5,session); sign(bytes,key); return bytes;
    }
    static boolean isAcknowledgement(byte[] bytes, int length, byte[] key, long session) throws GeneralSecurityException {
        return length == 32 && isType(bytes,length,5,32) && ByteBuffer.wrap(bytes).getLong(8) == session && signed(bytes,length,key);
    }
    static byte[] audio(byte[] key, long session, long sequence, byte[] pcm) throws GeneralSecurityException {
        if (pcm.length != 960 || sequence < 0 || sequence > 0xffffffffL) throw new GeneralSecurityException("Invalid PCM packet or exhausted session");
        byte[] bytes = new byte[1008]; System.arraycopy(packet(32,2,session),0,bytes,0,32);
        ByteBuffer format = ByteBuffer.wrap(bytes); format.position(16);
        format.putInt((int)sequence).putLong(sequence * 240).putShort((short)240).putShort((short)976);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,Arrays.copyOfRange(bytes,8,20)));
        cipher.updateAAD(bytes,0,32); System.arraycopy(cipher.doFinal(pcm),0,bytes,32,976); return bytes;
    }
    static WifiAudioProtocol.AudioPacket decryptAudio(byte[] bytes, int length, byte[] key) throws GeneralSecurityException {
        if (length != 1008 || !isType(bytes,length,2,32)) throw new GeneralSecurityException("Invalid v3 audio");
        ByteBuffer b = ByteBuffer.wrap(bytes); long session = b.getLong(8), sequence = Integer.toUnsignedLong(b.getInt(16)), timestamp = b.getLong(20);
        if (timestamp != sequence * 240 || b.getShort(28) != 240 || b.getShort(30) != 976) throw new GeneralSecurityException("Invalid audio header");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,Arrays.copyOfRange(bytes,8,20)));
        cipher.updateAAD(bytes,0,32);
        return new WifiAudioProtocol.AudioPacket(session,sequence,timestamp,240,cipher.doFinal(bytes,32,976));
    }
}
