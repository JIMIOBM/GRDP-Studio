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
let chartResizeObserver = null
const paramsCollapsed = ref(false)
const paramsPanelWidth = ref(238)
const resizingParamsPanel = ref(false)
const legendSelected = ref({ '实际运行曲线': true, '理论基准线': true })

// 与水侵分析共用相同的视觉规范；坐标范围、周期分组和计算结果保持原逻辑。
const chartAxisStyle = () => ({
    axisLine: { show: true, lineStyle: { color: '#555' } },
    // 仅格式化刻度文本，避免浮点长尾挤占绘图区，原始数值不变。
    axisLabel: { color: '#555', fontSize: 12,
        formatter: value => Number.isFinite(Number(value)) ? String(Number(Number(value).toPrecision(8))) : String(value) },
    nameTextStyle: { color: '#555', fontSize: 12 },
    minorTick: { show: true },
    splitLine: { show: true, lineStyle: { color: '#dce5f2' } },
    minorSplitLine: { show: true, lineStyle: { color: '#f1f5fb' } }
})
const chartHeading = () => ({ text: '库存量与压力/偏差系数关系图', left: 'center', top: 8,
    textStyle: { fontSize: 14, fontWeight: 600, color: '#333' } })
const toggleLegend = name => {
    legendSelected.value[name] = !legendSelected.value[name]
    chart?.dispatchAction({ type: legendSelected.value[name] ? 'legendSelect' : 'legendUnSelect', name })
}

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

let pvtListSequence = 0
let pvtDetailSequence = 0
const loadPvtOptions = async () => {
    const sequence = ++pvtListSequence
    ++pvtDetailSequence
    const targetWellName = wellName.value

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
                    targetWellName
                )
            ) || []

        if (sequence !== pvtListSequence || targetWellName !== wellName.value) return
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
        }

    } catch (error) {

        if (sequence !== pvtListSequence || targetWellName !== wellName.value) return
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
    const sequence = ++pvtDetailSequence
    const targetWellName = wellName.value
    const targetPvtId = selectedPvtId.value

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
                targetPvtId,
                props.projectId,
                props.gasReservoirId,
                targetWellName
            )
        )

        if (sequence !== pvtDetailSequence || targetWellName !== wellName.value || targetPvtId !== selectedPvtId.value) return
        pvtDetail.value = detail

        console.log(
            'PVT详情（含Z数据）:',
            detail
        )

    } catch (error) {

        if (sequence !== pvtDetailSequence || targetWellName !== wellName.value || targetPvtId !== selectedPvtId.value) return
        console.warn(
            '加载PVT详情失败',
            error
        )

        ElMessage.warning(
            '加载PVT详情失败，Z数据可能不可用'
        )
    }
}

