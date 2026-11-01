using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace LifeLog.Core;

/// <summary>
/// 수집기 앱 설정. %LOCALAPPDATA%\LifeLog\collector\config.json.
/// 기기 롤 비밀번호는 DPAPI(현재 사용자)로 암호화해 둔다. 관리자 접속 정보는 저장하지 않는다(Documents/인증·권한.md 3절).
/// </summary>
public sealed class Config
{
    public static string Dir { get; } = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "LifeLog", "collector");
    public static string PluginsDir => Path.Combine(Dir, "plugins");
    public static string QueuePath => Path.Combine(Dir, "queue.db");
    private static string FilePath => Path.Combine(Dir, "config.json");

    [JsonPropertyName("device")] public string Device { get; set; } = "";
    [JsonPropertyName("host")] public string Host { get; set; } = "";
    [JsonPropertyName("port")] public int Port { get; set; } = 5432;
    [JsonPropertyName("database")] public string Database { get; set; } = "";
    [JsonPropertyName("ssl")] public bool Ssl { get; set; } = true;
    /// <summary>풀러 테넌트 접미사. Supabase 풀러는 사용자 이름이 `롤.프로젝트ref` 꼴이어야 테넌트를 안다. 맨 Postgres면 비워 둔다.</summary>
    [JsonPropertyName("tenant")] public string Tenant { get; set; } = "";
    /// <summary>DPAPI로 보호한 비밀번호(base64). 평문은 메모리에서만.</summary>
    [JsonPropertyName("password_protected")] public string PasswordProtected { get; set; } = "";

    public string User => "lifelog_device_" + Device + (string.IsNullOrEmpty(Tenant) ? "" : "." + Tenant);

    public static Config? Load()
    {
        if (!File.Exists(FilePath)) return null;
        return JsonSerializer.Deserialize<Config>(File.ReadAllText(FilePath));
    }

    public void Save()
    {
        Directory.CreateDirectory(Dir);
        File.WriteAllText(FilePath, JsonSerializer.Serialize(this, new JsonSerializerOptions { WriteIndented = true }));
    }

    public void SetPassword(string plain)
        => PasswordProtected = Convert.ToBase64String(ProtectedData.Protect(Encoding.UTF8.GetBytes(plain), null, DataProtectionScope.CurrentUser));

    public string GetPassword()
        => Encoding.UTF8.GetString(ProtectedData.Unprotect(Convert.FromBase64String(PasswordProtected), null, DataProtectionScope.CurrentUser));

    /// <summary>Npgsql 키워드 형식. 비밀번호에 URL 예약 문자가 섞이므로 URL을 쓰지 않는다.</summary>
    public string ConnectionString()
    {
        var b = new Npgsql.NpgsqlConnectionStringBuilder
        {
            Host = Host, Port = Port, Database = Database, Username = User, Password = GetPassword(),
            SslMode = Ssl ? Npgsql.SslMode.Require : Npgsql.SslMode.Prefer,
            Pooling = true, MaxPoolSize = 2, MinPoolSize = 0,   // 앱당 연결 하나(풀러 연결 수 한도)
            ApplicationName = "lifelog-collector",
        };
        return b.ConnectionString;
    }
}
