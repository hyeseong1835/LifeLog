using System.Text.Json;
using System.Text.Json.Nodes;
using System.Text.Json.Serialization;

namespace LifeLog.Core;

/// <summary>플러그인 매니페스트(Documents/매니페스트.md). 완전한 검증은 DB 등록 함수가 하고, 여기서는 호스트가 쓰는 만큼만 읽는다.</summary>
public sealed class Manifest
{
    [JsonPropertyName("type")] public string Type { get; set; } = "";
    [JsonPropertyName("id")] public string Id { get; set; } = "";
    [JsonPropertyName("version")] public string Version { get; set; } = "";
    [JsonPropertyName("name")] public string Name { get; set; } = "";
    [JsonPropertyName("min_host_version")] public int MinHostVersion { get; set; }
    [JsonPropertyName("runtimes")] public Dictionary<string, string> Runtimes { get; set; } = new();
    [JsonPropertyName("capabilities")] public List<Capability> Capabilities { get; set; } = new();
    [JsonPropertyName("settings")] public List<SettingDecl> Settings { get; set; } = new();
    [JsonPropertyName("privacy")] public List<SettingDecl> Privacy { get; set; } = new();
    [JsonPropertyName("outputs")] public List<Output> Outputs { get; set; } = new();
    /// <summary>도는 플랫폼(windows | android | …). null이면 가리지 않음(0007).</summary>
    [JsonPropertyName("platforms")] public List<string>? Platforms { get; set; }
    /// <summary>앱 밖에서 따로 설치·실행해야 하는 프로그램·서비스(0007).</summary>
    [JsonPropertyName("requires")] public List<Requirement> Requires { get; set; } = new();

    public bool Supports(string platform) => Platforms is null || Platforms.Count == 0 || Platforms.Contains(platform);

    /// <summary>원문 JSON. 등록 함수에 그대로 보낸다(호스트가 다시 직렬화하면 멱등 비교가 깨질 수 있다).</summary>
    [JsonIgnore] public string Raw { get; private set; } = "";

    public sealed class Requirement
    {
        [JsonPropertyName("name")] public string Name { get; set; } = "";
        [JsonPropertyName("why")] public string? Why { get; set; }
        [JsonPropertyName("url")] public string? Url { get; set; }
        [JsonPropertyName("platforms")] public List<string>? Platforms { get; set; }
    }

    public sealed class Capability
    {
        [JsonPropertyName("id")] public string Id { get; set; } = "";
        [JsonPropertyName("required")] public bool Required { get; set; }
    }

    public sealed class SettingDecl
    {
        [JsonPropertyName("key")] public string Key { get; set; } = "";
        [JsonPropertyName("type")] public string Type { get; set; } = "";
        [JsonPropertyName("default")] public JsonNode? Default { get; set; }
        [JsonPropertyName("label")] public string Label { get; set; } = "";
    }

    public sealed class Output
    {
        [JsonPropertyName("name")] public string Name { get; set; } = "";
        [JsonPropertyName("kind")] public string Kind { get; set; } = "";
        [JsonPropertyName("schema_version")] public int SchemaVersion { get; set; }
        [JsonPropertyName("columns")] public Dictionary<string, string> Columns { get; set; } = new();
        [JsonPropertyName("label")] public string Label { get; set; } = "";
        [JsonPropertyName("identity")] public List<string> Identity { get; set; } = new();
        [JsonPropertyName("index")] public List<string> Index { get; set; } = new();

        public bool IsSpan => Kind == "span";
    }

    public Output? FindOutput(string name) => Outputs.FirstOrDefault(o => o.Name == name);

    /// <summary>테이블 이름 = 매니페스트 표기 그대로: "{id}/{output}@{schema_version}"</summary>
    public string TableName(Output o) => $"{Id}/{o.Name}@{o.SchemaVersion}";

    public static Manifest Load(string path)
    {
        var raw = File.ReadAllText(path);
        var m = JsonSerializer.Deserialize<Manifest>(raw) ?? throw new InvalidDataException($"매니페스트를 읽을 수 없습니다: {path}");
        if (string.IsNullOrEmpty(m.Id) || string.IsNullOrEmpty(m.Type)) throw new InvalidDataException($"매니페스트에 id·type이 없습니다: {path}");
        m.Raw = raw;
        return m;
    }
}
