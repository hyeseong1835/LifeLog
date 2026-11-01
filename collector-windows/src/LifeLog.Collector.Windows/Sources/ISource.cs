namespace LifeLog.Collector.Windows.Sources;

/// <summary>네이티브 소스(Documents/수집기 호스트 API.md 1절). OS 이벤트를 원시 값 그대로 낸다. emit은 아무 스레드에서나 불러도 된다.</summary>
public interface ISource : IDisposable
{
    string Id { get; }
    void Start(Action<Dictionary<string, object?>> emit);
}
