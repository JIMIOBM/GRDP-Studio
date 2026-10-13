using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Text;
using Avro.IO;
using Avro.Specific;
using Schlumberger.Avocet.Extension.Adapter.Stingray.Shared;
using Slb.Production.EngineService;
using Slb.Production.EngineService.Simulation;
using Slb.Production.Engineering.EngineService.Client_CLR;

namespace Grdp.Iam
{
    // Experimental integration adapter. Compiled against the installed PIPESIM 2022 client.
    // The Agent2021 suffix is required by IAM 2022.1's existing adapter discovery.
    public sealed class Pipesim2022Agent2021 : IStingrayEngineAgent
    {
        private ProcessManagerLauncher manager;
        private EngineServiceFactory factory;
        private INetworkSimulationService service;
        private string restart = "";
        private readonly TimeSpan timeout = TimeSpan.FromSeconds(90);
        public bool IsSimulationServiceCreated { get { return service != null; } }
        public bool IsEngineServiceInitialized { get { return factory != null; } }
        public int Processors { get; set; }
        public CalculationStopReason StopReason { get; private set; }
        public IStingraySimulationResults SimulationResults { get; private set; }
        public IStingrayQuery Query { get; private set; }
        public List<string> ExceptionMessages { get; private set; }
        public List<IStingrayMessageItem> EngineMessages { get; private set; }
        private static void Log(string text) {
            string path = Environment.GetEnvironmentVariable("GRDP_IAM_PIPESIM_TRACE");
            if (string.IsNullOrEmpty(path))
                path = Path.Combine(Path.GetDirectoryName(typeof(Pipesim2022Agent2021).Assembly.Location), "GRDP.IAM.PIPESIM.trace.log");
            try { File.AppendAllText(path, DateTime.UtcNow.ToString("o") + " PID=" + Process.GetCurrentProcess().Id + " " + text + Environment.NewLine); }
            catch { }
        }
        public Pipesim2022Agent2021()
        {
            ExceptionMessages = new List<string>();
            EngineMessages = new List<IStingrayMessageItem>();
            Log("Agent constructed domain=" + AppDomain.CurrentDomain.FriendlyName);
        }
        public bool InitializeEngineService(string programs, string logFile)
        {
            Log("InitializeEngineService " + programs);
            try
            {
                manager = new ProcessManagerLauncher(programs, "", true);
                factory = new EngineServiceFactory(manager.get_broker_url(), manager.get_process_manager_id());
                Log("Engine client ready " + manager.get_broker_url());
                return true;
            }
            catch (Exception e) { ExceptionMessages.Add(e.ToString()); TearDown(); return false; }
        }
        public bool CreateSimulationService(string keywords, string directory, int processors)
        {
            Log("CreateSimulationService start " + directory + " processors=" + processors);
            ExceptionMessages.Clear();
            EngineMessages.Clear();
            try
            {
                if (processors != 1) throw new InvalidOperationException("The GRDP demo supports one PIPESIM process only.");
                Processors = processors;
                bool valid = false;
                var attachments = Attachments.BuildAttachments(keywords, directory, out valid);
                if (!valid) throw new InvalidOperationException("Unable to resolve model attachments.");
                var data = new EngineServiceFactoryData {
                    simulation_model = keywords, number_of_processes = 1, enable_recording = false,
                    attachments = attachments.Select(a => new AttachmentFile {
                        filename = a.Filename, content = Encoding.ASCII.GetBytes(a.Content)
                    }).ToList()
                };
                service = factory.get_network_simulation_service(data);
                Log("Engine simulation service created");
                var response = service.query(timeout);
                Log("Engine query complete success=" + response.success);
                if (!response.success) throw new InvalidOperationException("PIPESIM model query failed.");
                Query = new ModelQuery(response);
                Log("IAM query wrapper built");
                foreach (var message in service.initialization_messages()) AddMessage(message);
                return !ExceptionMessages.Any();
            }
            catch (Exception e) { Log("CreateSimulationService exception " + e); ExceptionMessages.Add(e.ToString()); return false; }
        }
        private void AddMessage(MessageItem message)
        {
            EngineMessages.Add(new StingrayMessageItem((StingrayMessageType)message.severity, message.message));
            if (message.severity == MessageType.ES_ERROR || message.severity == MessageType.ES_FATAL)
                ExceptionMessages.Add(message.message);
        }
        private static NetworkSimulationRequestMessage Request(IStingrayNetworkSimulationRequestMessage input)
        {
            return new NetworkSimulationRequestMessage {
                debug_numbers = "1010", result_equipment_ids = new List<ResultEquipmentId>(),
                global_changes = new List<KeywordChange>(),
                branch_changes = new List<BranchChanges>(), restart_solution = "",
                equipment_changes = input.EquipmentChanges.Select(e => new EquipmentChanges {
                    equipment_id = e.equipment_id, new_maincode = e.new_maincode,
                    keyword_changes = e.keyword_changes.Select(k => new KeywordChange {
                        subcode_path = k.subcode_path, value = k.value
                    }).ToList()
                }).ToList()
            };
        }
        public byte[] CreateRequestMsgFile(IStingrayNetworkSimulationRequestMessage input)
        {
            var request = Request(input);
            using (var stream = new MemoryStream()) {
                new SpecificDefaultWriter(request.Schema).Write(request, new BinaryEncoder(stream));
                return stream.ToArray();
            }
        }
        public bool Calculate(IStingrayNetworkSimulationRequestMessage input, bool useRestart, string recordFilePath)
        {
            Log("Calculate start");
            ExceptionMessages.Clear();
            EngineMessages.Clear();
            SimulationResults = null;
            try
            {
                var request = Request(input);
                if (useRestart) request.restart_solution = restart;
                var response = service.calculate(request, timeout);
                Log("Engine calculation complete success=" + response.success);
                foreach (var message in response.messages.items) AddMessage(message);
                if (response.network_response_message != null) {
                    restart = response.network_response_message.restart_solution;
                    new EquipmentVariableAnalysisServiceDeprecated(response.network_response_message).AddPressureTemperatureDifferences();
                }
                SimulationResults = new ModelResults(response);
                StopReason = CalculationStopReason.CalculationFinished;
                return response.success && !ExceptionMessages.Any();
            }
            catch (Exception e) { Log("Calculate exception " + e); ExceptionMessages.Add(e.ToString()); return false; }
        }
        public void Abort() { StopReason = CalculationStopReason.TerminatedByUser; TerminateSimulationService(); }
        public void TerminateSimulationService() { if (service != null) { service.Dispose(); service = null; } }
        public void TearDown()
        {
            TerminateSimulationService();
            try { if (factory != null) factory.Dispose(); }
            finally { factory = null; if (manager != null) manager.Dispose(); manager = null; }
        }
    }

