package dev.nofocus.folderplayer;

import org.junit.Test;
import java.security.MessageDigest;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import static org.junit.Assert.*;

public final class PhoneAudioProtocolTest {
    @Test public void reverseSenderMatchesWindowsAndOriginalV2Fixtures() throws Exception {
        byte[] key = WifiAudioProtocol.keyFromPairingCode("ABCD-EFGH-JKLM-NPQR");
        byte[] hello = WifiAudioProtocol.createHello(key, 7, "Desktop");
        assertEquals("ecafd3d9a8658ac4e47367c5ecbee76ddc9dc35e5ae63a9396a9cfc91cb3b8b9", hash(hello));
        assertEquals(7, WifiAudioProtocol.parseHello(hello, hello.length, key).sessionId);
        byte[] pcm = new byte[960];
        for (int i = 0; i < pcm.length; i++) pcm[i] = (byte)(i * 31);
        byte[] packet = WifiAudioProtocol.encryptAudio(key, 7, 9, pcm);
        assertEquals("710bf212c2fa9dcc850841445065ef9f88ae64532a01822805c25ae751f3b0ec", hash(packet));
        assertArrayEquals(pcm, WifiAudioProtocol.decryptAudio(packet, packet.length, key).pcm);
        byte[] ack = WifiAudioProtocol.helloAcknowledgement(7, key);
        assertTrue(WifiAudioProtocol.isAcknowledgement(ack, ack.length, key, 7));
        assertFalse(WifiAudioProtocol.isAcknowledgement(ack, ack.length, key, 8));
        ack[31] ^= 1;
        assertFalse(WifiAudioProtocol.isAcknowledgement(ack, ack.length, key, 7));
    }
    @Test(expected = GeneralSecurityException.class) public void neverReusesNonceAfterSequenceWrap() throws Exception {
        WifiAudioProtocol.encryptAudio(new byte[32], 7, 0x100000000L, new byte[960]);
    }
    @Test public void discoversOnlyThePcReceiverPort() {
        assertTrue(WifiAudioProtocol.isDiscoveryRequest(WifiAudioProtocol.discoveryRequest(), 8));
        byte[] response = new byte[]{'N','F','P','R',2,2,(byte)0x9b,(byte)0x8e};
        assertTrue(WifiAudioProtocol.isPcDiscoveryResponse(response, 8));
        assertFalse(WifiAudioProtocol.isPcDiscoveryResponse(WifiAudioProtocol.discoveryResponse(), 8));
    }
    @Test public void validatesPcAddressBeforeSharing() {
        assertTrue(PhoneAudioActivity.validAddress("192.168.1.10"));
        assertTrue(PhoneAudioActivity.validAddress("10.0.0.255")); // Valid host on networks wider than /24.
        for (String host : new String[]{"", "localhost", "1.2.3", "1.2.3.999", "127.0.0.1", "224.0.0.1", "0.0.0.0"})
            assertFalse(host, PhoneAudioActivity.validAddress(host));
    }
    private static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
