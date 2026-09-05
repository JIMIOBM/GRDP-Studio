<script setup>
import {
    computed,
    nextTick,
    onBeforeUnmount,
    onMounted,
    ref,
    watch
} from 'vue'

import * as echarts from 'echarts'
import { ElMessage } from 'element-plus'

import { pvtStorageApi } from '@/api/pvtStorage'
import { diagnosticCurveApi } from '@/api/diagnosticCurve'

const props = defineProps({
    node: Object,
    projectId: [Number, String],
    gasReservoirId: [Number, String]
})

const emit = defineEmits(['recalculate'])

const unwrap = response => {
    const first =
        response?.data ??
        response ??
        {}

    return first?.data ??
        first ??
        {}
}

const activePanel =
    ref('input')

const importing =
    ref(false)

const calculating =
    ref(false)

const selectedPvtId =
    ref('')

const pvtOptions =
    ref([])
const pvtDetail =
    ref(null)
const rows =
    ref([])

const importedFileName =
    ref('')

const fileInput =
    ref(null)

const inputUpperLimit =
    ref('')

const inputLowerLimit =
    ref('')

const chartEl =
    ref(null)

const result =
    ref(null)

let chart = null

/**
 * Excel无单位列时使用的固定项目约定。
 *
 * 你原来的Excel数值示例是 27,507.9 / -17,156.1 这一量级，
 * 这里按 10^4 m3 处理，再换算成图上的 10^8 m3。
 *
 * 如果你项目Excel模板实际上已经直接使用 10^8 m3，
 * 只需要把这里改成 1.0。
 */
const DEFAULT_GAS_TO_1E8_M3 =
    1.0e-4

const wellName =
    computed(
        () =>
            props.node?.wellName ||
            ''
    )

const parseNumber = value => {

    if (
        value === null ||
        value === undefined ||
        value === ''
    ) {
        return null
    }

    const text =
        String(value)
            .trim()
            .replace(/,/g, '')
            .replace(/\s+/g, '')

    if (!text) {
        return null
    }

    const number =
        Number(text)

    return Number.isFinite(number)
        ? number
        : null
}

/**
 * ============================
 * PVT
 * ============================
 */

const loadPvtOptions = async () => {

    pvtOptions.value = []
    selectedPvtId.value = ''

    if (!wellName.value) {
        return
    }

    try {

        const summaries =
            unwrap(
                await pvtStorageApi.list(
                    props.projectId,
                    props.gasReservoirId,
                    wellName.value
                )
            ) || []

        pvtOptions.value =
            Array.isArray(summaries)
                ? summaries
                : []

        if (
            pvtOptions.value.length > 0
        ) {
            selectedPvtId.value =
                String(
                    pvtOptions.value[0].pvtId
                )
            loadPvtDetail()
        }

    } catch (error) {

        console.warn(
            '加载PVT性质失败',
            error
        )

        ElMessage.error(
            '加载PVT性质失败'
        )
    }
}

/**
 * ============================
 * 加载PVT详情（含Z数据）
 * ============================
 */
const loadPvtDetail = async () => {

    pvtDetail.value = null

    if (
        !selectedPvtId.value ||
        !wellName.value
    ) {
        return
    }

    try {

        const detail = unwrap(
            await pvtStorageApi.getDetail(
                selectedPvtId.value,
                props.projectId,
                props.gasReservoirId,
                wellName.value
            )
        )

        pvtDetail.value = detail

        console.log(
            'PVT详情（含Z数据）:',
            detail
        )

    } catch (error) {

        console.warn(
            '加载PVT详情失败',
            error
        )

        ElMessage.warning(
            '加载PVT详情失败，Z数据可能不可用'
        )
    }
}

watch(
    () => props.node?.wellName,
    () => {
        loadPvtOptions()
    },
    {
        immediate: true
    }
)

watch(
    () => selectedPvtId.value,
    () => {
        loadPvtDetail()
    }
)

const selectedPvt =
    computed(
        () =>
            pvtOptions.value.find(
                item =>
                    String(item?.pvtId) ===
                    String(
                        selectedPvtId.value
                    )
            ) || null
    )

