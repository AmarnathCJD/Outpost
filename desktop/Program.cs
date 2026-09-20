using Microsoft.Win32;
using System.Diagnostics;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;

namespace Outpost;

internal static class Program
{
    [STAThread]
    static void Main(string[] args)
    {
        ApplicationConfiguration.Initialize();
        using var mutex = new Mutex(true, @"Local\Outpost.Desktop.Host", out var first);
        if (!first) { MessageBox.Show("Outpost is already running. Open it from the system tray.", "Outpost"); return; }
        try { Application.Run(new HostWindow(args.Contains("--tray"))); }
        catch (Exception e) { MessageBox.Show(e.Message, "Outpost could not start", MessageBoxButtons.OK, MessageBoxIcon.Error); }
    }
}

internal sealed partial class HostConfig
{
    public string Root { get; set; } = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "Outpost", "workspaces");
    public int Port { get; set; } = 8787;
    public string Relay { get; set; } = "";
    public bool UseRelay { get; set; }
    public bool UseGateway { get; set; }
    public string GatewayHost { get; set; } = "";
    public string GatewayUrl { get; set; } = "";
    public int GatewayControlPort { get; set; } = 8443;
    public int GatewayPhonePort { get; set; } = 2223;
    public string GatewayFingerprint { get; set; } = "";
    public string ProtectedGatewayToken { get; set; } = "";
    internal string GatewayToken() => ProtectedGatewayToken == "" ? "" : Encoding.UTF8.GetString(ProtectedData.Unprotect(Convert.FromBase64String(ProtectedGatewayToken), null, DataProtectionScope.CurrentUser));
    internal void SetGatewayToken(string value) => ProtectedGatewayToken = value == "" ? "" : Convert.ToBase64String(ProtectedData.Protect(Encoding.UTF8.GetBytes(value), null, DataProtectionScope.CurrentUser));
    public int SshPort { get; set; } = 2222;
    public int SshConfigVersion { get; set; }
    public string SshUser { get; set; } = "outpost";
    public string DirectInterface { get; set; } = "";
    public string DirectAddress { get; set; } = "";
    public int RelayPort { get; set; } = 18787;
    public int PreviewLocalPort { get; set; } = 8080;
    public int PreviewRelayPort { get; set; } = 18080;
    public bool Preview { get; set; }
    public bool AutoStart { get; set; }
    public bool AtLogin { get; set; }
    public bool KeepAwake { get; set; } = true;
    public string ProtectedToken { get; set; } = "";
    internal static string DirectoryPath => Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Outpost");
    internal static string ConfigPath => Path.Combine(DirectoryPath, "host.json");
    internal static HostConfig Load()
    {
        var config = File.Exists(ConfigPath) ? JsonSerializer.Deserialize<HostConfig>(File.ReadAllText(ConfigPath)) ?? throw new InvalidDataException("Host configuration is empty.") : new();
        if (config.SshConfigVersion < 1) { config.SshPort = 2222; config.SshUser = "outpost"; config.SshConfigVersion = 1; config.Save(); }
        return config;
    }
    internal string Token()
    {
        if (ProtectedToken.Length == 0)
        {
            ProtectedToken = Convert.ToBase64String(ProtectedData.Protect(RandomNumberGenerator.GetBytes(32), null, DataProtectionScope.CurrentUser));
            Save();
        }
        return Convert.ToHexString(ProtectedData.Unprotect(Convert.FromBase64String(ProtectedToken), null, DataProtectionScope.CurrentUser));
    }
    internal void Save()
    {
        Directory.CreateDirectory(DirectoryPath);
        var temp = ConfigPath + ".tmp";
        File.WriteAllText(temp, JsonSerializer.Serialize(this, new JsonSerializerOptions { WriteIndented = true }));
        File.Move(temp, ConfigPath, true);
    }
}

internal sealed partial class HostWindow : Form
{
    static readonly Color Background = Color.FromArgb(24, 24, 24), Surface = Color.FromArgb(31, 31, 31), Border = Color.FromArgb(55, 55, 61), Foreground = Color.FromArgb(230, 230, 233), Muted = Color.FromArgb(167, 171, 181), Accent = Color.FromArgb(0, 122, 204);
    readonly HostConfig config = HostConfig.Load();
    readonly string token;
    readonly HttpClient http = new() { Timeout = TimeSpan.FromSeconds(5) };
    readonly RelayJob relayJob = new();
    readonly ComboBox laptopAddress = new() { DropDownStyle = ComboBoxStyle.DropDownList, BackColor = Surface, ForeColor = Foreground, Width = 700, Margin = new Padding(0, 0, 0, 12), AccessibleName = "Laptop network address" };
    readonly TextBox sshUser = Input("Laptop SSH username"), addressOverride = Input("Laptop address override");
    readonly NumericUpDown sshPort = new() { Minimum = 1, Maximum = 65535, Value = 2222, BackColor = Surface, ForeColor = Foreground, Width = 700, Margin = new Padding(0, 0, 0, 12), AccessibleName = "Laptop SSH port" };
    readonly CheckBox useRelay = Check("Use an external SSH relay instead (optional)");
    readonly CheckBox useGateway = Check("Use the VPS relay for phone access");
    readonly TextBox gatewayHost = Input("VPS address"), gatewayUrl = Input("VPS WebSocket URL"), gatewayToken = Input("VPS relay token"), gatewayFingerprint = Input("VPS SSH fingerprint");
    readonly NumericUpDown gatewayControlPort = Number("VPS laptop port", 8443), gatewayPhonePort = Number("VPS phone port", 2223);
    readonly Label gatewayStatus = new() { AutoSize = true };
    string gatewayState = "disabled", gatewayError = "";
    bool sshListening;
    DateTime nextDirectCheck = DateTime.MinValue;
    readonly Panel content = new() { Dock = DockStyle.Fill };
    readonly FlowLayoutPanel navigation = new() { Dock = DockStyle.Left, Width = 210, FlowDirection = FlowDirection.TopDown, WrapContents = false, Padding = new Padding(12, 28, 12, 12) };
    readonly Label status = new() { Dock = DockStyle.Bottom, Height = 34, TextAlign = ContentAlignment.MiddleLeft, Padding = new Padding(16, 0, 0, 0) };
    readonly Label overview = new() { AutoSize = true };
    readonly TextBox root = Input("Workspace folder"), relayAlias = Input("SSH relay alias"), phoneDetails = Input("Phone connection details", true), logs = Input("Host log", true);
    readonly NumericUpDown port = Number("Backend port", 8787), relayPort = Number("Relay port", 18787), previewLocal = Number("Local preview port", 8080), previewRemote = Number("Preview relay port", 18080);
    readonly CheckBox autoStart = Check("Start hosting when Outpost opens"), atLogin = Check("Open Outpost at Windows sign-in"), awake = Check("Keep laptop awake while hosting (display may sleep)"), preview = Check("Forward a local development service too");
    readonly Button start = Button("Start hosting"), stop = Button("Stop hosting"), applyHost = Button("Save host settings"), applyRelay = Button("Save and reconnect relay");
    readonly TextBox gitName = Input("Git author name"), gitEmail = Input("Git author email"), prBase = Input("PR target branch"), migration = Input("Migration branch"), protect = Input("Protected branches"), environment = Input("Environment variables", true), tasks = Input("Saved commands", true);
    readonly ListView toolsList = new() { View = View.Details, Height = 246, FullRowSelect = true, HideSelection = false };
    readonly NotifyIcon tray;
    readonly System.Windows.Forms.Timer timer = new() { Interval = 3000 };
    readonly Dictionary<string, Control> pages = new();
    Process? backend, relay;
    bool online, busy, ticking, exiting, settingsLoaded, relayVerified;
    int relayFailures;
    DateTime nextRelay = DateTime.MinValue, relayStarted;
    JsonObject? serverSettings;
    string resolvedRelay = "", relayState = "Not configured";
    [DllImport("kernel32.dll")] static extern uint SetThreadExecutionState(uint flags);

