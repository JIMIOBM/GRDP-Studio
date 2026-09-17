package com.grdp.studio.pipeline;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/** API quantities have explicit engineering-unit suffixes; the solver converts to SI. */
public final class PipelineDtos {
    private PipelineDtos() {}
    public record Segment(@NotBlank @Size(max=100) String name,
            @Positive double lengthM, @Positive double diameterMm,
            @PositiveOrZero double roughnessMm, double elevationChangeM,
            double ambientC, @PositiveOrZero double heatTransferWm2K) {}
    public record Equipment(@NotBlank @Size(max=100) String name, @NotBlank String type,
            @PositiveOrZero int afterSegment, @PositiveOrZero double lossK,
            @Positive double pressureRatio, @Positive @DecimalMax("1") double efficiency,
            @Positive double maxPressureMpa, @Positive double maxPowerKw) {}
    /** Water availability for hydrate screening; the correlation assumes pure, uninhibited water. */
    public record Constraints(String waterState) {}
    @JsonIgnoreProperties(ignoreUnknown=true)
    public record BoundaryNode(String nodeId,Double supplyRate10k,Double withdrawalRate10k,
            Double pressureMpa,Double temperatureC) {}
    public record BoundaryCase(String id,String operatingAt,List<BoundaryNode> nodes) {}
    /** A well keeps all operating cases; activeCaseId selects only the editable row or standalone thermal preview. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Boundary(int topologyRevision,String activeCaseId,List<BoundaryCase> cases) {
        @JsonIgnore public BoundaryCase activeCase() {
            if(activeCaseId==null||cases==null)return null;
            return cases.stream().filter(c->c!=null&&activeCaseId.equals(c.id())).findFirst().orElse(null);
        }
        @JsonIgnore public List<BoundaryNode> nodes() {
            var selected=activeCase();return selected==null?null:selected.nodes();
        }
    }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Input(String target, @NotBlank String thermalMode,
            @NotBlank String frictionMethod, Double inletMpa, Double outletMpa,
            Double rate10k, Double inletC, @Positive Double gasGravity,
            @Positive Double z, @Positive Double viscosityMpaS, @Positive Double cpJkgK,
            Double jtKmpa, @Positive Double standardPressurePa, @Positive Double standardTemperatureK,
            @Positive Double standardZ, @Size(max=30) List<@NotNull @Valid Segment> segments,
            @Size(max=30) List<@NotNull @Valid Equipment> equipment,
            Constraints constraints, PipelineGasModel.Snapshot gasModel, Boundary boundary,
            PipelineTemperature.Snapshot thermalModel) {
        /** Existing input histories predate the saved thermal-parameter snapshot. */
        public Input(String target,String thermalMode,String frictionMethod,Double inletMpa,Double outletMpa,
                Double rate10k,Double inletC,Double gasGravity,Double z,Double viscosityMpaS,Double cpJkgK,
                Double jtKmpa,Double standardPressurePa,Double standardTemperatureK,Double standardZ,
                List<Segment> segments,List<Equipment> equipment,Constraints constraints,PipelineGasModel.Snapshot gasModel,Boundary boundary) {
            this(target,thermalMode,frictionMethod,inletMpa,outletMpa,rate10k,inletC,gasGravity,z,viscosityMpaS,
                    cpJkgK,jtKmpa,standardPressurePa,standardTemperatureK,standardZ,segments,equipment,constraints,gasModel,boundary,null);
        }
        public Input withThermalModel(PipelineTemperature.Snapshot snapshot) {
            return new Input(target,thermalMode,frictionMethod,inletMpa,outletMpa,rate10k,inletC,gasGravity,z,
                    viscosityMpaS,cpJkgK,jtKmpa,standardPressurePa,standardTemperatureK,standardZ,segments,equipment,
                    constraints,gasModel,boundary,snapshot);
        }
        /** Scalar boundaries remain readable for histories and callers saved before node boundaries. */
        public Input(String target,String thermalMode,String frictionMethod,Double inletMpa,Double outletMpa,
                Double rate10k,Double inletC,Double gasGravity,Double z,Double viscosityMpaS,Double cpJkgK,
                Double jtKmpa,Double standardPressurePa,Double standardTemperatureK,Double standardZ,
                List<Segment> segments,List<Equipment> equipment,Constraints constraints,PipelineGasModel.Snapshot gasModel) {
            this(target,thermalMode,frictionMethod,inletMpa,outletMpa,rate10k,inletC,gasGravity,z,viscosityMpaS,
                    cpJkgK,jtKmpa,standardPressurePa,standardTemperatureK,standardZ,segments,equipment,constraints,gasModel,null);
        }
        /** Existing fixed-property histories and callers have no EOS snapshot. */
        public Input(String target,String thermalMode,String frictionMethod,Double inletMpa,Double outletMpa,
                Double rate10k,Double inletC,Double gasGravity,Double z,Double viscosityMpaS,Double cpJkgK,
                Double jtKmpa,Double standardPressurePa,Double standardTemperatureK,Double standardZ,
                List<Segment> segments,List<Equipment> equipment,Constraints constraints) {
            this(target,thermalMode,frictionMethod,inletMpa,outletMpa,rate10k,inletC,gasGravity,z,viscosityMpaS,
                    cpJkgK,jtKmpa,standardPressurePa,standardTemperatureK,standardZ,segments,equipment,constraints,null);
        }
    }
    /** Each page may save an incomplete draft; only its own fields are merged and validated. */
    public record SectionSaveRequest(@Positive long projectId, @Positive long gasReservoirId,
            @NotBlank @Size(max=100) String wellName, @PositiveOrZero int revision,
            @NotNull Input input) {}
    public record Detail(long id, int revision, int topologyRevision, Input input) {}
    public record Point(int segmentIndex, String location, double distanceM, double pressureMpa,
            double temperatureC, double velocityMs, double densityKgM3, double reynolds, double frictionFactor,
            Double z, Double cpJkgK, Double viscosityMpaS) {}
    public record Assessment(String kind, String location, double distanceM, String status,
            Double actual, Double limit, Double margin, String unit, String reason) {}
    public record EquipmentResult(String name, String type, double distanceM, double inletMpa,
            double outletMpa, double outletC, double powerKw) {}
    public record Result(String algorithmVersion, double inletMpa, double outletMpa, double rate10k,
            double outletC, double pressureDropMpa, double maxVelocityMs, double minTemperatureC,
            double totalPowerKw, int iterations, double residualMpa, List<Point> points,
            List<EquipmentResult> equipment, List<Assessment> assessments, List<String> notes) {}
}
