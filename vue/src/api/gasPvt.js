import request from '@/utils/request'

/**
 * 天然气 PVT 前端接口入口。
 *
 * curve-one～three 是本项目后端包装后的批量曲线接口，不是原平台 toolbox 接口。
 * 后端负责创建 toolbox、遍历压力点并提取结果，页面只需要传一份公共计算参数。
 * 10 分钟超时用于容纳 5～200 MPa 多个压力点的远程计算。
 */
export const gasPvtApi = {
  // 曲线 1：天然气偏差系数 Z + 气体拟压力 m(p)。
  calculateCurveOne: (data) =>
    request.post('/pvt/gas/curve-one', data, {
      timeout: 600000,
      headers: {
        'Process-Env': 'prod'
      }
    }),
  // 曲线 2：天然气体积系数 Bg + 天然气密度。
  calculateCurveTwo: (data) =>
    request.post('/pvt/gas/curve-two', data, {
      timeout: 600000,
      headers: {
        'Process-Env': 'prod'
      }
    }),
  // 曲线 3：天然气压缩系数 Cg。
  calculateCurveThree: (data) =>
    request.post('/pvt/gas/curve-three', data, {
      timeout: 600000,
      headers: {
        'Process-Env': 'prod'
      }
    }),
  // 曲线 4：天然气黏度 μg。
  calculateViscosityCurve: (data) =>
    request.post('/pvt/gas/viscosity-curve', data, {
      timeout: 600000,
      headers: {
        'Process-Env': 'prod'
      }
    })
}
