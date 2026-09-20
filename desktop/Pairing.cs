using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using QRCoder;

namespace Outpost;

internal sealed partial class HostConfig
{
    public bool EnableDiscovery { get; set; } = true;
    public string HostName { get; set; } = Environment.MachineName;
    public string ProtectedPairingPhrase { get; set; } = "";
    internal string PairingPhrase()
    {
        if (ProtectedPairingPhrase == "") { NewPairingPhrase(); Save(); }
        return Encoding.UTF8.GetString(ProtectedData.Unprotect(Convert.FromBase64String(ProtectedPairingPhrase), null, DataProtectionScope.CurrentUser));
    }
    internal void NewPairingPhrase()
    {
        var raw = Convert.ToHexString(RandomNumberGenerator.GetBytes(16)).ToLowerInvariant();
        var phrase = string.Join("-", Enumerable.Range(0, 8).Select(i => raw.Substring(i * 4, 4)));
        ProtectedPairingPhrase = Convert.ToBase64String(ProtectedData.Protect(Encoding.UTF8.GetBytes(phrase), null, DataProtectionScope.CurrentUser));
    }
}

internal sealed partial class HostWindow
{
    readonly TextBox pairingName = Input("Laptop name"), pairingCode = Input("Pairing code");
    readonly CheckBox pairingEnabled = Check("Make this laptop discoverable to paired phones");
    readonly Label pairingStatus = new() { AutoSize = true };
    string registeredHost = "";

    string PairingRelayUrl()
    {
        if (!config.UseGateway || !Uri.TryCreate(config.GatewayUrl, UriKind.Absolute, out var url) || url.Scheme != "wss" || !url.AbsolutePath.EndsWith("/laptop"))
            throw new InvalidOperationException("Configure the HTTPS URL in VPS relay first, then start hosting.");
        var builder = new UriBuilder(url) { Scheme = "https", Path = url.AbsolutePath[..^7], Query = "", Fragment = "" };
        return builder.Uri.AbsoluteUri.TrimEnd('/');
    }
    string PairingInvitation()
    {
        var json = JsonSerializer.Serialize(new { relay = PairingRelayUrl(), phrase = config.PairingPhrase() });
        return "outpost://pair#" + Convert.ToBase64String(Encoding.UTF8.GetBytes(json)).TrimEnd('=').Replace('+', '-').Replace('/', '_');
    }
    void BuildPairing()
    {
        var p = Page("Pair a phone", "Your laptop, one scan away", "In the Android app, open Hosts and scan this laptop's QR code. Or enter the relay address and pairing code once. Your phone remembers the laptop when its Wi-Fi address changes.");
        pairingName.Text = config.HostName; pairingEnabled.Checked = config.EnableDiscovery;
        pairingCode.Text = config.PairingPhrase(); pairingCode.ReadOnly = true; pairingCode.UseSystemPasswordChar = true;
        Add(p, pairingStatus); Add(p, pairingEnabled); Field(p, "Name shown on your phone", pairingName);
        Field(p, "Private pairing code", pairingCode);
        Add(p, Row(Action("Show QR code", ShowPairingQr), Action("Copy pairing invitation", () => Clipboard.SetText(PairingInvitation()))));
        Add(p, Row(Action("Copy relay address", () => Clipboard.SetText(PairingRelayUrl())), Action("Copy pairing code", () => Clipboard.SetText(config.PairingPhrase()))));
        Add(p, Row(Action("Save pairing settings", SavePairing), Action("Generate new code", () => {
            if (online) throw new InvalidOperationException("Stop hosting before replacing the pairing code.");
            config.NewPairingPhrase(); config.Save(); pairingCode.Text = config.PairingPhrase();
            Log("Pairing code replaced. Start hosting, then pair your phones again.");
        })));
        Add(p, Label("Stop hosting before changing the name or pairing settings. Stopping hosting ends running terminal sessions. After starting, wait for Registered before pairing. A new code replaces discovery access; existing direct SSH credentials remain valid."));
        Add(p, Row(AsyncAction("Start hosting", StartHost), AsyncAction("Stop hosting", StopHost)));
    }
    void SavePairing()
    {
        var name = pairingName.Text.Trim();
        if (name.Length < 1 || name.Length > 60 || name.Any(char.IsControl)) throw new InvalidOperationException("Use a laptop name between 1 and 60 characters.");
        if (online && (name != config.HostName || pairingEnabled.Checked != config.EnableDiscovery)) throw new InvalidOperationException("Stop hosting before changing pairing settings.");
        if (pairingEnabled.Checked) _ = PairingRelayUrl();
        config.HostName = name; config.EnableDiscovery = pairingEnabled.Checked; config.Save();
        Log("Pairing settings saved. Start hosting to register this laptop.");
    }
    void ShowPairingQr()
    {
        if (!online || registeredHost == "" || gatewayState != "connected") throw new InvalidOperationException("Start hosting and wait for Registered before pairing a phone.");
        using var generator = new QRCodeGenerator();
        using var data = generator.CreateQrCode(PairingInvitation(), QRCodeGenerator.ECCLevel.M);
        using var qr = new QRCode(data);
        using var bitmap = qr.GetGraphic(7);
        using var dialog = new Form { Text = "Pair with " + config.HostName, StartPosition = FormStartPosition.CenterParent, ClientSize = new Size(480, 550), BackColor = Color.White, ForeColor = Color.Black, MinimizeBox = false, MaximizeBox = false, FormBorderStyle = FormBorderStyle.FixedDialog, Icon = Icon };
        dialog.Controls.Add(new PictureBox { Image = bitmap, Dock = DockStyle.Fill, SizeMode = PictureBoxSizeMode.Zoom, Padding = new Padding(12) });
        dialog.Controls.Add(new Label { Text = "Android: Hosts > Scan laptop QR\nKeep this code private. It pairs access to your workspace.", Dock = DockStyle.Bottom, Height = 72, TextAlign = ContentAlignment.MiddleCenter, Font = new Font("Segoe UI", 10) });
        dialog.ShowDialog(this);
    }
}
