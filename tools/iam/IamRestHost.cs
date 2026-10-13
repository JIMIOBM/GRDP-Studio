using System;
using System.Globalization;
using System.IO;
using System.Reflection;
using System.Threading;

// Separate .NET Framework host: never reference IAM assemblies from the .NET 10 Worker.
public sealed class IamDomainRunner : MarshalByRefObject
{
    public override object InitializeLifetimeService() { return null; }

    private static void TraceAssembly(string prefix, Assembly assembly)
    {
        string name = assembly == null ? "<null>" : assembly.FullName;
        if (name.IndexOf("stingray", StringComparison.OrdinalIgnoreCase) < 0 &&
            name.IndexOf("adapter", StringComparison.OrdinalIgnoreCase) < 0 &&
            name.IndexOf("engine", StringComparison.OrdinalIgnoreCase) < 0) return;
        string path = "";
        try { if (assembly != null && !assembly.IsDynamic) path = assembly.Location; } catch { }
        Trace(prefix + " " + name + " " + path);
    }

    private static void Trace(string line)
    {
        string path = Environment.GetEnvironmentVariable("GRDP_IAM_HOST_TRACE");
        if (string.IsNullOrEmpty(path)) return;
        try { File.AppendAllText(path, DateTime.UtcNow.ToString("o") + " " + line + Environment.NewLine); }
        catch { }
    }

    public int Run(string executable)
    {
        CultureInfo.DefaultThreadCurrentUICulture = CultureInfo.InvariantCulture;
        Thread.CurrentThread.CurrentUICulture = CultureInfo.InvariantCulture;
        Console.WriteLine("IAM UI resources: neutral; numeric culture: " + CultureInfo.CurrentCulture.Name);
        Trace("START " + executable + " APPBASE=" + AppDomain.CurrentDomain.BaseDirectory);
        AppDomain.CurrentDomain.AssemblyLoad += (sender, eventArgs) => TraceAssembly("LOAD", eventArgs.LoadedAssembly);
        AppDomain.CurrentDomain.AssemblyResolve += (sender, eventArgs) => {
            Trace("RESOLVE " + eventArgs.Name + " REQUEST=" + eventArgs.RequestingAssembly);
            return null;
        };
        try
        {
            // Do not automatically reload other sessions' persisted workspaces.
            return AppDomain.CurrentDomain.ExecuteAssembly(executable, new[] { "clean" });
        }
        catch (Exception error)
        {
            // Format here: proprietary exception types cannot always cross the AppDomain boundary.
            Console.Error.WriteLine(error.ToString());
            return 1;
        }
    }
}

public static class IamRestHost
{
    [STAThread]
    public static int Main(string[] args)
    {
        if (args.Length < 2 || args.Length > 4)
        {
            Console.Error.WriteLine("Usage: IamRestHost.exe <IAM installation> <workspace directory> [MPI root] [diagnostic log]");
            return 2;
        }
        string executable = Path.Combine(Path.GetFullPath(args[0]), "Schlumberger.Avocet.WebAPI.Server.exe");
        if (!File.Exists(executable) || !File.Exists(executable + ".config")) return 2;
        string workspaces = Path.GetFullPath(args[1]);
        Directory.CreateDirectory(workspaces);
        Environment.SetEnvironmentVariable("IAM_WORKSPACE_PATH", workspaces);
        if (args.Length >= 3)
        {
            string mpi = Path.GetFullPath(args[2]);
            string legacyBin = Path.Combine(mpi, "intel64", "bin");
            string mpiBin = File.Exists(Path.Combine(legacyBin, "mpiexec.exe"))
                ? legacyBin : Path.Combine(mpi, "bin");
            if (!File.Exists(Path.Combine(mpiBin, "mpiexec.exe")))
            {
                Console.Error.WriteLine("MPI root has no mpiexec.exe in bin or intel64/bin: " + mpi);
                return 2;
            }
            Environment.SetEnvironmentVariable("I_MPI_ROOT", mpi);
            Environment.SetEnvironmentVariable("I_MPI_ONEAPI_ROOT", mpi);
            Environment.SetEnvironmentVariable("PATH", mpiBin + ";" + Environment.GetEnvironmentVariable("PATH"));
            Console.WriteLine("Process MPI root: " + mpi);
            Console.WriteLine("MPI executable directory: " + mpiBin);
            if (!File.Exists(Path.Combine(legacyBin, "mpiexec.exe")))
                Console.Error.WriteLine("IAM 2022.1 R2SL expects intel64/bin/mpiexec.exe; this MPI layout requires compatibility verification.");
        }
        if (args.Length == 4)
        {
            string trace = Path.GetFullPath(args[3]);
            Environment.SetEnvironmentVariable("GRDP_IAM_HOST_TRACE", trace);
            Environment.SetEnvironmentVariable("GRDP_IAM_PIPESIM_TRACE", Path.ChangeExtension(trace, ".adapter.log"));
            Console.WriteLine("IAM diagnostic log: " + trace);
        }
        string[] names = { @"Global\GRDP-IAM", @"Global\GRDP-Eclipse-100", @"Global\GRDP-Pipesim-Golden-Capture" };
        var held = new System.Collections.Generic.List<Mutex>();
        try
        {
            foreach (string name in names)
            {
                var mutex = new Mutex(false, name);
                bool acquired;
                try { acquired = mutex.WaitOne(0); }
                catch (AbandonedMutexException)
                {
                    // Ownership was granted, but prior process cleanup is unknown. Fail closed.
                    mutex.ReleaseMutex();
                    mutex.Dispose();
                    Console.Error.WriteLine("Abandoned resource lock; check simulator processes: " + name);
                    return 3;
                }
                if (!acquired)
                {
                    mutex.Dispose();
                    Console.Error.WriteLine("Simulator resource is busy: " + name);
                    return 3;
                }
                held.Add(mutex);
            }
            string installation = Path.GetDirectoryName(executable);
            Directory.SetCurrentDirectory(installation);
            var setup = new AppDomainSetup { ApplicationBase = installation, ConfigurationFile = executable + ".config" };
            var domain = AppDomain.CreateDomain("GRDP IAM REST", null, setup);
            try
            {
                var runner = (IamDomainRunner)domain.CreateInstanceFromAndUnwrap(
                    Assembly.GetExecutingAssembly().Location, typeof(IamDomainRunner).FullName);
                return runner.Run(executable);
            }
            finally { AppDomain.Unload(domain); }
        }
        catch (Exception error) { Console.Error.WriteLine(error.ToString()); return 1; }
        finally
        {
            for (int i = held.Count - 1; i >= 0; --i) { held[i].ReleaseMutex(); held[i].Dispose(); }
        }
    }
}
