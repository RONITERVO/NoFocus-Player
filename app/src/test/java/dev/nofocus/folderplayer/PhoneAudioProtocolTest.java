package dev.nofocus.folderplayer;

import org.junit.Test;
import java.security.MessageDigest;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import static org.junit.Assert.*;

public final class PhoneAudioProtocolTest {
    @Test public void reverseV3MatchesWindowsAndRequiresFreshReceiverProof() throws Exception {
        byte[] root = WifiAudioProtocol.keyFromPairingCode("ABCD-EFGH-JKLM-NPQR"), nonce = new byte[16];
        for(int i=0;i<nonce.length;i++)nonce[i]=(byte)i;
        byte[] hello = PhoneAudioProtocol.hello(root,7,"Desktop");
        assertEquals("557bf06294946167b8804396c9f55c84f67a7e10034f5bb57897ec98e10c583a",hash(hello));
        assertEquals(7,PhoneAudioProtocol.parseHello(hello,hello.length,root));
        byte[] challenge = PhoneAudioProtocol.challenge(root,7,nonce);
        assertArrayEquals(nonce,PhoneAudioProtocol.challengeNonce(challenge,48,root,7));
        assertNull(PhoneAudioProtocol.challengeNonce(challenge,48,root,8));
        byte[] key = PhoneAudioProtocol.sessionKey(root,7,nonce);
        assertEquals("bbe8ce2e06e5a4487da613b3fc988f93b9132d0c1ab043806667221dba014cd0",HexFormat.of().formatHex(key));
        byte[] confirm = PhoneAudioProtocol.confirm(key,7,nonce);
        assertEquals("661d8dcddc6255029cffd1e95df2d5804d1ac34630c63cfb6b79cce47cf2ec30",hash(confirm));
        assertTrue(PhoneAudioProtocol.isConfirmation(confirm,48,key,7,nonce));
        byte[] replacement = nonce.clone(); replacement[0] ^= 1;
        byte[] nextKey = PhoneAudioProtocol.sessionKey(root,7,replacement);
        assertFalse(PhoneAudioProtocol.isConfirmation(confirm,48,nextKey,7,replacement));
        byte[] ready = PhoneAudioProtocol.acknowledge(key,7);
        assertTrue(PhoneAudioProtocol.isAcknowledgement(ready,32,key,7));
        assertFalse(PhoneAudioProtocol.isAcknowledgement(ready,32,nextKey,7));
        byte[] pcm = new byte[960];for(int i=0;i<pcm.length;i++)pcm[i]=(byte)(i * 31);
        byte[] audio = PhoneAudioProtocol.audio(key,7,9,pcm);
        assertEquals("6b52630a57422bd3f55eacb482e07d77c49935bbe2caaee7b73ec8a837e40cc0",hash(audio));
        assertArrayEquals(pcm,PhoneAudioProtocol.decryptAudio(audio,audio.length,key).pcm);
        try {PhoneAudioProtocol.decryptAudio(audio,audio.length,nextKey);fail("old session accepted by a fresh key");}catch(GeneralSecurityException expected){}
        challenge[47]^=1;assertNull(PhoneAudioProtocol.challengeNonce(challenge,48,root,7));
        audio[audio.length-1]^=1;
        try {PhoneAudioProtocol.decryptAudio(audio,audio.length,key);fail("tampered audio accepted");}catch(GeneralSecurityException expected){}
        byte[] v2 = WifiAudioProtocol.createHello(root,7,"Old app");
        try {PhoneAudioProtocol.parseHello(v2,v2.length,root);fail("v2 downgrade accepted");}catch(GeneralSecurityException expected){}
    }
    @Test(expected = GeneralSecurityException.class) public void v3NeverWrapsSequence() throws Exception {
        PhoneAudioProtocol.audio(new byte[32],7,0x100000000L,new byte[960]);
    }
    @Test public void originalV2FixturesStayCompatible() throws Exception {
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
