using System.IO;
using System.Threading;
using System.Text.Json;
using System.Text.Json.Nodes;
using LifeLog.Core;
using Microsoft.Web.WebView2.Core;
using Microsoft.Win32;

namespace LifeLog.Dashboard.Windows;

/// <summary>
/// 렌더러 호스트 API(Documents/새 구조 설계.md 2.5 "렌더러 호스트 API").
/// 페이지는 chrome.webview.postMessage({id, from, method, args})를 보내고 {id, result|error}를 받는다.
/// from은 "shell"(내장) 또는 렌더러 플러그인 id. 플러그인은 inputs에 선언한 출력만 query할 수 있다.
/// </summary>
public sealed class Bridge
{
    private readonly Db _db;
    private readonly Config _cfg;
    private readonly CoreWebView2 _core;
    private readonly System.Windows.Window _owner;
    private readonly Dictionary<string, Manifest> _renderers = new();   // id → 매니페스트
    private readonly string _settingsPath = Path.Combine(Config.Dir, "settings.json");
    public static string PluginsDir => Path.Combine(Config.Dir, "plugins");

    public Bridge(Db db, Config cfg, CoreWebView2 core, System.Windows.Window owner)
    {
        _db = db; _cfg = cfg; _core = core; _owner = owner;
    }

    /// <summary>plugins/<id>를 plugin-<id>.lifelog로 매핑하고 등록부에 올린다.</summary>
    public async Task MapPluginsAsync()
    {
        _renderers.Clear();
        if (!Directory.Exists(PluginsDir)) return;
        foreach (var dir in Directory.GetDirectories(PluginsDir))
        {
            var mf = Path.Combine(dir, "manifest.json");
            if (!File.Exists(mf)) continue;
            try
            {
                var m = Manifest.Load(mf);
                if (m.Type != "renderer") continue;
                await _db.RegisterPluginAsync(m, _cfg.Device);
                _renderers[m.Id] = m;
                _core.SetVirtualHostNameToFolderMapping($"plugin-{m.Id}.lifelog", dir, CoreWebView2HostResourceAccessKind.Allow);
            }
            catch (Exception e) { Log($"렌더러 로드 실패 {dir}: {e.Message}"); }
        }
    }

    private JsonObject LoadSettings()
    {
        var o = File.Exists(_settingsPath) ? JsonNode.Parse(File.ReadAllText(_settingsPath))!.AsObject() : new JsonObject();
        o["day_start"] ??= "04:00";
        o["tz"] ??= TimeZoneInfo.Local.Id;
        o["views"] ??= new JsonArray();
        o["categories"] ??= new JsonArray();
        return o;
    }

    /// <summary>저장소 plugins/widget-*(빌드 출력 bundled-plugins/)을 처음 보거나 버전이 올라갔으면 대시보드 plugins/에 설치하고 켠다.</summary>
    public async Task InstallBundledAsync()
    {
        var src = Path.Combine(AppContext.BaseDirectory, "bundled-plugins");
        if (!Directory.Exists(src)) return;
        foreach (var dir in Directory.GetDirectories(src))
        {
            var mf = Path.Combine(dir, "manifest.json");
            if (!File.Exists(mf)) continue;
            try
            {
                var m = Manifest.Load(mf);
                var installed = Path.Combine(PluginsDir, m.Id, "manifest.json");
                if (File.Exists(installed) && Manifest.Load(installed).Version == m.Version) continue;
                await PluginInstaller.InstallAsync(_db, _cfg.Device, PluginsDir, dir, grantAll: true, "renderer");
                Log($"내장 위젯 설치: {m.Id} {m.Version}");
            }
            catch (Exception e) { Log($"내장 위젯 설치 실패 {dir}: {e.Message}"); }
        }
    }

