import unittest

from nofocus_sender import AUDIO_HEADER, FRAMES_PER_PACKET, audio_packet, hello_packet, pairing_key


class FakeAesGcm:
    def encrypt(self, nonce, pcm, aad):
        self.nonce = nonce
        self.pcm = pcm
        self.aad = aad
        return pcm + bytes(16)


class ProtocolTest(unittest.TestCase):
    def test_pairing_code_format_is_normalized(self):
        self.assertEqual(pairing_key("ABCD-EFGH-JKLM-NPQR"), pairing_key("abcdefghjklmnpqr"))

    def test_hello_has_fixed_authenticated_wire_size(self):
        packet = hello_packet(pairing_key("ABCDEFGHJKLMNPQR"), 7, "Desktop")
        self.assertEqual(64, len(packet))

    def test_audio_header_is_authenticated_and_nonce_is_unique_per_sequence(self):
        fake = FakeAesGcm()
        pcm = bytes(FRAMES_PER_PACKET * 4)
        packet = audio_packet(fake, 42, 9, 2160, pcm)
        self.assertEqual(AUDIO_HEADER.size + len(pcm) + 16, len(packet))
        self.assertEqual(packet[:AUDIO_HEADER.size], fake.aad)
        self.assertEqual(12, len(fake.nonce))


if __name__ == "__main__":
    unittest.main()
