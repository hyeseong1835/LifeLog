using System.Security.Cryptography;
using System.Text;

namespace LifeLog.Core;

/// <summary>
/// 결정적 id. tools/import/common.py와 바이트 단위로 같아야 이관 데이터와 수렴한다.
///   key가 있으면  uuid5(NS, plugin | device | output | key)
///   없으면        uuid5(NS, plugin | device | output | start(UTC ISO, Python isoformat) | identity 값들(Python json.dumps))
/// 구분자는 \x1f.
/// </summary>
public static class Ids
{
    public static readonly Guid Namespace = Guid.Parse("7d1b3c2e-5a4f-4e8b-9c6d-2f1e0a9b8c7d");
    private const char Sep = '\x1f';

    public static Guid RowId(string plugin, string device, string output, string key)
        => Uuid5(Namespace, string.Join(Sep, plugin, device, output, key));

    public static Guid RowId(string plugin, string device, string output, DateTimeOffset start, IReadOnlyList<object?> identityValues)
        => Uuid5(Namespace, string.Join(Sep, plugin, device, output, PyIso(start), PyJsonList(identityValues)));

    /// <summary>RFC 4122 v5 (SHA-1). Python uuid.uuid5와 같다.</summary>
    public static Guid Uuid5(Guid ns, string name)
    {
        var nsBytes = ToNetworkOrder(ns.ToByteArray());
        var nameBytes = Encoding.UTF8.GetBytes(name);
        var hash = SHA1.HashData([.. nsBytes, .. nameBytes]);
        var b = new byte[16];
        Array.Copy(hash, b, 16);
        b[6] = (byte)((b[6] & 0x0F) | 0x50);
        b[8] = (byte)((b[8] & 0x3F) | 0x80);
        return new Guid(ToNetworkOrder(b));
    }

    // Guid.ToByteArray는 처음 세 필드를 리틀엔디언으로 준다. RFC 순서로 뒤집는다(양방향 같은 연산).
    private static byte[] ToNetworkOrder(byte[] g)
    {
        var b = (byte[])g.Clone();
        Array.Reverse(b, 0, 4); Array.Reverse(b, 4, 2); Array.Reverse(b, 6, 2);
        return b;
    }

    /// <summary>Python datetime.isoformat() (UTC, 마이크로초가 0이면 소수 없음).</summary>
    public static string PyIso(DateTimeOffset t)
    {
        var u = t.ToUniversalTime();
        long micro = (u.Ticks % TimeSpan.TicksPerSecond) / 10;
        var s = u.ToString("yyyy-MM-dd'T'HH:mm:ss");
        return micro == 0 ? s + "+00:00" : s + "." + micro.ToString("D6") + "+00:00";
    }

    /// <summary>Python json.dumps(list, ensure_ascii=False, separators=(",",":")).</summary>
    public static string PyJsonList(IReadOnlyList<object?> values)
    {
        var sb = new StringBuilder("[");
        for (int i = 0; i < values.Count; i++)
        {
            if (i > 0) sb.Append(',');
            sb.Append(PyJsonValue(values[i]));
        }
        return sb.Append(']').ToString();
    }

    private static string PyJsonValue(object? v) => v switch
    {
        null => "null",
        bool b => b ? "true" : "false",
        string s => PyJsonString(s),
        int or long or short or byte => Convert.ToString(v, System.Globalization.CultureInfo.InvariantCulture)!,
        double d => PyFloat(d),
        float f => PyFloat(f),
        decimal m => PyFloat((double)m),
        _ => PyJsonString(v.ToString() ?? ""),
    };

    // Python repr(float): 정수값이면 "1.0", 아니면 최단 표현. 지수 표기는 identity 값으로 쓰지 않는다고 가정한다.
    private static string PyFloat(double d)
    {
        var s = d.ToString("R", System.Globalization.CultureInfo.InvariantCulture);
        return s.Contains('.') || s.Contains('E') || s.Contains('e') ? s : s + ".0";
    }

    private static string PyJsonString(string s)
    {
        var sb = new StringBuilder("\"");
        foreach (var c in s)
        {
            switch (c)
            {
                case '"': sb.Append("\\\""); break;
                case '\\': sb.Append("\\\\"); break;
                case '\n': sb.Append("\\n"); break;
                case '\r': sb.Append("\\r"); break;
                case '\t': sb.Append("\\t"); break;
                case '\b': sb.Append("\\b"); break;
                case '\f': sb.Append("\\f"); break;
                default:
                    if (c < 0x20) sb.Append("\\u").Append(((int)c).ToString("x4"));
                    else sb.Append(c);
                    break;
            }
        }
        return sb.Append('"').ToString();
    }
}
