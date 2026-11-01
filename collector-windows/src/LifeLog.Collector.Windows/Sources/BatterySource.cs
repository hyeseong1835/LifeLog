using System.Runtime.InteropServices;

namespace LifeLog.Collector.Windows.Sources;

/// <summary>
/// 배터리·전원 연결. GetSystemPowerStatus를 30초마다 읽어 퍼센트나 AC 연결이 바뀌면, 그리고 적어도 10분마다
/// { time, percent(배터리 없으면 null), ac }를 낸다.
/// </summary>
public sealed class BatterySource : ISource
{
    public string Id => "battery";

    [StructLayout(LayoutKind.Sequential)]
    private struct SYSTEM_POWER_STATUS { public byte ACLineStatus, BatteryFlag, BatteryLifePercent, SystemStatusFlag; public int BatteryLifeTime, BatteryFullLifeTime; }
    [DllImport("kernel32.dll")] private static extern bool GetSystemPowerStatus(out SYSTEM_POWER_STATUS s);

    private System.Threading.Timer? _timer;
    private (int? percent, bool ac)? _last;
    private DateTime _lastEmit;

    public void Start(Action<Dictionary<string, object?>> emit)
    {
        _timer = new System.Threading.Timer(_ =>
        {
            if (!GetSystemPowerStatus(out var s)) return;
            int? percent = (s.BatteryFlag & 128) != 0 || s.BatteryLifePercent == 255 ? null : s.BatteryLifePercent;
            bool ac = s.ACLineStatus == 1;
            var cur = (percent, ac);
            var now = DateTime.UtcNow;
            if (_last == cur && now - _lastEmit < TimeSpan.FromMinutes(10)) return;
            _last = cur; _lastEmit = now;
            emit(new Dictionary<string, object?> { ["time"] = now, ["percent"] = percent, ["ac"] = ac });
        }, null, TimeSpan.Zero, TimeSpan.FromSeconds(30));
    }

    public void Dispose() => _timer?.Dispose();
}