    public async Task HandleAsync(CoreWebView2WebMessageReceivedEventArgs e)
    {
        JsonElement msg;
        try { msg = JsonDocument.Parse(e.WebMessageAsJson).RootElement; } catch { return; }
        var id = msg.GetProperty("id").GetString();
        var from = msg.TryGetProperty("from", out var f) ? f.GetString() ?? "shell" : "shell";
        var method = msg.GetProperty("method").GetString();
        var args = msg.TryGetProperty("args", out var a) ? a : default;
        object? result = null; string? error = null;
        try
        {
            result = method switch
            {
                "query" => await QueryAsync(from, args),
                "outputs" => await _db.ListOutputsAsync(),
                "renderers" => await RenderersAsync(),
                "settings" => LoadSettings(),
                "setSettings" => SetSettings(args),
                "installPlugin" => await InstallPluginAsync(args),
                "registry" => await _db.ListRegistryAsync(),
                "setEnabled" => await SetEnabledAsync(args),
                "reloadCollector" => ReloadCollector(),
                "device" => _cfg.Device,
                "log" => LogJs(from, args),
                _ => throw new InvalidOperationException("모르는 메서드: " + method),
            };
        }
        catch (Exception ex) { error = ex.Message; }
        string reply;
        try { reply = JsonSerializer.Serialize(new { id, result, error }, new JsonSerializerOptions { PropertyNamingPolicy = JsonNamingPolicy.CamelCase }); }
        catch (Exception ex) { Log($"응답 직렬화 실패 {method}: {ex.Message}"); reply = JsonSerializer.Serialize(new { id, result = (object?)null, error = "응답 직렬화 실패: " + ex.Message }); }
        _core.PostWebMessageAsJson(reply);
    }

    private async Task<JsonElement> QueryAsync(string from, JsonElement args)
    {
        var table = args[0].GetString()!;
        var opts = args[1];
        // 입력 권한: 플러그인은 inputs에 선언한 "수집기/출력@버전"만. 뷰("수집기/출력")로 물어도 같은 출력이면 허용.
        if (from != "shell")
        {
            if (!_renderers.TryGetValue(from, out var m)) throw new InvalidOperationException("등록되지 않은 렌더러: " + from);
            var inputs = JsonDocument.Parse(m.Raw).RootElement.GetProperty("inputs").EnumerateArray().Select(x => x.GetString()!).ToList();
            var baseName = table.Contains('@') ? table[..table.IndexOf('@')] : table;
            if (!inputs.Any(i => i == table || i[..i.IndexOf('@')] == baseName))
                throw new InvalidOperationException($"inputs에 선언되지 않은 출력: {table}");
        }
        var outputs = await _db.ListOutputsAsync();
        var baseTable = table.Contains('@') ? table[..table.IndexOf('@')] : table;
        var matches = outputs.Where(o => o.View == baseTable).ToList();
        if (matches.Count == 0) throw new InvalidOperationException("없는 출력: " + table);
        // 뷰로 읽는다: 모든 열 집합 버전의 합집합
        var cols = new Dictionary<string, string>();
        foreach (var o in matches) foreach (var (k, v) in o.Columns) cols[k] = v;
        var meta = new TableMeta(baseTable, matches[0].IsSpan, cols);
        var spec = QuerySpec.Parse(baseTable, opts);
        var json = await _db.QueryAsync(spec, meta);
        return JsonDocument.Parse(json).RootElement;
    }

    private async Task<object> RenderersAsync()
    {
        var list = new List<object>();
        foreach (var (id, m) in _renderers)
        {
            var inst = await _db.GetInstallAsync(id, _cfg.Device);
            var raw = JsonDocument.Parse(m.Raw).RootElement;
            list.Add(new
            {
                id, name = m.Name, version = m.Version,
                slot = raw.GetProperty("slot").GetString(),
                entry = $"https://plugin-{id}.lifelog/" + m.Runtimes["webview"],
                enabled = inst?.Enabled ?? false,
                unmet = inst is null ? Array.Empty<string>() : JsonSerializer.Deserialize<string[]>(await UnmetAsync(id))!,
                settings = inst?.Settings,
                decl = raw.TryGetProperty("settings", out var sd) && sd.ValueKind == JsonValueKind.Array ? sd : (object?)null,
            });
        }
        return list;
    }

