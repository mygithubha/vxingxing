using System.Diagnostics;
using System.Text.Json;
using System.Text.Json.Nodes;

namespace AutoConnect.Windows;

public sealed class MainForm : Form
{
    private readonly TextBox _configPath = new() { ReadOnly = true, Width = 390 };
    private readonly Label _coreStatus = new() { AutoSize = true, Text = "核心：未启动" };
    private readonly Label _httpStatus = new() { AutoSize = true, Text = "HTTP：127.0.0.1:2081（未启动）" };
    private readonly Label _socksStatus = new() { AutoSize = true, Text = "SOCKS5：127.0.0.1:2082（未启动）" };
    private readonly Button _start = new() { Text = "启动代理", Width = 110 };
    private readonly Button _stop = new() { Text = "停止代理", Width = 110, Enabled = false };
    private readonly CheckBox _systemProxy = new() { Text = "同时启用 Windows 系统代理（仅覆盖遵循系统代理的软件）", AutoSize = true };
    private Process? _core;
    private string? _loadedConfig;
    private string? _generatedConfig;
    private bool _ownsSystemProxy;

    public MainForm()
    {
        Text = "Auto Connect · Windows";
        Width = 650;
        Height = 390;
        StartPosition = FormStartPosition.CenterScreen;
        FormBorderStyle = FormBorderStyle.FixedDialog;
        MaximizeBox = false;

        var layout = new TableLayoutPanel { Dock = DockStyle.Fill, Padding = new Padding(18), ColumnCount = 1, RowCount = 8 };
        layout.RowStyles.Add(new RowStyle(SizeType.Absolute, 42));
        layout.RowStyles.Add(new RowStyle(SizeType.Absolute, 48));
        layout.RowStyles.Add(new RowStyle(SizeType.Absolute, 34));
        layout.RowStyles.Add(new RowStyle(SizeType.Absolute, 34));
        layout.RowStyles.Add(new RowStyle(SizeType.Absolute, 34));
        layout.RowStyles.Add(new RowStyle(SizeType.Absolute, 45));
        layout.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
        layout.RowStyles.Add(new RowStyle(SizeType.Absolute, 28));

        var heading = new Label { Text = "Auto Connect for Windows 10", Font = new Font(Font.FontFamily, 16, FontStyle.Bold), AutoSize = true };
        layout.Controls.Add(heading, 0, 0);

        var fileRow = new FlowLayoutPanel { Dock = DockStyle.Fill, WrapContents = false };
        var browse = new Button { Text = "导入 sing-box 配置", Width = 145 };
        browse.Click += (_, _) => SelectConfig();
        fileRow.Controls.Add(browse);
        fileRow.Controls.Add(_configPath);
        layout.Controls.Add(fileRow, 0, 1);

        layout.Controls.Add(_coreStatus, 0, 2);
        layout.Controls.Add(_httpStatus, 0, 3);
        layout.Controls.Add(_socksStatus, 0, 4);

        var actionRow = new FlowLayoutPanel { Dock = DockStyle.Fill };
        _start.Click += async (_, _) => await StartProxyAsync();
        _stop.Click += (_, _) => StopProxy();
        actionRow.Controls.Add(_start);
        actionRow.Controls.Add(_stop);
        layout.Controls.Add(actionRow, 0, 5);

        var notes = new Label
        {
            Dock = DockStyle.Fill,
            AutoSize = false,
            Text = "浏览器扩展配置：HTTP 127.0.0.1:2081；SOCKS5 127.0.0.1:2082。\r\n本机代理默认仅允许本机访问，不会向局域网开放。\r\n当前版本先提供配置文件接入与本地代理入口；订阅导入、节点记忆/测速、TUN 全局模式仍在开发中。",
        };
        layout.Controls.Add(notes, 0, 6);
        layout.Controls.Add(_systemProxy, 0, 7);
        Controls.Add(layout);
        FormClosing += (_, _) => StopProxy();
    }

    private void SelectConfig()
    {
        using var dialog = new OpenFileDialog { Filter = "sing-box JSON 配置 (*.json)|*.json|所有文件 (*.*)|*.*" };
        if (dialog.ShowDialog(this) != DialogResult.OK) return;
        try
        {
            var json = JsonNode.Parse(File.ReadAllText(dialog.FileName)) as JsonObject
                ?? throw new InvalidDataException("配置文件根节点必须是 JSON 对象。");
            if (json["outbounds"] is not JsonArray outbounds || outbounds.Count == 0)
                throw new InvalidDataException("配置中没有 outbounds 节点。");
            _loadedConfig = dialog.FileName;
            _configPath.Text = Path.GetFileName(dialog.FileName);
            _coreStatus.Text = "核心：配置已载入，尚未启动";
        }
        catch (Exception ex) { MessageBox.Show(this, ex.Message, "配置读取失败", MessageBoxButtons.OK, MessageBoxIcon.Error); }
    }