/**
 * ============================
 * 从PVT表自动提取Z
 *
 * 页面不显示任何Z参数。
 * ============================
 */

const normalizeKey = value =>
    String(value ?? '')
        .replace(
            /[^A-Za-z0-9]/g,
            ''
        )
        .toLowerCase()

const Z_KEYS = [
    'z',
    'zFactor',
    'deviationFactor',
    'gasDeviationFactor',
    'naturalGasDeviationFactor',
    'compressibilityFactor'
]

const PRESSURE_KEYS = [
    'pressure',
    'formationPressure',
    'reservoirPressure',
    'p'
]

const findDirectNumber = (
    object,
    candidateNames
) => {

    if (
        !object ||
        typeof object !== 'object' ||
        Array.isArray(object)
    ) {
        return null
    }

    const candidates =
        candidateNames.map(
            normalizeKey
        )

    for (
        const [key, value]
        of Object.entries(object)
    ) {

        if (
            candidates.includes(
                normalizeKey(key)
            )
        ) {

            const number =
                parseNumber(value)

            if (
                number !== null
            ) {
                return number
            }
        }
    }

    return null
}

const findNumberDeep = (
    source,
    candidateNames,
    depth = 0
) => {

    if (
        source === null ||
        source === undefined ||
        depth > 7
    ) {
        return null
    }

    if (
        Array.isArray(source)
    ) {

        for (const item of source) {

            const found =
                findNumberDeep(
                    item,
                    candidateNames,
                    depth + 1
                )

            if (
                found !== null
            ) {
                return found
            }
        }

        return null
    }

    if (
        typeof source !== 'object'
    ) {
        return null
    }

    const direct =
        findDirectNumber(
            source,
            candidateNames
        )

    if (
        direct !== null
    ) {
        return direct
    }

    for (
        const value
        of Object.values(source)
    ) {

        const found =
            findNumberDeep(
                value,
                candidateNames,
                depth + 1
            )

        if (
            found !== null
        ) {
            return found
        }
    }

    return null
}

/**
 * 在PVT对象中查找“压力-Z”数组。
 */
const findZCurveDeep = (
    source,
    depth = 0
) => {

    if (
        source === null ||
        source === undefined ||
        depth > 7
    ) {
        return null
    }

    if (
        Array.isArray(source)
    ) {

        const points =
            source
                .map(item => {

                    const pressure =
                        findDirectNumber(
                            item,
                            PRESSURE_KEYS
                        )

                    const zFactor =
                        findDirectNumber(
                            item,
                            Z_KEYS
                        )

                    if (
                        pressure === null ||
                        zFactor === null ||
                        pressure <= 0 ||
                        zFactor <= 0
                    ) {
                        return null
                    }

                    return {
                        pressure,
                        zFactor
                    }
                })
                .filter(Boolean)

        if (
            points.length >= 2
        ) {

            points.sort(
                (a, b) =>
                    a.pressure
                    - b.pressure
            )

            return points
        }

        for (
            const item
            of source
        ) {

            const nested =
                findZCurveDeep(
                    item,
                    depth + 1
                )

            if (
                nested
            ) {
                return nested
            }
        }

        return null
    }

    if (
        typeof source === 'object'
    ) {

        for (
            const value
            of Object.values(source)
        ) {

            const nested =
                findZCurveDeep(
                    value,
                    depth + 1
                )

            if (
                nested
            ) {
                return nested
            }
        }
    }

    return null
}

