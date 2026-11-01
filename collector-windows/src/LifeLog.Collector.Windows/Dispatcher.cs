using System.Collections.Concurrent;

namespace LifeLog.Collector.Windows;

/// <summary>플러그인 JS는 전부 이 스레드에서만 돈다(Jint 엔진은 스레드 안전하지 않다). 소스는 여기로 Post한다.</summary>
public sealed class Dispatcher : IDisposable
{
    private readonly BlockingCollection<Action> _q = new();
    private readonly Thread _t;
    private readonly Action<string> _log;

    public Dispatcher(Action<string> log)
    {
        _log = log;
        _t = new Thread(Loop) { Name = "lifelog-dispatcher", IsBackground = true };
        _t.Start();
    }

    public void Post(Action a) => _q.Add(a);

    /// <summary>작업을 올리고 끝날 때까지 기다린다(종료 시 stop 호출용).</summary>
    public void Invoke(Action a)
    {
        if (Thread.CurrentThread == _t) { a(); return; }
        using var done = new ManualResetEventSlim();
        Exception? err = null;
        Post(() => { try { a(); } catch (Exception e) { err = e; } finally { done.Set(); } });
        done.Wait();
        if (err != null) throw err;
    }

    private void Loop()
    {
        foreach (var a in _q.GetConsumingEnumerable())
        {
            try { a(); }
            catch (Exception e) { _log("디스패처 작업 실패: " + e); }
        }
    }

    public void Dispose()
    {
        _q.CompleteAdding();
        _t.Join(TimeSpan.FromSeconds(5));
    }
}
