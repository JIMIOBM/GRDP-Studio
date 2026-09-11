package com.grdp.studio.pipeline;

import com.grdp.studio.common.BusinessException;
import org.springframework.stereotype.Service;
import java.util.*;
import static com.grdp.studio.pipeline.PipelineDtos.*;
import static com.grdp.studio.pipeline.PipelineTopology.*;
import static com.grdp.studio.pipeline.PipelineTemperatureCalculator.Config;

/** One steady operating case on a rooted tree. The existing pipe solver owns all hydraulic equations. */
@Service
public class PipelineNetworkCalculator {
    public record PipeResult(String edgeId,String name,String sourceNodeId,String targetNodeId,
            double inletMpa,double outletMpa,double inletC,double outletC,double rate10k,
            double ambientC,double heatTransferWm2K) {}
    /** Identity belongs to the topology node and its ordered devices, never to a display name. */
    public record DeviceResult(String id,String nodeId,int sequence,String name,String type,String edgeId,
            double inletMpa,double outletMpa,double inletC,double outletC,double rate10k,double powerKw,
            double maxPressureMpa,Double maxPowerKw,double pressureMarginMpa,Double powerMarginKw,String status) {}
    public record HydrateResult(String edgeId,String name,double distanceM,String pointLabel,double pressureMpa,
            double temperatureC,Double equilibriumC,Double marginC,String status,String reason,int sampledPoints,int evaluatedPoints) {}
    public record NetworkResult(List<PipeResult> pipes,List<DeviceResult> equipment,List<HydrateResult> hydrate,List<String> notes) {}
    private record Tree(Node root,Map<String,Node> nodes,Map<String,List<Edge>> outgoing,List<Edge> ordered) {}
    private record State(double pressure,double temperature) {}
    private record Solved(Input input,Result result) {}
    private final PipelineCalculator flow;
    private final PipelineTemperatureCalculator heat;
    private final PipelineGasProperties gas;
    private final PipelineGasModel gasModel;

    public PipelineNetworkCalculator(PipelineCalculator flow,PipelineTemperatureCalculator heat,
            PipelineGasProperties gas,PipelineGasModel gasModel) {
        this.flow=flow;this.heat=heat;this.gas=gas;this.gasModel=gasModel;
    }

