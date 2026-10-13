using System;
using System.Collections;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Reflection;
using System.Web.Script.Serialization;

// Diagnostic only: explicitly exercise the installed 2021 agent's public API.
// This does not alter IAM's adapter selection or establish supported 2022 compatibility.
public sealed class PipesimAgentRunner : MarshalByRefObject
{
    [System.Runtime.InteropServices.DllImport("kernel32.dll", CharSet = System.Runtime.InteropServices.CharSet.Unicode, SetLastError = true)]
    private static extern IntPtr LoadLibrary(string path);
    public override object InitializeLifetimeService() { return null; }

    public int Run(string installation, string programs, string evidence, string tntPath)
    {
        CultureInfo.DefaultThreadCurrentUICulture = CultureInfo.InvariantCulture;
        string adapters = Path.Combine(installation, "Extensions", "Adapters");
        string agentDirectory = Path.Combine(adapters, "Stingray2021CLR");
        string[] folders = { agentDirectory, adapters, installation };
        bool installedClient = Environment.GetEnvironmentVariable("GRDP_IAM_PROBE_USE_INSTALLED_CLIENT") == "1";
        if (installedClient) folders = new[] { programs, agentDirectory, adapters, installation };
        AppDomain.CurrentDomain.AssemblyResolve += delegate(object sender, ResolveEventArgs args)
        {
            string filename = new AssemblyName(args.Name).Name + ".dll";
            if (installedClient && filename.StartsWith("Slb.Production.Engineering.EngineService."))
            {
                string clientPath = Path.Combine(programs, filename);
                if (File.Exists(clientPath)) return Assembly.LoadFrom(clientPath);
            }
            foreach (string folder in folders)
            {
                string path = Path.Combine(folder, filename);
                if (File.Exists(path)) return Assembly.LoadFrom(path);
            }
            return null;
        };
        Environment.SetEnvironmentVariable("PATH", programs + ";" + agentDirectory + ";" + Environment.GetEnvironmentVariable("PATH"));
        Directory.SetCurrentDirectory(programs);
        var report = new Dictionary<string, object>();
        report["agent"] = "StingrayEngineAgent2021CLR";
        report["installedClient"] = installedClient;
        report["pipesimPrograms"] = programs;
        report["utc"] = DateTime.UtcNow.ToString("o");
        report["coupledSolveVerified"] = false;
        report["tntPath"] = tntPath;
        object agent = null;
        object managerForCleanup = null;
        Type type = null;
        bool initialized = false;
        bool serviceCreated = false;
        bool cleaned = false;
        try
        {
            if (Environment.GetEnvironmentVariable("GRDP_IAM_PROBE_ACTUAL_MANAGER") == "1")
            {
                Console.WriteLine("@@IAM_PHASE:AGENT_INITIALIZING");
                Assembly managerAssembly = Assembly.LoadFrom(Path.Combine(adapters, "Schlumberger.Avocet.Extension.Adapter.Stingray.dll"));
                Type managerType = managerAssembly.GetType("Schlumberger.Avocet.Extension.Adapter.Stingray.StingrayEngineAgentManager", true);
                managerForCleanup = Activator.CreateInstance(managerType, true);
                managerType.GetMethod("SetPipesimInstallationDirectory").Invoke(managerForCleanup, new object[] { programs });
                agent = managerType.GetProperty("Agent").GetValue(managerForCleanup, null);
                report["iamManagerLoadedType"] = agent == null ? null : agent.GetType().AssemblyQualifiedName;
                type = agent == null ? null : agent.GetType();
                initialized = agent != null && (bool)type.GetProperty("IsEngineServiceInitialized").GetValue(agent, null);
                report["managerInitialized"] = initialized;
                if (initialized && tntPath != null)
                {
                    Console.WriteLine("@@IAM_PHASE:SERVICE_CREATING");
                    serviceCreated = (bool)type.GetMethod("CreateSimulationService").Invoke(agent,
                        new object[] { File.ReadAllText(tntPath), Path.GetDirectoryName(tntPath), 1 });
                    report["managerServiceCreated"] = serviceCreated;
                    if (serviceCreated)
                    {
                        Console.WriteLine("@@IAM_PHASE:CALCULATING");
                        object calculated = type.GetMethod("Calculate").Invoke(agent,
                            new object[] { Activator.CreateInstance(Assembly.LoadFrom(Path.Combine(adapters, "Schlumberger.Avocet.Extension.Adapter.Stingray.Shared.dll")).GetType("Schlumberger.Avocet.Extension.Adapter.Stingray.Shared.StingrayNetworkSimulationRequestMessage", true), new object[] { "{\"equipment_changes\":[]}" }), false, "" });
                        report["managerCalculated"] = calculated;
                        Console.WriteLine("@@IAM_PHASE:COLLECTING");
                        object simulationResults = type.GetProperty("SimulationResults").GetValue(agent, null);
                        report["managerResults"] = simulationResults;
                        report["managerVariableTypes"] = ReadVariableTypes(simulationResults);
                    }
                }
            }
            else
            {
            if (Environment.GetEnvironmentVariable("GRDP_IAM_PROBE_NATIVE_CLIENT") == "1")
            {
                var handle = LoadLibrary(Path.Combine(programs, "Slb.Production.Engineering.EngineService.Client.dll"));
                report["nativeClientLoaded"] = handle != IntPtr.Zero;
                if (handle == IntPtr.Zero) throw new System.ComponentModel.Win32Exception();
            }
            string agentPath = Path.Combine(agentDirectory,
                "Schlumberger.Avocet.Extension.Adapter.Stingray.Agent2021CLR.dll");
            if (installedClient)
            {
                string isolated = Path.Combine(Path.GetDirectoryName(evidence), "isolated-agent");
                Directory.CreateDirectory(isolated);
                string copy = Path.Combine(isolated, Path.GetFileName(agentPath));
                File.Copy(agentPath, copy, true);
                agentPath = copy;
            }
            string customAgent = Environment.GetEnvironmentVariable("GRDP_IAM_PROBE_CUSTOM_AGENT");
            if (!string.IsNullOrEmpty(customAgent)) agentPath = Path.GetFullPath(customAgent);
            Assembly assembly = Assembly.LoadFrom(agentPath);
            type = assembly.GetType(string.IsNullOrEmpty(customAgent) ?
                "Schlumberger.Avocet.Extension.Adapter.Stingray.Agent2021CLR.StingrayEngineAgent2021CLR" :
                "Grdp.Iam.Pipesim2022Agent2021", true);
            agent = Activator.CreateInstance(type);
            Console.WriteLine("Initializing engine service");
            initialized = (bool)type.GetMethod("InitializeEngineService").Invoke(agent,
                new object[] { programs, Path.Combine(Path.GetDirectoryName(evidence), "agent.log") });
            report["initialized"] = initialized;
            report["isEngineServiceInitialized"] = type.GetProperty("IsEngineServiceInitialized").GetValue(agent, null);
            Console.WriteLine("Engine service initialized: " + initialized);
            File.WriteAllText(evidence + ".progress.json", new JavaScriptSerializer().Serialize(report));
            if (initialized && tntPath != null)
            {
                string keywords = File.ReadAllText(tntPath);
                Console.WriteLine("Creating simulation service from TNT");
                serviceCreated = (bool)type.GetMethod("CreateSimulationService").Invoke(agent,
                    new object[] { keywords, Path.GetDirectoryName(tntPath), 1 });
                report["serviceCreated"] = serviceCreated;
                report["isSimulationServiceCreated"] = type.GetProperty("IsSimulationServiceCreated").GetValue(agent, null);
            if (serviceCreated && !string.IsNullOrEmpty(customAgent))
                {
                    var query = type.GetProperty("Query").GetValue(agent, null);
                    var equipment = (IEnumerable)query.GetType().GetProperty("Equipment").GetValue(query, null);
                    var rows = new List<Dictionary<string, object>>();
                    foreach (var item in equipment)
                    {
                        var row = new Dictionary<string, object>();
                        foreach (string property in new[] { "Id", "Name", "NodeType", "NodeBoundaryConditionType", "NodeBoundaryConditionFlowType" })
                            row[property] = item.GetType().GetProperty(property).GetValue(item, null);
                        rows.Add(row);
                    }
                    report["queryEquipment"] = rows;
                }
                if (serviceCreated && !string.IsNullOrEmpty(customAgent))
                {
                    var shared = Assembly.LoadFrom(Path.Combine(adapters, "Schlumberger.Avocet.Extension.Adapter.Stingray.Shared.dll"));
                    var request = Activator.CreateInstance(shared.GetType("Schlumberger.Avocet.Extension.Adapter.Stingray.Shared.StingrayNetworkSimulationRequestMessage", true), new object[] { "{\"equipment_changes\":[]}" });
                    Console.WriteLine("Calculating through GRDP IAM adapter");
                    report["calculated"] = type.GetMethod("Calculate").Invoke(agent, new object[] { request, false, "" });
                    report["results"] = type.GetProperty("SimulationResults").GetValue(agent, null);
                }
            }
            }
            var errors = new List<string>();
            foreach (object error in (IEnumerable)type.GetProperty("ExceptionMessages").GetValue(agent, null))
                errors.Add(Convert.ToString(error));
            report["exceptionMessages"] = errors;
            if (serviceCreated)
            {
                var messages = new List<string>();
                foreach (object message in (IEnumerable)type.GetProperty("EngineMessages").GetValue(agent, null))
                    messages.Add(Convert.ToString(message.GetType().GetProperty("Message").GetValue(message, null)));
                report["engineMessages"] = messages;
            }
        }
        catch (Exception error) { report["error"] = error.ToString(); }
        finally
        {
            if (initialized)
            {
                try
                {
                    if (Environment.GetEnvironmentVariable("GRDP_IAM_PROBE_ACTUAL_MANAGER") == "1")
                    {
                        var managerAssembly = Assembly.LoadFrom(Path.Combine(adapters, "Schlumberger.Avocet.Extension.Adapter.Stingray.dll"));
                        var managerType = managerAssembly.GetType("Schlumberger.Avocet.Extension.Adapter.Stingray.StingrayEngineAgentManager", true);
                        managerType.GetMethod("TerminateSimulationService").Invoke(managerForCleanup, null);
                        var tearDown = type.GetMethod("TearDown");
                        if (tearDown != null) tearDown.Invoke(agent, null);
                    }
                    else type.GetMethod("TerminateSimulationService").Invoke(agent, null);
                }
                catch (Exception error) { report["serviceCleanupError"] = error.ToString(); }
                try { type.GetMethod("TearDown").Invoke(agent, null); cleaned = true; }
                catch (Exception error) { report["cleanupError"] = error.ToString(); }
            }
            report["cleaned"] = cleaned;
            var serializer = new JavaScriptSerializer { MaxJsonLength = 32 * 1024 * 1024 };
            string json = serializer.Serialize(report);
            File.WriteAllText(evidence, json);
            Console.WriteLine(json);
        }
        return initialized && cleaned && (tntPath == null || serviceCreated) && !report.ContainsKey("error")
            && (!report.ContainsKey("calculated") || (bool)report["calculated"]) ? 0 : 1;
    }

