using System.Runtime.InteropServices;
using System.Text;

namespace LifeLog.Collector.Windows.Sources;

/// <summary>
/// 포커스 창 변경·제목 변경. SetWinEventHook(EVENT_SYSTEM_FOREGROUND, EVENT_OBJECT_NAMECHANGE).
/// 이벤트: { time, app(exe 이름), title, pid }. 제목 변경은 포커스 창의 것만. UWP(ApplicationFrameHost)는 자식 창의 프로세스를 찾는다.
/// 같은 (app, title, pid)가 이어지면 내지 않는다(브라우저 탭 전환 잡음은 플러그인이 아니라 여기서 줄인다).
/// </summary>
public sealed class WindowSource : ISource
{
    public string Id => "window";

    private const uint EVENT_SYSTEM_FOREGROUND = 0x0003;
    private const uint EVENT_OBJECT_NAMECHANGE = 0x800C;
    private const uint WINEVENT_OUTOFCONTEXT = 0;
    private const uint WM_QUIT = 0x0012;
    private const int OBJID_WINDOW = 0;

    private delegate void WinEventDelegate(IntPtr hook, uint eventType, IntPtr hwnd, int idObject, int idChild, uint thread, uint time);

    [DllImport("user32.dll")] private static extern IntPtr SetWinEventHook(uint min, uint max, IntPtr mod, WinEventDelegate proc, uint pid, uint tid, uint flags);
    [DllImport("user32.dll")] private static extern bool UnhookWinEvent(IntPtr hook);
    [DllImport("user32.dll")] private static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] private static extern int GetWindowTextW(IntPtr hwnd, StringBuilder sb, int max);
    [DllImport("user32.dll")] private static extern uint GetWindowThreadProcessId(IntPtr hwnd, out uint pid);
    [DllImport("user32.dll")] private static extern bool EnumChildWindows(IntPtr parent, EnumChildProc proc, IntPtr lp);
    private delegate bool EnumChildProc(IntPtr hwnd, IntPtr lp);
    [DllImport("user32.dll")] private static extern int GetMessageW(out MSG msg, IntPtr hwnd, uint min, uint max);
    [DllImport("user32.dll")] private static extern bool TranslateMessage(ref MSG msg);
    [DllImport("user32.dll")] private static extern IntPtr DispatchMessageW(ref MSG msg);
    [DllImport("user32.dll")] private static extern bool PostThreadMessageW(uint tid, uint msg, IntPtr wp, IntPtr lp);
    [DllImport("kernel32.dll")] private static extern uint GetCurrentThreadId();
    [DllImport("kernel32.dll")] private static extern IntPtr OpenProcess(uint access, bool inherit, uint pid);
    [DllImport("kernel32.dll")] private static extern bool CloseHandle(IntPtr h);
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode)] private static extern bool QueryFullProcessImageNameW(IntPtr h, uint flags, StringBuilder sb, ref int size);

    [StructLayout(LayoutKind.Sequential)]
    private struct MSG { public IntPtr hwnd; public uint message; public IntPtr wParam; public IntPtr lParam; public uint time; public int x, y; }

    private Thread? _thread;
    private uint _threadId;
    private WinEventDelegate? _proc;   // GC 방지
    private Action<Dictionary<string, object?>>? _emit;
    private (string app, string title, int pid)? _last;

    public void Start(Action<Dictionary<string, object?>> emit)
    {
        _emit = emit;
        _thread = new Thread(Loop) { Name = "lifelog-window-hook", IsBackground = true };
        _thread.Start();
    }

    private void Loop()
    {
        _threadId = GetCurrentThreadId();
        _proc = OnEvent;
        var h1 = SetWinEventHook(EVENT_SYSTEM_FOREGROUND, EVENT_SYSTEM_FOREGROUND, IntPtr.Zero, _proc, 0, 0, WINEVENT_OUTOFCONTEXT);
        var h2 = SetWinEventHook(EVENT_OBJECT_NAMECHANGE, EVENT_OBJECT_NAMECHANGE, IntPtr.Zero, _proc, 0, 0, WINEVENT_OUTOFCONTEXT);
        Emit(GetForegroundWindow());   // 시작 시점의 포커스 창
        while (GetMessageW(out var msg, IntPtr.Zero, 0, 0) > 0)
        {
            TranslateMessage(ref msg);
            DispatchMessageW(ref msg);
        }
        UnhookWinEvent(h1);
        UnhookWinEvent(h2);
    }

    private void OnEvent(IntPtr hook, uint eventType, IntPtr hwnd, int idObject, int idChild, uint thread, uint time)
    {
        if (hwnd == IntPtr.Zero) return;
        if (eventType == EVENT_OBJECT_NAMECHANGE && (idObject != OBJID_WINDOW || hwnd != GetForegroundWindow())) return;
        Emit(hwnd);
    }

    private void Emit(IntPtr hwnd)
    {
        if (hwnd == IntPtr.Zero) return;
        GetWindowThreadProcessId(hwnd, out var pid);
        var app = ExeName(pid);
        if (string.Equals(app, "ApplicationFrameHost.exe", StringComparison.OrdinalIgnoreCase))
        {
            // UWP: 다른 프로세스의 자식 창이 실제 앱
            uint childPid = 0;
            EnumChildWindows(hwnd, (child, _) =>
            {
                GetWindowThreadProcessId(child, out var p);
                if (p != pid) { childPid = p; return false; }
                return true;
            }, IntPtr.Zero);
            if (childPid != 0) { pid = childPid; app = ExeName(pid); }
        }
        var sb = new StringBuilder(512);
        GetWindowTextW(hwnd, sb, sb.Capacity);
        var title = sb.ToString();
        var cur = (app, title, (int)pid);
        if (_last == cur) return;
        _last = cur;
        _emit?.Invoke(new Dictionary<string, object?>
        {
            ["time"] = DateTime.UtcNow, ["app"] = app, ["title"] = title, ["pid"] = (int)pid,
        });
    }

    private static string ExeName(uint pid)
    {
        const uint PROCESS_QUERY_LIMITED_INFORMATION = 0x1000;
        var h = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, false, pid);
        if (h == IntPtr.Zero) return "pid:" + pid;   // 관리자 권한 프로세스 등
        try
        {
            var sb = new StringBuilder(1024);
            int size = sb.Capacity;
            return QueryFullProcessImageNameW(h, 0, sb, ref size) ? Path.GetFileName(sb.ToString()) : "pid:" + pid;
        }
        finally { CloseHandle(h); }
    }

    public void Dispose()
    {
        if (_threadId != 0) PostThreadMessageW(_threadId, WM_QUIT, IntPtr.Zero, IntPtr.Zero);
        _thread?.Join(TimeSpan.FromSeconds(3));
    }
}