    public NetworkResult calculate(Graph graph,Input base,PipelineTemperature.Snapshot thermalSnapshot,
            PipelineGasModel.Snapshot gasSnapshot,BoundaryCase condition,int topologyRevision) {
        require(base!=null,"请填写管流计算参数");
        require(Set.of("heat","isothermal").contains(String.valueOf(base.thermalMode())),"请选择有效的温度计算方式");
        require(gasSnapshot!=null,"请先在 PVT模型中保存完整组成和计算方法");
        require(condition!=null,"请填写要计算的边界工况");
        Tree tree=tree(graph);
        PipelineBoundary.validate(new Boundary(topologyRevision,condition.id(),List.of(condition)),graph,topologyRevision);
        Map<String,BoundaryNode> rows=new HashMap<>();condition.nodes().forEach(n->rows.put(n.nodeId(),n));
        BoundaryNode source=rows.get(tree.root().id());
        require(source!=null&&source.temperatureC()!=null,"请填写井口供气温度");
        var configurations=configurations(graph,base,thermalSnapshot,gasSnapshot,topologyRevision);
        var hydrate=PipelineHydrateModel.prepare(gasSnapshot);
        String waterState=base.constraints()==null?"unknown":base.constraints().waterState();
        var notes=new LinkedHashSet<String>();
        Map<String,Double> withdrawals=new HashMap<>();List<String> unknown=new ArrayList<>();
        boolean internalWithdrawal=false;
        for(Node node:tree.nodes().values()) {
            var row=rows.get(node.id());boolean root=node.id().equals(tree.root().id());
            require(root||row==null||!positive(row.supplyRate10k()),node.name()+"：当前单井树状管网不支持其他节点供气");
            require(!root||!positive(row.withdrawalRate10k()),"井口供气源不能同时登记非零取气量");
            if(root)continue;
            boolean terminal=tree.outgoing().get(node.id()).isEmpty();
            Double withdrawal=row==null?null:row.withdrawalRate10k();
            if(terminal&&withdrawal==null)unknown.add(node.id());
            else withdrawals.put(node.id(),withdrawal==null?0:withdrawal);
            internalWithdrawal|=!terminal&&positive(withdrawal);
        }
        boolean chain=tree.outgoing().values().stream().allMatch(edges->edges.size()<=1);
        Node terminal=tree.nodes().get(tree.ordered().getLast().target());
        BoundaryNode end=rows.get(terminal.id());
        Double terminalPressure=end==null?null:end.pressureMpa();
        // A serial line without local offtake retains the existing two-pressure and reverse-inlet solves.
        if(chain&&!internalWithdrawal&&unknown.size()==1&&source.supplyRate10k()==null
                &&source.pressureMpa()!=null&&terminalPressure!=null) {
            notes.add("供气量和末端分输量均缺失，按两端压力反算串联管线流量。");
            var solved=solve(tree,tree.ordered(),base,thermalSnapshot,gasSnapshot,configurations,
                    "rate",source.pressureMpa(),terminalPressure,null,source.temperatureC());
            return result(graph,tree.ordered(),solved,hydrate,waterState,notes);
        }
        require(unknown.size()<=1,"边界条件不足：多个末端未填写分输量，不能由井口总量唯一确定各支路流量");
        if(unknown.size()==1) {
            require(source.supplyRate10k()!=null,"边界条件不足：请填写缺失的末端分输量，或填写井口供气量以计算余额");
            double remainder=source.supplyRate10k()-withdrawals.values().stream().mapToDouble(Double::doubleValue).sum();
            require(remainder>=0,"已填分输量合计超过井口供气量，缺失末端的分输余额为负");
            withdrawals.put(unknown.getFirst(),remainder);
            notes.add(tree.nodes().get(unknown.getFirst()).name()+"：分输量按井口供气量扣除全部已填分输量的余额计算。");
        }
        Map<String,Double> subtree=new HashMap<>(withdrawals),rates=new HashMap<>();
        for(Edge edge:tree.ordered().reversed()) {
            double rate=subtree.getOrDefault(edge.target(),0.0);
            require(rate>0,edge.name()+"：当前稳态单相计算不支持零流量或倒流管段，请核对该支路分输量");
            rates.put(edge.id(),rate);subtree.merge(edge.source(),rate,Double::sum);
        }
        double total=subtree.get(tree.root().id());
        if(source.supplyRate10k()!=null&&Math.abs(source.supplyRate10k()-total)>1e-6*Math.max(1,Math.max(source.supplyRate10k(),total)))
            notes.add("井口实测供气量与分输量合计存在偏差（供气量减分输量为 "+(source.supplyRate10k()-total)+" ×10⁴m³/d）；按各节点分输量守恒计算，井口实测量保留用于对照。");
        notes.add("各管段流量为其下游全部节点分输量之和；沿线分输先从到达节点的流量中扣除，再进入下游支路。");
        notes.add("仅井口温度作为供气温度；内部和末端实测压力、温度保留用于对照，不钳制计算状态。");
        if(source.pressureMpa()==null) {
            require(chain&&!internalWithdrawal&&terminalPressure!=null,
                    "请填写井口入口压力；分支或沿线分输管网需已知井口压力，串联无分输管线可由末端压力和流量反算入口");
            notes.add("井口压力缺失，按末端压力和流量反算串联管线入口压力。");
            var solved=solve(tree,tree.ordered(),base,thermalSnapshot,gasSnapshot,configurations,
                    "inlet",null,terminalPressure,total,source.temperatureC());
            return result(graph,tree.ordered(),solved,hydrate,waterState,notes);
        }
        Map<String,State> states=new HashMap<>();states.put(tree.root().id(),new State(source.pressureMpa(),source.temperatureC()));
        Map<String,PipeResult> pipes=new HashMap<>();var devices=new ArrayList<DeviceResult>();
        Map<String,HydrateResult> hydrates=new HashMap<>();
        for(Edge edge:tree.ordered()) {
            State inlet=states.get(edge.source());
            Solved solved;
            try { solved=solve(tree,List.of(edge),base,thermalSnapshot,gasSnapshot,configurations,
                    "outlet",inlet.pressure(),null,rates.get(edge.id()),inlet.temperature()); }
            catch(BusinessException ex) { throw new BusinessException(400,edge.name()+"："+ex.getMessage()); }
            var pipe=pipe(edge,0,solved);pipes.put(edge.id(),pipe);
            devices.addAll(devices(edge,0,solved));
            hydrates.put(edge.id(),hydrate(edge,0,solved,hydrate,waterState));
            // The physical pipe outlet precedes all equipment at its target node.
            states.put(edge.target(),new State(solved.result().outletMpa(),solved.result().outletC()));
            addNotes(notes,solved.result());
        }
        return new NetworkResult(graph.edges().stream().map(e->pipes.get(e.id())).toList(),List.copyOf(devices),
                graph.edges().stream().map(e->hydrates.get(e.id())).toList(),List.copyOf(notes));
    }

