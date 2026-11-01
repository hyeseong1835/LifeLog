using System.Windows;
using LifeLog.Core;

namespace LifeLog.Dashboard.Windows;

public partial class App : Application
{
    protected override void OnStartup(StartupEventArgs e)
    {
        Config.AppName = "dashboard";
        base.OnStartup(e);
    }
}
