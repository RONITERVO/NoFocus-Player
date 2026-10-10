namespace NoFocus.Desktop;

internal static class AppConfigTests
{
    internal static void Run()
    {
        string directory = Path.Combine(Path.GetTempPath(), "nofocus-config-test-" + Guid.NewGuid());
        string file = Path.Combine(directory, "settings.json");
        try
        {
            new AppConfig { PhoneAddress = "192.168.1.2", ProtectedPairingCode = "old", ProtectedReceiverCode = "old-reverse" }.Save(file);
            AppConfig home = AppConfig.Load(file), manual = AppConfig.Load(file);
            manual.PhoneAddress = "192.168.1.9"; manual.PairingCode = "ABCDEFGHIJKLMNOP"; manual.ReceiverCode = "QRSTUVWXYZABCDEF"; manual.Save(file);
            home.ReceiverBufferPackets = 8;
            AppConfig.SaveReceiverBuffer(home.ReceiverBufferPackets, file);
            AppConfig saved = AppConfig.Load(file);
            if (saved.PhoneAddress != manual.PhoneAddress || saved.ProtectedPairingCode != manual.ProtectedPairingCode
                || saved.ProtectedReceiverCode != manual.ProtectedReceiverCode || saved.ReceiverBufferPackets != 8)
                throw new Exception("Changing the buffer overwrote manual pairing settings.");
        }
        finally { if (Directory.Exists(directory)) Directory.Delete(directory, true); }
        Console.WriteLine("Changing the audio buffer preserves newer manual pairing settings.");
    }
}
