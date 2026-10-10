package dev.nofocus.folderplayer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Locale;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Versioned, authenticated wire format shared with the desktop sender. */
final class WifiAudioProtocol {
    static final int PORT = 39821;
    static final int PC_RECEIVER_PORT = 39822;
    static final int FRAMES_PER_PACKET = 240;
    static final int PCM_BYTES = FRAMES_PER_PACKET * 4;
    static final int SAMPLE_RATE = 48_000;
    static final int CHANNELS = 2;
    static final int BYTES_PER_SAMPLE = 2;
    static final int HELLO_SIZE = 64;
    static final int AUDIO_HEADER_SIZE = 32;
    static final int GCM_TAG_SIZE = 16;
    static final int MAX_DATAGRAM_SIZE = 4096;

    private static final int MAGIC = 0x4e465032; // NFP2
    private static final byte VERSION = 2;
    private static final byte TYPE_HELLO = 1;
    private static final byte TYPE_AUDIO = 2;
    private static final byte[] DISCOVERY_REQUEST = new byte[]{'N', 'F', 'P', 'D', VERSION, 1, 0, 8};
    private static final byte[] DISCOVERY_RESPONSE = new byte[]{
            'N', 'F', 'P', 'R', VERSION, 2, (byte) (PORT >>> 8), (byte) PORT
    };

    private WifiAudioProtocol() {
    }

    static byte[] keyFromPairingCode(String pairingCode) throws GeneralSecurityException {
        String normalized = pairingCode == null ? "" : pairingCode.replace("-", "").trim().toUpperCase(Locale.ROOT);
        if (normalized.length() < 16) {
            throw new GeneralSecurityException("Pairing code is invalid");
        }
        return MessageDigest.getInstance("SHA-256").digest(normalized.getBytes(StandardCharsets.US_ASCII));
    }

    static Hello parseHello(byte[] packet, int length, byte[] key) throws GeneralSecurityException {
        if (length != HELLO_SIZE || !hasPrefix(packet, length, TYPE_HELLO, HELLO_SIZE)) {
            throw new GeneralSecurityException("Invalid hello packet");
        }
        byte[] expected = hmac(key, packet, 0, HELLO_SIZE - 16);
        byte[] actual = Arrays.copyOfRange(packet, HELLO_SIZE - 16, HELLO_SIZE);
        if (!MessageDigest.isEqual(Arrays.copyOf(expected, 16), actual)) {
            throw new GeneralSecurityException("Hello authentication failed");
        }

        ByteBuffer input = ByteBuffer.wrap(packet, 8, HELLO_SIZE - 8).order(ByteOrder.BIG_ENDIAN);
        long sessionId = input.getLong();
        int sampleRate = input.getInt();
        int channels = input.get() & 0xff;
        int bytesPerSample = input.get() & 0xff;
        int framesPerPacket = input.getShort() & 0xffff;
        byte[] nameBytes = new byte[24];
        input.get(nameBytes);
        int nameLength = 0;
        while (nameLength < nameBytes.length && nameBytes[nameLength] != 0) {
            nameLength++;
        }
        String senderName = new String(nameBytes, 0, nameLength, StandardCharsets.UTF_8).trim();
        if (sampleRate != SAMPLE_RATE || channels != CHANNELS || bytesPerSample != BYTES_PER_SAMPLE
                || framesPerPacket < 120 || framesPerPacket > 960) {
            throw new GeneralSecurityException("Unsupported audio format");
        }
        return new Hello(sessionId, framesPerPacket, senderName.isEmpty() ? "Computer" : senderName);
    }