    private async Task StartProxyAsync()
    {
        if (_loadedConfig is null) { MessageBox.Show(this, "请先导入 sing-box JSON 配置文件。"); return; }
        try
        {
            var source = JsonNode.Parse(await File.ReadAllTextAsync(_loadedConfig)) as JsonObject
                ?? throw new InvalidDataException("无效的 JSON 配置。");
            var inbounds = source["inbounds"] as JsonArray ?? new JsonArray();
            RemoveManagedInbound(inbounds, "autoconnect-http");
            RemoveManagedInbound(inbounds, "autoconnect-socks5");
            inbounds.Add(new JsonObject { ["type"] = "http", ["tag"] = "autoconnect-http", ["listen"] = "127.0.0.1", ["listen_port"] = 2081 });
            inbounds.Add(new JsonObject { ["type"] = "socks", ["tag"] = "autoconnect-socks5", ["listen"] = "127.0.0.1", ["listen_port"] = 2082 });
            source["inbounds"] = inbounds;

            _generatedConfig = Path.Combine(Path.GetTempPath(), $"autoconnect-{Guid.NewGuid():N}.json");
            await File.WriteAllTextAsync(_generatedConfig, source.ToJsonString(new JsonSerializerOptions { WriteIndented = true }));

            var corePath = Path.Combine(AppContext.BaseDirectory, "sing-box.exe");
            if (!File.Exists(corePath)) throw new FileNotFoundException("未找到 sing-box.exe。请先安装官方 Windows amd64 核心，并放在程序同目录。", corePath);

            _core = new Process
            {
                StartInfo = new ProcessStartInfo(corePath, $"run -c \"{_generatedConfig}\"")
                {
                    UseShellExecute = false, CreateNoWindow = true,
                    RedirectStandardError = true, RedirectStandardOutput = true,
                    WorkingDirectory = AppContext.BaseDirectory
                },
                EnableRaisingEvents = true
            };
            _core.Start();
            await Task.Delay(1200);
            if (_core.HasExited) throw new InvalidOperationException("sing-box 启动失败：" + await _core.StandardError.ReadToEndAsync());

            _coreStatus.Text = "核心：运行中";
            _httpStatus.Text = "HTTP：127.0.0.1:2081（监听中）";
            _socksStatus.Text = "SOCKS5：127.0.0.1:2082（监听中）";
            _start.Enabled = false;
            _stop.Enabled = true;

            if (_systemProxy.Checked)
            {
                WindowsProxy.SetHttpProxy("127.0.0.1:2081");
                _ownsSystemProxy = true;
            }
        }
        catch (Exception ex)
        {
            StopProxy();
            MessageBox.Show(this, ex.Message, "启动失败", MessageBoxButtons.OK, MessageBoxIcon.Error);
        }
    }

    private static void RemoveManagedInbound(JsonArray inbounds, string tag)
    {
        for (var i = inbounds.Count - 1; i >= 0; i--)
            if (inbounds[i]?["tag"]?.GetValue<string>() == tag) inbounds.RemoveAt(i);
    }

    private void StopProxy()
    {
        try { if (_core is { HasExited: false }) { _core.Kill(true); _core.WaitForExit(2500); } } catch { }
        _core?.Dispose();
        _core = null;
        if (_ownsSystemProxy) { WindowsProxy.Disable(); _ownsSystemProxy = false; }
        if (_generatedConfig is not null) { try { File.Delete(_generatedConfig); } catch { } _generatedConfig = null; }
        _coreStatus.Text = "核心：未启动";
        _httpStatus.Text = "HTTP：127.0.0.1:2081（未启动）";
        _socksStatus.Text = "SOCKS5：127.0.0.1:2082（未启动）";
        _start.Enabled = true;
        _stop.Enabled = false;
    }
}

internal static class WindowsProxy
{
    private const string InternetSettings = @"Software\Microsoft\Windows\CurrentVersion\Internet Settings";

    public static void SetHttpProxy(string address)
    {
        using var key = Microsoft.Win32.Registry.CurrentUser.OpenSubKey(InternetSettings, true)
            ?? throw new InvalidOperationException("无法访问 Windows 用户代理设置。");
        key.SetValue("ProxyEnable", 1, Microsoft.Win32.RegistryValueKind.DWord);
        key.SetValue("ProxyServer", address, Microsoft.Win32.RegistryValueKind.String);
        key.SetValue("ProxyOverride", "<local>;127.0.0.1;localhost", Microsoft.Win32.RegistryValueKind.String);
    }

    public static void Disable()
    {
        using var key = Microsoft.Win32.Registry.CurrentUser.OpenSubKey(InternetSettings, true);
        key?.SetValue("ProxyEnable", 0, Microsoft.Win32.RegistryValueKind.DWord);
    }
}
