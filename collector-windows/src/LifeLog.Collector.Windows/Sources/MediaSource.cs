using Windows.Media.Control;

namespace LifeLog.Collector.Windows.Sources;

/// <summary>
/// 미디어 세션(GlobalSystemMediaTransportControlsSessionManager). 세션·속성·재생 상태가 바뀔 때
/// { time, app(AUMID), title, artist, album, playing }을 낸다. 같은 값이 이어지면 내지 않는다.
/// </summary>
public sealed class MediaSource : ISource
{
    public string Id => "media";
    private Action<Dictionary<string, object?>>? _emit;
    private GlobalSystemMediaTransportControlsSessionManager? _mgr;
    private readonly Dictionary<string, (string title, string artist, string album, bool playing)> _last = new();
    private readonly HashSet<GlobalSystemMediaTransportControlsSession> _hooked = new();

    public void Start(Action<Dictionary<string, object?>> emit)
    {
        _emit = emit;
        _ = InitAsync();
    }

    private async Task InitAsync()
    {
        _mgr = await GlobalSystemMediaTransportControlsSessionManager.RequestAsync();
        _mgr.SessionsChanged += (mgr, args) => HookAll();
        HookAll();
    }

    private void HookAll()
    {
        if (_mgr is null) return;
        foreach (var s in _mgr.GetSessions())
        {
            lock (_hooked)
            {
                if (!_hooked.Add(s)) continue;
            }
            s.MediaPropertiesChanged += (sess, args) => Report(sess).ConfigureAwait(false);
            s.PlaybackInfoChanged += (sess, args) => Report(sess).ConfigureAwait(false);
            _ = Report(s);
        }
    }

    private async Task Report(GlobalSystemMediaTransportControlsSession s)
    {
        try
        {
            var p = await s.TryGetMediaPropertiesAsync();
            var info = s.GetPlaybackInfo();
            var playing = info.PlaybackStatus == GlobalSystemMediaTransportControlsSessionPlaybackStatus.Playing;
            var cur = (p.Title ?? "", p.Artist ?? "", p.AlbumTitle ?? "", playing);
            var app = s.SourceAppUserModelId;
            lock (_last)
            {
                if (_last.TryGetValue(app, out var prev) && prev == cur) return;
                _last[app] = cur;
            }
            _emit?.Invoke(new Dictionary<string, object?>
            {
                ["time"] = DateTime.UtcNow, ["app"] = app, ["title"] = cur.Item1, ["artist"] = cur.Item2,
                ["album"] = cur.Item3, ["playing"] = playing,
            });
        }
        catch (Exception)
        {
            // 세션이 사라진 뒤 속성 조회가 실패하는 경우. 무시한다.
        }
    }

    public void Dispose() { }
}