    private static List<Dictionary<string, object>> ReadVariableTypes(object simulationResults)
    {
        var result = new List<Dictionary<string, object>>();
        if (simulationResults == null) return result;
        var resultType = simulationResults.GetType();
        var equipmentProperty = resultType.GetProperty("EquipmentResults");
        var variableMethod = resultType.GetMethod("GetVariableTypeOfEquipmentResult");
        if (equipmentProperty == null || variableMethod == null) return result;
        var equipmentRows = equipmentProperty.GetValue(simulationResults, null) as IEnumerable;
        if (equipmentRows == null) return result;
        var seen = new HashSet<int>();
        foreach (object equipment in equipmentRows)
        {
            foreach (object item in EnumerateResultRows(equipment))
            {
                object variable = variableMethod.Invoke(simulationResults, new[] { item });
                if (variable == null) continue;
                int id = Convert.ToInt32(ReadProperty(variable, "id", "Id"));
                if (!seen.Add(id)) continue;
                var row = new Dictionary<string, object>();
                row["id"] = id;
                row["name"] = ReadProperty(variable, "name", "Name");
                row["measurement"] = ReadProperty(variable, "measurement", "Measurement");
                row["unit"] = ReadProperty(variable, "unit", "Unit");
                result.Add(row);
            }
        }
        return result;
    }

