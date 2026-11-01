using System.IO;
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
        if (File.Exists(_settingsPath)) return JsonNode.Parse(File.ReadAllText(_settingsPath))!.AsObject();
        return new JsonObject { ["day_start"] = "04:00", ["tz"] = TimeZoneInfo.Local.Id };
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
                "installPlugin" => await InstallPluginAsync(),
                "device" => _cfg.Device,
                _ => throw new InvalidOperationException("모르는 메서드: " + method),
            };
        }
        catch (Exception ex) { error = ex.Message; }
        var reply = JsonSerializer.Serialize(new { id, result, error }, new JsonSerializerOptions { PropertyNamingPolicy = JsonNamingPolicy.CamelCase });
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

    private async Task<object> InstallPluginAsync()
    {
        string? folder = null;
        _owner.Dispatcher.Invoke(() =>
        {
            var dlg = new OpenFolderDialog { Title = "렌더러 플러그인 폴더(manifest.json이 있는 곳)" };
            if (dlg.ShowDialog(_owner) == true) folder = dlg.FolderName;
        });
        if (folder is null) return new { installed = false };
        var m = Manifest.Load(Path.Combine(folder, "manifest.json"));
        if (m.Type != "renderer") throw new InvalidOperationException("렌더러 매니페스트가 아닙니다");
        var dest = Path.Combine(PluginsDir, m.Id);
        if (Directory.Exists(dest)) Directory.Delete(dest, true);
        Directory.CreateDirectory(dest);
        foreach (var f in Directory.GetFiles(folder, "*", SearchOption.AllDirectories))
        {
            var target = Path.Combine(dest, Path.GetRelativePath(folder, f));
            Directory.CreateDirectory(Path.GetDirectoryName(target)!);
            File.Copy(f, target, true);
        }
        await _db.RegisterPluginAsync(m, _cfg.Device);
        // 지금은 권한 전부 허용하고 켠다. 항목별 허용 화면은 뒤로.
        var granted = JsonSerializer.Serialize(m.Capabilities.Select(c => c.Id));
        await _db.SetInstallAsync(m.Id, _cfg.Device, m.Version, true, null, granted);
        await MapPluginsAsync();
        return new { installed = true, id = m.Id };
    }

    private static void Log(string s) => System.Diagnostics.Debug.WriteLine(s);
}