    private Solved solve(Tree tree,List<Edge> edges,Input base,PipelineTemperature.Snapshot thermal,
            PipelineGasModel.Snapshot snapshot,Map<String,Config> configs,String target,
            Double pin,Double pout,Double rate,double tin) {
        boolean coupled="heat".equals(base.thermalMode());
        var equipment=new ArrayList<Equipment>();
        for(int i=0;i<edges.size();i++)equipment.addAll(equipment(tree.nodes().get(edges.get(i).target()),i));
        var prepared=gas.prepare(snapshot.method(),snapshot.composition());
        var initialSegments=new ArrayList<Segment>();
        for(Edge edge:edges)initialSegments.add(segment(tree,edge,0,0));
        Input initial=gasModel.withSnapshot(input(base,snapshot,thermal,target,coupled?"heat":"isothermal",pin,pout,rate,tin,initialSegments,equipment),snapshot);
        if(!coupled)return new Solved(initial,flow.calculate(initial));
        require(initial.standardPressurePa()!=null&&initial.standardTemperatureK()!=null,
                "请补全标况压力与温度");
        double massPerRate=prepared.at(initial.standardPressurePa()/1e6,initial.standardTemperatureK()-273.15).densityKgM3()/8.64;
        Result previous=null;double trialRate=rate==null?1:rate;
        if("rate".equals(target)) {
            // The existing inverse solver supplies a physically derived initial flow, not an arbitrary split.
            previous=flow.calculate(input(initial,snapshot,null,target,"isothermal",pin,pout,null,tin,initialSegments,equipment));
            trialRate=previous.rate10k();
        }
        double[] previousU=new double[edges.size()];
        for(int iteration=0;iteration<thermal.settings().maxIterations();iteration++) {
            var segments=new ArrayList<Segment>();double change=0;
            for(int i=0;i<edges.size();i++) {
                Edge edge=edges.get(i);Config config=configs.get(edge.id());
                double meanP="inlet".equals(target)?pout:pin,meanT=tin;
                if(previous!=null) {
                    var points=physicalPoints(previous,equipment,i);
                    meanP=points.stream().mapToDouble(Point::pressureMpa).average().orElseThrow();
                    meanT=points.stream().mapToDouble(Point::temperatureC).average().orElseThrow();
                }
                var state=prepared.at(meanP,meanT);var viscosity=prepared.standingViscosity(meanP,meanT);
                var source=new PipelinePvtThermalProperties.Detail(snapshot.pvtId(),"当前井组分 PVT",snapshot.compositionRevision(),
                        meanP,meanT,state.densityKgM3(),viscosity.viscosityMpaS(),state.cpJkgK(),null,Map.of(),null,viscosity);
                Config used=PipelineTemperature.withProperties(config,source,trialRate*massPerRate);
                double u=heat.calculate(number(edge.parameters(),"diameterMm",edge.name()),used).innerAreaU();
                change=Math.max(change,Math.abs(u-previousU[i])/Math.max(u,1e-12));previousU[i]=u;
                segments.add(segment(tree,edge,config.ambientC(),u));
            }
            Input current=input(initial,snapshot,thermal,target,"heat",pin,pout,rate,tin,segments,equipment);
            Result calculated=flow.calculate(current);
            double tolerance=thermal.settings().tolerance();
            if(previous!=null&&change<tolerance&&Math.abs(calculated.rate10k()-trialRate)/Math.max(calculated.rate10k(),1e-8)<tolerance
                    &&temperatureChange(previous,calculated,equipment,edges.size())<tolerance)
                return new Solved(current,calculated);
            previous=calculated;trialRate=(trialRate+calculated.rate10k())/2;
        }
        throw new BusinessException(400,"温压联算未收敛，请检查保存的温度参数、边界条件与迭代设置");
    }