    private static IEnumerable EnumerateResultRows(object equipment)
    {
        if (equipment == null) yield break;
        foreach (string name in new[] { "results", "Results" })
        {
            var property = equipment.GetType().GetProperty(name);
            IEnumerable rows = property == null ? null : property.GetValue(equipment, null) as IEnumerable;
            if (rows != null)
                foreach (object row in rows) yield return row;
        }
        var points = ReadProperty(equipment, "point_results", "PointResults") as IEnumerable;
        if (points == null) yield break;
        foreach (object point in points)
        {
            var property = point.GetType().GetProperty("results") ?? point.GetType().GetProperty("Results");
            IEnumerable rows = property == null ? null : property.GetValue(point, null) as IEnumerable;
            if (rows != null)
                foreach (object row in rows) yield return row;
        }
    }

    private static object ReadProperty(object target, string lower, string upper)
    {
        if (target == null) return null;
        var property = target.GetType().GetProperty(lower) ?? target.GetType().GetProperty(upper);
        return property == null ? null : property.GetValue(target, null);
    }
}

public static class PipesimAgentProbe
{
    [STAThread]
    public static int Main(string[] args)
    {
        if (args.Length != 3 && args.Length != 4) return 2;
        string installation = Path.GetFullPath(args[0]);
        string programs = Path.GetFullPath(args[1]);
        string evidence = Path.GetFullPath(args[2]);
        string tntPath = args.Length == 4 ? Path.GetFullPath(args[3]) : null;
        if (File.Exists(evidence)) return 2;
        if (tntPath != null && !File.Exists(tntPath)) return 2;
        Directory.CreateDirectory(Path.GetDirectoryName(evidence));
        if (Environment.GetEnvironmentVariable("GRDP_IAM_PROBE_SKIP_MUTEX") == "1")
            return RunIsolated(installation, programs, evidence, tntPath);
        using (var mutex = new System.Threading.Mutex(false, @"Global\GRDP-Pipesim-Golden-Capture"))
        {
            bool acquired;
            try { acquired = mutex.WaitOne(0); }
            catch (System.Threading.AbandonedMutexException) { acquired = true; }
            if (!acquired) return 3;
            try { return RunIsolated(installation, programs, evidence, tntPath); }
            finally { mutex.ReleaseMutex(); }
        }
    }

    private static int RunIsolated(string installation, string programs, string evidence, string tntPath)
    {
        var setup = new AppDomainSetup {
            ApplicationBase = installation,
            ConfigurationFile = Path.Combine(installation, "Schlumberger.Avocet.exe.config")
        };
        var domain = AppDomain.CreateDomain("GRDP isolated PIPESIM agent test", null, setup);
        try
        {
            var runner = (PipesimAgentRunner)domain.CreateInstanceFromAndUnwrap(
                Assembly.GetExecutingAssembly().Location, typeof(PipesimAgentRunner).FullName);
            return runner.Run(installation, programs, evidence, tntPath);
        }
        finally { AppDomain.Unload(domain); }
    }
}
