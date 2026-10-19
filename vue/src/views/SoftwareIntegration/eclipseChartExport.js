// One download per action, including panels with different physical units.
export async function exportEclipseCharts(charts, filename) {
  if (!charts.length) return
  const frames = await Promise.all(charts.map(chart => new Promise((resolve, reject) => {
    const image = new Image()
    image.onload = () => resolve(image); image.onerror = reject
    image.src = chart.getDataURL({ type: 'png', pixelRatio: 2, backgroundColor: '#fff' })
  })))
  const canvas = document.createElement('canvas')
  canvas.width = Math.max(...frames.map(frame => frame.width))
  canvas.height = frames.reduce((height, frame) => height + frame.height, 0)
  const context = canvas.getContext('2d')
  context.fillStyle = '#fff'; context.fillRect(0, 0, canvas.width, canvas.height)
  let top = 0
  for (const frame of frames) { context.drawImage(frame, 0, top); top += frame.height }
  const link = document.createElement('a'); link.href = canvas.toDataURL('image/png'); link.download = filename; link.click()
}
