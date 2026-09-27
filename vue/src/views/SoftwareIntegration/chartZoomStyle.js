export const compactChartSlider = (bottom = 18) => ({
  type: 'slider',
  xAxisIndex: 0,
  height: 6,
  bottom,
  borderColor: 'transparent',
  backgroundColor: '#f0f1f3',
  fillerColor: '#c2c7ce',
  showDataShadow: false,
  showDetail: false,
  brushSelect: false,
  handleIcon: 'path://M0,0 L6,0 L6,12 L0,12 Z',
  handleSize: 12,
  handleStyle: { color: '#a8afb8', borderWidth: 0 },
  emphasis: { handleStyle: { color: '#858e99', borderWidth: 0 } }
})
