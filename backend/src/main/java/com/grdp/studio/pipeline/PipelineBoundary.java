package com.grdp.studio.pipeline;

import com.grdp.studio.common.BusinessException;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.*;
import static com.grdp.studio.pipeline.PipelineDtos.*;

/** Validates all node-boundary cases; resolves the selected case for standalone thermal preview. */
final class PipelineBoundary {
    private PipelineBoundary() {}
    static void validate(Boundary boundary,PipelineTopology.Graph graph,int revision) {
        require(boundary!=null,"请填写节点边界条件");
        if(boundary.topologyRevision()!=revision || revision<=0)
            throw new BusinessException(409,"边界绑定的拓扑版本已变化，请核对保留的节点边界并重新保存");
        require(boundary.cases()!=null&&boundary.cases().size()<=744,"边界工况最多744行");
        var ids=new HashSet<String>();graph.nodes().forEach(n->ids.add(n.id()));
        validateTimes(boundary,false);
        var caseIds=new HashSet<String>();
        for(int i=0;i<boundary.cases().size();i++) {
            var operatingCase=boundary.cases().get(i);String label="第 "+(i+1)+" 行工况：";
            require(operatingCase!=null,label+"工况不能为空");
            String id=operatingCase.id();
            require(id!=null&&!id.isBlank()&&id.length()<=100&&id.equals(id.trim()),label+"工况编号不能为空、含首尾空格或超过100字符");
            require(caseIds.add(id),label+"工况编号重复："+id);
            validateCase(operatingCase,ids,label);
        }
        require(boundary.activeCaseId()==null||caseIds.contains(boundary.activeCaseId()),"选中的边界工况不存在，请重新选择工况");
    }
    /** Standalone thermal preview accepts an undated single draft; saved boundaries require timestamps. */
    static void validateForSave(Boundary boundary,PipelineTopology.Graph graph,int revision) {
        validate(boundary,graph,revision);validateTimes(boundary,true);
    }
    static Boundary forSave(Boundary boundary) {
        if(boundary==null)return null;
        validateTimes(boundary,true);
        return new Boundary(boundary.topologyRevision(),boundary.activeCaseId(),boundary.cases().stream()
                .map(c->new BoundaryCase(c.id(),PipelineBoundaryTime.canonical(c.operatingAt()),c.nodes())).toList());
    }
    private static void validateTimes(Boundary boundary,boolean required) {
        require(boundary!=null&&boundary.cases()!=null&&boundary.cases().size()<=744,"边界工况最多744行");
        var times=new HashSet<LocalDateTime>();
        for(int i=0;i<boundary.cases().size();i++) {
            var operatingCase=boundary.cases().get(i);String label="第 "+(i+1)+" 行工况：";
            require(operatingCase!=null,label+"工况不能为空");
            String value=operatingCase.operatingAt();
            require(value!=null||(!required&&boundary.cases().size()<=1),label+"请填写工况时间（YYYY/MM/DD HH:mm）");
            if(value==null)continue;
            LocalDateTime parsed;
            try {parsed=PipelineBoundaryTime.parse(value);}
            catch(DateTimeParseException ex) {throw new BusinessException(400,label+"请填写有效的工况时间（YYYY/MM/DD HH:mm），日期、小时和分钟必须有效");}
            require(times.add(parsed),label+"工况时间重复："+value);
        }
    }
    private static void validateCase(BoundaryCase operatingCase,Set<String> ids,String label) {
        require(operatingCase.nodes()!=null&&operatingCase.nodes().size()<=200,label+"节点边界最多200项");
        var seen=new HashSet<String>();
        for(BoundaryNode node:operatingCase.nodes()) {
            require(node!=null&&node.nodeId()!=null&&ids.contains(node.nodeId()),label+"边界节点不属于当前井已保存拓扑，或节点已被删除；请核对保留的边界记录");
            require(seen.add(node.nodeId()),label+"边界节点重复："+node.nodeId());
            number(node.supplyRate10k(),0,true,label+"节点供气量");number(node.withdrawalRate10k(),0,true,label+"节点分输量");
            number(node.pressureMpa(),0,false,label+"节点绝对压力");number(node.temperatureC(),-273.15,false,label+"节点温度");
        }
    }
    private static void requireActive(Boundary boundary) {
        require(boundary.activeCaseId()!=null&&!boundary.activeCaseId().isBlank(),"请先选择要计算的边界工况");
        require(boundary.activeCase()!=null,"选中的边界工况不存在，请重新选择工况");
    }
    private static void number(Double value,double lower,boolean inclusive,String name) {
        require(value==null||(Double.isFinite(value)&&(inclusive?value>=lower:value>lower)),
                name+(inclusive?"必须为大于或等于 ":"必须为大于 ")+lower+" 的有限数值");
    }
    static Input resolve(Input input,PipelineTopology.Graph graph,int revision) {
        require(input!=null,"请填写计算输入");
        if(input.boundary()==null)return input;
        validate(input.boundary(),graph,revision);
        requireActive(input.boundary());
        var path=path(graph);var source=path.getFirst();var terminal=path.getLast();
        Map<String,BoundaryNode> data=new HashMap<>();input.boundary().nodes().forEach(n->data.put(n.nodeId(),n));
        for(int i=0;i<path.size();i++) {
            var node=path.get(i);var row=data.get(node.id());if(row==null)continue;
            if(i>0&&i<path.size()-1) {
                require(!nonzero(row.supplyRate10k())&&!nonzero(row.withdrawalRate10k()),
                        node.name()+"：已登记内部供气或分输，本期串联求解暂不支持沿线增减流量");
            } else {
                require(i!=0||!nonzero(row.withdrawalRate10k()),"供气源节点的取气量暂不支持当前串联求解");
                require(i!=path.size()-1||!nonzero(row.supplyRate10k()),"末端节点的供气量暂不支持当前串联求解");
            }
        }
        var start=data.get(source.id());var end=data.get(terminal.id());
        Double pin=start==null?null:start.pressureMpa(),pout=end==null?null:end.pressureMpa();
        Double supply=start==null?null:start.supplyRate10k();
        Double withdrawal=end==null?null:end.withdrawalRate10k();
        Double rate=supply!=null?supply:withdrawal;
        require(rate==null||rate>0,"当前串联求解不支持零流量工况，请给出正向输送流量");
        String target;
        if(pin!=null&&rate!=null) { target="outlet";pout=null; }
        else if(pout!=null&&rate!=null)target="inlet";
        else if(pin!=null&&pout!=null)target="rate";
        else throw new BusinessException(400,"边界不完整：请填写入口压力与供气/末端取气流量，或末端压力与流量；流量均缺失时需填写两端压力");
        require(start!=null&&start.temperatureC()!=null,"请填写供气源节点温度");
        return new Input(target,input.thermalMode(),input.frictionMethod(),pin,pout,rate,start.temperatureC(),
                input.gasGravity(),input.z(),input.viscosityMpaS(),input.cpJkgK(),input.jtKmpa(),
                input.standardPressurePa(),input.standardTemperatureK(),input.standardZ(),input.segments(),
                input.equipment(),input.constraints(),input.gasModel(),input.boundary(),input.thermalModel());
    }
    static Result assess(Input input,PipelineTopology.Graph graph,Result result) {
        if(input.boundary()==null)return result;
        requireActive(input.boundary());
        var path=path(graph);var notes=new ArrayList<>(result.notes());
        Map<String,BoundaryNode> data=new HashMap<>();input.boundary().nodes().forEach(n->data.put(n.nodeId(),n));
        var start=data.get(path.getFirst().id());var end=data.get(path.getLast().id());
        Double supply=start==null?null:start.supplyRate10k(),withdrawal=end==null?null:end.withdrawalRate10k();
        String flow=supply!=null?"井口供气量":"末端取气量";
        notes.add(switch(input.target()) {
            case "outlet" -> "采用入口压力和"+flow+"计算出口压力，末端实测压力保留用于结果对照。";
            case "inlet" -> "入口压力缺失，采用末端压力和"+flow+"计算入口压力。";
            case "rate" -> "供气量与末端取气量均缺失，采用两端压力计算输量。";
            default -> throw new BusinessException(400,"边界求解目标无效");
        });
        if(supply!=null&&withdrawal!=null&&Math.abs(supply-withdrawal)>1e-6*Math.max(1,Math.max(supply,withdrawal)))
            notes.add("实测供气量与末端取气量存在偏差（供气量减取气量为 "+(supply-withdrawal)+" ×10⁴m³/d）；本次沿线按井口供气量保持稳态输量，末端取气量保留用于对照。");
        notes.add("仅供气源温度作为入口温度；内部实测压力和其他节点温度保留用于结果对照，不约束沿程求解。");
        return new Result(result.algorithmVersion(),result.inletMpa(),result.outletMpa(),result.rate10k(),result.outletC(),
                result.pressureDropMpa(),result.maxVelocityMs(),result.minTemperatureC(),result.totalPowerKw(),result.iterations(),
                result.residualMpa(),result.points(),result.equipment(),result.assessments(),List.copyOf(notes));
    }
    private static boolean nonzero(Double value) {return value!=null&&value>0;}
    private static List<PipelineTopology.Node> path(PipelineTopology.Graph graph) {
        var sources=graph.nodes().stream().filter(n->"well".equals(n.type())).toList();
        require(sources.size()==1,"当前串联求解需要一个供气源井口");
        var nodes=new HashMap<String,PipelineTopology.Node>();graph.nodes().forEach(n->nodes.put(n.id(),n));
        var outgoing=new HashMap<String,PipelineTopology.Edge>();var incoming=new HashSet<String>();
        for(var e:graph.edges())require(outgoing.put(e.source(),e)==null&&incoming.add(e.target()),"已保存分支、汇合或多分输拓扑；本期仅支持单线串联计算");
        var ordered=new ArrayList<PipelineTopology.Node>();var seen=new HashSet<String>();var node=sources.getFirst();
        while(node!=null&&seen.add(node.id())) {
            ordered.add(node);var edge=outgoing.get(node.id());
            if(edge==null)break;node=nodes.get(edge.target());
        }
        require(ordered.size()>=2&&ordered.size()==nodes.size()&&graph.edges().size()==ordered.size()-1
                &&!outgoing.containsKey(ordered.getLast().id()),"当前边界求解需要从井口到单一末端的完整串联拓扑");
        return ordered;
    }
    private static void require(boolean condition,String message) {if(!condition)throw new BusinessException(400,message);}
}