const buildPvtData = () => {

    const pvt = pvtDetail.value || selectedPvt.value
    if (!pvt) {
        throw new Error(
            '未找到所选PVT表'
        )
    }

    /*
     * 优先使用压力-Z曲线。
     * 这样如果PVT表里有多个压力点，不会误把其中某一个Z当成固定Z。
     */
    const zCurve =
        findZCurveDeep(
            pvt
        )

    if (
        zCurve &&
        zCurve.length >= 2
    ) {
        return {
            fixedZ: null,
            zCurve
        }
    }

    /*
     * 如果PVT表只保存一个Z，则直接使用。
     */
    const fixedZ =
        findNumberDeep(
            pvt,
            Z_KEYS
        )

    if (
        fixedZ !== null &&
        fixedZ > 0
    ) {
        return {
            fixedZ,
            zCurve: []
        }
    }

    throw new Error(
        '所选PVT表对象中没有找到Z数据。当前代码不会让你手工输入Z；如果PVT列表接口只返回摘要，需要把PVT详情接口接到这里。'
    )
}

/**
 * ============================
 * Excel
 * ============================
 */

const chooseFile = () => {
    fileInput.value?.click()
}

const normalizeHeader = value =>
    String(value ?? '')
        .trim()
        .replace(/\s+/g, '')
        .replace(/[（）()]/g, '')
        .replace(/[：:]/g, '')
        .replace(/[\/／]/g, '')
        .toLowerCase()

/**
 * 不提供气量单位设置。
 *
 * 如果Excel表头写明单位，则自动识别；
 * 没写单位时使用项目固定默认值 DEFAULT_GAS_TO_1E8_M3。
 */
const detectGasFactorTo1E8M3 =
    rawHeader => {

        const text =
            String(rawHeader ?? '')
                .replace(/\s+/g, '')
                .toLowerCase()

        if (
            text.includes('10⁸') ||
            text.includes('10^8') ||
            text.includes('亿m3') ||
            text.includes('亿m³') ||
            text.includes('亿方')
        ) {
            return 1.0
        }

        if (
            text.includes('10⁴') ||
            text.includes('10^4') ||
            text.includes('万m3') ||
            text.includes('万m³') ||
            text.includes('万方')
        ) {
            return 1.0e-4
        }

        if (
            text.includes('m3') ||
            text.includes('m³')
        ) {
            return 1.0e-8
        }

        return DEFAULT_GAS_TO_1E8_M3
    }

const normalizeSignedGas = (
    rawGas,
    cycle
) => {

    const gas =
        parseNumber(rawGas)

    if (
        gas === null
    ) {
        return null
    }

    const cycleText =
        String(cycle ?? '')
            .trim()

    /*
     * 统一约定：
     * 采气 > 0
     * 注气 < 0
     */
    if (
        cycleText.includes('注')
    ) {
        return -Math.abs(gas)
    }

    if (
        cycleText.includes('采') ||
        cycleText.includes('产')
    ) {
        return Math.abs(gas)
    }

    /*
     * 周期文字没有说明时，
     * 使用Excel原始正负号。
     */
    return gas
}

