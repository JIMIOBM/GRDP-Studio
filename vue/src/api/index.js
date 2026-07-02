import request from '@/utils/request'

/* ===== 用户相关接口 ===== */
export const userApi = {
  // 登录
  login: (data) => request.post('/user/login', data),
  // 注册
  register: (data) => request.post('/user/register', data),
  // 获取用户列表（后台）
  list: (params) => request.get('/user/list', { params }),
  // 新增 / 更新
  save: (data) => request.post('/user/save', data),
  // 删除
  remove: (id) => request.delete(`/user/${id}`),
  // 修改密码
  updatePassword: (data) => request.post('/user/password', data),
  // 更新个人信息
  updateInfo: (data) => request.post('/user/info', data)
}

/* ===== 项目树 / 井库相关接口 ===== */
export const treeApi = {
  // 获取左侧项目树（井 / 库 / 库群）
  getTree: () => request.get('/project/tree')
}

/* ===== 解析融合（IPR）相关接口 ===== */
export const iprApi = {
  // 提交计算任务
  compute: (data) => request.post('/ipr/compute', data),
  // 导入文件
  importFile: (formData) =>
    request.post('/ipr/import', formData, {
      headers: { 'Content-Type': 'multipart/form-data' }
    })
}

export default { userApi, treeApi, iprApi }