    static AudioPacket decryptAudio(byte[] packet, int length, byte[] key) throws GeneralSecurityException {
        if (length < AUDIO_HEADER_SIZE + GCM_TAG_SIZE
                || length > MAX_DATAGRAM_SIZE
                || !hasPrefix(packet, length, TYPE_AUDIO, AUDIO_HEADER_SIZE)) {
            throw new GeneralSecurityException("Invalid audio packet");
        }
        ByteBuffer header = ByteBuffer.wrap(packet, 8, AUDIO_HEADER_SIZE - 8).order(ByteOrder.BIG_ENDIAN);
        long sessionId = header.getLong();
        long sequence = Integer.toUnsignedLong(header.getInt());
        long timestampFrames = header.getLong();
        int frameCount = header.getShort() & 0xffff;
        int encryptedLength = header.getShort() & 0xffff;
        int expectedPlainLength = frameCount * CHANNELS * BYTES_PER_SAMPLE;
        if (frameCount < 1 || frameCount > 960 || encryptedLength != expectedPlainLength + GCM_TAG_SIZE
                || length != AUDIO_HEADER_SIZE + encryptedLength) {
            throw new GeneralSecurityException("Invalid audio payload size");
        }

        byte[] nonce = ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN)
                .putLong(sessionId).putInt((int) sequence).array();
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        cipher.updateAAD(packet, 0, AUDIO_HEADER_SIZE);
        byte[] pcm = cipher.doFinal(packet, AUDIO_HEADER_SIZE, encryptedLength);
        return new AudioPacket(sessionId, sequence, timestampFrames, frameCount, pcm);
    }

    static boolean isDiscoveryRequest(byte[] packet, int length) {
        if (packet == null || length != DISCOVERY_REQUEST.length) {
            return false;
        }
        return MessageDigest.isEqual(DISCOVERY_REQUEST, Arrays.copyOf(packet, length));
    }

    static byte[] discoveryResponse() {
        return Arrays.copyOf(DISCOVERY_RESPONSE, DISCOVERY_RESPONSE.length);
    }

    static byte[] helloAcknowledgement(long sessionId, byte[] key) throws GeneralSecurityException {
        byte[] acknowledgement = new byte[32];
        ByteBuffer header = ByteBuffer.wrap(acknowledgement).order(ByteOrder.BIG_ENDIAN);
        header.putInt(MAGIC).put(VERSION).put((byte) 3).putShort((short) 32).putLong(sessionId);
        byte[] authentication = hmac(key, acknowledgement, 0, 16);
        System.arraycopy(authentication, 0, acknowledgement, 16, 16);
        return acknowledgement;
    }

    // The reverse direction uses the existing v2 wire format on its own port.
    static byte[] createHello(byte[] key, long sessionId, String name) throws GeneralSecurityException {
        byte[] packet = new byte[HELLO_SIZE];
        ByteBuffer out = ByteBuffer.wrap(packet).order(ByteOrder.BIG_ENDIAN);
        out.putInt(MAGIC).put(VERSION).put(TYPE_HELLO).putShort((short) HELLO_SIZE);
        out.putLong(sessionId).putInt(SAMPLE_RATE).put((byte) CHANNELS).put((byte) BYTES_PER_SAMPLE)
                .putShort((short) FRAMES_PER_PACKET);
        out.put(Arrays.copyOf(name.getBytes(StandardCharsets.UTF_8), 24));
        System.arraycopy(hmac(key, packet, 0, 48), 0, packet, 48, 16);
        return packet;
    }

    static byte[] encryptAudio(byte[] key, long sessionId, long sequence, byte[] pcm) throws GeneralSecurityException {
        if (pcm.length != PCM_BYTES || sequence < 0 || sequence > 0xffffffffL)
            throw new GeneralSecurityException("Invalid PCM packet or exhausted session");
        byte[] packet = new byte[AUDIO_HEADER_SIZE + PCM_BYTES + GCM_TAG_SIZE];
        ByteBuffer out = ByteBuffer.wrap(packet).order(ByteOrder.BIG_ENDIAN);
        out.putInt(MAGIC).put(VERSION).put(TYPE_AUDIO).putShort((short) AUDIO_HEADER_SIZE)
                .putLong(sessionId).putInt((int) sequence).putLong(sequence * FRAMES_PER_PACKET)
                .putShort((short) FRAMES_PER_PACKET).putShort((short) (PCM_BYTES + GCM_TAG_SIZE));
        byte[] nonce = ByteBuffer.allocate(12).putLong(sessionId).putInt((int) sequence).array();
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        cipher.updateAAD(packet, 0, AUDIO_HEADER_SIZE);
        System.arraycopy(cipher.doFinal(pcm), 0, packet, AUDIO_HEADER_SIZE, PCM_BYTES + GCM_TAG_SIZE);
        return packet;
    }

    static boolean isAcknowledgement(byte[] packet, int length, byte[] key, long sessionId) throws GeneralSecurityException {
        return length == 32 && MessageDigest.isEqual(Arrays.copyOf(packet, length), helloAcknowledgement(sessionId, key));
    }

    static byte[] discoveryRequest() { return DISCOVERY_REQUEST.clone(); }

    static boolean isPcDiscoveryResponse(byte[] packet, int length) {
        byte[] expected = discoveryResponse();
        expected[6] = (byte) (PC_RECEIVER_PORT >>> 8); expected[7] = (byte) PC_RECEIVER_PORT;
        return length == expected.length && MessageDigest.isEqual(Arrays.copyOf(packet, length), expected);
    }

    private static boolean hasPrefix(byte[] packet, int length, byte type, int headerSize) {
        if (packet == null || length < 8) {
            return false;
        }
        ByteBuffer prefix = ByteBuffer.wrap(packet, 0, 8).order(ByteOrder.BIG_ENDIAN);
        return prefix.getInt() == MAGIC
                && prefix.get() == VERSION
                && prefix.get() == type
                && (prefix.getShort() & 0xffff) == headerSize;
    }

    private static byte[] hmac(byte[] key, byte[] data, int offset, int length) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        mac.update(data, offset, length);
        return mac.doFinal();
    }

    static final class Hello {
        final long sessionId;
        final int framesPerPacket;
        final String senderName;

        Hello(long sessionId, int framesPerPacket, String senderName) {
            this.sessionId = sessionId;
            this.framesPerPacket = framesPerPacket;
            this.senderName = senderName;
        }
    }

    static final class AudioPacket {
        final long sessionId;
        final long sequence;
        final long timestampFrames;
        final int frameCount;
        final byte[] pcm;

        AudioPacket(long sessionId, long sequence, long timestampFrames, int frameCount, byte[] pcm) {
            this.sessionId = sessionId;
            this.sequence = sequence;
            this.timestampFrames = timestampFrames;
            this.frameCount = frameCount;
            this.pcm = pcm;
        }
    }
}