const handleFile = async event => {

    const file =
        event.target.files?.[0]

    event.target.value = ''

    if (!file) {
        return
    }

    importing.value = true

    try {

        const XLSX =
            await import('xlsx')

        const data =
            await new Promise(
                (resolve, reject) => {

                    const reader =
                        new FileReader()

                    reader.onload =
                        e => {

                            try {

                                const workbook =
                                    XLSX.read(
                                        e.target.result,
                                        {
                                            type:
                                                'array',

                                            cellDates:
                                                true
                                        }
                                    )

                                const sheet =
                                    workbook.Sheets[
                                    workbook.SheetNames[0]
                                    ]

                                resolve(
                                    XLSX.utils.sheet_to_json(
                                        sheet,
                                        {
                                            header: 1,
                                            raw: false
                                        }
                                    )
                                )

                            } catch (error) {
                                reject(error)
                            }
                        }

                    reader.onerror =
                        reject

                    reader.readAsArrayBuffer(
                        file
                    )
                }
            )

        if (
            !data ||
            data.length < 2
        ) {
            ElMessage.warning(
                '文件内容为空或格式不正确'
            )
            return
        }

        const header =
            data[0].map(
                value =>
                    String(value ?? '')
                        .trim()
            )

        const normalizedHeader =
            header.map(
                normalizeHeader
            )

        const timeIdx =
            normalizedHeader.findIndex(
                value =>
                    [
                        '时间',
                        '日期',
                        '生产时间',
                        '生产日期'
                    ].some(
                        key =>
                            value === key ||
                            value.includes(key)
                    )
            )

        const exactGasHeaders = [
            '注采气',
            '注采气量',
            '注气量',
            '采气量',
            '产气量',
            '天然气产量',
            '日产气量',
            '月产气量',
            '累计产气量'
        ]

        let gasIdx =
            normalizedHeader.findIndex(
                value =>
                    exactGasHeaders.includes(
                        value
                    )
            )

        if (
            gasIdx === -1
        ) {

            gasIdx =
                normalizedHeader.findIndex(
                    value =>
                        value.includes(
                            '注采气'
                        ) ||
                        value.includes(
                            '注气量'
                        ) ||
                        value.includes(
                            '采气量'
                        ) ||
                        value.includes(
                            '产气量'
                        ) ||
                        value.includes(
                            '天然气产量'
                        )
                )
        }

        const cycleIdx =
            normalizedHeader.findIndex(
                value =>
                    [
                        '周期',
                        '生产周期',
                        '生产轮次',
                        '运行阶段'
                    ].some(
                        key =>
                            value === key ||
                            value.includes(key)
                    )
            )

        /*
         * 注意：
         * 不再查找压力列。
         */
        if (
            timeIdx === -1 ||
            gasIdx === -1 ||
            cycleIdx === -1
        ) {

            ElMessage.error(
                '表头需包含：时间、注/采气、周期'
            )

            return
        }

        const gasFactor =
            detectGasFactorTo1E8M3(
                header[gasIdx]
            )

        const cumulativeGasColumn =
            normalizedHeader[gasIdx]
                .includes('累计')

        let previousCumulativeGas =
            null

        const importedRows = []

        for (
            let sourceIndex = 1;
            sourceIndex < data.length;
            sourceIndex++
        ) {

            const sourceRow =
                data[sourceIndex]

            if (
                !sourceRow ||
                !sourceRow.some(
                    cell =>
                        cell !== undefined &&
                        cell !== null &&
                        cell !== ''
                )
            ) {
                continue
            }

            const cycle =
                sourceRow[cycleIdx] ?? ''

            let rawGas =
                parseNumber(
                    sourceRow[gasIdx]
                )

            if (
                rawGas === null
            ) {

                importedRows.push({
                    sequence:
                        importedRows.length + 1,

                    time:
                        sourceRow[timeIdx] ?? '',

                    gasRaw:
                        sourceRow[gasIdx] ?? '',

                    gas:
                        null,

                    cycle
                })

                continue
            }

            /*
             * 如果导入列是累计产气量，
             * 自动差分成单期量。
             */
            if (
                cumulativeGasColumn
            ) {

                const current =
                    rawGas

                rawGas =
                    previousCumulativeGas === null
                        ? current
                        : current
                        - previousCumulativeGas

                previousCumulativeGas =
                    current
            }

            const signedGas =
                normalizeSignedGas(
                    rawGas,
                    cycle
                )

            importedRows.push({
                sequence:
                    importedRows.length + 1,

                time:
                    sourceRow[timeIdx] ?? '',

                /*
                 * 表格显示用户Excel中的数值。
                 */
                gasRaw:
                    signedGas,

                /*
                 * 发送给后端时已经统一成10^8 m3。
                 */
                gas:
                    signedGas === null
                        ? null
                        : signedGas
                        * gasFactor,

                cycle
            })
        }

        const invalidGasRows =
            importedRows.filter(
                row =>
                    row.gas === null ||
                    !Number.isFinite(
                        row.gas
                    ) ||
                    row.gas === 0
            )

        rows.value =
            importedRows

        importedFileName.value =
            file.name

        if (
            invalidGasRows.length > 0
        ) {

            ElMessage.warning(
                `已导入 ${rows.value.length} 行，其中 ${invalidGasRows.length} 行注/采气量无效`
            )

        } else {

            ElMessage.success(
                `成功导入 ${rows.value.length} 行数据`
            )
        }

    } catch (error) {

        console.error(
            '文件解析失败',
            error
        )

        ElMessage.error(
            '文件解析失败，请检查文件格式'
        )

    } finally {

        importing.value = false
    }
}

