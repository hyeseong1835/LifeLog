using System.Runtime.InteropServices;

namespace LifeLog.Collector.Windows.Sources;

/// <summary>
/// 입력 활동량. 저수준 키보드·마우스 훅으로 1분 단위 횟수만 센다(어떤 키인지는 보지 않는다).
/// 매 분 경계에 지난 1분이 0이 아니면 { time(분 시작), keys, clicks, scrolls, mouse_px }를 낸다.
/// 관리자 권한 창에 포커스가 있는 동안의 입력은 UIPI 때문에 보이지 않는다.
/// </summary>
public sealed class InputSource : ISource
{
    public string Id => "input";

    private delegate IntPtr HookProc(int nCode, IntPtr wParam, IntPtr lParam);
    [DllImport("user32.dll", SetLastError = true)] private static extern IntPtr SetWindowsHookEx(int idHook, HookProc lpfn, IntPtr hMod, uint dwThreadId);
    [DllImport("user32.dll")] private static extern bool UnhookWindowsHookEx(IntPtr hhk);
    [DllImport("user32.dll")] private static extern IntPtr CallNextHookEx(IntPtr hhk, int nCode, IntPtr wParam, IntPtr lParam);
    [DllImport("user32.dll")] private static extern int GetMessage(out MSG msg, IntPtr hWnd, uint min, uint max);
    [DllImport("user32.dll")] private static extern bool TranslateMessage(ref MSG msg);
    [DllImport("user32.dll")] private static extern IntPtr DispatchMessage(ref MSG msg);
    [DllImport("user32.dll")] private static extern bool PostThreadMessage(uint threadId, uint msg, IntPtr wParam, IntPtr lParam);
    [DllImport("kernel32.dll")] private static extern uint GetCurrentThreadId();

    [StructLayout(LayoutKind.Sequential)] private struct POINT { public int x, y; }
    [StructLayout(LayoutKind.Sequential)] private struct MSG { public IntPtr hwnd; public uint message; public IntPtr wParam, lParam; public uint time; public POINT pt; }
    [StructLayout(LayoutKind.Sequential)] private struct MSLLHOOKSTRUCT { public POINT pt; public uint mouseData, flags, time; public UIntPtr dwExtraInfo; }

    private const int WH_KEYBOARD_LL = 13, WH_MOUSE_LL = 14;
    private const uint WM_QUIT = 0x12, WM_KEYDOWN = 0x100, WM_SYSKEYDOWN = 0x104, WM_MOUSEMOVE = 0x200,
        WM_LBUTTONDOWN = 0x201, WM_RBUTTONDOWN = 0x204, WM_MBUTTONDOWN = 0x207, WM_XBUTTONDOWN = 0x20B, WM_MOUSEWHEEL = 0x20A, WM_MOUSEHWHEEL = 0x20E;

    private HookProc? _kb, _ms;   // GC가 거두지 않게 필드로 잡아 둔다
    private IntPtr _hkb, _hms;
    private uint _threadId;
    private long _keys, _clicks, _scrolls, _mousePx;
    private int _lastX = int.MinValue, _lastY;
    private System.Threading.Timer? _timer;
    private Action<Dictionary<string, object?>>? _emit;

    public void Start(Action<Dictionary<string, object?>> emit)
    {
        _emit = emit;
        var ready = new ManualResetEventSlim();
        new Thread(() =>
        {
            _threadId = GetCurrentThreadId();
            _kb = KeyboardHook; _ms = MouseHook;
            _hkb = SetWindowsHookEx(WH_KEYBOARD_LL, _kb, IntPtr.Zero, 0);
            _hms = SetWindowsHookEx(WH_MOUSE_LL, _ms, IntPtr.Zero, 0);
            ready.Set();
            while (GetMessage(out var msg, IntPtr.Zero, 0, 0) > 0) { TranslateMessage(ref msg); DispatchMessage(ref msg); }
            if (_hkb != IntPtr.Zero) UnhookWindowsHookEx(_hkb);
            if (_hms != IntPtr.Zero) UnhookWindowsHookEx(_hms);
        }) { Name = "lifelog-input", IsBackground = true }.Start();
        ready.Wait();
        if (_hkb == IntPtr.Zero || _hms == IntPtr.Zero) throw new InvalidOperationException("입력 훅 설치 실패");
        // 다음 분 경계부터 1분마다
        var now = DateTime.UtcNow;
        var first = new DateTime(now.Year, now.Month, now.Day, now.Hour, now.Minute, 0, DateTimeKind.Utc).AddMinutes(1) - now;
        _timer = new System.Threading.Timer(_ => Flush(), null, first, TimeSpan.FromMinutes(1));
    }

    private void Flush()
    {
        var keys = Interlocked.Exchange(ref _keys, 0);
        var clicks = Interlocked.Exchange(ref _clicks, 0);
        var scrolls = Interlocked.Exchange(ref _scrolls, 0);
        var px = Interlocked.Exchange(ref _mousePx, 0);
        if (keys == 0 && clicks == 0 && scrolls == 0 && px == 0) return;
        var now = DateTime.UtcNow;
        var minute = new DateTime(now.Year, now.Month, now.Day, now.Hour, now.Minute, 0, DateTimeKind.Utc);
        if (now - minute < TimeSpan.FromSeconds(30)) minute = minute.AddMinutes(-1);   // 경계 직후에 불리므로 지난 분
        _emit?.Invoke(new Dictionary<string, object?> { ["time"] = minute, ["keys"] = keys, ["clicks"] = clicks, ["scrolls"] = scrolls, ["mouse_px"] = px });
    }

    private IntPtr KeyboardHook(int nCode, IntPtr wParam, IntPtr lParam)
    {
        if (nCode >= 0) { var m = (uint)wParam; if (m == WM_KEYDOWN || m == WM_SYSKEYDOWN) Interlocked.Increment(ref _keys); }
        return CallNextHookEx(_hkb, nCode, wParam, lParam);
    }

    private IntPtr MouseHook(int nCode, IntPtr wParam, IntPtr lParam)
    {
        if (nCode >= 0)
        {
            var m = (uint)wParam;
            switch (m)
            {
                case WM_LBUTTONDOWN: case WM_RBUTTONDOWN: case WM_MBUTTONDOWN: case WM_XBUTTONDOWN: Interlocked.Increment(ref _clicks); break;
                case WM_MOUSEWHEEL: case WM_MOUSEHWHEEL: Interlocked.Increment(ref _scrolls); break;
                case WM_MOUSEMOVE:
                    var s = Marshal.PtrToStructure<MSLLHOOKSTRUCT>(lParam);
                    if (_lastX != int.MinValue) Interlocked.Add(ref _mousePx, Math.Abs(s.pt.x - _lastX) + Math.Abs(s.pt.y - _lastY));
                    _lastX = s.pt.x; _lastY = s.pt.y;
                    break;
            }
        }
        return CallNextHookEx(_hms, nCode, wParam, lParam);
    }

    public void Dispose()
    {
        _timer?.Dispose();
        if (_threadId != 0) PostThreadMessage(_threadId, WM_QUIT, IntPtr.Zero, IntPtr.Zero);
    }
}
