package com.grdp.studio.storagemainfactor;

import com.grdp.studio.common.BusinessException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 单位与枚举换算的纯函数测试。
 * 断言里的数值全部取自本机实测（spec §4.1 / §4.4），不是凭构造的样例。
 */
class StorageMainFactorUnitsTests {

    @Test
    void convertsBetweenStoredUnitsUsingMeasuredValues() {
        // 原始地层压力：mb_input 存 Pa，水侵表存 MPa
        assertEquals(50.0, StorageMainFactorUnits.paToMpa(50_000_000d), 1e-12);
        assertEquals(50_000_000d, StorageMainFactorUnits.mpaToPa(50d), 1e-6);
        // 地层温度：mb_input 存 K，水侵表存 ℃
        assertEquals(80.0, StorageMainFactorUnits.kelvinToCelsius(353.15), 1e-9);
        assertEquals(353.15, StorageMainFactorUnits.celsiusToKelvin(80d), 1e-9);
        // 地质储量：output 存 m³，旧平台入参要 10^8m³
        assertEquals(23.398270898104453, StorageMainFactorUnits.cubicMeterToHundredMillion(2339827089.8104453), 1e-9);
        assertEquals(1.358E9, StorageMainFactorUnits.hundredMillionToCubicMeter(13.58), 1e2);
        // 束缚水饱和度：mb_input 存小数，水侵表存 %
        assertEquals(26.16, StorageMainFactorUnits.fractionToPercent(0.2616), 1e-9);
        assertEquals(0.2616, StorageMainFactorUnits.percentToFraction(26.16), 1e-9);
        // 压缩系数：mb_input 存 1/Pa，水侵表存 1/MPa
        assertEquals(1.0E-4, StorageMainFactorUnits.perPaToPerMpa(1.0E-10), 1e-18);
        assertEquals(1.0E-10, StorageMainFactorUnits.perMpaToPerPa(1.0E-4), 1e-18);
    }

    @Test
    void nullInNullOut() {
        assertNull(StorageMainFactorUnits.paToMpa(null));
        assertNull(StorageMainFactorUnits.fractionToPercent(null));
        assertNull(StorageMainFactorUnits.cubicMeterToHundredMillion(null));
        assertNull(StorageMainFactorUnits.perPaToPerMpa(null));
    }

    @Test
    void mapsChineseEnumsToPlatformCodes() {
        assertEquals(0, StorageMainFactorUnits.gasTypeCode("干气"));
        assertEquals(1, StorageMainFactorUnits.gasTypeCode("湿气"));
        assertEquals(2, StorageMainFactorUnits.gasTypeCode("凝析气"));
        assertEquals(0, StorageMainFactorUnits.modificationMethodCode("Wichert-Aziz 修正方法"));
        assertEquals(1, StorageMainFactorUnits.modificationMethodCode("Carr-Kobayashi-Burrous 修正方法"));
        assertEquals(0, StorageMainFactorUnits.deviationFactorMethodCode("Dranchuk-Abu-Kassem 方法"));
        assertEquals(1, StorageMainFactorUnits.deviationFactorMethodCode("Dranchuk-Purvis-Robinson 方法"));
        assertEquals(2, StorageMainFactorUnits.deviationFactorMethodCode("Hall-Yarborough 方法"));
    }

    @Test
    void rejectsUnknownEnumInsteadOfDefaultingToZero() {
        assertThrows(BusinessException.class, () -> StorageMainFactorUnits.gasTypeCode("未知气型"));
        assertThrows(BusinessException.class, () -> StorageMainFactorUnits.gasTypeCode(null));
        assertThrows(BusinessException.class, () -> StorageMainFactorUnits.deviationFactorMethodCode("未知方法"));
    }
}
