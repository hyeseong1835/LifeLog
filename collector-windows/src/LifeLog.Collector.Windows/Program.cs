using System.Diagnostics;
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

          setup   --device ID --name 이름 --host H --port P --database DB [--no-ssl] [--tenant 프로젝트ref]
                  --tenant: Supabase 풀러는 사용자 이름이 롤.프로젝트ref 꼴이어야 한다. 맨 Postgres면 생략
          setup-reset  같은 인자. 이미 등록된 기기의 비밀번호를 다시 받아 설정한다
                  관리자 접속 문자열은 환경 변수 LIFELOG_ADMIN_DSN 또는 표준 입력으로 받는다(저장하지 않음).
                  기기 롤을 만들고 비밀번호를 DPAPI로 보호해 config.json에 둔다.
          install <플러그인 폴더 또는 zip> [--grant-all]
                  plugins/<id>로 복사하고 등록한다. --grant-all은 매니페스트의 권한을 전부 허용하고 켠다(개발용).
          enable <id> | disable <id>
          run [--tray]   수집 시작. --tray면 콘솔을 숨기고 트레이 아이콘으로 돈다
          stop           실행 중인 수집기를 정상 종료시킨다(열린 구간을 닫고 큐를 비운다)
          autostart on|off   로그온 시 자동 실행(HKCU Run 키)
          status  설정·큐·플러그인 상태

        로그: %LOCALAPPDATA%\LifeLog\collector\collector.log
        """;

    const string StopEventName = @"Local\LifeLog.Collector.Stop";
    const string MutexName = @"Local\LifeLog.Collector.Running";
    const string TaskName = "LifeLogCollector";

    public static async Task<int> RunAsync(string[] args)
    {
        if (args.Length == 0) { Console.WriteLine(Usage); return 1; }
        try
        {
            switch (args[0])
            {
                case "setup": return await SetupAsync(args[1..], reset: false);
                case "setup-reset": return await SetupAsync(args[1..], reset: true);
                case "install": return await InstallAsync(args[1..]);
                case "enable": return await EnableAsync(args[1], true);
                case "disable": return await EnableAsync(args[1], false);
                case "run": return await RunHostAsync(args.Contains("--tray"));
                case "stop": return Stop();
                case "autostart": return Autostart(args.Length > 1 && args[1] == "on");
                case "set-tenant": return SetTenant(args.Length > 1 ? args[1] : "");
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

    /// <summary>reset=true면 이미 등록된 기기의 비밀번호를 reset_device_password로 다시 받는다(이관 도구가 먼저 등록한 경우 등).</summary>
    static async Task<int> SetupAsync(string[] a, bool reset)
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
            await using var cmd = reset
                ? new NpgsqlCommand("SELECT lifelog.reset_device_password($1)", conn)
                : new NpgsqlCommand("SELECT lifelog.register_device($1, $2, $3)", conn);
            cmd.Parameters.Add(new NpgsqlParameter { Value = device });
            if (!reset)
            {
                cmd.Parameters.Add(new NpgsqlParameter { Value = name });
                cmd.Parameters.Add(new NpgsqlParameter { Value = "Windows " + Environment.OSVersion.Version });
            }
            pw = (string)(await cmd.ExecuteScalarAsync())!;
        }
        var cfg = new Config { Device = device, Host = host, Port = port, Database = db, Ssl = ssl, Tenant = Opt(a, "--tenant") ?? "" };
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

    static async Task<int> RunHostAsync(bool tray)
    {
        var cfg = Config.Load() ?? throw new InvalidOperationException("setup을 먼저 하세요");
        using var single = new Mutex(true, MutexName, out var first);
        if (!first) { Console.Error.WriteLine("이미 실행 중입니다. `stop`으로 끝내세요."); return 3; }
        using var cts = new CancellationTokenSource();
        Console.CancelKeyPress += (_, e) => { e.Cancel = true; cts.Cancel(); };
        using var stopEvent = new EventWaitHandle(false, EventResetMode.ManualReset, StopEventName);
        ThreadPool.RegisterWaitForSingleObject(stopEvent, (_, _) => cts.Cancel(), null, -1, true);
        await using var host = new Host(cfg);
        using var trayIcon = tray ? new Tray(() => host.Status, () => cts.Cancel()) : null;
        trayIcon?.Start();
        await host.RunAsync(cts.Token);
        return 0;
    }

    static int SetTenant(string tenant)
    {
        var cfg = Config.Load() ?? throw new InvalidOperationException("setup을 먼저 하세요");
        cfg.Tenant = tenant;
        cfg.Save();
        Console.WriteLine(string.IsNullOrEmpty(tenant) ? "테넌트 접미사 없음" : $"접속 사용자: {cfg.User}");
        return 0;
    }

    static int Stop()
    {
        if (!EventWaitHandle.TryOpenExisting(StopEventName, out var ev)) { Console.WriteLine("실행 중인 수집기가 없습니다"); return 1; }
        ev.Set();
        // 뮤텍스가 풀릴 때까지(= 정상 종료) 기다린다
        for (int i = 0; i < 60; i++)
        {
            if (!Mutex.TryOpenExisting(MutexName, out var m)) { Console.WriteLine("종료됨"); return 0; }
            m.Dispose();
            Thread.Sleep(500);
        }
        Console.WriteLine("30초 안에 끝나지 않았습니다. 로그를 보세요");
        return 2;
    }

    static int Autostart(bool on)
    {
        // 작업 스케줄러의 onlogon 트리거는 관리자 권한이 필요해 HKCU Run 키를 쓴다(사용자 권한으로 충분)
        var exe = Environment.ProcessPath ?? throw new InvalidOperationException("실행 파일 경로를 알 수 없습니다");
        using var key = Microsoft.Win32.Registry.CurrentUser.CreateSubKey(@"Software\Microsoft\Windows\CurrentVersion\Run", true)!;
        if (on) { key.SetValue(TaskName, $"\"{exe}\" run --tray"); Console.WriteLine($"로그온 시 자동 실행 등록(HKCU Run): {exe} run --tray"); }
        else { key.DeleteValue(TaskName, false); Console.WriteLine("자동 실행 해제"); }
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
                foreach (var o in m.Outputs)
                {
                    var n = await db.CountTodayAsync(m.Id + "/" + o.Name, o.IsSpan, cfg.Device);
                    Console.WriteLine($"      {o.Name}: 오늘 {n}행");
                }
            }
        }
        return 0;
    }
}
