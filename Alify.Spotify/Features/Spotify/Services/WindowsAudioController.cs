using System;
using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Threading.Tasks;

namespace Alify.Features.Spotify.Services;

#if WINDOWS || true

[Guid("BCDE0395-E52F-467C-8E3D-C4579291692E")]
[ComImport]
internal class MMDeviceEnumeratorComObject { }

[Guid("A95664D2-9614-4F35-A746-DE8DB63617E6"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
internal interface IMMDeviceEnumerator
{
    int EnumAudioEndpoints(int dataFlow, int stateMask, out IntPtr devices);
    int GetDefaultAudioEndpoint(int dataFlow, int role, out IMMDevice endpoint);
}

[Guid("D666063F-1587-4E43-81F1-B948E807363F"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
internal interface IMMDevice
{
    int Activate(ref Guid id, int clsCtx, IntPtr activationParams, [MarshalAs(UnmanagedType.IUnknown)] out object interfacePointer);
}

[Guid("77AA99A0-1BD6-484F-8BC7-2C654C9A9B6F"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
internal interface IAudioSessionManager2
{
    int GetAudioSessionControl(ref Guid audioSessionGuid, int streamFlags, out IntPtr sessionControl);
    int GetSimpleAudioVolume(ref Guid audioSessionGuid, int streamFlags, out IntPtr audioVolume);
    int GetSessionEnumerator(out IAudioSessionEnumerator sessionEnum);
}

[Guid("E2F5BB11-0570-40CA-ACDD-3AA01277DEE8"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
internal interface IAudioSessionEnumerator
{
    int GetCount(out int sessionCount);
    int GetSession(int sessionIndex, out IAudioSessionControl session);
}

[Guid("F4B1A599-7266-4319-A8CA-E70ACB11E8CD"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
internal interface IAudioSessionControl
{
    int GetState(out int state);
    int GetDisplayName([MarshalAs(UnmanagedType.LPWStr)] out string displayName);
}

[Guid("bfb7ff88-7239-4fc9-8fa2-07c950be9c6d"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
internal interface IAudioSessionControl2 : IAudioSessionControl
{
    new int GetState(out int state);
    new int GetDisplayName([MarshalAs(UnmanagedType.LPWStr)] out string displayName);
    int SetDisplayName([MarshalAs(UnmanagedType.LPWStr)] string displayName, ref Guid eventContext);
    int GetIconPath([MarshalAs(UnmanagedType.LPWStr)] out string iconPath);
    int SetIconPath([MarshalAs(UnmanagedType.LPWStr)] string iconPath, ref Guid eventContext);
    int GetGroupingParam(out Guid groupingParam);
    int SetGroupingParam(ref Guid groupingParam, ref Guid eventContext);
    int RegisterAudioSessionNotification(IntPtr newNotifications);
    int UnregisterAudioSessionNotification(IntPtr newNotifications);
    int GetSessionIdentifier([MarshalAs(UnmanagedType.LPWStr)] out string sessionIdentifier);
    int GetSessionInstanceIdentifier([MarshalAs(UnmanagedType.LPWStr)] out string sessionInstanceIdentifier);
    int GetProcessId(out int processId);
    int IsSystemSoundsSession();
    int SetDuckingPreference(bool optOut);
}

[Guid("87CE5498-68D6-44E5-9215-6DA47EF883D8"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
internal interface ISimpleAudioVolume
{
    int SetMasterVolume(float level, ref Guid eventContext);
    int GetMasterVolume(out float level);
    int SetMute(bool isMuted, ref Guid eventContext);
    int GetMute(out bool isMuted);
}

#endif

/// <summary>
/// Hardware audio controller for Windows Core Audio (WASAPI).
/// Enables 0ms hardware volume ducking and smooth 200ms volume ramping directly for Spotify.exe.
/// </summary>
public static class WindowsAudioController
{
    private static readonly Guid EmptyGuid = Guid.Empty;
    private const int WM_APPCOMMAND = 0x0319;
    private const int APPCOMMAND_MEDIA_NEXTTRACK = 0xB0000;
    private static readonly IntPtr HWND_BROADCAST = new IntPtr(0xffff);

    [DllImport("user32.dll", SetLastError = true)]
    private static extern IntPtr SendMessage(IntPtr hWnd, int Msg, IntPtr wParam, IntPtr lParam);

    /// <summary>
    /// Sends a native Windows media key event to skip to the next track in Spotify.
    /// Works instantly with 0ms latency even without active Spotify Web API tokens.
    /// </summary>
    public static void SendMediaNextTrack()
    {
        if (!OperatingSystem.IsWindows()) return;
        try
        {
            SendMessage(HWND_BROADCAST, WM_APPCOMMAND, IntPtr.Zero, (IntPtr)APPCOMMAND_MEDIA_NEXTTRACK);
        }
        catch
        {
            // Silently ignore if Windows messaging fails
        }
    }

    /// <summary>
    /// Instantly ducks Spotify.exe's volume in the Windows Volume Mixer to 0.0 (0ms latency).
    /// Returns the original volume level so it can be restored.
    /// </summary>
    public static float? DuckSpotifyVolume()
    {
        if (!OperatingSystem.IsWindows()) return null;

        try
        {
            var enumerator = (IMMDeviceEnumerator)new MMDeviceEnumeratorComObject();
            if (enumerator.GetDefaultAudioEndpoint(0, 1, out var device) != 0) return null; // eRender = 0, eMultimedia = 1

            var iid = typeof(IAudioSessionManager2).GUID;
            if (device.Activate(ref iid, 23, IntPtr.Zero, out var mgrObj) != 0) return null;

            var mgr = (IAudioSessionManager2)mgrObj;
            if (mgr.GetSessionEnumerator(out var sessionEnum) != 0) return null;
            if (sessionEnum.GetCount(out var count) != 0) return null;

            float? originalVol = null;

            for (int i = 0; i < count; i++)
            {
                if (sessionEnum.GetSession(i, out var ctl) != 0) continue;
                if (ctl is not IAudioSessionControl2 ctl2) continue;

                if (ctl2.GetProcessId(out var pid) != 0 || pid <= 0) continue;

                bool isSpotify = false;
                try
                {
                    var proc = Process.GetProcessById(pid);
                    isSpotify = proc.ProcessName.Equals("Spotify", StringComparison.OrdinalIgnoreCase);
                }
                catch { }

                if (isSpotify && ctl is ISimpleAudioVolume vol)
                {
                    if (vol.GetMasterVolume(out var curLevel) == 0 && curLevel > 0)
                    {
                        originalVol ??= curLevel;
                    }
                    var empty = Guid.Empty;
                    vol.SetMasterVolume(0.0f, ref empty);
                }
            }

            return originalVol ?? 1.0f;
        }
        catch
        {
            return null;
        }
    }

    /// <summary>
    /// Smoothly ramps Spotify.exe volume back up to the target level over ~150-200ms in discrete increments.
    /// </summary>
    public static async Task RestoreSpotifyVolumeSmoothlyAsync(float targetVolume, int durationMs = 150, int steps = 4)
    {
        if (!OperatingSystem.IsWindows() || targetVolume <= 0.0f) return;

        try
        {
            int stepDelay = Math.Max(25, durationMs / Math.Max(1, steps));

            for (int i = 1; i <= steps; i++)
            {
                await Task.Delay(stepDelay);
                float intermediate = (targetVolume * i) / steps;
                SetSpotifyMasterVolume(intermediate);
            }

            // Ensure exact target volume
            SetSpotifyMasterVolume(targetVolume);
        }
        catch
        {
            // Silently complete
        }
    }

    /// <summary>
    /// Sets Spotify.exe's master volume level in Windows Core Audio.
    /// </summary>
    public static void SetSpotifyMasterVolume(float level)
    {
        if (!OperatingSystem.IsWindows()) return;

        try
        {
            var enumerator = (IMMDeviceEnumerator)new MMDeviceEnumeratorComObject();
            if (enumerator.GetDefaultAudioEndpoint(0, 1, out var device) != 0) return;

            var iid = typeof(IAudioSessionManager2).GUID;
            if (device.Activate(ref iid, 23, IntPtr.Zero, out var mgrObj) != 0) return;

            var mgr = (IAudioSessionManager2)mgrObj;
            if (mgr.GetSessionEnumerator(out var sessionEnum) != 0) return;
            if (sessionEnum.GetCount(out var count) != 0) return;

            for (int i = 0; i < count; i++)
            {
                if (sessionEnum.GetSession(i, out var ctl) != 0) continue;
                if (ctl is not IAudioSessionControl2 ctl2) continue;

                if (ctl2.GetProcessId(out var pid) != 0 || pid <= 0) continue;

                bool isSpotify = false;
                try
                {
                    var proc = Process.GetProcessById(pid);
                    isSpotify = proc.ProcessName.Equals("Spotify", StringComparison.OrdinalIgnoreCase);
                }
                catch { }

                if (isSpotify && ctl is ISimpleAudioVolume vol)
                {
                    var empty = Guid.Empty;
                    vol.SetMasterVolume(Math.Clamp(level, 0.0f, 1.0f), ref empty);
                }
            }
        }
        catch { }
    }

    /// <summary>
    /// Executes the 350ms Soft Glide Skip:
    /// 1. Instantly ducks Spotify.exe hardware audio to 0% (0ms).
    /// 2. Dispatches the action (e.g. Spotify API SkipNext or Media Key).
    /// 3. Pauses 50ms for buffer handoff.
    /// 4. Ramps volume smoothly back up over 150ms.
    /// </summary>
    public static async Task SoftGlideAsync(Func<Task> actionDuringDuck)
    {
        float? originalVolume = null;

        try
        {
            originalVolume = DuckSpotifyVolume();
            if (actionDuringDuck != null)
            {
                await actionDuringDuck();
            }
        }
        finally
        {
            await Task.Delay(50);
            await RestoreSpotifyVolumeSmoothlyAsync(originalVolume ?? 1.0f);
        }
    }
}
