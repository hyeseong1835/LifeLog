using System.IO.Compression;
using System.Text.Json;
using LifeLog.Core;
using LifeLog.Collector.Windows;
using Npgsql;

return await Cli.RunAsync(args);

static class Cli
{
    const string Usage = """
        lifelog-collector <명령>

          setup   --device ID --name 이름 --host H --port P --database DB [--no-ssl]
                  관리자 접속 문자열은 환경 변수 LIFELOG_ADMIN_DSN 또는 표준 입력으로 받는다(저장하지 않음).
                  기기 롤을 만들고 비밀번호를 DPAPI로 보호해 config.json에 둔다.
          install <플러그인 폴더 또는 zip> [--grant-all]
                  plugins/<id>로 복사하고 등록한다. --grant-all은 매니페스트의 권한을 전부 허용하고 켠다(개발용).
          enable <id> | disable <id>
          run     수집 시작
          status  설정·큐·플러그인 상태
        """;

    public static async Task<int> RunAsync(string[] args)
    {
        if (args.Length == 0) { Console.WriteLine(Usage); return 1; }
        try
        {
            switch (args[0])
            {
                case "setup": return await SetupAsync(args[1..]);
                case "install": return await InstallAsync(args[1..]);
                case "enable": return await EnableAsync(args[1], true);
                case "disable": return await EnableAsync(args[1], false);
                case "run": return await RunHostAsync();
                case "status": return await StatusAsync();
                default: Console.WriteLine(Usage); return 1;
            }
        }
        catch (Exception e)
        {
            Console.Error.WriteLine("오류: " + e.Message);
            return 2;
        }
    }

    static string? Opt(string[] a, string name) { var i = Array.IndexOf(a, name); return i >= 0 && i + 1 < a.Length ? a[i + 1] : null; }

    static async Task<int> SetupAsync(string[] a)
    {
        var device = Opt(a, "--device") ?? throw new ArgumentException("--device가 필요합니다");
        var name = Opt(a, "--name") ?? device;
        var host = Opt(a, "--host") ?? "localhost";
        var port = int.Parse(Opt(a, "--port") ?? "5432");
        var db = Opt(a, "--database") ?? "postgres";
        var ssl = !a.Contains("--no-ssl");
        var admin = Environment.GetEnvironmentVariable("LIFELOG_ADMIN_DSN");
        if (string.IsNullOrEmpty(admin))
        {
            Console.Write("관리자 접속 문자열(저장하지 않음): ");
            admin = Console.ReadLine();
        }
        if (string.IsNullOrEmpty(admin)) throw new ArgumentException("관리자 접속 문자열이 없습니다");

        string pw;
        await using (var conn = new NpgsqlConnection(admin))
        {
            await conn.OpenAsync();
            await using var cmd = new NpgsqlCommand("SELECT lifelog.register_device($1, $2, $3)", conn);
            cmd.Parameters.Add(new NpgsqlParameter { Value = device });
            cmd.Parameters.Add(new NpgsqlParameter { Value = name });
            cmd.Parameters.Add(new NpgsqlParameter { Value = "Windows " + Environment.OSVersion.Version });
            pw = (string)(await cmd.ExecuteScalarAsync())!;
        }
        var cfg = new Config { Device = device, Host = host, Port = port, Database = db, Ssl = ssl };
        cfg.SetPassword(pw);
        cfg.Save();
        Console.WriteLine($"기기 {device} 등록 완료. 설정: {Config.Dir}");
        return 0;
    }

    static async Task<int> InstallAsync(string[] a)
    {
        var src = a.Length > 0 ? a[0] : throw new ArgumentException("플러그인 경로가 필요합니다");
        var grantAll = a.Contains("--grant-all");
        string tmp = src;
        if (File.Exists(src) && src.EndsWith(".zip", StringComparison.OrdinalIgnoreCase))
        {
            tmp = Path.Combine(Path.GetTempPath(), "lifelog-plugin-" + Guid.NewGuid().ToString("N"));
            ZipFile.ExtractToDirectory(src, tmp);
        }
        var m = Manifest.Load(Path.Combine(tmp, "manifest.json"));
        var dest = Path.Combine(Config.PluginsDir, m.Id);
        if (Directory.Exists(dest)) Directory.Delete(dest, true);
        Directory.CreateDirectory(dest);
        foreach (var f in Directory.GetFiles(tmp, "*", SearchOption.AllDirectories))
        {
            var target = Path.Combine(dest, Path.GetRelativePath(tmp, f));
            Directory.CreateDirectory(Path.GetDirectoryName(target)!);
            File.Copy(f, target, true);
        }

        var cfg = Config.Load() ?? throw new InvalidOperationException("setup을 먼저 하세요");
        await using var db = new Db(cfg.ConnectionString());
        var r = await db.RegisterPluginAsync(m, cfg.Device);
        Console.WriteLine($"등록: {r}");
        if (grantAll)
        {
            var granted = JsonSerializer.Serialize(m.Capabilities.Select(c => c.Id));
            var row = await db.SetInstallAsync(m.Id, cfg.Device, m.Version, true, null, granted);
            Console.WriteLine($"켬(권한 전부 허용): {row}");
        }
        else
        {
            Console.WriteLine($"설치됨. 권한을 허용하고 켜려면: enable {m.Id}");
        }
        return 0;
    }

    static async Task<int> EnableAsync(string id, bool on)
    {
        var cfg = Config.Load() ?? throw new InvalidOperationException("setup을 먼저 하세요");
        var m = Manifest.Load(Path.Combine(Config.PluginsDir, id, "manifest.json"));
        await using var db = new Db(cfg.ConnectionString());
        var inst = await db.GetInstallAsync(id, cfg.Device) ?? throw new InvalidOperationException("설치되지 않은 플러그인입니다");
        var granted = on ? JsonSerializer.Serialize(m.Capabilities.Select(c => c.Id)) : JsonSerializer.Serialize(inst.Granted);
        var row = await db.SetInstallAsync(id, cfg.Device, inst.InstalledVersion, on, inst.Settings.GetRawText(), granted);
        Console.WriteLine(row);
        return 0;
    }

    static async Task<int> RunHostAsync()
    {
        var cfg = Config.Load() ?? throw new InvalidOperationException("setup을 먼저 하세요");
        using var cts = new CancellationTokenSource();
        Console.CancelKeyPress += (_, e) => { e.Cancel = true; cts.Cancel(); };
        await using var host = new Host(cfg);
        await host.RunAsync(cts.Token);
        return 0;
    }

    static async Task<int> StatusAsync()
    {
        var cfg = Config.Load();
        if (cfg is null) { Console.WriteLine("설정 없음. setup을 먼저 하세요"); return 1; }
        Console.WriteLine($"기기 {cfg.Device}  DB {cfg.Host}:{cfg.Port}/{cfg.Database}  사용자 {cfg.User}");
        using (var q = new Queue(Config.QueuePath)) Console.WriteLine($"큐 대기 {q.Count()}건");
        if (Directory.Exists(Config.PluginsDir))
        {
            await using var db = new Db(cfg.ConnectionString());
            foreach (var dir in Directory.GetDirectories(Config.PluginsDir))
            {
                var m = Manifest.Load(Path.Combine(dir, "manifest.json"));
                var inst = await db.GetInstallAsync(m.Id, cfg.Device);
                Console.WriteLine($"  {m.Id} {m.Version}  {(inst is null ? "미등록" : inst.Enabled ? "켜짐" : "꺼짐")}  권한 {(inst is null ? "-" : string.Join(",", inst.Granted))}");
            }
        }
        return 0;
    }
}