    private static double temperatureChange(Result before,Result after,List<Equipment> equipment,int count) {
        double change=0;
        for(int i=0;i<count;i++)change=Math.max(change,Math.abs(physicalPoints(before,equipment,i).getLast().temperatureC()
                -physicalPoints(after,equipment,i).getLast().temperatureC()));
        return change;
    }
    private static Input input(Input base,PipelineGasModel.Snapshot gas,PipelineTemperature.Snapshot thermal,
            String target,String mode,Double pin,Double pout,Double rate,double tin,List<Segment> segments,List<Equipment> equipment) {
        return new Input(target,mode,base.frictionMethod(),pin,pout,rate,tin,base.gasGravity(),base.z(),base.viscosityMpaS(),
                base.cpJkgK(),base.jtKmpa(),base.standardPressurePa(),base.standardTemperatureK(),base.standardZ(),
                List.copyOf(segments),List.copyOf(equipment),base.constraints(),gas,null,"heat".equals(mode)?thermal:null);
    }
    private static List<Point> physicalPoints(Result result,List<Equipment> equipment,int index) {
        var points=result.points().stream().filter(p->p.segmentIndex()==index).toList();
        int devices=(int)equipment.stream().filter(e->e.afterSegment()==index).count();
        return points.subList(0,points.size()-devices);
    }
    private static PipeResult pipe(Edge edge,int index,Solved solved) {
        var points=physicalPoints(solved.result(),solved.input().equipment(),index);var first=points.getFirst();var last=points.getLast();
        var segment=solved.input().segments().get(index);
        return new PipeResult(edge.id(),edge.name(),edge.source(),edge.target(),first.pressureMpa(),last.pressureMpa(),
                first.temperatureC(),last.temperatureC(),solved.result().rate10k(),segment.ambientC(),segment.heatTransferWm2K());
    }
    private static NetworkResult result(Graph graph,List<Edge> ordered,Solved solved,PipelineHydrateModel.Prepared hydrate,
            String waterState,LinkedHashSet<String> notes) {
        Map<String,PipeResult> pipes=new HashMap<>();var devices=new ArrayList<DeviceResult>();
        Map<String,HydrateResult> hydrates=new HashMap<>();
        for(int i=0;i<ordered.size();i++) {
            pipes.put(ordered.get(i).id(),pipe(ordered.get(i),i,solved));
            devices.addAll(devices(ordered.get(i),i,solved));
            hydrates.put(ordered.get(i).id(),hydrate(ordered.get(i),i,solved,hydrate,waterState));
        }
        addNotes(notes,solved.result());
        return new NetworkResult(graph.edges().stream().map(e->pipes.get(e.id())).toList(),List.copyOf(devices),
                graph.edges().stream().map(e->hydrates.get(e.id())).toList(),List.copyOf(notes));
    }
    private static List<DeviceResult> devices(Edge edge,int segmentIndex,Solved solved) {
        var configured=solved.input().equipment().stream().filter(e->e.afterSegment()==segmentIndex).toList();
        if(configured.isEmpty())return List.of();
        int offset=(int)solved.input().equipment().stream().filter(e->e.afterSegment()<segmentIndex).count();
        double inletC=physicalPoints(solved.result(),solved.input().equipment(),segmentIndex).getLast().temperatureC();
        var devices=new ArrayList<DeviceResult>();
        for(int index=0;index<configured.size();index++) {
            var parameter=configured.get(index);var value=solved.result().equipment().get(offset+index);
            boolean compressor="compressor".equals(parameter.type());
            double pressureMargin=parameter.maxPressureMpa()-Math.max(value.inletMpa(),value.outletMpa());
            Double powerMargin=compressor?parameter.maxPowerKw()-value.powerKw():null;
            String status=pressureMargin>=0&&(powerMargin==null||powerMargin>=0)?"pass":"fail";
            devices.add(new DeviceResult(edge.target()+":"+index,edge.target(),index+1,parameter.name(),parameter.type(),edge.id(),
                    value.inletMpa(),value.outletMpa(),inletC,value.outletC(),solved.result().rate10k(),value.powerKw(),
                    parameter.maxPressureMpa(),compressor?parameter.maxPowerKw():null,pressureMargin,powerMargin,status));
            inletC=value.outletC();
        }
        return List.copyOf(devices);
    }
    /** Screens actual solved samples, including the inlet/outlet states of every target-node device. */
    private static HydrateResult hydrate(Edge edge,int segmentIndex,Solved solved,PipelineHydrateModel.Prepared model,String waterState) {
        var points=solved.result().points().stream().filter(p->p.segmentIndex()==segmentIndex).toList();
        return screenHydrate(edge,points,model,waterState);
    }
    static HydrateResult screenHydrate(Edge edge,List<Point> points,PipelineHydrateModel.Prepared model,String waterState) {
        require(!points.isEmpty(),edge.name()+"：缺少沿程温压采样，不能评价水合物");
        Point minimum=null,unavailable=null;double minimumMargin=Double.POSITIVE_INFINITY;
        Double equilibriumC=null;String issue=null;int evaluated=0;
        for(Point point:points) {
            var equilibrium=model.at(point.pressureMpa());
            if(!equilibrium.evaluated()) {
                if(unavailable==null){unavailable=point;issue=equilibrium.reason();}
                continue;
            }
            evaluated++;
            double margin=point.temperatureC()-equilibrium.temperatureC();
            if(margin<minimumMargin){minimum=point;minimumMargin=margin;equilibriumC=equilibrium.temperatureC();}
        }
        if(unavailable!=null) {
            String reason="存在超出经验式适用范围或物性条件不足的沿程点，不能给出全管最小温度裕度："+issue;
            return new HydrateResult(edge.id(),edge.name(),unavailable.distanceM()-points.getFirst().distanceM(),unavailable.location(),
                    unavailable.pressureMpa(),unavailable.temperatureC(),null,null,"not_evaluated",reason,points.size(),evaluated);
        }
        require(minimum!=null,edge.name()+"：缺少可评价的沿程温压采样");
        String status,reason;
        if(minimumMargin>0) {
            status="pass";reason="全部采样点温度高于经验水合物平衡温度；仅作温压筛查。";
        } else if(minimumMargin==0) {
            status="equilibrium";reason="最小温度裕度为零，处于经验水合物平衡边界。";
        } else if("available".equals(waterState)) {
            status="risk";reason="在存在可用纯水、无盐及无抑制剂的假设下，采样点进入可能生成水合物的温压区。";
        } else {
            status="conditional";reason="采样点温压进入经验水合物生成区，水状态未知，不能据此确定实际生成。";
        }
        return new HydrateResult(edge.id(),edge.name(),minimum.distanceM()-points.getFirst().distanceM(),minimum.location(),
                minimum.pressureMpa(),minimum.temperatureC(),equilibriumC,minimumMargin,status,reason,points.size(),evaluated);
    }
    private static void addNotes(Set<String> notes,Result result) {
        // Each edge uses the serial kernel; the network itself is not restricted to one series path.
        for(String note:result.notes())notes.add(note.replace("串联管线", "树状管网逐管段"));
        for(var assessment:result.assessments())if("fail".equals(assessment.status()))
            notes.add(assessment.location()+"："+assessment.reason()+"（"+assessment.actual()+" / "+assessment.limit()+" "+assessment.unit()+"）");
    }
    private static Map<String,Config> configurations(Graph graph,Input base,PipelineTemperature.Snapshot thermal,
            PipelineGasModel.Snapshot gas,int topologyRevision) {
        if(!"heat".equals(base.thermalMode()))return Map.of();
        require(thermal!=null&&thermal.revision()>0&&thermal.settings()!=null,"请先保存当前井温度模型参数，再进行热力管流计算");
        require(thermal.topologyRevision()==topologyRevision,"温度参数绑定的拓扑版本已变化，请重新加载并保存温度模型");
        var settings=thermal.settings();
        require(Double.isFinite(settings.tolerance())&&settings.tolerance()>=.00001&&settings.tolerance()<=.1
                &&settings.maxIterations()>=2&&settings.maxIterations()<=80,"保存的温度联算迭代设置无效");
        require(settings.segments()!=null,"请补全保存的温度参数");
        var configs=new HashMap<String,Config>();
        for(Config config:settings.segments()) {
            require(config!=null&&config.edgeId()!=null&&configs.putIfAbsent(config.edgeId(),config)==null,"保存的管段温度参数存在空项或重复项");
            require(config.pvtId()!=null&&config.pvtId()==gas.pvtId(),"温度模型选用的 PVT 已变化，请重新加载并保存全部管段温度参数");
            require(config.ambientC()!=null&&Double.isFinite(config.ambientC())&&config.ambientC()>-100&&config.ambientC()<300,"请填写各管段有效的环境温度");
            require(config.gasConductivityWmK()!=null&&Double.isFinite(config.gasConductivityWmK())&&config.gasConductivityWmK()>0,"请填写各管段的气体导热系数 λ（W/(m·K)）");
        }
        require(configs.keySet().equals(new HashSet<>(graph.edges().stream().map(Edge::id).toList())),"保存的温度参数未覆盖当前全部管道，或仍含已删除管道；请重新核对并保存");
        return configs;
    }
    private static Segment segment(Tree tree,Edge edge,double ambient,double u) {
        return new Segment(edge.name(),number(edge.parameters(),"lengthM",edge.name()),number(edge.parameters(),"diameterMm",edge.name()),
                number(edge.parameters(),"roughnessMm",edge.name()),
                number(tree.nodes().get(edge.target()).parameters(),"elevationM",tree.nodes().get(edge.target()).name())
                        -number(tree.nodes().get(edge.source()).parameters(),"elevationM",tree.nodes().get(edge.source()).name()),ambient,u);
    }
    private static List<Equipment> equipment(Node node,int index) {
        if(!Set.of("valve","compressor").contains(node.type()))return List.of();
        var equipment=new ArrayList<Equipment>();equipment.add(PipelineFlowTopology.device(node.name(),node.type(),node.parameters(),index));
        Object extras=node.parameters().get("extraEquipment");
        require(extras==null||extras instanceof List<?>,node.name()+"：附加设备参数无效");
        if(extras instanceof List<?> list)for(Object item:list) {
            require(item instanceof Map<?,?>,node.name()+"：附加设备参数无效");
            var map=(Map<?,?>)item;
            require(map.get("name") instanceof String&&!((String)map.get("name")).isBlank()&&map.get("type") instanceof String,node.name()+"：附加设备名称或类型缺失");
            equipment.add(PipelineFlowTopology.device((String)map.get("name"),(String)map.get("type"),map,index));
        }
        return equipment;
    }
    private static Tree tree(Graph graph) {
        require(graph!=null&&graph.nodes()!=null&&graph.edges()!=null&&!graph.edges().isEmpty(),"请先保存已连接的管网拓扑");
        var nodes=new LinkedHashMap<String,Node>();var outgoing=new HashMap<String,List<Edge>>();
        for(Node node:graph.nodes()) {
            require(node!=null&&node.id()!=null&&node.type()!=null&&node.parameters()!=null&&nodes.putIfAbsent(node.id(),node)==null,"拓扑节点为空或编号重复");
            outgoing.put(node.id(),new ArrayList<>());
        }
        var sources=nodes.values().stream().filter(n->"well".equals(n.type())).toList();
        require(sources.size()==1,"树状管网需要且只能有一个井口供气源");
        Node root=sources.getFirst();var incoming=new HashSet<String>();var edgeIds=new HashSet<String>();
        for(Edge edge:graph.edges()) {
            require(edge!=null&&edge.id()!=null&&edge.parameters()!=null&&edgeIds.add(edge.id()),"拓扑管道为空或编号重复");
            require(nodes.containsKey(edge.source())&&nodes.containsKey(edge.target()),"管道连接到不存在的节点");
            require(!edge.source().equals(edge.target())&&!root.id().equals(edge.target())&&incoming.add(edge.target()),"当前支持单井树状分支管网，不支持汇合、环路或流向井口的管道");
            outgoing.get(edge.source()).add(edge);
        }
        var ordered=new ArrayList<Edge>();var visited=new HashSet<String>();var queue=new ArrayDeque<String>();queue.add(root.id());
        while(!queue.isEmpty()) {
            String id=queue.remove();require(visited.add(id),"拓扑存在环路");
            for(Edge edge:outgoing.get(id)){ordered.add(edge);queue.add(edge.target());}
        }
        require(visited.size()==nodes.size()&&ordered.size()==graph.edges().size(),"拓扑存在未连接到井口的节点、管道或环路");
        return new Tree(root,nodes,outgoing,List.copyOf(ordered));
    }
    private static double number(Map<String,Object> values,String key,String name) {
        Object value=values.get(key);require(value instanceof Number&&Double.isFinite(((Number)value).doubleValue()),name+"：请填写有效的 "+key);
        return ((Number)value).doubleValue();
    }
    private static boolean positive(Double value){return value!=null&&value>0;}
    private static void require(boolean condition,String message){if(!condition)throw new BusinessException(400,message);}
}