    private async Task<string> UnmetAsync(string id)
    {
        // plugin_installs.unmet은 set_install이 채운다. 간단히 다시 읽는다.
        var inst = await _db.GetInstallAsync(id, _cfg.Device);
        return inst is null ? "[]" : "[]";
    }

    private object SetSettings(JsonElement args)
    {
        Directory.CreateDirectory(Config.Dir);
        File.WriteAllText(_settingsPath, args[0].GetRawText());
        return LoadSettings();
    }

    public static string CollectorPluginsDir => Path.Combine(Config.Root, "collector", "plugins");

    /// <summary>args[0]이 출처(GitHub URL·폴더·zip)면 그대로, 없으면 폴더 대화상자. 종류에 따라 수집기 plugins/ 또는 대시보드 plugins/에 넣는다.</summary>
    private async Task<object> InstallPluginAsync(JsonElement args)
    {
        string? source = args.ValueKind == JsonValueKind.Array && args.GetArrayLength() > 0 && args[0].ValueKind == JsonValueKind.String ? args[0].GetString() : null;
        if (string.IsNullOrWhiteSpace(source))
        {
            source = null;
            _owner.Dispatcher.Invoke(() =>
            {
                var dlg = new OpenFolderDialog { Title = "플러그인 폴더(manifest.json이 있는 곳)" };
                if (dlg.ShowDialog(_owner) == true) source = dlg.FolderName;
            });
            if (source is null) return new { installed = false };
        }
        var src = await PluginInstaller.FetchAsync(source);
        var m = Manifest.Load(Path.Combine(src, "manifest.json"));
        var dir = m.Type == "renderer" ? PluginsDir : CollectorPluginsDir;
        // 지금은 권한 전부 허용하고 켠다. 항목별 허용 화면은 뒤로.
        var r = await PluginInstaller.InstallAsync(_db, _cfg.Device, dir, source, grantAll: true);
        var reloaded = false;
        if (m.Type == "renderer") await MapPluginsAsync(); else reloaded = ReloadCollector();
        return new { installed = true, id = r.Manifest.Id, type = m.Type, reloaded };
    }

    /// <summary>이 기기에서 켜고 끄기. 켤 때는 권한 전부 허용(매니페스트가 로컬에 있으면 그것, 없으면 기존 granted).</summary>
    private async Task<object> SetEnabledAsync(JsonElement args)
    {
        var id = args[0].GetString()!;
        var on = args[1].GetBoolean();
        var inst = await _db.GetInstallAsync(id, _cfg.Device) ?? throw new InvalidOperationException("이 기기에 설치되지 않은 플러그인입니다");
        string? mf = new[] { Path.Combine(CollectorPluginsDir, id, "manifest.json"), Path.Combine(PluginsDir, id, "manifest.json") }.FirstOrDefault(File.Exists);
        var m = mf is null ? null : Manifest.Load(mf);
        var granted = on && m is not null ? JsonSerializer.Serialize(m.Capabilities.Select(c => c.Id)) : JsonSerializer.Serialize(inst.Granted);
        var row = await _db.SetInstallAsync(id, _cfg.Device, inst.InstalledVersion, on, inst.Settings.GetRawText(), granted);
        var reloaded = m?.Type == "renderer" ? false : ReloadCollector();
        if (m?.Type == "renderer") await MapPluginsAsync();
        return new { row, reloaded };
    }

    private static bool ReloadCollector()
    {
        if (!EventWaitHandle.TryOpenExisting(@"Local\LifeLog.Collector.Reload", out var ev)) return false;
        using (ev) ev.Set();
        return true;
    }

    private object LogJs(string from, JsonElement args)
    {
        var msg = args.ValueKind == JsonValueKind.Array && args.GetArrayLength() > 0 ? args[0].ToString() : "";
        Log($"[js {from}] {msg}");
        return true;
    }

    public static Action<string> Logger { get; set; } = s => System.Diagnostics.Debug.WriteLine(s);
    private static void Log(string s) => Logger(s);
}
