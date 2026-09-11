<script setup>
import {computed,nextTick, onBeforeUnmount,onMounted,ref,watch} from 'vue'
import * as echarts from 'echarts'
import { ElMessage } from 'element-plus'

import { pvtStorageApi } from '@/api/pvtStorage'
import { deletedPvtRecord, matchesPvtScope } from '@/utils/pvtRecordActions'
import { diagnosticCurveApi } from '@/api/diagnosticCurve'

const props = defineProps({
    node: Object,
    projectId: [Number, String],
    gasReservoirId: [Number, String]
})

const emit = defineEmits(['recalculate', 'saved'])

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

/*
 * 当前已经保存/打开的诊断方案。
 * diagnosticId 为空表示当前页面还没有落库。
 */
const diagnosticId =
    ref(null)

const diagnosticName =
    ref('')

const saving =
    ref(false)

/*
 * 冻结“本次计算真正使用的PVT”。
 * 保存时绝不重新 buildPvtData()，避免PVT详情异步变化导致保存失败。
 */
const calculatedPvtSnapshot =
    ref(null)

const calculatedPvtId =
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
 * PVT / 已保存诊断方案
 * ============================
 */

const cloneJson = value =>
    value === null || value === undefined
        ? value
        : JSON.parse(
            JSON.stringify(value)
        )

const toPositiveId = value => {
    const number =
        Number(value)

    return Number.isInteger(number) &&
        number > 0
        ? number
        : null
}

const nodeDiagnosticId =
    computed(
        () =>
            toPositiveId(
                props.node?.diagnosticId
            )
    )

const invalidateCalculatedState = () => {
    result.value = null
    calculatedPvtSnapshot.value = null
    calculatedPvtId.value = null

    if (
        activePanel.value === 'analysis'
    ) {
        activePanel.value = 'input'
    }
}

const loadPvtDetail = async () => {
    pvtDetail.value = null

    if (
        !selectedPvtId.value ||
        !wellName.value
    ) {
        return null
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

        return detail

    } catch (error) {
        console.warn(
            '加载PVT详情失败',
            error
        )

        ElMessage.warning(
            '加载PVT详情失败，Z数据可能不可用'
        )

        return null
    }
}