/**
 * ============================
 * 计算
 * ============================
 */

const handleRecalculate = async () => {

    if (
        !selectedPvtId.value
    ) {
        ElMessage.warning(
            '请先选择PVT表'
        )
        return
    }

    if (
        !rows.value.length
    ) {
        ElMessage.warning(
            '请先导入数据表'
        )
        return
    }

    const upperLimit =
        parseNumber(
            inputUpperLimit.value
        )

    const lowerLimit =
        parseNumber(
            inputLowerLimit.value
        )

    if (
        upperLimit === null ||
        lowerLimit === null
    ) {
        ElMessage.warning(
            '压力上下限必须是有效数字'
        )
        return
    }

    if (
        lowerLimit <= 0 ||
        upperLimit <= lowerLimit
    ) {
        ElMessage.warning(
            '上限压力必须大于下限压力，且下限必须大于0'
        )
        return
    }

    const productionData =
        rows.value.map(
            row => ({
                sequence:
                    Number(
                        row.sequence
                    ),

                time:
                    String(
                        row.time ?? ''
                    ),

                gas:
                    row.gas,

                cycle:
                    String(
                        row.cycle ?? ''
                    )
            })
        )

    const invalidRows =
        productionData.filter(
            item =>
                item.gas === null ||
                !Number.isFinite(
                    item.gas
                ) ||
                item.gas === 0
        )

    if (
        invalidRows.length > 0
    ) {
        ElMessage.error(
            `有 ${invalidRows.length} 行注/采气量无效`
        )
        return
    }

    let pvt

    try {

        pvt =
            buildPvtData()

    } catch (error) {

        ElMessage.error(
            error?.message ||
            '无法从所选PVT表取得Z'
        )

        return
    }

    const requestData = {

        projectId:
            Number(
                props.projectId
            ),

        gasReservoirId:
            Number(
                props.gasReservoirId
            ),

        wellName:
            wellName.value,

        pvtId:
            Number(
                selectedPvtId.value
            ),

        upperLimit,

        lowerLimit,

        /*
         * 由PVT表自动带出，不是用户输入参数。
         */
        pvt,

        /*
         * Excel不含pressure字段。
         */
        productionData
    }

    calculating.value = true

    try {

        const response =
            await diagnosticCurveApi.calculate(
                requestData
            )

        const data =
            unwrap(response)

        result.value =
            data

        activePanel.value =
            'analysis'

        await nextTick()

        updateChart(
            data
        )

        emit(
            'recalculate',
            data
        )

        ElMessage.success(
            '计算完成'
        )

    } catch (error) {

        console.error(
            '诊断曲线计算失败',
            error
        )

        const message =
            error?.response
                ?.data
                ?.msg ||
            error?.response
                ?.data
                ?.message ||
            error?.message ||
            '计算失败'

        ElMessage.error(
            message
        )

    } finally {

        calculating.value = false
    }
}

/**
 * ============================
 * 图表
 * ============================
 */

const initChart = () => {

    if (
        !chartEl.value
    ) {
        return
    }

    if (!chart) {
        chart =
            echarts.init(
                chartEl.value
            )
    }

    chart.setOption({

        animation: false,

        legend: {
            top: 8,
            data: [
                '实际运行曲线',
                '理论基准线'
            ]
        },

        tooltip: {
            trigger: 'item'
        },

        grid: {
            left: 92,
            right: 42,
            top: 52,
            bottom: 72
        },

        /*
         * 和教材图一样从原点开始。
         *
         * 上限/下限不是坐标轴边界。
         */
        xAxis: {
            name:
                '库存量 G (10⁸m³)',

            nameLocation:
                'middle',

            nameGap: 44,

            min: 0,

            max: value =>
                value.max > 0
                    ? value.max * 1.10
                    : 1,

            type:
                'value'
        },

        yAxis: {
            name:
                '压力/天然气偏差系数 P/Z (MPa)',

            nameLocation:
                'middle',

            nameGap: 68,

            min: 0,

            max: value =>
                value.max > 0
                    ? value.max * 1.12
                    : 1,

            type:
                'value'
        },

        series: []
    })
}

