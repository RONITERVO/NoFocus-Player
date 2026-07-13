package dev.nofocus.folderplayer;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import static org.junit.Assert.*;

public class WifiAudioProtocolTest {
    private static final String CODE = "ABCDEFGHJKLMNPQR";

    @Test
    public void parsesAuthenticatedHello() throws Exception {
        byte[] key = WifiAudioProtocol.keyFromPairingCode(CODE);
        byte[] packet = hello(key, 19L, "Test PC");
        WifiAudioProtocol.Hello parsed = WifiAudioProtocol.parseHello(packet, packet.length, key);
        assertEquals(19L, parsed.sessionId);
        assertEquals(240, parsed.framesPerPacket);
        assertEquals("Test PC", parsed.senderName);
    }

    @Test(expected = GeneralSecurityException.class)
    public void rejectsModifiedHello() throws Exception {
        byte[] key = WifiAudioProtocol.keyFromPairingCode(CODE);
        byte[] packet = hello(key, 19L, "Test PC");
        packet[20] ^= 1;
        WifiAudioProtocol.parseHello(packet, packet.length, key);
    }

    @Test
    public void decryptsAuthenticatedPcmAndRejectsTampering() throws Exception {
        byte[] key = WifiAudioProtocol.keyFromPairingCode(CODE);
        byte[] pcm = new byte[240 * 4];
        Arrays.fill(pcm, (byte) 0x5a);
        byte[] packet = audio(key, 44L, 3, 720, pcm);
        WifiAudioProtocol.AudioPacket parsed = WifiAudioProtocol.decryptAudio(packet, packet.length, key);
        assertEquals(44L, parsed.sessionId);
        assertEquals(3L, parsed.sequence);
        assertArrayEquals(pcm, parsed.pcm);

        packet[packet.length - 1] ^= 1;
        try {
            WifiAudioProtocol.decryptAudio(packet, packet.length, key);
            fail("tampered ciphertext was accepted");
        } catch (GeneralSecurityException expected) {
            // Expected authentication failure.
        }
    }

    @Test
    public void recognizesFixedSizeDiscoveryAndAdvertisesPort() {
        byte[] request = new byte[]{'N', 'F', 'P', 'D', 2, 1, 0, 8};
        assertTrue(WifiAudioProtocol.isDiscoveryRequest(request, request.length));
        request[0] = 'X';
        assertFalse(WifiAudioProtocol.isDiscoveryRequest(request, request.length));
        byte[] response = WifiAudioProtocol.discoveryResponse();
        assertArrayEquals(new byte[]{'N', 'F', 'P', 'R', 2, 2, (byte) 0x9b, (byte) 0x8d}, response);
    }

    @Test
    public void helloAcknowledgementIsFixedSizeAndAuthenticated() throws Exception {
        byte[] key = WifiAudioProtocol.keyFromPairingCode(CODE);
        byte[] acknowledgement = WifiAudioProtocol.helloAcknowledgement(42L, key);
        assertEquals(32, acknowledgement.length);
        assertArrayEquals(new byte[]{'N', 'F', 'P', '2', 2, 3, 0, 32},
                Arrays.copyOf(acknowledgement, 8));
        acknowledgement[10] ^= 1;
        assertFalse(Arrays.equals(acknowledgement,
                WifiAudioProtocol.helloAcknowledgement(42L, key)));
    }

    private static byte[] hello(byte[] key, long session, String name) throws Exception {
        ByteBuffer body = ByteBuffer.allocate(48).order(ByteOrder.BIG_ENDIAN);
        body.putInt(0x4e465032).put((byte) 2).put((byte) 1).putShort((short) 64);
        body.putLong(session).putInt(48_000).put((byte) 2).put((byte) 2).putShort((short) 240);
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        body.put(Arrays.copyOf(nameBytes, 24));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        byte[] result = Arrays.copyOf(body.array(), 64);
        System.arraycopy(mac.doFinal(body.array()), 0, result, 48, 16);
        return result;
    }

    private static byte[] audio(byte[] key, long session, int sequence, long timestamp, byte[] pcm)
            throws Exception {
        ByteBuffer header = ByteBuffer.allocate(32).order(ByteOrder.BIG_ENDIAN);
        header.putInt(0x4e465032).put((byte) 2).put((byte) 2).putShort((short) 32);
        header.putLong(session).putInt(sequence).putLong(timestamp).putShort((short) 240)
                .putShort((short) (pcm.length + 16));
        ByteBuffer nonce = ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN).putLong(session).putInt(sequence);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                new GCMParameterSpec(128, nonce.array()));
        cipher.updateAAD(header.array());
        byte[] encrypted = cipher.doFinal(pcm);
        return ByteBuffer.allocate(header.capacity() + encrypted.length)
                .put(header.array()).put(encrypted).array();
    }
}
