using System.ComponentModel;
using System.Diagnostics;
using System.Runtime.InteropServices;
using Microsoft.Win32.SafeHandles;

namespace Grdp.SoftwareIntegration.Worker.Execution;

internal sealed record JobCleanupResult(bool Confirmed, bool TerminateUsed);

internal sealed class WindowsJobObject : IDisposable
{
    private const uint JobObjectLimitKillOnJobClose = 0x00002000;
    private readonly SafeFileHandle handle;
    private int disposed;

    private WindowsJobObject(SafeFileHandle handle) => this.handle = handle;

    public static WindowsJobObject CreateAndAssign(Process process)
    {
        if (!OperatingSystem.IsWindows())
        {
            throw new PlatformNotSupportedException("PIPESIM process ownership requires Windows Job Objects.");
        }

        var handle = NativeMethods.CreateJobObject(IntPtr.Zero, null);
        if (handle.IsInvalid)
        {
            throw new Win32Exception(Marshal.GetLastWin32Error());
        }

        var job = new WindowsJobObject(handle);
        try
        {
            var limits = new JobObjectExtendedLimitInformation
            {
                BasicLimitInformation = new JobObjectBasicLimitInformation
                {
                    LimitFlags = JobObjectLimitKillOnJobClose
                }
            };
            var length = Marshal.SizeOf<JobObjectExtendedLimitInformation>();
            var pointer = Marshal.AllocHGlobal(length);
            try
            {
                Marshal.StructureToPtr(limits, pointer, false);
                if (!NativeMethods.SetInformationJobObject(
                        handle,
                        JobObjectInformationClass.ExtendedLimitInformation,
                        pointer,
                        (uint)length))
                {
                    throw new Win32Exception(Marshal.GetLastWin32Error());
                }
            }
            finally
            {
                Marshal.FreeHGlobal(pointer);
            }

            if (!NativeMethods.AssignProcessToJobObject(handle, process.Handle))
            {
                throw new Win32Exception(Marshal.GetLastWin32Error());
            }

            return job;
        }
        catch
        {
            job.Dispose();
            throw;
        }
    }

    public async Task<JobCleanupResult> TerminateAndConfirmAsync(TimeSpan confirmationTimeout)
    {
        var queried = TryGetActiveProcessCount(out var active);
        var terminated = !queried || active > 0;
        if (terminated && !NativeMethods.TerminateJobObject(handle, 1))
        {
            return new JobCleanupResult(false, true);
        }

        return new JobCleanupResult(await ConfirmActiveProcessZeroAsync(confirmationTimeout), terminated);
    }

    public async Task<JobCleanupResult> ConfirmNormalExitAsync(Process root, TimeSpan confirmationTimeout)
    {
        if (!root.HasExited)
        {
            return new JobCleanupResult(false, false);
        }

        return await TerminateAndConfirmAsync(confirmationTimeout);
    }

    public void Dispose()
    {
        if (Interlocked.Exchange(ref disposed, 1) == 0)
        {
            handle.Dispose();
        }
    }

    private async Task<bool> ConfirmActiveProcessZeroAsync(TimeSpan confirmationTimeout)
    {
        var deadline = DateTimeOffset.UtcNow + confirmationTimeout;
        do
        {
            if (!TryGetActiveProcessCount(out var active)) return false;
            if (active == 0) return true;
            await Task.Delay(25);
        }
        while (DateTimeOffset.UtcNow < deadline);

        return TryGetActiveProcessCount(out var finalActive) && finalActive == 0;
    }

    private bool TryGetActiveProcessCount(out uint activeProcesses)
    {
        activeProcesses = 0;
        if (Volatile.Read(ref disposed) != 0) return false;
        var length = Marshal.SizeOf<JobObjectBasicAccountingInformation>();
        var pointer = Marshal.AllocHGlobal(length);
        try
        {
            if (!NativeMethods.QueryInformationJobObject(
                    handle,
                    JobObjectInformationClass.BasicAccountingInformation,
                    pointer,
                    (uint)length,
                    IntPtr.Zero))
            {
                return false;
            }

            activeProcesses = Marshal.PtrToStructure<JobObjectBasicAccountingInformation>(pointer).ActiveProcesses;
            return true;
        }
        finally
        {
            Marshal.FreeHGlobal(pointer);
        }
    }

    private enum JobObjectInformationClass
    {
        BasicAccountingInformation = 1,
        ExtendedLimitInformation = 9
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct JobObjectBasicAccountingInformation
    {
        public long TotalUserTime;
        public long TotalKernelTime;
        public long ThisPeriodTotalUserTime;
        public long ThisPeriodTotalKernelTime;
        public uint TotalPageFaultCount;
        public uint TotalProcesses;
        public uint ActiveProcesses;
        public uint TotalTerminatedProcesses;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct JobObjectBasicLimitInformation
    {
        public long PerProcessUserTimeLimit;
        public long PerJobUserTimeLimit;
        public uint LimitFlags;
        public UIntPtr MinimumWorkingSetSize;
        public UIntPtr MaximumWorkingSetSize;
        public uint ActiveProcessLimit;
        public UIntPtr Affinity;
        public uint PriorityClass;
        public uint SchedulingClass;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct IoCounters
    {
        public ulong ReadOperationCount;
        public ulong WriteOperationCount;
        public ulong OtherOperationCount;
        public ulong ReadTransferCount;
        public ulong WriteTransferCount;
        public ulong OtherTransferCount;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct JobObjectExtendedLimitInformation
    {
        public JobObjectBasicLimitInformation BasicLimitInformation;
        public IoCounters IoInfo;
        public UIntPtr ProcessMemoryLimit;
        public UIntPtr JobMemoryLimit;
        public UIntPtr PeakProcessMemoryUsed;
        public UIntPtr PeakJobMemoryUsed;
    }

    private static class NativeMethods
    {
        [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        public static extern SafeFileHandle CreateJobObject(IntPtr securityAttributes, string? name);

        [DllImport("kernel32.dll", SetLastError = true)]
        [return: MarshalAs(UnmanagedType.Bool)]
        public static extern bool SetInformationJobObject(
            SafeFileHandle job,
            JobObjectInformationClass informationClass,
            IntPtr information,
            uint informationLength);

        [DllImport("kernel32.dll", SetLastError = true)]
        [return: MarshalAs(UnmanagedType.Bool)]
        public static extern bool AssignProcessToJobObject(SafeFileHandle job, IntPtr process);

        [DllImport("kernel32.dll", SetLastError = true)]
        [return: MarshalAs(UnmanagedType.Bool)]
        public static extern bool TerminateJobObject(SafeFileHandle job, uint exitCode);

        [DllImport("kernel32.dll", SetLastError = true)]
        [return: MarshalAs(UnmanagedType.Bool)]
        public static extern bool QueryInformationJobObject(
            SafeFileHandle job,
            JobObjectInformationClass informationClass,
            IntPtr information,
            uint informationLength,
            IntPtr returnLength);
    }
}
