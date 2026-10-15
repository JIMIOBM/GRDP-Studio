/**
 * analyticMethodOptions.js
 * 产量不稳定分析-解析法（及其复用者：配产配注-注采拟合）共用的参数选项。
 *
 * 这些选项属于平台产品口径：平台增删计算方法/气藏类型时，改这一处即可，
 * 避免"解析法与注采拟合两个页面下拉选项不一致"。
 * 注意：数组顺序与平台 input 的数字编号一一对应（0/1/2…），调整顺序会改变编号含义。
 */
export const MODIFICATION_METHODS = ['Wichert-Aziz 修正方法', 'Carr-Kobayashi-Burrous 修正方法']
export const DEVIATION_METHODS = ['Dranchuk-Abu-Kassem 方法', 'Dranchuk-Purvis-Robinson 方法', 'Hall-Yarborough 方法']
export const VISCOSITY_METHODS = ['Lee-Gonzalez-Eakin 方法', 'Carr-Kobayashi-Burrous 方法', 'Sutton 方法']
export const MATERIAL_BALANCE_METHODS = ['封闭气藏', '定容气藏', '页岩气藏']
export const GAS_TYPES = ['干气', '湿气', '凝析气']
