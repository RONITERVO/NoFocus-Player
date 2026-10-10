using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace NoFocus.Desktop;

internal sealed class AppConfig
{
    public string PhoneAddress { get; set; } = "";
    public string ProtectedPairingCode { get; set; } = "";
    public string ProtectedReceiverCode { get; set; } = "";
    public int ReceiverBufferPackets { get; set; } = 4;

    internal string ReceiverCode
    {
        get
        {
            try { return Encoding.UTF8.GetString(ProtectedData.Unprotect(Convert.FromBase64String(ProtectedReceiverCode),
                null, DataProtectionScope.CurrentUser)); }
            catch (Exception error) when (error is CryptographicException or FormatException) { return ""; }
        }
        set => ProtectedReceiverCode = Convert.ToBase64String(ProtectedData.Protect(Encoding.UTF8.GetBytes(value),
            null, DataProtectionScope.CurrentUser));
    }

    private static string DirectoryPath => Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "NoFocus Speaker");
    private static string FilePath => Path.Combine(DirectoryPath, "settings.json");

    internal string PairingCode
    {
        get
        {
            if (string.IsNullOrEmpty(ProtectedPairingCode)) return "";
            try
            {
                byte[] plain = ProtectedData.Unprotect(Convert.FromBase64String(ProtectedPairingCode), null,
                    DataProtectionScope.CurrentUser);
                return Encoding.UTF8.GetString(plain);
            }
            catch (CryptographicException)
            {
                return "";
            }
        }
        set
        {
            byte[] protectedBytes = ProtectedData.Protect(Encoding.UTF8.GetBytes(value), null,
                DataProtectionScope.CurrentUser);
            ProtectedPairingCode = Convert.ToBase64String(protectedBytes);
        }
    }

    internal static AppConfig Load(string? file = null)
    {
        file ??= FilePath;
        try
        {
            return File.Exists(file)
                ? JsonSerializer.Deserialize<AppConfig>(File.ReadAllText(file)) ?? new AppConfig()
                : new AppConfig();
        }
        catch (Exception)
        {
            return new AppConfig();
        }
    }

    internal static void SaveReceiverBuffer(int packets, string? file = null)
    {
        // Manual setup can save a different instance while the home window stays open.
        AppConfig latest = Load(file);
        latest.ReceiverBufferPackets = Math.Clamp(packets, 2, 8);
        latest.Save(file);
    }

    internal void Save(string? file = null)
    {
        file ??= FilePath;
        Directory.CreateDirectory(Path.GetDirectoryName(file)!);
        string temporary = file + ".tmp";
        File.WriteAllText(temporary, JsonSerializer.Serialize(this, new JsonSerializerOptions { WriteIndented = true }));
        File.Move(temporary, file, true);
    }
}