    internal sealed class ModelQuery : IStingrayQuery
    {
        public IList<IStingrayEquipment> Equipment { get; private set; }
        public ModelQuery(SimulationResponseMessage data) {
            Equipment = data.model_query_response_message.nodes.Select(n => (IStingrayEquipment)new Equipment(n)).ToList();
        }
    }
    internal sealed class Equipment : IStingrayEquipment
    {
        private readonly ModelNode node;
        public Equipment(ModelNode data) { node = data; }
        public int Id { get { return node.uid; } }
        public string Name { get { return node.name; } }
        public StingrayNodeType NodeType { get { return (StingrayNodeType)node.extended_node_type; } }
        public StingrayNodeBoundaryConditionType NodeBoundaryConditionType { get { return (StingrayNodeBoundaryConditionType)node.node_bc.node_bc_type; } }
        public StingrayNodeBoundaryConditionFlowType NodeBoundaryConditionFlowType { get {
            return node.node_bc.rate_boundary_condition == null ? (StingrayNodeBoundaryConditionFlowType)0 :
                (StingrayNodeBoundaryConditionFlowType)node.node_bc.rate_boundary_condition.node_bc_rate_type;
        } }
    }
    internal sealed class ValueResult : IStingrayResult
    {
        private readonly Result data;
        public ValueResult(Result value) { data = value; }
        public int variable_type_id { get { return data.variable_type_id; } }
        public object value { get { return data.value; } }
    }
    internal sealed class PointValues : IStingrayPointResult
    {
        private readonly PointResult data;
        public PointValues(PointResult value) { data = value; results = value.results.Select(r => (IStingrayResult)new ValueResult(r)).ToList(); }
        public StingrayPointLocator locator { get { return (StingrayPointLocator)data.locator; } }
        public double location_offset { get { return data.location_offset; } }
        public StingrayFlowDirection flow { get { return (StingrayFlowDirection)data.flow; } }
        public List<IStingrayResult> results { get; private set; }
    }
    internal sealed class EquipmentValues : IStingrayEquipmentResult
    {
        private readonly EquipmentResults data;
        public EquipmentValues(EquipmentResults value) {
            data = value;
            results = value.results.Select(r => (IStingrayResult)new ValueResult(r)).ToList();
            point_results = value.point_results.Select(r => (IStingrayPointResult)new PointValues(r)).ToList();
        }
        public int equipment_id { get { return data.equipment_id; } }
        public string equipment_name { get { return data.equipment_name; } }
        public int equipment_type_id { get { return data.equipment_type_id; } }
        public List<IStingrayResult> results { get; private set; }
        public List<IStingrayPointResult> point_results { get; private set; }
    }
    internal sealed class Variable : IStingrayVariableType
    {
        private readonly VariableType data;
        public Variable(VariableType value) { data = value; }
        public int id { get { return data.id; } }
        public string name { get { return data.name; } }
        public string measurement { get { return data.measurement; } }
        public string unit { get { return data.unit; } }
        public StingrayValueType value_type { get { return (StingrayValueType)data.value_type; } }
    }
    internal sealed class ModelResults : IStingraySimulationResults
    {
        private readonly SimulationResponseMessage data;
        public ModelResults(SimulationResponseMessage value) {
            data = value;
            EquipmentResults = value.network_response_message == null ? new List<IStingrayEquipmentResult>() :
                value.network_response_message.results.equipment_results.Select(r => (IStingrayEquipmentResult)new EquipmentValues(r)).ToList();
        }
        public bool Success { get { return data.success; } }
        public string OutputFileContent { get { return data.output_file_content; } }
        public List<IStingrayEquipmentResult> EquipmentResults { get; private set; }
        public List<StingrayMessageItem> EngineMessages { get { return data.messages.items.Select(m => new StingrayMessageItem((StingrayMessageType)m.severity, m.message)).ToList(); } }
        public IStingrayVariableType GetVariableTypeOfEquipmentResult(IStingrayResult result) {
            return new Variable(data.network_response_message.results.directory.variable_types.Single(v => v.id == result.variable_type_id));
        }
        public string GetFluid(int id) {
            if (data.network_response_message == null) return null;
            var fluid = data.network_response_message.results.fluids.FirstOrDefault(f => f.id == id);
            if (fluid == null) return null;
            var gfa = fluid.properties.FirstOrDefault(p => p.variable_type_id == 89);
            return gfa == null ? null : gfa.value as string;
        }
    }
}
