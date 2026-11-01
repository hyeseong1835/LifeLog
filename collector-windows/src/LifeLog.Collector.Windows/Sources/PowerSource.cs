using Microsoft.Win32;

namespace LifeLog.Collector.Windows.Sources;

/// <summary>전원·잠금. { time, kind }: boot(호스트 시작) sleep wake lock unlock logon logoff shutdown.</summary>
public sealed class PowerSource : ISource
{
    public string Id => "power";
    private Action<Dictionary<string, object?>>? _emit;

    public void Start(Action<Dictionary<string, object?>> emit)
    {
        _emit = emit;
        SystemEvents.PowerModeChanged += OnPower;
        SystemEvents.SessionSwitch += OnSession;
        SystemEvents.SessionEnding += OnEnding;
        Emit("boot");
    }

    private void OnPower(object sender, PowerModeChangedEventArgs e)
    {
        if (e.Mode == PowerModes.Suspend) Emit("sleep");
        else if (e.Mode == PowerModes.Resume) Emit("wake");
    }

    private void OnSession(object sender, SessionSwitchEventArgs e)
    {
        var kind = e.Reason switch
        {
            SessionSwitchReason.SessionLock => "lock",
            SessionSwitchReason.SessionUnlock => "unlock",
            SessionSwitchReason.SessionLogon => "logon",
            SessionSwitchReason.SessionLogoff => "logoff",
            _ => null,
        };
        if (kind != null) Emit(kind);
    }

    private void OnEnding(object sender, SessionEndingEventArgs e)
        => Emit(e.Reason == SessionEndReasons.SystemShutdown ? "shutdown" : "logoff");

    private void Emit(string kind) => _emit?.Invoke(new Dictionary<string, object?> { ["time"] = DateTime.UtcNow, ["kind"] = kind });

    public void Dispose()
    {
        SystemEvents.PowerModeChanged -= OnPower;
        SystemEvents.SessionSwitch -= OnSession;
        SystemEvents.SessionEnding -= OnEnding;
    }
}
