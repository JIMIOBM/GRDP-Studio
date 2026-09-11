package com.grdp.studio.wellbore.risk.controller;

import com.grdp.studio.common.ApiResponse;
import com.grdp.studio.wellbore.risk.dto.*;
import com.grdp.studio.wellbore.risk.service.HydrateCalculator;
import com.grdp.studio.wellbore.risk.service.LiquidLoadingCalculator;
import com.grdp.studio.wellbore.risk.service.WellboreRiskStorageService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/wellbore")
public class WellboreRiskController {
    private final LiquidLoadingCalculator liquid;
    private final HydrateCalculator hydrate;
    private final WellboreRiskStorageService storage;
    public WellboreRiskController(LiquidLoadingCalculator liquid,HydrateCalculator hydrate,WellboreRiskStorageService storage){this.liquid=liquid;this.hydrate=hydrate;this.storage=storage;}

    @PostMapping("/liquid-loading/calculate") public ApiResponse<LiquidLoadingResult> calculateLiquid(@Valid @RequestBody LiquidLoadingRequest request){return ApiResponse.success(liquid.calculate(request));}
    @PostMapping("/liquid-loading/records/save") public ApiResponse<Map<String,Object>> saveLiquid(@Valid @RequestBody LiquidLoadingSaveRequest request){return ApiResponse.success(storage.saveLiquid(request));}
    @GetMapping("/liquid-loading/records") public ApiResponse<List<Map<String,Object>>> liquidList(@RequestParam long projectId,@RequestParam long gasReservoirId,@RequestParam String wellName){return ApiResponse.success(storage.liquidList(projectId,gasReservoirId,wellName));}
    @GetMapping("/liquid-loading/records/{id}") public ApiResponse<Map<String,Object>> liquidDetail(@PathVariable long id,@RequestParam long projectId,@RequestParam long gasReservoirId,@RequestParam String wellName){return ApiResponse.success(storage.liquidDetail(id,projectId,gasReservoirId,wellName));}
    @DeleteMapping("/liquid-loading/records/{id}") public ApiResponse<Void> deleteLiquid(@PathVariable long id,@RequestParam long projectId,@RequestParam long gasReservoirId,@RequestParam String wellName){storage.deleteLiquid(id,projectId,gasReservoirId,wellName);return ApiResponse.success();}

    @PostMapping("/hydrate/calculate") public ApiResponse<HydrateResult> calculateHydrate(@Valid @RequestBody HydrateRequest request){return ApiResponse.success(hydrate.calculate(request));}
    @PostMapping("/hydrate/records/save") public ApiResponse<Map<String,Object>> saveHydrate(@Valid @RequestBody HydrateSaveRequest request){return ApiResponse.success(storage.saveHydrate(request));}
    @GetMapping("/hydrate/records") public ApiResponse<List<Map<String,Object>>> hydrateList(@RequestParam long projectId,@RequestParam long gasReservoirId,@RequestParam String wellName){return ApiResponse.success(storage.hydrateList(projectId,gasReservoirId,wellName));}
    @GetMapping("/hydrate/records/{id}") public ApiResponse<Map<String,Object>> hydrateDetail(@PathVariable long id,@RequestParam long projectId,@RequestParam long gasReservoirId,@RequestParam String wellName){return ApiResponse.success(storage.hydrateDetail(id,projectId,gasReservoirId,wellName));}
    @DeleteMapping("/hydrate/records/{id}") public ApiResponse<Void> deleteHydrate(@PathVariable long id,@RequestParam long projectId,@RequestParam long gasReservoirId,@RequestParam String wellName){storage.deleteHydrate(id,projectId,gasReservoirId,wellName);return ApiResponse.success();}
}
