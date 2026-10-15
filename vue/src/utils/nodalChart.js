export const nodalStatus = code => ({ SUCCESS: '计算完成', INCOMPLETE_CONSTRAINTS: '约束未完成', PARTIAL_RESULT: '部分工况不可计算', SEARCH_LIMIT_REACHED: '搜索范围不足', NO_FEASIBLE_RANGE: '无可行区间', MULTIPLE_INTERSECTIONS: '多个边界交点' }[code] || code)
const xy = p => [p.rate, p.pressure]
export function nodalEnvelope(result) {
  if(Array.isArray(result.feasibleRegions)) return result.feasibleRegions.map(region=>region.map(xy))
  const slices = [...(result.regionScenarios || [])].sort((a,b) => a.reservoirPressure-b.reservoirPressure), polygons = []
  const valid = s => s.status !== 'INCOMPLETE_CONSTRAINTS' && s.intervals?.length > 0
  const edge = (s,i) => [xy(i.from), ...s.formation.filter(p=>p.rate>i.from.rate && p.rate<i.to.rate).map(xy), xy(i.to)]
  // Local failures must not hide other confirmed intervals. Pair only unambiguous branches.
  for(let i=1;i<slices.length;i++) {
    const a=slices[i-1], b=slices[i]
    if(!valid(a) || !valid(b)) continue
    for(const left of a.intervals) {
      const overlaps = right => Math.max(left.from.rate,right.from.rate) < Math.min(left.to.rate,right.to.rate)
      const matches=b.intervals.filter(overlaps)
      if(matches.length !== 1) continue
      const right=matches[0]
      if(a.intervals.filter(x=>Math.max(x.from.rate,right.from.rate)<Math.min(x.to.rate,right.to.rate)).length!==1) continue
      polygons.push([...edge(a,left),...edge(b,right).reverse()])
    }
  }
  return polygons
}
export function nodalConstraintBoundary(scenario, code) {
  const points=[], candidates=scenario.candidates || []
  for(let i=1;i<candidates.length;i++) {
    const a=candidates[i-1], b=candidates[i], x=a.checks?.find(c=>c.code===code), y=b.checks?.find(c=>c.code===code)
    if(x?.margin==null || y?.margin==null || !['PASS','FAIL'].includes(x.status) || !['PASS','FAIL'].includes(y.status) || x.margin*y.margin>0 || x.margin===y.margin) continue
    const t=x.margin/(x.margin-y.margin)
    const p=[a.rate+(b.rate-a.rate)*t,a.pressure+(b.pressure-a.pressure)*t]
    if(!points.some(q=>Math.abs(q[0]-p[0])<1e-6)) points.push(p)
  }
  return points
}
export function nodalLegendGraphic(items) {
  const rowHeight=25, width=250, height=items.length*rowHeight+16
  const children=[{type:'rect',silent:false,cursor:'move',shape:{x:0,y:0,width,height,r:3},style:{fill:'rgba(255,255,255,.94)',stroke:'#d9dde3',lineWidth:1}}]
  items.forEach((item,index)=>{
    const y=14+index*rowHeight
    children.push(item.symbol==='circle'
      ? {type:'circle',silent:true,shape:{cx:22,cy:y,r:6},style:{fill:item.color}}
      : {type:'line',silent:true,shape:{x1:8,y1:y,x2:38,y2:y},style:{stroke:item.color,lineWidth:2}})
    children.push({type:'text',silent:true,style:{x:46,y,text:item.name,fill:'#5f6368',font:'13px sans-serif',verticalAlign:'middle'}})
  })
  // Use the children's real bounds for right alignment; raw group bounds are zero-sized
  // and place most of the legend outside the canvas.
  return {id:'nodal-draggable-legend',type:'group',right:12,top:22,z:100,draggable:true,cursor:'move',children}
}
export function nodalChartOption(result, operation) {
  const injection=operation==='injection', maximumName=`最大合理${injection?'注':'采'}气能力`
  const line=(name,data,color)=>({name,type:'line',showSymbol:false,smooth:false,connectNulls:false,data,lineStyle:{width:2,color},itemStyle:{color},z:3})
  const series=[line(injection?'井筒流入曲线':'井筒流出曲线',result.wellboreCurve.map(xy),injection?'#c77575':'#aa70aa')], maximum=[]
  result.scenarios.forEach((s,i)=>{
    const colors=injection?['#e59138','#41a269','#36a9d2','#996dce']:['#31577a','#e59138','#41a269','#36a9d2']
    const name=`${injection?'地层流出':'地层流入'}曲线 Pr=${s.reservoirPressure} MPa`
    series.push(line(name,s.formation.map(xy),colors[i%colors.length]))
    if(s.maximum) maximum.push({name,value:xy(s.maximum),stabilityPending:s.status==='MULTIPLE_INTERSECTIONS'})
  })
  const constraintColors={liquidLoading:'#4575b4',erosion:'#ed4545'}
  if(Array.isArray(result.constraintCurves)) {
    for(const curve of result.constraintCurves) for(const segment of curve.segments||[]) {
      if(segment.length) series.push(line(curve.name,segment.map(xy),constraintColors[curve.code]||'#777'))
    }
  } else {
    // Old saved snapshots did not persist formal constraint curves.
    const slices=[...(result.regionScenarios?.length?result.regionScenarios:result.scenarios)].sort((a,b)=>a.reservoirPressure-b.reservoirPressure)
    for(const [code,name,color] of [['liquidLoading','临界携液流量','#4575b4'],['erosion','临界冲蚀流量','#ed4545']]) {
      if(injection && code==='liquidLoading') continue
      const data=slices.map(s=>{const edges=nodalConstraintBoundary(s,code);return edges.length===1?edges[0]:[null,null]})
      if(data.some(p=>p[0]!=null)) series.push(line(name,data,color))
    }
  }
  const polygons=nodalEnvelope(result)
  if(polygons.length) series.push({name:'合理能力范围',type:'custom',silent:true,z:1,data:polygons.map((_,i)=>[i]),
    renderItem(params,api) {
      const points=polygons[params.dataIndex].map(p=>api.coord(p)),xs=points.map(p=>p[0]),ys=points.map(p=>p[1])
      const left=Math.min(...xs),right=Math.max(...xs),top=Math.min(...ys),bottom=Math.max(...ys)
      const children=[{type:'polygon',shape:{points},style:{fill:'rgba(100,100,100,0.08)'}}]
      for(let x=left;x<=right;x+=12) children.push({type:'line',shape:{x1:x,y1:top,x2:x,y2:bottom},style:{stroke:'#656565',lineWidth:0.6}})
      for(let y=top;y<=bottom;y+=12) children.push({type:'line',shape:{x1:left,y1:y,x2:right,y2:y},style:{stroke:'#656565',lineWidth:0.6}})
      return {type:'group',clipPath:{type:'polygon',shape:{points}},children}
    }})
  if(maximum.length) series.push({name:maximumName,type:'scatter',symbol:'circle',symbolSize:12,data:maximum,z:5,itemStyle:{color:'#e32626'},tooltip:{trigger:'item',formatter:p=>`${p.name}<br/>${maximumName}${p.data.stabilityPending?'（多交点，稳定性待确认）':''}<br/>气量：${Number(p.value[0]).toFixed(4)} ×10⁴ m³/d<br/>井底流压：${Number(p.value[1]).toFixed(4)} MPa`}})
  const legendSeries=[...series.slice(1,1+result.scenarios.length),series[0],...series.filter(s=>s.name.startsWith('临界'))]
  const legendItems=[...(maximum.length?[{name:maximumName,color:'#e32626',symbol:'circle'}]:[]),
    ...legendSeries.filter((s,i,a)=>a.findIndex(x=>x.name===s.name)===i).map(s=>({name:s.name,color:s.lineStyle.color,symbol:'line'}))]
  return {animation:false,tooltip:{trigger:'axis'},legend:{show:false},graphic:[nodalLegendGraphic(legendItems)],grid:{top:40,left:75,right:280,bottom:65},
    xAxis:{type:'value',name:`${injection?'注气量':'采气量'} (10⁴ m³/d)`,nameLocation:'middle',nameGap:35,min:0,splitLine:{show:true,lineStyle:{color:'#dfe7f2',width:1}} ,axisLine:{show:true}},
    yAxis:{type:'value',name:'井底流压 (MPa)',splitLine:{show:true,lineStyle:{color:'#dfe7f2',width:1}} ,axisLine:{show:true}},series}
}


