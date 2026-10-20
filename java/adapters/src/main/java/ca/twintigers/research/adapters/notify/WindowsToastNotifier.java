package ca.twintigers.research.adapters.notify;

import ca.twintigers.research.domain.alerts.Notification;
import ca.twintigers.research.ports.DataSourceException;
import ca.twintigers.research.ports.Notifier;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

/**
 * Windows desktop notification ("toast") through the built-in Windows Runtime API, called from Windows
 * PowerShell 5.1. Nothing to install. The title and body travel in environment variables, never inside the
 * script text, so alert content cannot be interpreted as code. Shown under the app name "Twintigers Research"
 * (registered per user by scripts\register-notifications.ps1; falls back to "Windows PowerShell" if not registered).
 * Needs an interactive session (the scheduled task runs only while the user is logged on). Windows still applies
 * Do Not Disturb / focus: then the toast goes silently to Notification Center (Win+N) instead of popping up.
 */
public final class WindowsToastNotifier implements Notifier {

    static final String SCRIPT = String.join("\n",
            "$ErrorActionPreference = 'Stop'",
            "[Windows.UI.Notifications.ToastNotificationManager, Windows.UI.Notifications, ContentType = WindowsRuntime] | Out-Null",
            "[Windows.Data.Xml.Dom.XmlDocument, Windows.Data.Xml.Dom.XmlDocument, ContentType = WindowsRuntime] | Out-Null",
            "$t = [System.Security.SecurityElement]::Escape($env:RJ_TOAST_TITLE)",
            "$b = [System.Security.SecurityElement]::Escape($env:RJ_TOAST_BODY)",
            "$s = if ($env:RJ_TOAST_URGENT -eq '1') { ' scenario=\"reminder\"' } else { '' }",
            "$xml = New-Object Windows.Data.Xml.Dom.XmlDocument",
            "$xml.LoadXml(\"<toast$s><visual><binding template='ToastGeneric'><text>$t</text><text>$b</text></binding></visual>\" + "
                    + "$(if ($s) { \"<actions><action content='Dismiss' arguments='dismiss' activationType='system'/></actions>\" } else { '' }) + \"</toast>\")",
            "$id = 'Twintigers.ResearchMonitor'",
            "if (-not (Test-Path \"HKCU:\\Software\\Classes\\AppUserModelId\\$id\")) { $id = '{1AC14E77-02E7-4E5D-B744-2EB1AE5198B7}\\WindowsPowerShell\\v1.0\\powershell.exe' }",
            "[Windows.UI.Notifications.ToastNotificationManager]::CreateToastNotifier($id).Show([Windows.UI.Notifications.ToastNotification]::new($xml))");

    @Override
    public String channel() {
        return "toast";
    }

    @Override
    public void send(Notification n) throws DataSourceException {
        String encoded = Base64.getEncoder().encodeToString(SCRIPT.getBytes(StandardCharsets.UTF_16LE));
        ProcessBuilder pb = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                "-EncodedCommand", encoded).redirectErrorStream(true);
        pb.environment().put("RJ_TOAST_TITLE", n.title());
        pb.environment().put("RJ_TOAST_BODY", n.body());
        pb.environment().put("RJ_TOAST_URGENT", n.priority() == Notification.Priority.HIGH ? "1" : "0");
        try {
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            if (!p.waitFor(30, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new DataSourceException("Windows toast timed out.");
            }
            if (p.exitValue() != 0) {
                throw new DataSourceException("Windows toast failed: " + (out.length() > 300 ? out.substring(0, 300) : out));
            }
        } catch (java.io.IOException e) {
            throw new DataSourceException("Windows toast could not start PowerShell: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DataSourceException("Interrupted while showing a Windows toast.", e);
        }
    }
}
