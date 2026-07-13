/**
 * nodeType.js
 * 节点类型枚举常量
 *
 * 建议路径：src/constants/nodeType.js
 */
export const NODETYPE = {
    NodeType_GasReservoir: 1,                                          // 气藏
    NodeType_Well: 2,                                                  // 井
    NodeType_ProductivityEvaluation: 3,                                // 产能评价
    NodeType_ProductivityEvaluationByPressure: 4,                      // 产能评价-压力形式
    NodeType_ProductivityEvaluationByPressureSquared: 5,               // 产能评价-压力平方形式
    NodeType_ProductivityEvaluationByPseudoPressure: 6,                // 产能评价-拟压力形式
    NodeType_ProductivityEvaluationByFlowEquation: 7,                  // 产能评价-渗流方程
    NodeType_ProductivityEvaluationOnePointWellTest: 8,                // 产能评价-一点法试井
    NodeType_ProductivityEvaluationBackPressureWellTest: 9,            // 产能评价-回压试井
    NodeType_ProductivityEvaluationIsochronalWellTest: 10,             // 产能评价-等时试井
    NodeType_ProductivityEvaluationModifiedIsochronalWellTest: 11,     // 产能评价-修正等时试井
    NodeType_ProductivityEvaluationFlowEquationInterpretationResult: 12,
    NodeType_ProductivityEvaluationFlowEquationProductivityInstabilityAnalysisResult: 13,
    NodeType_WaterInvasionAnalysis: 14,                                // 水侵动态分析
    NodeType_WaterInvasionAnalysisIdentify: 15,                        // 水侵动态分析-水侵识别
    NodeType_WaterInvasionAnalysisInflux: 16,                          // 水侵动态分析-水侵量
    NodeType_WaterInvasionAnalysisDriveMechanism: 17,                  // 水侵动态分析-驱动机制
    NodeType_WaterInvasionAnalysisBodySize: 18,                        // 水侵动态分析-水体大小
    NodeType_WaterInvasionAnalysisActiveness: 19,                      // 水侵动态分析-水体活跃性
    NodeType_ProductionDeclineAnalysis: 20,                            // 产量递减分析
    NodeType_ProductionDeclineAnalysisWell: 21,                        // 产量递减分析-单井
    NodeType_ProductionDeclineAnalysisWells: 22,                       // 产量递减分析-井组/气藏
    NodeType_ProductionDeclineAnalysisDaily: 23,                       // 产量递减分析-气产量
    NodeType_ProductionDeclineAnalysisCumulative: 24,                  // 产量递减分析-累产气量
    NodeType_ProductivityInstabilityAnalysis: 25,                      // 产量不稳定分析
    NodeType_AnalysisMethods: 26,                                      // 产量不稳定分析-解析法
    NodeType_TypicalCurve: 27,                                         // 产量不稳定分析-典型曲线
    NodeType_TypicalCurveBlasingame: 28,
    NodeType_TypicalCurveAG: 29,
    NodeType_TypicalCurveNPI: 30,
    NodeType_TypicalCurveTransient: 31,
    NodeType_ProductionAnalysis: 32,                                   // 生产分析
    NodeType_ProductionAnalysisVolume: 33,
    NodeType_ProductionAnalysisPressure: 34,
    NodeType_ProductionAnalysisLiquid: 35,
    NodeType_DynamicOriginalGasInplace: 36,                            // 动态储量分析
    NodeType_VerticalWellTypicalCurveBlasingame: 37,
    NodeType_VerticalWellTypicalCurveAG: 38,
    NodeType_VerticalWellTypicalCurveNPI: 39,
    NodeType_VerticalWellTypicalCurveTransient: 40,
    NodeType_HorizontalWellTypicalCurveBlasingame: 41,
    NodeType_HorizontalWellTypicalCurveAG: 42,
    NodeType_HorizontalWellTypicalCurveNPI: 43,
    NodeType_HorizontalWellTypicalCurveTransient: 44,
    NodeType_FracturedVerticalWellTypicalCurveBlasingame: 45,
    NodeType_FracturedVerticalWellTypicalCurveAG: 46,
    NodeType_FracturedVerticalWellTypicalCurveNPI: 47,
    NodeType_FracturedVerticalWellTypicalCurveTransient: 48,
    NodeType_FracturedHorizontalWellTypicalCurveBlasingame: 49,
    NodeType_FracturedHorizontalWellTypicalCurveAG: 50,
    NodeType_FracturedHorizontalWellTypicalCurveNPI: 51,
    NodeType_FracturedHorizontalWellTypicalCurveTransient: 52,
    NodeType_ConfinedGasReservoirMaterialBalanceMethodForActualStaticPressure: 53,
    NodeType_ConfinedGasReservoirMaterialBalanceMethodForCalculatedStaticPressure: 54,
    NodeType_ConstantVolumeGasReservoirMaterialBalanceMethodForActualStaticPressure: 55,
    NodeType_ConstantVolumeReservoirMaterialBalanceMethodForCalculatedStaticPressure: 56,
    NodeType_FlowingBalanceMethodBasedOnTopPressure: 57,               // 流动物质平衡方程-井口流压
    NodeType_FlowingBalanceMethodBasedOnBottomPressure: 58,            // 流动物质平衡方程-井底流压
    NodeType_DynamicMaterialBalanceMethodBlasingame: 59,
    NodeType_DynamicPrediction: 60,                                    // 动态预测
    NodeType_DynamicPredictionReservoir: 61,
    NodeType_DynamicPredictionWells: 62,
    NodeType_DynamicPredictionWell: 63,
    NodeType_DynamicPredictionReservoirInput: 64,
    NodeType_DynamicPredictionReservoirProductionState: 65,
    NodeType_DynamicPredictionWellsProductionState: 66,
    NodeType_DynamicPredictionWellProductionState: 67,
    NodeType_DynamicPredictionReservoirGasProduction: 68,
    NodeType_DynamicPredictionReservoirCumulativeGasProduction: 69,
    NodeType_DynamicPredictionReservoirFormationPressure: 70,
    NodeType_DynamicPredictionReservoirRecoveryDegree: 71,
    NodeType_DynamicPredictionWellsGasProduction: 72,
    NodeType_DynamicPredictionWellsCumulativeGasProduction: 73,
    NodeType_DynamicPredictionWellsFormationPressure: 74,
    NodeType_DynamicPredictionWellsRecoveryDegree: 75,
    NodeType_DynamicPredictionWellGasProduction: 76,
    NodeType_DynamicPredictionWellCumulativeGasProduction: 77,
    NodeType_DynamicPredictionWellFormationPressure: 78,
    NodeType_DynamicPredictionWellsInput: 79,
    NodeType_DynamicPredictionWellInput: 80,
    NodeType_ConfinedGasReservoirMaterialBalanceMethod: 81,
    NodeType_ConstantVolumeGasReservoirMaterialBalanceMethod: 82,
    NodeType_MaterialBalanceMethod: 83,
    NodeType_MaterialBalanceMethodForActualStaticPressure: 84,
    NodeType_MaterialBalanceMethodForCalculatedStaticPressure: 85,
    NodeType_ProductionDeclineAnalysis_ARPS: 86,
    NodeType_ProductionDeclineAnalysis_PLE: 87,
    NodeType_ProductionDeclineAnalysis_DUONG: 88,
    NodeType_ProductionDeclineAnalysis_SEDM: 89,
    NodeType_TypicalCurveWattenbarger: 93,
    NodeType_VerticalWellTypicalCurveWattenbarger:94,
    NodeType_HorizontalWellTypicalCurveWattenbarger:95,
    NodeType_FracturedVerticalWellTypicalCurveWattenbarger:96,
    NodeType_FracturedHorizontalWellTypicalCurveWattenbarger:97,
    NodeType_DynamicOriginal: 200,                                     // 动态分析（根节点）
}

