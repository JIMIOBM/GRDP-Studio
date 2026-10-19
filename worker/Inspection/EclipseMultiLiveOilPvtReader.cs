using System.Text.RegularExpressions;
using Grdp.SoftwareIntegration.Worker.Contracts;
namespace Grdp.SoftwareIntegration.Worker.Inspection;
internal static class EclipseMultiLiveOilPvtReader
{
    internal static EclipseMultiLiveOilPvtContext? Read(string text,string sourceFile)=>ReadCore(text,sourceFile,false);
    internal static EclipseMultiLiveOilPvtContext? ReadCombined(string text,string sourceFile)=>ReadCore(text,sourceFile,true);
    private static EclipseMultiLiveOilPvtContext? ReadCore(string text,string sourceFile,bool combined)
    {
        var lines=Regex.Split(text,"\\r\\n|\\r|\\n").Select(l=>l.Split("--",2)[0].Trim()).ToArray();
        int[] Find(string key)=>lines.Select((line,i)=>(line,i)).Where(x=>Regex.IsMatch(x.line,"^"+key+@"(?:\s+=+)?$",RegexOptions.IgnoreCase|RegexOptions.CultureInvariant)).Select(x=>x.i).ToArray();
        string[] blocked=["INCLUDE","LGR","ENDLGR","COMPS","THERMAL","VAPOIL","PVDO","PVTG","PVCDO","PVCO","API","SOLVENT","MISCIBLE","PBVD","RVVD","PDVD","PRESSURE","SWAT","SGAS","RS","RV","ROCKCOMP","ROCKTAB","ROCKTABH","ROCKTABW","ROCKOPTS","EQLOPTS","VE","MULTIREG","MULTREGT","MULTREGP","MULTREGD","EQUALREG","COPYREG","ADDREG","OPERATER"];
        if(blocked.Any(k=>lines.Any(l=>Regex.IsMatch(l,"^"+k+@"(?:\s|$)",RegexOptions.IgnoreCase|RegexOptions.CultureInvariant)))||new[]{"OIL","GAS","WATER","DISGAS"}.Any(k=>Find(k).Length!=1))return null;
        var props=Find("PROPS");var solution=Find("SOLUTION");var summary=Find("SUMMARY");var tabs=Find("TABDIMS");var dims=Find("DIMENS");var eqlDims=Find("EQLDIMS");var pvtnum=Find("PVTNUM");var eqlnum=Find("EQLNUM");
        if(props.Length!=1||solution.Length!=1||summary.Length!=1||tabs.Length!=1||dims.Length!=1||eqlDims.Length!=1||pvtnum.Length!=1||eqlnum.Length!=1||!(tabs[0]<props[0]&&eqlDims[0]<props[0]&&props[0]<pvtnum[0]&&props[0]<eqlnum[0]&&pvtnum[0]<solution[0]&&eqlnum[0]<solution[0]&&solution[0]<summary[0]))return null;
        if(lines.Skip(props[0]+1).Any(l=>Regex.IsMatch(l,@"^(EQUALS|COPY|MULTIPLY|ADD|OPERATE|BOX|ENDBOX)(?:\s|$)",RegexOptions.IgnoreCase|RegexOptions.CultureInvariant)))return null;
        string First(int start)=>lines.Skip(start+1).FirstOrDefault(l=>l.Length>0)??"";
        var tab=Regex.Match(First(tabs[0]),"^"+(combined?"[2-8]":"1")+@"\s+([2-8])\s+[1-9][0-9]*\s+([1-9][0-9]*)\s+[1-9][0-9]*\s+([1-9][0-9]*)(?:\s+[1-9][0-9]*)*\s*/$",RegexOptions.CultureInvariant);
        var dim=Regex.Match(First(dims[0]),@"^([1-9][0-9]*)\s+([1-9][0-9]*)\s+([1-9][0-9]*)\s*/$",RegexOptions.CultureInvariant);
        var eq=Regex.Match(First(eqlDims[0]),@"^([2-8])\s+([1-9][0-9]*)\s+[1-9][0-9]*\s+[1-9][0-9]*\s+[1-9][0-9]*\s*/$",RegexOptions.CultureInvariant);
        if(!tab.Success||!dim.Success||!eq.Success||!int.TryParse(tab.Groups[2].Value,out var maxPressures)||maxPressures>100000||!int.TryParse(tab.Groups[3].Value,out var maxRatios)||maxRatios>100000||!int.TryParse(eq.Groups[2].Value,out var maxDepths)||maxDepths>100000)return null;
        int count=int.Parse(tab.Groups[1].Value,System.Globalization.CultureInfo.InvariantCulture);if(eq.Groups[1].Value!=tab.Groups[1].Value)return null;
        long cells=1;for(int i=1;i<=3;i++){if(!int.TryParse(dim.Groups[i].Value,out var n)||n>2000000||cells*n>2000000)return null;cells*=n;}
        var map=Map(pvtnum[0]);var equilibriumMap=Map(eqlnum[0]);if(map is null||equilibriumMap is null||!map.SequenceEqual(equilibriumMap))return null;
        var cellCounts=Enumerable.Range(1,count).Select(n=>map.Count(v=>v==n)).ToArray();if(cellCounts.Any(n=>n==0))return null;
        var pvto=Find("PVTO");var pvdg=Find("PVDG");var regionStart=Math.Min(pvtnum[0],eqlnum[0]);
        if(pvto.Length!=1||pvdg.Length!=1||pvdg[0]<=props[0]||pvto[0]<=pvdg[0]||pvto[0]>=regionStart)return null;
        var gas=Records(pvdg[0],pvto[0],3,3);if(gas is null||gas.Any(r=>!EclipseGasPvtReader.ValidRows(r.Select(v=>new EclipseGasPvtRow(v.LineNumber,v.Values)).ToArray())||r.Count<2||r.Count>maxPressures))return null;
        var oil=new List<IReadOnlyList<EclipseLiveOilPvtRecord>>();var records=new List<EclipseLiveOilPvtRecord>();var rows=new List<EclipseOilPvtRow>();int end=pvto[0];
        for(int i=pvto[0]+1;i<regionStart;i++){
            if(lines[i].Length==0)continue;var tokens=Tokens(lines[i]);
            if(tokens.Length==1&&tokens[0]=="/"){
                if(rows.Count!=0||!EclipseLiveOilPvtReader.ValidRecords(records,pvto[0]+1))return null;
                oil.Add(records.ToArray());records=[];end=i;if(oil.Count==count)break;continue;
            }
            int columns=rows.Count==0?4:3;bool slash=tokens.Length==columns+1&&tokens[^1]=="/";
            if(tokens.Length!=columns&&!slash||tokens[..columns].Any(v=>v.Length>64||!EclipsePvtwReader.Number(v,out _)))return null;
            rows.Add(new(i+1,tokens[..columns]));if(rows.Count>64||rows.Count>maxPressures)return null;
            if(slash){records.Add(new(rows.ToArray()));rows=[];if(records.Count>32||records.Count>maxRatios)return null;}
        }
        if(oil.Count!=count||!Keyword(lines.Skip(end+1).FirstOrDefault(l=>l.Length>0)))return null;
        foreach(var key in new[]{"PVTW","ROCK"})if(!Auxiliary(key,key=="PVTW"?4:2,key=="PVTW"?5:2))return null;
        var gravity=Find("GRAVITY");var density=Find("DENSITY");if(gravity.Length+density.Length!=1||!Auxiliary(gravity.Length==1?"GRAVITY":"DENSITY",3,3))return null;
        var equil=Find("EQUIL");var rsvd=Find("RSVD");if(equil.Length!=1||rsvd.Length!=1||equil[0]<=solution[0]||rsvd[0]<=equil[0]||rsvd[0]>=summary[0])return null;
        var equilibria=Records(equil[0],rsvd[0],9,9);var depths=Records(rsvd[0],summary[0],2,2);if(equilibria is null||depths is null)return null;
        for(int i=0;i<count;i++){
            if(equilibria[i].Count!=1)return null;var values=equilibria[i][0].Values.Select(v=>{EclipsePvtwReader.Number(v,out var n);return n;}).ToArray();
            if(values[1]<=0||values[2]<values[4]||values[3]<0||values[5]<0||values[6]<=0||values[6]!=Math.Truncate(values[6])||values[7]!=0||values[8]!=0)return null;
            if(depths[i].Count<2||depths[i].Count>maxDepths)return null;double priorDepth=double.NegativeInfinity;EclipsePvtwReader.Number(oil[i][^1].Rows[0].Values[0],out var maximumRs);
            foreach(var row in depths[i]){EclipsePvtwReader.Number(row.Values[0],out var depth);EclipsePvtwReader.Number(row.Values[1],out var rs);if(depth<=priorDepth||rs<0||rs>maximumRs)return null;priorDepth=depth;}
        }
        return new("eclipse-multi-live-oil-pvt-context/1",sourceFile,count,oil.Select((t,i)=>new EclipseLiveOilPvtRegion(i+1,cellCounts[i],pvto[0]+1,t,new EclipseRegionGasPvtTable(pvdg[0]+1,gas[i].Select(r=>new EclipseGasPvtRow(r.LineNumber,r.Values)).ToArray()))).ToArray());

        string[] Tokens(string line)=>Regex.Matches(line,@"[^\s/]+|/",RegexOptions.CultureInvariant).Select(m=>m.Value).ToArray();
        bool Keyword(string? line)=>line is not null&&Regex.IsMatch(line,@"^[A-Za-z][A-Za-z0-9-]*(?:\s+=+)?$",RegexOptions.CultureInvariant);
        int[]? Map(int start){
            var result=new List<int>();bool closed=false;
            for(int i=start+1;i<solution[0];i++){
                if(lines[i].Length==0)continue;
                foreach(var token in Tokens(lines[i])){
                    if(closed)return null;if(token=="/"){closed=true;continue;}
                    var m=Regex.Match(token,@"^(?:([1-9][0-9]*)\*)?([1-9][0-9]*)$",RegexOptions.CultureInvariant);int repeats=1;
                    if(!m.Success||!int.TryParse(m.Groups[2].Value,out var n)||n>count||m.Groups[1].Success&&!int.TryParse(m.Groups[1].Value,out repeats)||repeats>cells||result.Count+(long)repeats>cells)return null;
                    result.AddRange(Enumerable.Repeat(n,repeats));
                }
                if(closed)return result.Count==cells?result.ToArray():null;
            }return null;
        }
        List<List<EclipsePvtRow>>? Records(int start,int stop,int min,int max){
            var output=new List<List<EclipsePvtRow>>();var current=new List<EclipsePvtRow>();int last=start;
            for(int i=start+1;i<stop;i++){
                if(lines[i].Length==0)continue;if(Keyword(lines[i]))break;var tokens=Tokens(lines[i]);bool slash=tokens[^1]=="/";var numeric=slash?tokens[..^1]:tokens;
                if(numeric.Length>0){if(numeric.Length<min||numeric.Length>max||numeric.Any(v=>v.Length>64||!EclipsePvtwReader.Number(v,out _)))return null;current.Add(new(i+1,numeric));if(current.Count>64)return null;}
                if(slash){if(current.Count==0)return null;output.Add(current);current=[];if(output.Count>count)return null;}last=i;
            }
            return current.Count==0&&output.Count==count&&Keyword(lines.Skip(last+1).FirstOrDefault(l=>l.Length>0))?output:null;
        }
        bool Auxiliary(string key,int min,int max){var found=Find(key);if(found.Length!=1||found[0]<=props[0]||found[0]>=regionStart)return false;var records=Records(found[0],regionStart,min,max);return records is not null&&records.All(r=>r.Count==1);}
    }
}