const updateChart = data => {

    initChart()

    if (!chart) {
        return
    }

    const cycleCurves =
        Array.isArray(
            data?.cycleCurves
        )
            ? data.cycleCurves
            : []

    const standardLine =
        Array.isArray(
            data?.standardLine
        )
            ? data.standardLine
            : []

    const actualSeries =
        cycleCurves.map(
            (cycle, index) => ({

                id:
                    `diagnostic-cycle-${index}`,

                /*
                 * 每个周期是独立series，
                 * 因此不同周期间绝不会自动连线。
                 */
                name:
                    '实际运行曲线',

                type:
                    'line',

                smooth: false,

                showSymbol: false,

                connectNulls: false,

                lineStyle: {
                    width: 2,
                    color: '#5470c6'
                },

                itemStyle: {
                    color: '#5470c6'
                },

                data:
                    (
                        Array.isArray(
                            cycle?.points
                        )
                            ? cycle.points
                            : []
                    ).map(
                        item => ({
                            value: [
                                Number(
                                    item.inventory
                                ),
                                Number(
                                    item.pressureOverZ
                                )
                            ],

                            raw: item,

                            cycleName:
                                cycle?.cycle
                                || `周期${index + 1}`
                        })
                    )
            })
        )

    const theoreticalSeries = {

        id:
            'diagnostic-standard-line',

        name:
            '理论基准线',

        type:
            'line',

        smooth: false,

        showSymbol: false,

        data:
            standardLine.map(
                item => [
                    Number(
                        item.inventory
                    ),
                    Number(
                        item.pressureOverZ
                    )
                ]
            ),

        lineStyle: {
            type:
                'dashed',
            width: 2,
            color: '#a6d608'
        },

        itemStyle: {
            color: '#a6d608'
        }
    }

    /*
     * 周期数量每次可能不同，
     * clear后重新创建series，防止旧周期残留。
     */
    chart.clear()

    chart.setOption({

        animation: false,

        legend: {
            top: 8,
            data: [
                '实际运行曲线',
                '理论基准线'
            ]
        },

        tooltip: {

            trigger: 'axis',

            formatter: paramsList => {

                const list =
                    Array.isArray(paramsList)
                        ? paramsList
                        : [paramsList]

                const blocks = []

                for (const params of list) {

                    if (
                        params.seriesName ===
                        '理论基准线'
                    ) {

                        blocks.push([
                            '<strong>理论基准线 P/Z = kG</strong>',
                            `库存量：${Number(params.value?.[0]).toFixed(4)} ×10⁸m³`,
                            `P/Z：${Number(params.value?.[1]).toFixed(4)} MPa`
                        ].join('<br/>'))

                        continue
                    }

                    const raw =
                        (
                            params.data
                            && typeof params.data === 'object'
                        )
                            ? params.data.raw
                            : null

                    if (!raw) {
                        continue
                    }

                    const directionText =
                        raw.direction === 'INJECTION'
                            ? '注气'
                            : raw.direction === 'PRODUCTION'
                                ? '采气'
                                : '-'

                    const cycleName =
                        params.data?.cycleName
                        || raw.cycle
                        || '-'

                    blocks.push([
                        `<strong>${cycleName}</strong>`,
                        `方向：${directionText}`,
                        `库存量：${Number(raw.inventory).toFixed(4)} ×10⁸m³`,
                        `压力：${Number(raw.estimatedPressure).toFixed(4)} MPa`,
                        `Z：${Number(raw.zFactor).toFixed(6)}`,
                        `P/Z：${Number(raw.pressureOverZ).toFixed(4)} MPa`
                    ].join('<br/>'))
                }

                return blocks.length > 0
                    ? blocks.join(
                        '<hr style="margin:4px 0;border-color:#eee"/>'
                    )
                    : ''
            },

            axisPointer: {
                type: 'cross',
                crossStyle: {
                    color: '#999'
                }
            }
        },

        grid: {
            left: 92,
            right: 42,
            top: 52,
            bottom: 72
        },

        /*
         * 关键：
         * 坐标轴从0开始并自动扩展。
         *
         * 压力上限/下限只约束实际运行压力，
         * 不再直接作为P/Z图像边界。
         */
        xAxis: {
            name:
                '库存量 G (10⁸m³)',

            nameLocation:
                'middle',

            nameGap: 44,

            min: 0,

            max: value =>
                value.max > 0
                    ? value.max * 1.10
                    : 1,

            type:
                'value'
        },

        yAxis: {
            name:
                '压力/天然气偏差系数 P/Z (MPa)',

            nameLocation:
                'middle',

            nameGap: 68,

            min: 0,

            max: value =>
                value.max > 0
                    ? value.max * 1.12
                    : 1,

            type:
                'value'
        },

        series: [
            ...actualSeries,
            theoreticalSeries
        ]
    })

    chart.resize()
}