    public HostWindow(bool trayMode)
    {
        token = config.Token();
        Text = "Outpost — Desktop host"; Name = "OutpostHost";
        Font = new Font("Segoe UI", 10f); BackColor = Background; ForeColor = Foreground;
        ClientSize = new Size(1060, 820); MinimumSize = new Size(920, 680);
        StartPosition = FormStartPosition.CenterScreen; AutoScaleMode = AutoScaleMode.Dpi;
        Icon = Icon.ExtractAssociatedIcon(Application.ExecutablePath) ?? SystemIcons.Application;
        http.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", token);
        root.Text = config.Root; port.Value = ClampPort(config.Port); relayAlias.Text = config.Relay; relayPort.Value = ClampPort(config.RelayPort);
        sshUser.Text = config.SshUser; sshPort.Value = Math.Clamp(config.SshPort, 1, 65535); addressOverride.Text = config.DirectAddress; useRelay.Checked = config.UseRelay;
        useGateway.Checked = config.UseGateway; gatewayHost.Text = config.GatewayHost; gatewayUrl.Text = config.GatewayUrl; gatewayToken.Text = config.GatewayToken(); gatewayToken.UseSystemPasswordChar = true;
        gatewayControlPort.Value = ClampPort(config.GatewayControlPort); gatewayPhonePort.Value = ClampPort(config.GatewayPhonePort); gatewayFingerprint.Text = config.GatewayFingerprint;
        previewLocal.Value = ClampPort(config.PreviewLocalPort); previewRemote.Value = ClampPort(config.PreviewRelayPort);
        preview.Checked = config.Preview; autoStart.Checked = config.AutoStart; atLogin.Checked = config.AtLogin; awake.Checked = config.KeepAwake;
        root.AccessibleDescription = "Parent folder containing your Git repositories";
        root.Name = "WorkspaceRoot"; start.Name = "StartHost"; stop.Name = "StopHost";
        navigation.BackColor = Surface; status.BackColor = Accent; status.ForeColor = Color.White;
        Controls.Add(content); Controls.Add(navigation); Controls.Add(status);
        var brand = Label("OUTPOST", 16, true); brand.Width = 155; brand.Height = 46; navigation.Controls.Add(brand);
        BuildWorkspace(); BuildPhone(); BuildGateway(); BuildPairing(); BuildSettings(); BuildTools();
        var logPage = Page("Activity", "Host and relay activity", "Connection errors appear here. Credentials are never written to this log.");
        logs.ReadOnly = true; logs.Height = 460; logs.Font = new Font("Consolas", 10); logPage.Controls.Add(logs);
        Add(logPage, Row(Action("Clear log", () => logs.Clear()), Action("Open configuration folder", () => OpenFolder(HostConfig.DirectoryPath))));
        tray = new NotifyIcon { Icon = Icon, Text = "Outpost desktop host", Visible = true };
        var menu = new ContextMenuStrip();
        menu.Items.Add("Open Outpost", null, (_, _) => RestoreWindow());
        menu.Items.Add("Start hosting", null, async (_, _) => await Run(StartHost));
        menu.Items.Add("Stop hosting", null, async (_, _) => await Run(StopHost));
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add("Exit and stop hosting", null, async (_, _) => await ExitHost());
        tray.ContextMenuStrip = menu; tray.DoubleClick += (_, _) => RestoreWindow();
        timer.Tick += async (_, _) => await Tick();
        FormClosing += (_, e) =>
        {
            if (e.CloseReason == CloseReason.WindowsShutDown) { exiting = true; StopRelay(); Kill(backend); tray.Visible = false; return; }
            if (!exiting) { e.Cancel = true; Hide(); tray.ShowBalloonTip(2500, "Outpost is in the tray", "Open Outpost from the system tray. Use Exit and stop hosting to quit.", ToolTipIcon.Info); }
        };
        Shown += async (_, _) =>
        {
            await Run(async () => { online = await Healthy(); if (online) Log("Reconnected to your running desktop backend."); else if (config.AutoStart) await StartHost(); await RefreshTools(); });
            if (trayMode) Hide();
            timer.Start(); UpdateState();
        };
        ShowPage("Workspace"); UpdateState(); UpdatePhone();
    }
    static int ClampPort(int value) => Math.Clamp(value, 1024, 65535);
    static Label Label(string text, float size = 10f, bool bold = false) => new() { Text = text, AutoSize = true, ForeColor = bold ? Foreground : Muted, Font = new Font("Segoe UI", size, bold ? FontStyle.Bold : FontStyle.Regular), Margin = new Padding(0, 4, 0, 8) };
    static TextBox Input(string name, bool multiline = false) => new() { AccessibleName = name, BackColor = Surface, ForeColor = Foreground, BorderStyle = BorderStyle.FixedSingle, Multiline = multiline, ScrollBars = multiline ? ScrollBars.Vertical : ScrollBars.None, Height = multiline ? 128 : 31, Width = 700, Margin = new Padding(0, 0, 0, 12), AcceptsReturn = multiline };
    static NumericUpDown Number(string name, int value) => new() { AccessibleName = name, Minimum = 1024, Maximum = 65535, Value = value, BackColor = Surface, ForeColor = Foreground, Width = 700, Height = 31, Margin = new Padding(0, 0, 0, 12) };
    static CheckBox Check(string text) => new() { Text = text, AccessibleName = text, AutoSize = true, Margin = new Padding(0, 5, 0, 8) };
    static Button Button(string text) { var b = new Button { Text = text, AccessibleName = text, AutoSize = true, MinimumSize = new Size(116, 38), FlatStyle = FlatStyle.Flat, BackColor = Surface, ForeColor = Foreground, Margin = new Padding(0, 0, 10, 0), Padding = new Padding(10, 2, 10, 2) }; b.FlatAppearance.BorderColor = Border; return b; }
    Button Action(string text, Action action) { var b = Button(text); b.Click += async (_, _) => await Run(() => { action(); return Task.CompletedTask; }); return b; }
    Button AsyncAction(string text, Func<Task> action) { var b = Button(text); b.Click += async (_, _) => await Run(action); return b; }
    static FlowLayoutPanel Row(params Control[] controls) { var p = new FlowLayoutPanel { AutoSize = true, WrapContents = true, Margin = new Padding(0, 6, 0, 16) }; p.Controls.AddRange(controls); return p; }
    static void Add(FlowLayoutPanel page, Control control) => page.Controls.Add(control);
    static void Field(FlowLayoutPanel page, string caption, Control control) { Add(page, Label(caption)); Add(page, control); }
    FlowLayoutPanel Page(string key, string title, string description)
    {
        var outer = new Panel { Dock = DockStyle.Fill, AutoScroll = true, Visible = false };
        var flow = new FlowLayoutPanel { AutoSize = true, FlowDirection = FlowDirection.TopDown, WrapContents = false, Dock = DockStyle.Top, Padding = new Padding(28, 24, 28, 28) };
        outer.Controls.Add(flow); content.Controls.Add(outer); pages[key] = outer;
        void ResizeItems() { var width = Math.Max(480, outer.ClientSize.Width - 72); foreach (Control c in flow.Controls) { c.Width = width; if (c is Label) c.MaximumSize = new Size(width, 0); } }
        outer.SizeChanged += (_, _) => ResizeItems(); flow.ControlAdded += (_, _) => ResizeItems();
        Add(flow, Label(title, 20, true)); Add(flow, Label(description));
        var nav = Button(key); nav.Width = 186; nav.Height = 44; nav.AutoSize = false; nav.UseMnemonic = false; nav.Font = new Font("Segoe UI", 10); nav.TextAlign = ContentAlignment.MiddleLeft; nav.Margin = new Padding(0, 4, 0, 4);
        nav.Click += (_, _) => ShowPage(key); navigation.Controls.Add(nav);
        return flow;
    }
    void ShowPage(string key) { foreach (var (name, page) in pages) page.Visible = name == key; foreach (Control nav in navigation.Controls) if (nav is Button) nav.BackColor = nav.Text == key ? Accent : Surface; }
    void BuildWorkspace()
    {
        var p = Page("Workspace", "Your laptop. Your workspace.", "Work on the same files from your desktop and phone. Leave this host running to return to your terminals later.");
        overview.Font = new Font("Segoe UI", 13, FontStyle.Bold); overview.Margin = new Padding(0, 12, 0, 16); Add(p, overview);
        Field(p, "Workspace folder · parent of your Git repositories", root);
        Add(p, Row(Action("Choose folder…", () => { using var dialog = new FolderBrowserDialog { InitialDirectory = root.Text, Description = "Choose the parent folder of your repositories", UseDescriptionForTitle = true }; if (dialog.ShowDialog(this) == DialogResult.OK) root.Text = dialog.SelectedPath; }), Action("Open folder", () => OpenFolder(root.Text))));
        Field(p, "Backend port · local to this computer", port);
        Add(p, awake); Add(p, autoStart); Add(p, atLogin);
        start.BackColor = Accent; start.Click += async (_, _) => await Run(StartHost);
        stop.Click += async (_, _) => await Run(StopHost);
        applyHost.Click += async (_, _) => await Run(() => { SaveHost(); return Task.CompletedTask; });
        Add(p, Row(start, stop, applyHost));
        Add(p, Label("Closing this window keeps Outpost in the tray. Stopping the host ends its terminals. Files and settings stay on disk. Laptop shutdown or sleep interrupts phone access."));
        Add(p, Row(AsyncAction("Refresh workspaces", async () => { var result = await Api(HttpMethod.Get, "/api/projects"); var names = result?.AsArray().Select(x => x?["name"]?.ToString()).ToArray() ?? []; MessageBox.Show(this, names.Length == 0 ? "No repositories found. Choose their parent folder, or clone a repository from your phone." : string.Join(Environment.NewLine, names), "Available workspaces"); })));
        var exit = Button("Exit and stop hosting"); exit.Click += async (_, _) => await ExitHost(); Add(p, Row(exit));
    }
    void BuildPhone()
    {
        var p = Page("Phone access", "Connect your phone", "Your laptop runs your workspace. Use the connection details below to reach its files and terminals, directly or through your configured relay.");
        Add(p, Row(Action("Pair a phone with QR or code", () => ShowPage("Pair a phone"))));
        RefreshAddresses();
        phoneDetails.ReadOnly = true; phoneDetails.Height = 190; Field(p, "Enter these details in Android → Settings → Connection", phoneDetails);
        Add(p, Row(Action("Copy laptop token", () => { Clipboard.SetText(token); Log("Laptop token copied to clipboard. Paste it in the phone connection settings."); }), Action("Copy connection details", () => Clipboard.SetText(phoneDetails.Text))));
        Add(p, Label("For access across changing Wi-Fi networks, configure the VPS relay section. Direct LAN access remains available while hosting.")); Add(p, useRelay);
        var direct = new FlowLayoutPanel { AutoSize = true, FlowDirection = FlowDirection.TopDown, WrapContents = false, Margin = new Padding(0) };
        var advanced = new FlowLayoutPanel { AutoSize = true, FlowDirection = FlowDirection.TopDown, WrapContents = false, Margin = new Padding(0) };
        void FitNested(FlowLayoutPanel group) { foreach (Control child in group.Controls) { child.Width = group.Width; if (child is Label) child.MaximumSize = new Size(group.Width, 0); } }
        direct.SizeChanged += (_, _) => FitNested(direct); advanced.SizeChanged += (_, _) => FitNested(advanced);
        Field(direct, "Laptop network · Wi-Fi for the same network, VPN for access away", laptopAddress);
        Field(direct, "Optional address override · laptop VPN name or reachable hostname", addressOverride);
        Field(direct, "Outpost SSH username", sshUser); Field(direct, "SSH port on this laptop", sshPort);
        Add(direct, Row(AsyncAction("Save direct connection", async () => { SaveDirect(); nextDirectCheck = DateTime.MinValue; await RefreshDirect(); }), AsyncAction("Refresh laptop addresses", async () => { RefreshAddresses(); await RefreshDirect(); })));
        Add(direct, Row(AsyncAction("Allow phone connections...", EnableDirectSsh), Action("Set up access away…", () => OpenSetup("-Vpn"))));
        Add(direct, Row(Action("Show SSH fingerprint", () => {
            var key = Path.Combine(HostConfig.DirectoryPath, "ssh", "host_ed25519.pub");
            if (!File.Exists(key)) throw new InvalidOperationException("Start hosting once to create the laptop SSH identity.");
            var blob = Convert.FromBase64String(File.ReadAllText(key).Split(' ', StringSplitOptions.RemoveEmptyEntries)[1]);
            var fingerprint = "SHA256:" + Convert.ToBase64String(SHA256.HashData(blob)).TrimEnd('=');
            MessageBox.Show(this, fingerprint, "Compare with the phone SSH fingerprint");
        }), Action("SSH key folder", () => OpenFolder(Path.Combine(HostConfig.DirectoryPath, "ssh")))));
        Add(direct, Label("SSH is built into Outpost. Allow phone connections once to add its Windows firewall rule. Use Copy laptop token for both the SSH password and API token. Hosting must stay running."));
        Add(direct, Label("A Wi-Fi address works only where that network is reachable. For mobile data, connect the phone and laptop with a VPN such as Tailscale, or configure your router for SSH forwarding. Select the laptop VPN address above once connected. Ubuntu is not needed."));
        Field(advanced, "SSH relay alias or user@host · optional external server", relayAlias);
        Field(advanced, "Backend port on the relay · choose a free port", relayPort);
        Add(advanced, preview); Field(advanced, "Optional service port on this laptop", previewLocal); Field(advanced, "Optional service port on the relay", previewRemote);
        applyRelay.Click += async (_, _) => await Run(async () => { SaveRelay(); StopRelay(); relayFailures = 0; nextRelay = DateTime.MinValue; if (online) await StartRelay(); UpdatePhone(); });
        Add(advanced, Row(applyRelay, AsyncAction("Check SSH connection", CheckRelay)));
        Add(advanced, Label("This optional mode connects through another SSH server. Verify that server once with ssh YOUR_ALIAS. It requires key authentication or a key unlocked in ssh-agent."));
        Add(p, direct); Add(p, advanced); direct.Visible = !config.UseRelay; advanced.Visible = config.UseRelay;
        useRelay.CheckedChanged += (_, _) => { config.UseRelay = useRelay.Checked; config.Save(); StopRelay(); nextRelay = DateTime.MinValue; direct.Visible = !config.UseRelay; advanced.Visible = config.UseRelay; UpdateState(); UpdatePhone(); };
    }
    void BuildGateway()
    {
        var p = Page("VPS relay", "Reach your laptop from anywhere", "Your laptop opens an outbound connection to the VPS. Changing Wi-Fi addresses and router restrictions do not require a static laptop IP. Files and terminals stay on the laptop.");
        Add(p, gatewayStatus); Add(p, useGateway);
        Field(p, "VPS address", gatewayHost);
        Field(p, "Optional HTTPS relay URL - wss://host/path/laptop", gatewayUrl);
        Field(p, "Laptop connection port on VPS", gatewayControlPort);
        Field(p, "Phone SSH port on VPS", gatewayPhonePort);
        Field(p, "Relay token from the VPS installer", gatewayToken);
        Field(p, "Relay SSH fingerprint from the VPS installer", gatewayFingerprint);
        Add(p, Row(Action("Save relay settings", SaveGateway), Action("Copy phone details", () => { UpdatePhone(); Clipboard.SetText(phoneDetails.Text); }), Action("Copy laptop token", () => Clipboard.SetText(token))));
        Add(p, Label("Stop hosting before changing relay settings, then start hosting again. The laptop reconnects automatically after Wi-Fi changes. The phone uses the laptop token for its SSH password and API token; the relay token is only for the laptop connection."));
        Add(p, Label("The VPS relay service must be running and both configured ports must be reachable. The relay transports an encrypted connection to the laptop and cannot browse its files. Sleeping or shutting down the laptop makes its workspace unavailable until it returns."));
    }
    void SaveGateway()
    {
        var host = gatewayHost.Text.Trim(); var url = gatewayUrl.Text.Trim(); var secret = gatewayToken.Text.Trim(); var fingerprint = gatewayFingerprint.Text.Trim();
        if (useGateway.Checked)
        {
            if (host == "" || host.Length > 253 || (!System.Net.IPAddress.TryParse(host, out _) && !Regex.IsMatch(host, @"^[a-zA-Z0-9][a-zA-Z0-9.-]*$"))) throw new InvalidOperationException("Enter the VPS IP address or hostname without a URL or port.");
            if (url != "" && (!Uri.TryCreate(url, UriKind.Absolute, out var parsed) || parsed.Scheme != "wss" || parsed.UserInfo != "" || parsed.Query != "" || parsed.Fragment != "" || !parsed.AbsolutePath.EndsWith("/laptop"))) throw new InvalidOperationException("Enter a wss:// relay URL ending in /laptop, without credentials, query or fragment.");
            if (secret.Length < 32 || !Regex.IsMatch(fingerprint, @"^SHA256:[A-Za-z0-9+/]{43}$")) throw new InvalidOperationException("Paste the relay token and SHA256 SSH fingerprint from the VPS installer.");
            if (gatewayControlPort.Value == gatewayPhonePort.Value) throw new InvalidOperationException("The laptop connection port and phone port must be different.");
        }
        var changed = config.UseGateway != useGateway.Checked || url != config.GatewayUrl || host != config.GatewayHost || secret != config.GatewayToken() || fingerprint != config.GatewayFingerprint || (int)gatewayControlPort.Value != config.GatewayControlPort || (int)gatewayPhonePort.Value != config.GatewayPhonePort;
        if (online && changed) throw new InvalidOperationException("Stop hosting before changing VPS relay settings, then start hosting again.");
        config.UseGateway = useGateway.Checked; config.GatewayHost = host; config.GatewayUrl = url; config.SetGatewayToken(secret); config.GatewayFingerprint = fingerprint;
        config.GatewayControlPort = (int)gatewayControlPort.Value; config.GatewayPhonePort = (int)gatewayPhonePort.Value;
        if (config.UseGateway) { config.UseRelay = false; useRelay.Checked = false; StopRelay(); }
        config.Save(); UpdatePhone(); Log("VPS relay settings saved.");
    }
    void RefreshAddresses()
    {
        var selected = (laptopAddress.SelectedItem as LaptopAddress)?.InterfaceId ?? config.DirectInterface;
        var addresses = DirectHost.Addresses();
        laptopAddress.Items.Clear(); laptopAddress.Items.AddRange(addresses.Cast<object>().ToArray());
        laptopAddress.SelectedItem = addresses.FirstOrDefault(x => x.InterfaceId == selected) ?? addresses.FirstOrDefault();
    }
    void SaveDirect()
    {
        var address = addressOverride.Text.Trim();
        if (address.Length > 253 || (address != "" && !System.Net.IPAddress.TryParse(address, out _) && !Regex.IsMatch(address, @"^[a-zA-Z0-9][a-zA-Z0-9.-]*$"))) throw new InvalidOperationException("Enter a laptop IP address or hostname without a URL or port.");
        var user = sshUser.Text.Trim();
        if (user.Length == 0 || user.Length > 128 || user.Any(char.IsWhiteSpace) || user.Any(char.IsControl)) throw new InvalidOperationException("Enter an Outpost SSH username without spaces.");
        if (sshPort.Value == port.Value) throw new InvalidOperationException("SSH and backend ports must be different.");
        if (online && (config.SshUser != user || config.SshPort != (int)sshPort.Value)) throw new InvalidOperationException("Stop hosting before changing the SSH username or port, then start again and update the firewall rule.");
        config.SshUser = user; config.SshPort = (int)sshPort.Value; config.DirectAddress = address; config.DirectInterface = (laptopAddress.SelectedItem as LaptopAddress)?.InterfaceId ?? "";
        config.Save(); Log("Direct laptop connection settings saved."); UpdatePhone();
    }
    async Task RefreshDirect()
    {
        sshListening = await DirectHost.SshListening(config.SshPort);
        nextDirectCheck = DateTime.UtcNow.AddSeconds(15); UpdateState(); UpdatePhone();
    }
    async Task EnableDirectSsh()
    {
        SaveDirect();
        var script = Path.Combine(AppContext.BaseDirectory, "allow-phone-windows.ps1");
        if (!File.Exists(script)) throw new FileNotFoundException("Extract the complete Windows ZIP; allow-phone-windows.ps1 is missing.");
        var info = new ProcessStartInfo("powershell.exe") { UseShellExecute = true, Verb = "runas", WindowStyle = ProcessWindowStyle.Hidden };
        foreach (var arg in new[] { "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", script, "-SshPort", config.SshPort.ToString(), "-ServerPath", Path.Combine(AppContext.BaseDirectory, "outpost-server.exe") }) info.ArgumentList.Add(arg);
        using var setup = Process.Start(info) ?? throw new InvalidOperationException("Could not open firewall setup.");
        await setup.WaitForExitAsync();
        if (setup.ExitCode != 0) { var path = Path.Combine(HostConfig.DirectoryPath, "firewall-setup.log"); throw new InvalidOperationException(File.Exists(path) ? File.ReadAllText(path).Trim() : "Firewall setup failed. Check firewall-setup.log in the configuration folder."); }
        Log("Windows firewall allows phone connections to Outpost.");
        nextDirectCheck = DateTime.MinValue;
        MessageBox.Show(this, "Outpost is allowed through Windows Firewall. Keep hosting running and connect with the details above.", "Phone access ready");
    }
    void BuildSettings()
    {
        var p = Page("Git & environment", "Settings for this workspace", "Shared with your phone. Reload before editing if you changed settings on Android. Environment values apply to new terminals and host commands.");
        Add(p, Row(AsyncAction("Load current settings", LoadSettings), AsyncAction("Save workspace settings", SaveSettings)));
        Field(p, "Git author name", gitName); Field(p, "Git author email", gitEmail); Field(p, "Feature PR target", prBase); Field(p, "Migration branch", migration); Field(p, "Protected branches · separated by commas", protect);
        Field(p, "Environment · NAME=value lines, or a JSON object for multiline values", environment);
        Field(p, "Saved PowerShell commands · Label=command lines, or a JSON object", tasks);
        Add(p, Label("Main/master protections stay enabled. Use separate migration workspaces and push migrations only after approval. API keys entered here are stored in the workspace's local settings file; keep the folder private."));
    }
    void BuildTools()
    {
        var p = Page("Developer tools", "Use your native Windows tools", "Outpost runs as your Windows user. Installed tools and existing GitHub, Codex and Claude logins are available to new terminals.");
        toolsList.BackColor = Surface; toolsList.ForeColor = Foreground; toolsList.BorderStyle = BorderStyle.FixedSingle;
        toolsList.Columns.Add("Tool", 155); toolsList.Columns.Add("Location / status", 510); Add(p, toolsList);
        Add(p, Row(AsyncAction("Refresh tools", RefreshTools), Action("Install core tools…", () => OpenSetup("-Core")), Action("Install AI CLIs…", () => OpenSetup("-AI")), Action("SQL Server setup…", () => OpenSetup("-Sql"))));
        Add(p, Label("Setup opens an interactive terminal so you can see installer prompts. Restart hosting after installing tools. SQL Server uses a native Express installation; existing SQL Server installations can be configured in Git & environment."));
        Add(p, Row(Action("Open PowerShell", () => OpenTerminal("")), Action("GitHub login", () => OpenTerminal("gh auth login --hostname github.com --git-protocol https --web; if ($LASTEXITCODE -eq 0) { gh auth setup-git }")), Action("Codex login", () => OpenTerminal("codex login --device-auth")), Action("Claude login", () => OpenTerminal("claude auth login"))));
        Add(p, Label("You can also start these login sessions from Android. Host environment settings apply to sessions started in the app; desktop login buttons use your Windows user environment."));
    }
    async Task Run(Func<Task> action)
    {
        if (busy) return; busy = true; while (ticking) await Task.Delay(50); UpdateState();
        try { await action(); }
        catch (Exception e) { Log(e.Message); ShowPage("Activity"); }
        finally { busy = false; UpdateState(); }
    }
    void Log(string message)
    {
        if (IsDisposed || !IsHandleCreated) return;
        if (InvokeRequired) { try { BeginInvoke(() => Log(message)); } catch (InvalidOperationException) { } return; }
        var clean = message.Replace(token, "[redacted]");
        if (logs.TextLength > 80000) logs.Text = logs.Text[^40000..];
        logs.AppendText($"{DateTime.Now:HH:mm:ss}  {clean}{Environment.NewLine}");
    }
    void SaveHost()
    {
        var target = Path.GetFullPath(root.Text.Trim());
        if (!Path.IsPathFullyQualified(root.Text.Trim())) throw new InvalidOperationException("Choose an absolute workspace folder.");
        if (online && (target != config.Root || (int)port.Value != config.Port)) throw new InvalidOperationException("Stop hosting before changing the workspace folder or backend port.");
        Directory.CreateDirectory(target);
        config.Root = target; config.Port = (int)port.Value; config.AutoStart = autoStart.Checked; config.KeepAwake = awake.Checked; config.AtLogin = atLogin.Checked;
        using var run = Registry.CurrentUser.CreateSubKey(@"Software\Microsoft\Windows\CurrentVersion\Run");
        if (config.AtLogin) run.SetValue("Outpost", $"\"{Environment.ProcessPath}\" --tray"); else run.DeleteValue("Outpost", false);
        config.Save(); Log("Host preferences saved.");
    }
    void SaveRelay()
    {
        var alias = relayAlias.Text.Trim();
        if (alias.Length > 200 || (alias.Length > 0 && (!Regex.IsMatch(alias, @"^[a-zA-Z0-9][a-zA-Z0-9_.@:\[\]-]*$") || alias.StartsWith('-')))) throw new InvalidOperationException("Enter a valid SSH alias or user@host.");
        if (preview.Checked && relayPort.Value == previewRemote.Value) throw new InvalidOperationException("Backend and preview relay ports must be different.");
        config.Relay = alias; config.RelayPort = (int)relayPort.Value; config.Preview = preview.Checked; config.PreviewLocalPort = (int)previewLocal.Value; config.PreviewRelayPort = (int)previewRemote.Value;
        config.Save(); resolvedRelay = "";
    }
    string Url(string path) => $"http://127.0.0.1:{config.Port}{path}";
    async Task<JsonNode?> Api(HttpMethod method, string path, JsonNode? body = null)
    {
        using var request = new HttpRequestMessage(method, Url(path));
        if (body != null) request.Content = JsonContent.Create(body);
        using var response = await http.SendAsync(request);
        var text = await response.Content.ReadAsStringAsync();
        if (!response.IsSuccessStatusCode) { string reason; try { reason = JsonNode.Parse(text)?["error"]?.ToString() ?? response.ReasonPhrase ?? "Request failed"; } catch { reason = response.ReasonPhrase ?? "Request failed"; } throw new InvalidOperationException(reason); }
        return JsonNode.Parse(text);
    }
    async Task<bool> Healthy() { try { var health = await Api(HttpMethod.Get, "/api/health"); gatewayState = health?["relay"]?["state"]?.ToString() ?? "disabled"; gatewayError = health?["relay"]?["error"]?.ToString() ?? ""; registeredHost = health?["relay"]?["hostId"]?.ToString() ?? ""; return health?["platform"]?.ToString() == "windows" && health?["status"]?.ToString() == "ready"; } catch { return false; } }
    async Task StartHost()
    {
        if (online) return;
        SaveHost(); SaveGateway(); if (config.UseRelay) SaveRelay(); else SaveDirect();
        if (await Healthy()) { online = true; Log("Attached to existing desktop backend."); return; }
        var exe = Path.Combine(AppContext.BaseDirectory, "outpost-server.exe");
        if (!File.Exists(exe)) throw new FileNotFoundException("Keep outpost-server.exe beside Outpost.exe. Extract the complete Windows ZIP first.");
        RefreshPath();
        var info = Hidden(exe);
        info.Environment["WFY_ROOT"] = config.Root; info.Environment["WFY_LISTEN"] = $"127.0.0.1:{config.Port}"; info.Environment["WFY_TOKEN"] = token; info.Environment.Remove("WFY_TOKEN_FILE");
        info.Environment["WFY_SSH_LISTEN"] = $"0.0.0.0:{config.SshPort}";
        info.Environment["WFY_SSH_DIR"] = Path.Combine(HostConfig.DirectoryPath, "ssh");
        info.Environment["WFY_SSH_USER"] = config.SshUser;
        foreach (var key in new[] { "WFY_RELAY_ADDRESS", "WFY_RELAY_TOKEN", "WFY_RELAY_FINGERPRINT", "WFY_RELAY_PUBLIC_PORT", "WFY_RELAY_URL", "WFY_PAIRING_PHRASE", "WFY_HOST_NAME" }) info.Environment.Remove(key);
        if (config.UseGateway)
        {
            info.Environment["WFY_RELAY_ADDRESS"] = $"{(config.GatewayHost.Contains(':') ? "[" + config.GatewayHost + "]" : config.GatewayHost)}:{config.GatewayControlPort}";
            info.Environment["WFY_RELAY_URL"] = config.GatewayUrl;
            if (config.EnableDiscovery && config.GatewayUrl != "") {info.Environment["WFY_PAIRING_PHRASE"] = config.PairingPhrase();info.Environment["WFY_HOST_NAME"] = config.HostName;}
            info.Environment["WFY_RELAY_TOKEN"] = config.GatewayToken(); info.Environment["WFY_RELAY_FINGERPRINT"] = config.GatewayFingerprint;
            info.Environment["WFY_RELAY_PUBLIC_PORT"] = config.GatewayPhonePort.ToString();
        }
        backend?.Dispose(); backend = StartLogged(info, "host");
        for (var i = 0; i < 40; i++) { if (backend.HasExited) throw new InvalidOperationException("Backend exited. Check Activity for the reason (the port may already be in use)."); if (await Healthy()) { online = true; Log("Native Windows host is ready."); nextRelay = DateTime.MinValue; return; } await Task.Delay(150); }
        Kill(backend); throw new TimeoutException("Backend did not become ready. Check Activity.");
    }
    async Task StopHost()
    {
        StopRelay();
        if (online) { try { await Api(HttpMethod.Post, "/api/host/stop"); } catch (HttpRequestException) { } }
        if (backend != null && !backend.HasExited) { try { await backend.WaitForExitAsync().WaitAsync(TimeSpan.FromSeconds(12)); } catch (TimeoutException) { Kill(backend); } }
        backend?.Dispose(); backend = null; online = false; settingsLoaded = false; serverSettings = null;
        Log("Hosting stopped. Workspace files are saved on disk."); UpdateState();
    }
    async Task Tick()
    {
        if (ticking || busy || exiting) return; ticking = true;
        try
        {
            var wasOnline = online; online = await Healthy();
            if (wasOnline && !online) { Log("Backend is unreachable. Check Activity, then start hosting again."); StopRelay(); }
            if (relay != null && relay.HasExited) relayState = "Disconnected; retrying automatically";
            if (!config.UseRelay && DateTime.UtcNow >= nextDirectCheck) { RefreshAddresses(); await RefreshDirect(); }
            if (online && !config.UseGateway && config.UseRelay && config.Relay != "" && (relay == null || relay.HasExited) && DateTime.UtcNow >= nextRelay) await StartRelay();
            if (relay != null && !relay.HasExited && !relayVerified && DateTime.UtcNow - relayStarted > TimeSpan.FromSeconds(4))
            {
                // Check SSH forwarding allocation; never report a working relay from process creation alone.
                var probe = await CaptureProcess("ssh.exe", ["-T", "-o", "BatchMode=yes", "-o", "StrictHostKeyChecking=yes", "-o", "ConnectTimeout=8", config.Relay, "curl --config -"], $"silent\nshow-error\nfail\nmax-time = 4\nurl = \"http://127.0.0.1:{config.RelayPort}/api/health\"\nheader = \"Authorization: Bearer {token}\"\n");
                if (probe.Exit == 0 && JsonNode.Parse(probe.Output)?["platform"]?.ToString() == "windows") { relayVerified = true; relayFailures = 0; relayState = "Forwarding to this laptop"; Log("Authenticated relay connection to the Windows host verified."); }
                else { relayState = "SSH connected; endpoint check unavailable"; relayStarted = DateTime.UtcNow.AddSeconds(25); }
            }
            UpdateState(); UpdatePhone();
        }
        catch (Exception e) { Log(e.Message); nextRelay = DateTime.UtcNow.AddSeconds(20); }
        finally { ticking = false; }
    }
    async Task StartRelay()
    {
        if (config.UseGateway || !config.UseRelay || config.Relay == "" || !online || exiting) return;
        if (relay != null && !relay.HasExited) return;
        relay?.Dispose(); relay = null; relayVerified = false;
        var args = new List<string> { "-N", "-T", "-o", "BatchMode=yes", "-o", "StrictHostKeyChecking=yes", "-o", "ExitOnForwardFailure=yes", "-o", "ServerAliveInterval=15", "-o", "ServerAliveCountMax=3", "-o", "ConnectTimeout=10", "-R", $"127.0.0.1:{config.RelayPort}:127.0.0.1:{config.Port}" };
        if (config.Preview) args.AddRange(["-R", $"127.0.0.1:{config.PreviewRelayPort}:127.0.0.1:{config.PreviewLocalPort}"]);
        args.Add(config.Relay);
        relay = StartLogged(Hidden("ssh.exe", args), "relay"); relayStarted = DateTime.UtcNow; relayState = "Connecting…";
        try { relayJob.Attach(relay); } catch { Kill(relay); throw; }
        nextRelay = DateTime.UtcNow.AddSeconds(Math.Min(60, 5 * ++relayFailures));
        await ResolveRelay();
    }
    void StopRelay() { Kill(relay); relay?.Dispose(); relay = null; relayVerified = false; relayState = config.Relay == "" ? "Not configured" : "Stopped"; }
    async Task ResolveRelay()
    {
        if (resolvedRelay.Length > 0 || config.Relay == "") return;
        var result = await CaptureProcess("ssh.exe", ["-G", config.Relay]);
        if (result.Exit != 0) return;
        var values = new Dictionary<string, string>();
        foreach (var line in result.Output.Split('\n')) { var pair = line.Trim().Split(' ', 2); if (pair.Length == 2) values.TryAdd(pair[0], pair[1]); }
        resolvedRelay = $"SSH host: {values.GetValueOrDefault("hostname", config.Relay)}    port: {values.GetValueOrDefault("port", "22")}\r\nSSH user: {values.GetValueOrDefault("user", "your server user")}";
    }
    async Task CheckRelay()
    {
        SaveRelay(); StopRelay(); nextRelay = DateTime.MinValue; if (config.Relay == "") throw new InvalidOperationException("Enter an SSH relay alias first.");
        var result = await CaptureProcess("ssh.exe", ["-T", "-o", "BatchMode=yes", "-o", "StrictHostKeyChecking=yes", "-o", "ConnectTimeout=10", config.Relay, "printf outpost-relay-ready"]);
        if (result.Exit != 0 || !result.Output.Contains("outpost-relay-ready")) throw new InvalidOperationException("SSH check failed. Open Activity for details. Run ssh YOUR_ALIAS in a terminal to verify the host and configure your key.");
        await ResolveRelay(); UpdatePhone(); Log("SSH connection verified."); MessageBox.Show(this, "SSH key authentication works. Start hosting to open the phone relay.", "Relay ready");
    }
    void UpdateState()
    {
        start.Enabled = !busy && !online; stop.Enabled = !busy && online; applyHost.Enabled = !busy; applyRelay.Enabled = !busy;
        useRelay.Enabled = !config.UseGateway; root.ReadOnly = online; port.Enabled = !online; sshPort.Enabled = !online; sshUser.ReadOnly = online; overview.Text = online ? "Hosting on this computer" : "Ready when you are";
        pairingStatus.Text = online && gatewayState == "connected" && registeredHost != "" ? "Registered - ready to pair" : "Start hosting with HTTPS relay and discovery enabled to register.";
        gatewayStatus.Text = $"Relay: {(online ? gatewayState : "host stopped")}{(gatewayError == "" ? "" : "\r\n" + gatewayError)}";
        status.Text = $"{(online ? "Hosting" : "Stopped")}   |   PowerShell   |   {(config.UseGateway ? "VPS relay: " + gatewayState : config.UseRelay ? "Relay: " + relayState : "Laptop SSH: " + (sshListening ? "listening" : "not listening"))}";
        SetThreadExecutionState(online && config.KeepAwake ? 0x80000001 : 0x80000000);
        if (tray != null) tray.Text = online ? "Outpost — hosting" : "Outpost — stopped";
    }
    void UpdatePhone()
    {
        if (config.UseGateway)
        {
            phoneDetails.Text = $"Phone SSH host: {config.GatewayHost}    port: {config.GatewayPhonePort}\r\nSSH user: {config.SshUser}\r\nBackend host: 127.0.0.1    backend port: {config.Port}\r\nSSH password + API token: use Copy laptop token\r\nRelay URL: {(config.GatewayUrl == "" ? "leave empty" : config.GatewayUrl[..^6] + "phone")}\r\nVPS relay: {(online ? gatewayState : "host stopped")}\r\nFiles and terminals run on this laptop. Its Wi-Fi IP can change.";
            return;
        }
        if (config.UseRelay)
        {
            phoneDetails.Text = config.Relay == "" ? "Enter an SSH relay alias below, or turn off relay mode to connect directly to this laptop." : $"{(resolvedRelay.Length > 0 ? resolvedRelay : "SSH server: " + config.Relay)}\r\nBackend host: 127.0.0.1    backend port: {config.RelayPort}\r\nAPI token: use Copy laptop token above\r\nRelay: {relayState}";
            return;
        }
        var selected = laptopAddress.SelectedItem as LaptopAddress;
        var host = config.DirectAddress != "" ? config.DirectAddress : selected?.Address ?? "No connected network — refresh addresses";
        phoneDetails.Text = $"Laptop SSH host: {host}    port: {config.SshPort}\r\nSSH user: {config.SshUser}\r\nBackend host: 127.0.0.1    backend port: {config.Port}\r\nSSH password + API token: use Copy laptop token above\r\nLaptop SSH: {(sshListening ? "listening locally — phone must reach the laptop network" : "not listening - start hosting and check Activity")}\r\n{(selected?.IsVpn == true ? "Use this VPN address with both devices connected to the VPN." : "For mobile data, use a reachable laptop VPN/public address.")}";
    }
    async Task LoadSettings()
    {
        serverSettings = (await Api(HttpMethod.Get, "/api/settings"))?.AsObject() ?? throw new InvalidDataException("Missing settings.");
        string Value(string key) => serverSettings[key]?.ToString() ?? "";
        gitName.Text = Value("gitName"); gitEmail.Text = Value("gitEmail"); prBase.Text = Value("prBase"); migration.Text = Value("migrationBranch");
        protect.Text = string.Join(", ", serverSettings["protected"]?.AsArray().Select(x => x?.ToString()) ?? []);
        environment.Text = FormatMap(serverSettings["env"]); tasks.Text = FormatMap(serverSettings["tasks"]);
        settingsLoaded = true; Log("Loaded workspace settings.");
    }
    static string FormatMap(JsonNode? node)
    {
        if (node == null) return "";
        if (node.AsObject().Any(x => x.Key.Contains('=') || x.Key.Contains('\n') || (x.Value?.ToString().IndexOfAny(['\r', '\n']) ?? -1) >= 0)) return node.ToJsonString(new JsonSerializerOptions { WriteIndented = true });
        return string.Join("\r\n", node.AsObject().Select(x => $"{x.Key}={x.Value?.ToString()}"));
    }
    static JsonObject ParseMap(string text, bool env)
    {
        var result = new JsonObject();
        if (text.TrimStart().StartsWith('{'))
        {
            var parsed = JsonNode.Parse(text)?.AsObject() ?? throw new InvalidOperationException("Enter a JSON object with string values.");
            foreach (var (key, value) in parsed) if (value is not JsonValue json || !json.TryGetValue<string>(out _)) throw new InvalidOperationException("Environment values and commands must be strings.");
            return parsed;
        }
        foreach (var line in text.Replace("\r", "").Split('\n'))
        {
            if (string.IsNullOrWhiteSpace(line) || line.StartsWith('#')) continue;
            var split = line.IndexOf('='); if (split < 1) throw new InvalidOperationException("Each variable or command needs NAME=value on its own line.");
            var key = line[..split].Trim();
            if (env && !Regex.IsMatch(key, @"^[A-Za-z_][A-Za-z0-9_]*$")) throw new InvalidOperationException($"Invalid environment variable name: {key}");
            if (result.ContainsKey(key)) throw new InvalidOperationException($"Duplicate name: {key}");
            result[key] = line[(split + 1)..];
        }
        return result;
    }
    async Task SaveSettings()
    {
        if (!settingsLoaded || serverSettings == null) throw new InvalidOperationException("Load current settings before editing.");
        var current = await Api(HttpMethod.Get, "/api/settings");
        if (!JsonNode.DeepEquals(current, serverSettings)) throw new InvalidOperationException("Settings changed on another device. Reload current settings before saving.");
        var draft = serverSettings.DeepClone().AsObject();
        draft["gitName"] = gitName.Text.Trim(); draft["gitEmail"] = gitEmail.Text.Trim(); draft["prBase"] = prBase.Text.Trim(); draft["migrationBranch"] = migration.Text.Trim();
        draft["protected"] = JsonSerializer.SerializeToNode(protect.Text.Split(',', StringSplitOptions.TrimEntries | StringSplitOptions.RemoveEmptyEntries));
        draft["env"] = ParseMap(environment.Text, true); draft["tasks"] = ParseMap(tasks.Text, false);
        await Api(HttpMethod.Put, "/api/settings", draft); await LoadSettings(); Log("Workspace settings saved. Open a new terminal to use changed environment values.");
    }
    static ProcessStartInfo Hidden(string exe, IEnumerable<string>? args = null)
    {
        var info = new ProcessStartInfo(exe) { UseShellExecute = false, CreateNoWindow = true, RedirectStandardError = true, RedirectStandardOutput = true };
        if (args != null) foreach (var arg in args) info.ArgumentList.Add(arg);
        return info;
    }
    Process StartLogged(ProcessStartInfo info, string label)
    {
        var process = new Process { StartInfo = info };
        process.OutputDataReceived += (_, e) => { if (!string.IsNullOrWhiteSpace(e.Data)) Log($"{label}: {e.Data}"); };
        process.ErrorDataReceived += (_, e) => { if (!string.IsNullOrWhiteSpace(e.Data)) Log($"{label}: {e.Data}"); };
        process.Start(); process.BeginOutputReadLine(); process.BeginErrorReadLine(); return process;
    }
    async Task<(int Exit, string Output)> CaptureProcess(string exe, string[] args, string? input = null)
    {
        var info = Hidden(exe, args); info.RedirectStandardInput = input != null;
        using var p = new Process { StartInfo = info }; p.Start();
        if (input != null) { await p.StandardInput.WriteAsync(input); p.StandardInput.Close(); }
        var output = p.StandardOutput.ReadToEndAsync(); var error = p.StandardError.ReadToEndAsync();
        try { await p.WaitForExitAsync().WaitAsync(TimeSpan.FromSeconds(18)); } catch { Kill(p); throw; }
        var errors = await error; if (p.ExitCode != 0 && errors.Length > 0) Log(errors.Trim());
        return (p.ExitCode, await output);
    }
    static void Kill(Process? process) { try { if (process != null && !process.HasExited) process.Kill(true); } catch (InvalidOperationException) { } }
    static void RefreshPath()
    {
        var parts = new[] { Environment.GetEnvironmentVariable("PATH", EnvironmentVariableTarget.Machine), Environment.GetEnvironmentVariable("PATH", EnvironmentVariableTarget.User), Environment.GetEnvironmentVariable("PATH"), Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "npm"), Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), ".local", "bin"), Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "go", "bin") };
        // Refreshing tools must not keep appending the previous complete PATH. Besides
        // growing forever, that can exceed cmd.exe's limit and break npm CLI wrappers.
        var entries = parts.Where(x => !string.IsNullOrWhiteSpace(x)).SelectMany(x => x!.Split(';')).Select(x => x.Trim().Trim('"')).Where(x => x.Length > 0).Distinct(StringComparer.OrdinalIgnoreCase);
        Environment.SetEnvironmentVariable("PATH", string.Join(';', entries));
    }
    async Task RefreshTools()
    {
        RefreshPath(); toolsList.Items.Clear();
        foreach (var (name, exe) in new[] { ("Git", "git.exe"), ("Go", "go.exe"), ("GitHub CLI", "gh.exe"), ("Search (ripgrep)", "rg.exe"), ("Node", "node.exe"), ("Codex", "codex.cmd"), ("Claude", "claude.exe"), ("PowerShell 7", "pwsh.exe"), ("SSH relay", "ssh.exe"), ("SQL client", "sqlcmd.exe") })
        {
            var found = await CaptureProcess("where.exe", [exe]);
            if (found.Exit != 0 && name == "Claude") found = await CaptureProcess("where.exe", ["claude.cmd"]);
            if (found.Exit != 0 && name == "Codex") found = await CaptureProcess("where.exe", ["codex.exe"]);
            toolsList.Items.Add(new ListViewItem([name, found.Exit == 0 ? found.Output.Split('\r', '\n')[0] : (name == "PowerShell 7" ? "Optional — Windows PowerShell is available" : "Not found in PATH")]));
        }
    }
    void OpenSetup(string option)
    {
        var script = Path.Combine(AppContext.BaseDirectory, "setup-windows.ps1");
        if (!File.Exists(script)) throw new FileNotFoundException("setup-windows.ps1 is missing. Extract the complete Windows release.");
        var info = new ProcessStartInfo("powershell.exe") { UseShellExecute = true, Verb = option == "-Sql" ? "runas" : "open" };
        foreach (var arg in new[] { "-NoProfile", "-NoExit", "-ExecutionPolicy", "Bypass", "-File", script, option }) info.ArgumentList.Add(arg);
        Process.Start(info);
    }
    void OpenTerminal(string command)
    {
        RefreshPath(); var info = new ProcessStartInfo("powershell.exe") { UseShellExecute = true, WorkingDirectory = Directory.Exists(config.Root) ? config.Root : HostConfig.DirectoryPath };
        foreach (var arg in new[] { "-NoLogo", "-NoExit", "-ExecutionPolicy", "Bypass" }) info.ArgumentList.Add(arg);
        if (command != "") { info.ArgumentList.Add("-Command"); info.ArgumentList.Add(command); }
        Process.Start(info);
    }
    static void OpenFolder(string path) { Directory.CreateDirectory(path); Process.Start(new ProcessStartInfo("explorer.exe") { UseShellExecute = true, ArgumentList = { path } }); }
    void RestoreWindow() { Show(); WindowState = FormWindowState.Normal; Activate(); }
    async Task ExitHost()
    {
        if (busy) return; busy = true; exiting = true; timer.Stop(); while (ticking) await Task.Delay(50);
        try { await StopHost(); } catch (Exception e) { Log(e.Message); Kill(backend); StopRelay(); }
        SetThreadExecutionState(0x80000000); tray.Visible = false; tray.Dispose(); relayJob.Dispose(); http.Dispose(); Close();
    }
}
