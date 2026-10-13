using System;
using System.IO;
using System.Reflection;
using System.Collections.Generic;
using Slb.Production.Engineering.EngineService.Client_CLR;

// Diagnostic: test the installed 2022 engine's own client before building an IAM adapter.
public static class Pipesim2022ServiceProbe
{
    public static int Main(string[] args)
    {
        if (args.Length != 2) return 2;
        string programs = Path.GetFullPath(args[0]);
        AppDomain.CurrentDomain.AssemblyResolve += delegate(object sender, ResolveEventArgs e) {
            string file = Path.Combine(programs, new AssemblyName(e.Name).Name + ".dll");
            return File.Exists(file) ? Assembly.LoadFrom(file) : null;
        };
        Environment.SetEnvironmentVariable("PATH", programs + ";" + Environment.GetEnvironmentVariable("PATH"));
        Directory.SetCurrentDirectory(programs);
        try { return Run(programs, Path.GetFullPath(args[1])); }
        catch (Exception e) { Console.WriteLine(e); return 1; }
    }

    [System.Runtime.CompilerServices.MethodImpl(System.Runtime.CompilerServices.MethodImplOptions.NoInlining)]
    private static int Run(string programs, string tnt)
    {
        using (var manager = new ProcessManagerLauncher(programs, "", true))
        using (var factory = new EngineServiceFactory(manager.get_broker_url(), manager.get_process_manager_id()))
        {
            Console.WriteLine("2022 client initialized");
            var data = new EngineServiceFactoryData();
            data.simulation_model = File.ReadAllText(tnt);
            data.attachments = new List<Slb.Production.EngineService.Simulation.AttachmentFile>();
            data.number_of_processes = 1;
            data.enable_recording = false;
            using (var service = factory.get_network_simulation_service(data))
            {
                Console.WriteLine("2022 simulation service created");
                var result = service.query(TimeSpan.FromSeconds(60));
                Console.WriteLine("Query success: " + result.success);
                Console.WriteLine("Model nodes: " + result.model_query_response_message.nodes.Count);
                return result.success ? 0 : 1;
            }
        }
    }
}