const switchPanel =
    panel => {

        activePanel.value =
            panel

        if (
            panel === 'analysis'
        ) {

            nextTick(() => {

                initChart()

                if (
                    result.value
                ) {
                    updateChart(
                        result.value
                    )
                }
            })
        }
    }

const handleResize = () => {
    chart?.resize()
}

onMounted(() => {

    window.addEventListener(
        'resize',
        handleResize
    )
})

onBeforeUnmount(() => {

    window.removeEventListener(
        'resize',
        handleResize
    )

    if (chart) {

        chart.dispose()
        chart = null
    }
})
</script>

<template>
    <section class="diagnostic-workspace">

        <aside class="params-panel">

            <div class="panel-head">
                参数设置
            </div>

            <div class="panel-body">

                <label class="field">

                    <span>
                        选择PVT表
                    </span>

                    <select v-model="selectedPvtId">

                        <option value="" disabled>
                            {{
                                pvtOptions.length
                                    ? '请选择PVT性质'
                                    : '当前井暂无PVT性质'
                            }}
                        </option>

                        <option v-for="item in pvtOptions" :key="item.pvtId" :value="String(item.pvtId)">
                            {{
                                item.pvtName ||
                                `PVT性质${item.pvtNo ?? item.pvtId}`
                            }}
                        </option>

                    </select>

                </label>

                <label class="field">

                    <span>
                        选择数据表
                    </span>

                    <button type="button" class="local-import-button" :disabled="importing" @click="chooseFile">
                        {{
                            importing
                                ? '正在导入…'
                                : '本地导入'
                        }}
                    </button>

                    <input ref="fileInput" class="hidden-file" type="file" accept=".xlsx,.xls,.csv"
                        @change="handleFile" />

                    <small class="imported-data-name">
                        {{
                            importedFileName ||
                            '未导入文件'
                        }}
                    </small>

                </label>

                <label class="field">

                    <span>
                        压力上限 (MPa)
                    </span>

                    <input v-model="inputUpperLimit" placeholder="请输入上限" />

                </label>

                <label class="field">

                    <span>
                        压力下限 (MPa)
                    </span>

                    <input v-model="inputLowerLimit" placeholder="请输入下限" />

                </label>

                <div class="action-buttons">

                    <button type="button" class="calculate" :disabled="calculating" @click="handleRecalculate">
                        {{
                            calculating
                                ? '计算中…'
                                : '计算'
                        }}
                    </button>

                </div>

            </div>

        </aside>

        <main class="result-area">

            <div v-show="activePanel === 'input'" class="editable-data-grid">

                <el-table :data="rows" border height="100%">

                    <el-table-column label="序号" width="60" align="center">
                        <template #default="{ row }">
                            {{ row.sequence }}
                        </template>
                    </el-table-column>

                    <el-table-column label="时间" min-width="170" align="center">
                        <template #default="{ row }">
                            {{ row.time }}
                        </template>
                    </el-table-column>

                    <el-table-column label="注/采气" min-width="180" align="center">
                        <template #default="{ row }">
                            {{ row.gasRaw }}
                        </template>
                    </el-table-column>

                    <el-table-column label="周期" min-width="160" align="center">
                        <template #default="{ row }">
                            {{ row.cycle }}
                        </template>
                    </el-table-column>

                </el-table>

            </div>

            <div v-show="activePanel === 'analysis'" class="analysis-view">

                <div ref="chartEl" class="chart"></div>

            </div>

            <div class="bottom-tabs">

                <button :class="{
                    active:
                        activePanel === 'input'
                }" @click="switchPanel('input')">
                    数据列表
                </button>

                <button :class="{
                    active:
                        activePanel === 'analysis'
                }" @click="switchPanel('analysis')">
                    结果分析
                </button>

            </div>

        </main>

    </section>