// 每次从功能入口打开（包括同一口井）都重新查询，不依赖左侧 PVT 分支懒加载。
watch(
    () => [props.node, props.projectId, props.gasReservoirId],
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
        .trim()
        .toLowerCase()
        /*
         * 保留中文字符，只去除分隔符和单位符号。
         * 这样“压力(MPa)”“天然气偏差系数(dless)”也能识别。
         */
        .replace(
            /[^\p{L}\p{N}]/gu,
            ''
        )

const Z_KEYS = [
    'z',
    'zFactor',
    'deviationFactor',
    'gasDeviationFactor',
    'naturalGasDeviationFactor',
    'compressibilityFactor',

    /*
     * 兼容中文字段名。
     */
    '天然气偏差系数',
    '偏差系数',
    'z系数',
    '压缩因子'
]

const PRESSURE_KEYS = [
    'pressure',
    'formationPressure',
    'reservoirPressure',
    'p',

    /*
     * 兼容中文字段名。
     */
    '压力',
    '地层压力',
    '气藏压力'
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

        const normalized =
            normalizeKey(key)

        const matched =
            candidates.some(
                candidate =>
                    normalized === candidate ||
                    (
                        candidate.length > 1 &&
                        normalized.startsWith(
                            candidate
                        )
                    )
            )

        if (
            matched
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

/**
 * 查找真正的“对象级固定Z”。
 *
 * 这里主动跳过数组，避免把 Z(P) 曲线第一行的 Z
 * 错当成整个 PVT 的固定 Z。
 */
const findFixedZDeep = (
    source,
    depth = 0
) => {

    if (
        source === null ||
        source === undefined ||
        depth > 7 ||
        Array.isArray(source) ||
        typeof source !== 'object'
    ) {
        return null
    }

    const direct =
        findDirectNumber(
            source,
            Z_KEYS
        )

    if (
        direct !== null &&
        direct > 0
    ) {
        return direct
    }

    for (
        const value
        of Object.values(source)
    ) {

        if (
            Array.isArray(value)
        ) {
            continue
        }

        const found =
            findFixedZDeep(
                value,
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
 * 在 PVT 对象中递归查找 Pressure-Z 数组。
 *
 * 支持常见字段：
 * pressure / 压力(MPa)
 * zFactor / 天然气偏差系数(dless)
 */
const findZCurveDeep = (
    source,
    depth = 0
) => {

    if (
        source === null ||
        source === undefined ||
        depth > 8
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

            /*
             * 排序 + 同压力去重。
             */
            points.sort(
                (a, b) =>
                    a.pressure
                    - b.pressure
            )

            const unique = []

            for (
                const point
                of points
            ) {

                const previous =
                    unique[
                        unique.length - 1
                    ]

                if (
                    previous &&
                    Math.abs(
                        previous.pressure
                        - point.pressure
                    ) < 1e-9
                ) {
                    previous.zFactor =
                        (
                            previous.zFactor
                            + point.zFactor
                        ) / 2
                } else {
                    unique.push({
                        ...point
                    })
                }
            }

            if (
                unique.length >= 2
            ) {
                return unique
            }
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

    const candidates = [
        pvtDetail.value,
        selectedPvt.value
    ].filter(Boolean)

    if (
        candidates.length === 0
    ) {
        throw new Error(
            '未找到所选PVT表'
        )
    }

    /*
     * 第一优先级：
     * 使用整张 Pressure-Z 曲线。
     *
     * 固定的是“所选PVT表”，不是把Z强制当常数。
     */
    for (
        const pvt
        of candidates
    ) {

        const zCurve =
            findZCurveDeep(
                pvt
            )

        if (
            Array.isArray(zCurve) &&
            zCurve.length >= 2
        ) {
            return {
                fixedZ: null,
                zCurve
            }
        }
    }

    /*
     * 第二优先级：
     * 只有PVT确实保存的是单个固定Z时才使用。
     */
    for (
        const pvt
        of candidates
    ) {

        const fixedZ =
            findFixedZDeep(
                pvt
            )

        if (
            fixedZ !== null &&
            Number.isFinite(fixedZ) &&
            fixedZ > 0
        ) {
            return {
                fixedZ,
                zCurve: []
            }
        }
    }

    throw new Error(
        '所选PVT表没有找到有效的压力-Z数据。请确认PVT详情接口返回了“压力”和“天然气偏差系数Z”数据列。'
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

        /*
         * 列表接口可能只有摘要。
         * 点击计算时如果详情尚未加载完成，再主动读取一次。
         */
        if (
            !pvtDetail.value
        ) {
            await loadPvtDetail()
        }

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

        backgroundColor: '#fff',
        title: chartHeading(),
        legend: {
            show: false,
            selected: { ...legendSelected.value },
            data: [
                '实际运行曲线',
                '理论基准线'
            ]
        },

        tooltip: {
            trigger: 'item',
            confine: true
        },

        grid: {
            left: 92,
            right: 92,
            top: 44,
            bottom: 56
        },

        /*
         * 和教材图一样从原点开始。
         *
         * 上限/下限不是坐标轴边界。
         */
        xAxis: {
            ...chartAxisStyle(),
            name:
                '库存量 G (10⁸m³)',

            nameLocation:
                'middle',

            nameGap: 30,

            min: 0,

            max: value =>
                value.max > 0
                    ? value.max * 1.10
                    : 1,

            type:
                'value'
        },

        yAxis: {
            ...chartAxisStyle(),
            name:
                '压力/天然气偏差系数 P/Z (MPa)',

            nameLocation:
                'middle',

            nameGap: 58,

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

    const lowerPressureOverZ =
        Number(
            data?.lowerPressureOverZ
        )

    const upperPressureOverZ =
        Number(
            data?.upperPressureOverZ
        )

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
        },

        /*
         * 输入压力上下限经所选 PVT 换算成 P/Z 后，
         * 作为运行区间的上下参考线。
         *
         * 注意：坐标轴仍然从0开始，以保留理论线原点。
         */
        markLine:
            Number.isFinite(
                lowerPressureOverZ
            ) &&
            Number.isFinite(
                upperPressureOverZ
            )
                ? {
                    silent: true,
                    symbol: 'none',
                    label: {
                        position: 'insideEndTop',
                        backgroundColor: 'rgba(255,255,255,.9)',
                        padding: [2, 4],
                        formatter: params =>
                            `${params.name}: ${Number(params.value).toFixed(4)} MPa`
                    },
                    data: [
                        {
                            name: 'Pmin/Z(Pmin)',
                            yAxis:
                                lowerPressureOverZ
                        },
                        {
                            name: 'Pmax/Z(Pmax)',
                            yAxis:
                                upperPressureOverZ
                        }
                    ]
                }
                : undefined
    }

    /*
     * 周期数量每次可能不同，
     * clear后重新创建series，防止旧周期残留。
     */
    chart.clear()

    chart.setOption({

        animation: false,

        backgroundColor: '#fff',
        title: chartHeading(),
        legend: {
            show: false,
            selected: { ...legendSelected.value },
            data: [
                '实际运行曲线',
                '理论基准线'
            ]
        },

        tooltip: {

            trigger: 'axis',
            confine: true,

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
                        `本行气量：${Number(raw.gas).toFixed(4)} ×10⁸m³`,
                        `累计净注采量 C：${Number(raw.cumulativeNetGas).toFixed(4)} ×10⁸m³`,
                        `库存量 G：${Number(raw.inventory).toFixed(4)} ×10⁸m³`,
                        `理论稳定 P/Z：${Number(raw.stablePressureOverZ).toFixed(4)} MPa`,
                        `重建压力 P：${Number(raw.estimatedPressure).toFixed(4)} MPa`,
                        `Z(P)：${Number(raw.zFactor).toFixed(6)}`,
                        `运行 P/Z：${Number(raw.pressureOverZ).toFixed(4)} MPa`
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
            right: 92,
            top: 44,
            bottom: 56
        },

        /*
         * 关键：
         * 坐标轴从0开始并自动扩展。
         *
         * 压力上限/下限只约束实际运行压力，
         * 不再直接作为P/Z图像边界。
         */
        xAxis: {
            ...chartAxisStyle(),
            name:
                '库存量 G (10⁸m³)',

            nameLocation:
                'middle',

            nameGap: 30,

            min: 0,

            max: value =>
                value.max > 0
                    ? value.max * 1.10
                    : 1,

            type:
                'value'
        },

        yAxis: {
            ...chartAxisStyle(),
            name:
                '压力/天然气偏差系数 P/Z (MPa)',

            nameLocation:
                'middle',

            nameGap: 58,

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

const toggleParamsPanel = async () => {
    paramsCollapsed.value = !paramsCollapsed.value
    await nextTick()
    handleResize()
}
let resizeStartX = 0
let resizeStartWidth = 238
const resizeParamsPanel = event => {
    if (!resizingParamsPanel.value) return
    paramsPanelWidth.value = Math.min(520, Math.max(200, resizeStartWidth + event.clientX - resizeStartX))
}
const stopParamsPanelResize = () => {
    resizingParamsPanel.value = false
    window.removeEventListener('pointermove', resizeParamsPanel)
    window.removeEventListener('pointerup', stopParamsPanelResize)
    window.removeEventListener('pointercancel', stopParamsPanelResize)
}
const startParamsPanelResize = event => {
    if (event.button !== 0) return
    event.preventDefault()
    resizeStartX = event.clientX
    resizeStartWidth = paramsPanelWidth.value
    resizingParamsPanel.value = true
    window.addEventListener('pointermove', resizeParamsPanel)
    window.addEventListener('pointerup', stopParamsPanelResize)
    window.addEventListener('pointercancel', stopParamsPanelResize)
}

onMounted(() => {

    // 容器尺寸也会随侧栏折叠和拖宽改变，不能只监听浏览器窗口大小。
    chartResizeObserver = new ResizeObserver(handleResize)
    if (chartEl.value) chartResizeObserver.observe(chartEl.value)
    window.addEventListener(
        'resize',
        handleResize
    )
})

onBeforeUnmount(() => {

    stopParamsPanelResize()
    chartResizeObserver?.disconnect()
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

        <aside class="params-panel" :class="{ collapsed: paramsCollapsed, resizing: resizingParamsPanel }"
            :style="{ width: `${paramsCollapsed ? 22 : paramsPanelWidth}px`, minWidth: `${paramsCollapsed ? 22 : paramsPanelWidth}px` }">

            <button v-if="paramsCollapsed" type="button" class="panel-collapsed-tab" title="展开参数设置" @click="toggleParamsPanel">参数设置</button>
            <div v-show="!paramsCollapsed" class="panel-head">
                <span>参数设置</span>
                <button type="button" class="panel-toggle" title="收起参数设置" aria-label="收起参数设置" @click="toggleParamsPanel">
                    <svg width="14" height="14" viewBox="0 0 24 24" fill="#777" aria-hidden="true"><path d="M16,12V4H17V2H7V4H8V12L6,14V16H11.2V22H12.8V16H18V14L16,12Z" /></svg>
                </button>
            </div>

            <div v-show="!paramsCollapsed" class="panel-body">

                <label class="field">

                    <span>
                        选择PVT表
                    </span>

                    <el-select v-model="selectedPvtId" size="small" aria-label="选择PVT表"
                        :placeholder="pvtOptions.length ? '请选择PVT性质' : '当前井暂无PVT性质'" style="width:100%">

                        <el-option v-for="item in pvtOptions" :key="item.pvtId" :value="String(item.pvtId)"
                            :label="item.pvtName || `PVT性质${item.pvtNo ?? item.pvtId}`" />
                    </el-select>

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

                    <el-input v-model="inputUpperLimit" size="small" placeholder="请输入上限" aria-label="压力上限" />

                </label>

                <label class="field">

                    <span>
                        压力下限 (MPa)
                    </span>

                    <el-input v-model="inputLowerLimit" size="small" placeholder="请输入下限" aria-label="压力下限" />

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

            <div v-show="!paramsCollapsed" class="params-resizer" @pointerdown="startParamsPanelResize"></div>
        </aside>

        <main class="result-area">

            <div class="dynamic-result-tabs">
                <div class="dynamic-result-tab active" :title="`诊断曲线${wellName ? `-${wellName}` : ''}-分析结果`">
                    诊断曲线{{ wellName ? `-${wellName}` : '' }}-分析结果
                </div>
            </div>
            <div v-show="activePanel === 'input'" class="editable-data-grid">

                <el-table :data="rows" size="small" border stripe height="100%">

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

                    <!-- 图例固定在坐标网格右上角内侧，仍可点击切换曲线显隐。 -->
                    <div class="chart-legend">
                        <button v-for="(selected, name) in legendSelected" :key="name" type="button"
                            :class="{ muted: !selected }" :aria-pressed="selected" @click="toggleLegend(name)">
                            <i :class="{ theoretical: name === '理论基准线' }"></i>{{ name }}
                        </button>
                    </div>
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
                    结果分析图
                </button>

            </div>

        </main>

    </section>
</template>

<style lang="scss" scoped>
.diagnostic-workspace {
    display: flex;
    flex: 1;
    height: 100%;
    min-height: 0;
    background: #fff;
    color: #333;
    overflow: hidden;
}

.params-panel {
    width: 238px;
    min-width: 238px;
    flex-shrink: 0;
    min-height: 0;
    display: flex;
    flex-direction: column;
    border-right: 1px solid #e0e0e0;
    position: relative;
    &.collapsed { border-right: 0; }
    &.resizing { user-select: none; }
}

.panel-head {
    padding: 7px 12px 6px;
    display: flex;
    align-items: center;
    justify-content: space-between;
    flex-shrink: 0;
    background: #fff;
    border-bottom: 1px solid #f0f0f0;
    font-size: 13px;
}

.panel-toggle {
    width: 20px; height: 20px; padding: 0; border: 0; background: transparent;
    display: flex; align-items: center; justify-content: center; cursor: pointer; border-radius: 2px;
    &:hover { background: #fff8d8; }
}
.panel-collapsed-tab {
    width: 22px; height: 76px; padding: 0; display: flex; align-items: center; justify-content: center;
    writing-mode: vertical-rl; text-orientation: mixed; font: inherit; font-size: 13px; color: #333;
    cursor: pointer; background: #fff; border: 1px solid #e0e0e0; border-left: 0;
    &:hover { background: #fff8d8; }
}
.params-resizer {
    position: absolute; top: 0; right: -3px; width: 6px; height: 100%; cursor: col-resize; z-index: 4; touch-action: none;
    &:hover { background: rgba(242, 200, 17, .28); }
}

.panel-body {
    flex: 1;
    min-height: 0;
    overflow: auto;
    padding: 4px 12px 14px;
}

.field {
    display: block;
    margin-bottom: 9px;
    font-size: 12px;
}

.field>span {
    display: block;
    margin-bottom: 3px;
    color: #555;
}

.hidden-file {
    display: none;
}

.local-import-button {
    width: 100%;
    height: 24px;
    padding: 0 8px;
    border: 1px solid #dcdfe6;
    border-radius: 3px;
    background: #fff;
    color: #333;
    font: inherit;
    font-size: 12px;
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
    min-width: 86px;
    padding: 0 24px;
    border: 0;
    border-radius: 5px;
    color: #fff;
    cursor: pointer;
    background: #252525;
    font: inherit;
    font-size: 13px;
    font-weight: 600;
    &:hover:not(:disabled) { background: #050505; }
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
    min-width: 0;
    min-height: 0;
}

.editable-data-grid :deep(.el-table) { flex: 1; }

// 标题和底部页签占固定高度，图例悬浮在绘图区内。
.dynamic-result-tabs {
    height: 34px; display: flex; flex-shrink: 0; background: #fafafa;
    overflow: hidden; border-bottom: 1px solid #e4e7ed;
}
.dynamic-result-tab {
    max-width: 100%; padding: 0 12px; line-height: 34px; background: #f4d000;
    font-size: 14px; font-weight: 600; color: #202020; white-space: nowrap; text-overflow: ellipsis; overflow: hidden;
}
.analysis-view { position: relative; }
.chart-legend {
    position: absolute; z-index: 5; top: 56px; right: 104px;
    max-width: calc(100% - 116px); display: flex; flex-wrap: wrap;
    background: rgba(255,255,255,.9); border: 1px solid #eee;
    button { display: inline-flex; align-items: center; gap: 5px; padding: 6px 10px; border: 0; background: transparent;
        color: #555; font: inherit; font-size: 12px; cursor: pointer; }
    i { width: 18px; border-top: 2px solid #5470c6; }
    i.theoretical { border-top: 2px dashed #a6d608; }
    .muted { color: #aaa; i { border-color: #ccc; } }
}

.bottom-tabs {
    height: 30px;
    display: flex;
    flex-shrink: 0;
    border-top: 1px solid #e4e7ed;
    background: #fff;
}

.bottom-tabs button {
    height: 30px;
    min-width: 82px;
    padding: 0 14px;
    border: 0;
    border-right: 1px solid #e4e7ed;
    background: #fff;
    color: #333;
    font: inherit;
    font-size: 13px;
    white-space: nowrap;
    cursor: pointer;
}

.bottom-tabs button.active {
    color: #202020;
    box-shadow:
        inset 0 3px 0 #f2c811;
    font-weight: 600;
}
</style>
