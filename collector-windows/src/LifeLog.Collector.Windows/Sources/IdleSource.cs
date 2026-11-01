using System.Runtime.InteropServices;

namespace LifeLog.Collector.Windows.Sources;

/// <summary>
/// 입력 없음. GetLastInputInfo를 1초마다 읽어 임계값(기본 3분)을 넘는 순간 { time, since, idle: true },
/// 입력이 돌아오면 { time, idle: false }를 낸다. since는 마지막 입력 시각(= 자리 비움이 실제로 시작된 때).
/// </summary>
public sealed class IdleSource : ISource
{
    public string Id => "idle";
    public TimeSpan Threshold { get; init; } = TimeSpan.FromMinutes(3);

    [StructLayout(LayoutKind.Sequential)]
    private struct LASTINPUTINFO { public uint cbSize; public uint dwTime; }
    [DllImport("user32.dll")] private static extern bool GetLastInputInfo(ref LASTINPUTINFO info);

    private CancellationTokenSource? _cts;

    public void Start(Action<Dictionary<string, object?>> emit)
    {
        _cts = new CancellationTokenSource();
        var ct = _cts.Token;
        new Thread(() =>
        {
            bool idle = false;
            while (!ct.IsCancellationRequested)
            {
                var info = new LASTINPUTINFO { cbSize = (uint)Marshal.SizeOf<LASTINPUTINFO>() };
                if (GetLastInputInfo(ref info))
                {
                    uint now = (uint)Environment.TickCount;
                    var idleFor = TimeSpan.FromMilliseconds(unchecked(now - info.dwTime));
                    if (!idle && idleFor >= Threshold)
                    {
                        idle = true;
                        emit(new Dictionary<string, object?> { ["time"] = DateTime.UtcNow, ["since"] = DateTime.UtcNow - idleFor, ["idle"] = true });
                    }
                    else if (idle && idleFor < Threshold)
                    {
                        idle = false;
                        emit(new Dictionary<string, object?> { ["time"] = DateTime.UtcNow, ["idle"] = false });
                    }
                }
                try { Task.Delay(1000, ct).Wait(ct); } catch (OperationCanceledException) { }
            }
        }) { Name = "lifelog-idle", IsBackground = true }.Start();
    }

    public void Dispose() => _cts?.Cancel();
}