</template>

<style lang="scss" scoped>
.diagnostic-workspace {
    display: flex;
    height: 100%;
    min-height: 0;
    background: #fff;
}

.params-panel {
    width: 360px;
    min-width: 360px;
    display: flex;
    flex-direction: column;
    border-right: 1px solid #ddd;
}

.panel-head {
    height: 34px;
    padding: 0 12px;
    display: flex;
    align-items: center;
    background: #f2f2f2;
    border-bottom: 1px solid #ddd;
    font-size: 13px;
}

.panel-body {
    flex: 1;
    overflow: auto;
    padding: 10px 14px;
}

.field {
    display: block;
    margin-bottom: 12px;
    font-size: 12px;
}

.field>span {
    display: block;
    margin-bottom: 4px;
}

.field select,
.field input:not(.hidden-file) {
    width: 100%;
    height: 30px;
    box-sizing: border-box;
    border: 1px solid #aaa;
    border-radius: 3px;
    background: #fff;
    padding: 0 8px;
    font-size: 13px;
    outline: none;
}

.field select:focus,
.field input:not(.hidden-file):focus {
    border-color: #888;
}

.hidden-file {
    display: none;
}

.local-import-button {
    width: 100%;
    height: 30px;
    padding: 0 8px;
    border: 1px solid #aaa;
    border-radius: 3px;
    background: #fff;
    color: #333;
    text-align: left;
    cursor: pointer;
}

.local-import-button:disabled {
    color: #999;
    cursor: wait;
}

.imported-data-name {
    display: block;
    margin-top: 4px;
    overflow: hidden;
    color: #777;
    font-size: 11px;
    text-overflow: ellipsis;
    white-space: nowrap;
}

.action-buttons {
    display: flex;
    gap: 8px;
    margin-top: 4px;
}

.calculate {
    height: 32px;
    padding: 0 24px;
    border: 0;
    border-radius: 3px;
    color: #fff;
    cursor: pointer;
    background: #111;
}

.calculate:disabled {
    opacity: .6;
    cursor: not-allowed;
}

.result-area {
    flex: 1;
    min-width: 0;
    min-height: 0;
    display: flex;
    flex-direction: column;
}

.editable-data-grid,
.analysis-view {
    flex: 1;
    min-height: 0;
    display: flex;
    flex-direction: column;
}

.chart {
    flex: 1;
    min-height: 0;
}

:deep(.el-table .cell) {
    padding: 0;
    text-align: center;
}

:deep(.el-table th.el-table__cell > .cell) {
    padding: 0 10px;
}

:deep(.el-table td.el-table__cell) {
    padding: 0;
    background: #fff;
}

:deep(.el-table__row:hover > td.el-table__cell) {
    background: #fff !important;
}

.bottom-tabs {
    height: 31px;
    display: flex;
    flex-shrink: 0;
    border-top: 1px solid #ddd;
}

.bottom-tabs button {
    min-width: 110px;
    border: 0;
    border-right: 1px solid #ddd;
    background: #fff2f4;
    color: #999;
    cursor: pointer;
}

.bottom-tabs button.active {
    color: #222;
    box-shadow:
        inset 0 -2px #2b171a;
    font-weight: 600;
}
</style>