const loadPvtOptions = async (
    selectFirst = true
) => {
    pvtOptions.value = []
    selectedPvtId.value = ''
    pvtDetail.value = null

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
            selectFirst &&
            pvtOptions.value.length > 0
        ) {
            selectedPvtId.value =
                String(
                    pvtOptions.value[0].pvtId
                )

            await loadPvtDetail()
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

const loadDiagnosticRecord = async id => {
    const recordId =
        toPositiveId(id)

    if (
        !recordId ||
        !wellName.value
    ) {
        return
    }

    if (
        typeof diagnosticCurveApi.getRecord !==
        'function'
    ) {
        console.warn(
            'diagnosticCurveApi.getRecord 未定义，无法回显已保存诊断方案'
        )
        return
    }

    try {
        const detail = unwrap(
            await diagnosticCurveApi.getRecord(
                recordId,
                props.projectId,
                props.gasReservoirId,
                wellName.value
            )
        )

        diagnosticId.value =
            detail?.record?.diagnosticId ??
            recordId

        diagnosticName.value =
            detail?.record?.diagnosticName ??
            ''

        const savedPvtId =
            toPositiveId(
                detail?.pvtId
            )

        selectedPvtId.value =
            savedPvtId
                ? String(savedPvtId)
                : ''

        if (savedPvtId) {
            await loadPvtDetail()
        } else {
            pvtDetail.value = null
        }

        inputUpperLimit.value =
            detail?.upperPressureLimit ??
            ''

        inputLowerLimit.value =
            detail?.lowerPressureLimit ??
            ''

        rows.value =
            (
                Array.isArray(
                    detail?.productionData
                )
                    ? detail.productionData
                    : []
            ).map(item => ({
                sequence:
                    Number(
                        item?.sequence
                    ),

                time:
                    String(
                        item?.time ?? ''
                    ),

                /*
                 * 详情接口回读 gas 为10^8m3；
                 * 页面表格按10^4m3显示。
                 */
                gasRaw:
                    Number(item?.gas) *
                    10000,

                gas:
                    Number(
                        item?.gas
                    ),

                cycle:
                    String(
                        item?.cycle ?? ''
                    )
            }))

        importedFileName.value = ''

        result.value =
            detail?.result ??
            null

        calculatedPvtSnapshot.value =
            result.value &&
            detail?.pvtSnapshot
                ? cloneJson(
                    detail.pvtSnapshot
                )
                : null

        calculatedPvtId.value =
            result.value
                ? savedPvtId
                : null

        activePanel.value =
            result.value
                ? 'analysis'
                : 'input'

        if (result.value) {
            await nextTick()
            updateChart(
                result.value
            )
        }

    } catch (error) {
        console.error(
            '加载诊断方案失败',
            error
        )

        ElMessage.error(
            error?.response?.data?.msg ||
            error?.response?.data?.message ||
            error?.message ||
            '加载诊断方案失败'
        )
    }
}

const initializePage = async () => {
    diagnosticId.value = null
    diagnosticName.value = ''
    rows.value = []
    importedFileName.value = ''
    inputUpperLimit.value = ''
    inputLowerLimit.value = ''
    result.value = null
    calculatedPvtSnapshot.value = null
    calculatedPvtId.value = null
    activePanel.value = 'input'

    const savedId =
        nodeDiagnosticId.value

    await loadPvtOptions(
        !savedId
    )

    if (savedId) {
        await loadDiagnosticRecord(
            savedId
        )
    }
}

watch(
    [
        () => props.projectId,
        () => props.gasReservoirId,
        () => props.node?.wellName,
        () => props.node?.diagnosticId
    ],
    () => {
        initializePage()
    },
    {
        immediate: true
    }
)

const handlePvtSelectionChanged = async () => {
    invalidateCalculatedState()
    await loadPvtDetail()
}

/**
 * 从 PVT 详情接口的真实结构构造诊断计算需要的 PVT 数据。
 *
 * 后端 PvtRecordDetail.GasResultPoint 的字段是：
 * pressure + deviationFactor。
 * 这里不再递归猜字段名，避免保存阶段因详情结构变化而识别失败。
 */
const buildPvtData = () => {
    const detail =
        pvtDetail.value

    if (!detail) {
        throw new Error(
            'PVT详情尚未加载，请重新选择PVT后再试'
        )
    }

    const gasResults =
        Array.isArray(
            detail?.gasResults
        )
            ? detail.gasResults
            : []

    const points =
        gasResults
            .map(item => {
                const pressure =
                    parseNumber(
                        item?.pressure
                    )

                const zFactor =
                    parseNumber(
                        item?.deviationFactor
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
            .sort(
                (a, b) =>
                    a.pressure -
                    b.pressure
            )

    const uniqueCurve = []

    for (
        const point
        of points
    ) {
        const previous =
            uniqueCurve[
                uniqueCurve.length - 1
            ]

        if (
            previous &&
            Math.abs(
                previous.pressure -
                point.pressure
            ) < 1e-9
        ) {
            previous.zFactor =
                (
                    previous.zFactor +
                    point.zFactor
                ) / 2
        } else {
            uniqueCurve.push({
                ...point
            })
        }
    }

    if (
        uniqueCurve.length >= 2
    ) {
        return {
            fixedZ: null,
            zCurve: uniqueCurve
        }
    }

    console.error(
        '当前PVT详情:',
        detail
    )

    console.error(
        '当前PVT天然气结果 gasResults:',
        gasResults
    )

    throw new Error(
        `所选PVT没有有效的Pressure-Z曲线。` +
        `当前天然气结果共 ${gasResults.length} 行，` +
        `至少需要2行 pressure>0 且 deviationFactor>0 的数据。`
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

        /*
         * 新导入数据后，旧计算结果必须失效。
         */
        invalidateCalculatedState()

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

        /*
         * 冻结本次计算真正使用的PVT。
         * 保存时直接使用该快照，不再重新读取PVT详情。
         */
        calculatedPvtSnapshot.value =
            cloneJson(pvt)

        calculatedPvtId.value =
            toPositiveId(
                selectedPvtId.value
            )

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
 * 保存已计算诊断方案
 * ============================
 */

const buildProductionDataForSave = () =>
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

            /*
             * 这里保持计算接口单位10^8m3。
             * 数据库存储为10^4m3的转换由后端统一处理。
             */
            gas:
                row.gas,

            cycle:
                String(
                    row.cycle ?? ''
                )
        })
    )

const saveCalculated = async () => {
    if (!result.value) {
        ElMessage.warning(
            '请先计算诊断曲线'
        )
        return
    }

    if (
        !calculatedPvtSnapshot.value
    ) {
        ElMessage.error(
            '本次计算的PVT快照不存在，请重新计算后再保存'
        )
        return
    }

    if (
        typeof diagnosticCurveApi.saveRecord !==
        'function'
    ) {
        ElMessage.error(
            'diagnosticCurveApi.saveRecord 未配置'
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
        lowerLimit === null ||
        lowerLimit <= 0 ||
        upperLimit <= lowerLimit
    ) {
        ElMessage.error(
            '当前压力上下限无效，请重新计算'
        )
        return
    }

    const currentPvtId =
        toPositiveId(
            selectedPvtId.value
        )

    if (
        calculatedPvtId.value !== null &&
        currentPvtId !==
        calculatedPvtId.value
    ) {
        ElMessage.error(
            '当前PVT与本次计算使用的PVT不一致，请重新计算后再保存'
        )
        return
    }

    const productionData =
        buildProductionDataForSave()

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
            `有 ${invalidRows.length} 行注/采气量无效，不能保存`
        )
        return
    }

    const payload = {
        diagnosticId:
            diagnosticId.value,

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

        diagnosticName:
            diagnosticName.value ||
            props.node?.diagnosticName ||
            null,

        status:
            'CALCULATED',

        pvtId:
            currentPvtId,

        /*
         * 核心修复：
         * 保存计算时冻结的PVT，不再调用 buildPvtData()。
         */
        pvtSnapshot:
            cloneJson(
                calculatedPvtSnapshot.value
            ),

        upperPressureLimit:
            upperLimit,

        lowerPressureLimit:
            lowerLimit,

        remark:
            null,

        productionData,

        result:
            cloneJson(
                result.value
            )
    }

    saving.value = true

    try {
        const saved = unwrap(
            await diagnosticCurveApi.saveRecord(
                payload
            )
        )

        diagnosticId.value =
            saved?.diagnosticId ??
            diagnosticId.value

        diagnosticName.value =
            saved?.diagnosticName ??
            diagnosticName.value

        emit(
            'saved',
            saved
        )

        ElMessage.success(
            saved?.diagnosticName
                ? `已保存：${saved.diagnosticName}`
                : '诊断曲线已保存'
        )

    } catch (error) {
        console.error(
            '保存诊断曲线失败',
            error
        )

        ElMessage.error(
            error?.response?.data?.msg ||
            error?.response?.data?.message ||
            error?.message ||
            '保存失败'
        )

    } finally {
        saving.value = false
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

                    <select v-model="selectedPvtId" @change="handlePvtSelectionChanged">

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

                    <input v-model="inputUpperLimit" placeholder="请输入上限" @input="invalidateCalculatedState" />

                </label>

                <label class="field">

                    <span>
                        压力下限 (MPa)
                    </span>

                    <input v-model="inputLowerLimit" placeholder="请输入下限" @input="invalidateCalculatedState" />

                </label>

                <div class="action-buttons">

                    <button type="button" class="calculate" :disabled="calculating" @click="handleRecalculate">
                        {{
                            calculating
                                ? '计算中…'
                                : '计算'
                        }}
                    </button>

                    <button type="button" class="save-result" :disabled="saving || !result" @click="saveCalculated">
                        {{
                            saving
                                ? '保存中…'
                                : '保存结果'
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

                    <el-table-column label="注/采气量（10⁴m³）" min-width="180" align="center">
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

.save-result {
    height: 32px;
    padding: 0 18px;
    border: 1px solid #666;
    border-radius: 3px;
    background: #fff;
    color: #222;
    cursor: pointer;
}

.save-result:disabled {
    opacity: .5;
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
