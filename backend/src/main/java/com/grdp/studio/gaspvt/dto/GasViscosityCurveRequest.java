package com.grdp.studio.gaspvt.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 前端发起一次“天然气 PVT 整体计算”时使用的公共参数。
 *
 * <p>虽然类名沿用了 Viscosity，但曲线 1～4 共用同一套气体组成、计算方法和压力区间，
 * 所以当前四个接口都使用这个请求对象。</p>
 */
public record GasViscosityCurveRequest(
        /** 当前工程 ID，必须和前端 IprInterface 中的 PROJECT_ID 一致。 */
        @NotNull @Positive Long projectId,
        /** 天然气类型：0=干气，1=湿气，2=凝析气。 */
        @NotNull Integer gasType,
        /** 天然气相对密度（无量纲）。 */
        @NotNull @Positive Double specificGravity,
        /** H2S、CO2、N2 均使用界面表格中的摩尔百分含量数值。 */
        @NotNull @DecimalMin("0") Double h2SMoleFraction,
        @NotNull @DecimalMin("0") Double co2MoleFraction,
        @NotNull @DecimalMin("0") Double n2MoleFraction,
        /** 摄氏温度。 */
        @NotNull Double temperature,
        /** 压力扫描范围及步长，单位均为 MPa；当前前端传入 5～200，步长 5。 */
        @NotNull @DecimalMin("0") Double pressureStart,
        @NotNull @DecimalMin("0") Double pressureEnd,
        @NotNull @Positive Double pressureStep,
        /** 非烃气体修正方法：0=Wichert-Aziz，1=Carr-Kobayashi-Burrous。 */
        @NotNull Integer modificationMethod,
        /** 偏差系数方法：0=DAK，1=DPR，2=Hall-Yarborough。 */
        @NotNull Integer deviationFactorMethod,
        /** 黏度方法：0=Lee-Gonzalez-Eakin，1=CKB，2=Sutton。 */
        @NotNull Integer viscosityMethod
) {
}
