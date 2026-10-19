using System.Text.RegularExpressions;
using Grdp.SoftwareIntegration.Worker.Contracts;
namespace Grdp.SoftwareIntegration.Worker.Inspection;
internal static class EclipseMultiPvtReader
{
    internal static EclipseMultiPvtContext? Read(string text,string sourceFile)=>ReadCore(text,sourceFile,false);
    internal static EclipseMultiPvtContext? ReadCombined(string text,string sourceFile)=>ReadCore(text,sourceFile,true);
    private static EclipseMultiPvtContext? ReadCore(string text,string sourceFile,bool combined)
    {
        var lines=Regex.Split(text,"\\r\\n|\\r|\\n").Select(l=>l.Split("--",2)[0].Trim()).ToArray();
        int[] Find(string key)=>lines.Select((line,i)=>(line,i)).Where(x=>Regex.IsMatch(x.line,"^"+key+@"(?:\s+=+)?$",RegexOptions.IgnoreCase|RegexOptions.CultureInvariant)).Select(x=>x.i).ToArray();
        string[] blocked=["INCLUDE","LGR","ENDLGR","COMPS","THERMAL","DISGAS","VAPOIL","PVTO","PVTG","PVCDO","PVCO","PVTWSALT","PVTWAT","PVTGCO2","PVTSOL","SOLVENT","MISCIBLE","EQUIL","EQLNUM","RS","RV","RSCONST","RSCONSTT","PBUB","PDEW","ROCKCOMP","ROCKTAB","ROCKTABH","ROCKTABW","ROCKOPTS","EQUALREG","COPYREG","ADDREG","OPERATER","MULTIREG","MULTREGT","MULTREGP","MULTREGD"];
        if(blocked.Any(k=>lines.Any(l=>Regex.IsMatch(l,"^"+k+@"(?:\s|$)",RegexOptions.IgnoreCase|RegexOptions.CultureInvariant)))||new[]{"OIL","GAS","WATER"}.Any(k=>Find(k).Length!=1))return null;
        var props=Find("PROPS");var solution=Find("SOLUTION");var summary=Find("SUMMARY");var tabs=Find("TABDIMS");var dimensions=Find("DIMENS");var pvtnum=Find("PVTNUM");
        if(props.Length!=1||solution.Length!=1||summary.Length!=1||tabs.Length!=1||dimensions.Length!=1||pvtnum.Length!=1||!(tabs[0]<props[0]&&props[0]<pvtnum[0]&&pvtnum[0]<solution[0]&&solution[0]<summary[0]))return null;
        if(lines.Skip(props[0]+1).Any(l=>Regex.IsMatch(l,@"^(EQUALS|COPY|MULTIPLY|ADD|OPERATE|BOX|ENDBOX)(?:\s|$)",RegexOptions.IgnoreCase|RegexOptions.CultureInvariant)))return null;
        var tabRow=lines.Skip(tabs[0]+1).FirstOrDefault(l=>l.Length>0);var dimRow=lines.Skip(dimensions[0]+1).FirstOrDefault(l=>l.Length>0);
        if(tabRow is null||dimRow is null)return null;
        var tab=Regex.Match(tabRow,"^"+(combined?"[2-8]":"1")+@"\s+([2-8])\s+[1-9][0-9]*\s+([1-9][0-9]*)(?:\s+[1-9][0-9]*)*\s*/$",RegexOptions.CultureInvariant);
        var dim=Regex.Match(dimRow,@"^([1-9][0-9]*)\s+([1-9][0-9]*)\s+([1-9][0-9]*)\s*/$",RegexOptions.CultureInvariant);
        if(!tab.Success||!dim.Success||!int.TryParse(tab.Groups[2].Value,out var maxRows)||maxRows>100000)return null;
        long cells=1;for(var i=1;i<=3;i++){if(!int.TryParse(dim.Groups[i].Value,out var n)||n>2000000||cells*n>2000000)return null;cells*=n;}
        var count=int.Parse(tab.Groups[1].Value,System.Globalization.CultureInfo.InvariantCulture);var cellCounts=new int[count];
        if(!Array(pvtnum[0],solution[0],(value,n)=>{if(value!=Math.Truncate(value)||value<1||value>count)return false;cellCounts[(int)value-1]+=n;return true;})||cellCounts.Any(n=>n==0))return null;
        var initial=new Dictionary<string,List<double>>();
        foreach(var keyword in new[]{"PRESSURE","SWAT","SGAS"}){
            var found=Find(keyword);if(found.Length!=1||found[0]<=solution[0]||found[0]>=summary[0])return null;
            var values=new List<double>();initial[keyword]=values;
            if(!Array(found[0],summary[0],(v,n)=>{if(keyword=="PRESSURE"?v<=0:v<0||v>1)return false;values.AddRange(Enumerable.Repeat(v,n));return true;}))return null;
        }
        if(initial["SWAT"].Zip(initial["SGAS"]).Any(p=>p.First+p.Second>1))return null;
        var tablesByRegion=Enumerable.Range(0,count).Select(_=>new List<EclipsePvtTable>()).ToArray();
        foreach(var keyword in new[]{"PVDO","PVDG"}){
            var found=Find(keyword);if(found.Length!=1||found[0]<=props[0]||found[0]>=pvtnum[0])return null;
            var records=Records(found[0],pvtnum[0],3,3);if(records is null||records.Count!=count)return null;
            for(var r=0;r<count;r++){
                var rows=records[r];if(rows.Count>maxRows||!ValidRows(keyword,rows))return null;
                tablesByRegion[r].Add(new(keyword,found[0]+1,rows));
            }
        }
        foreach(var keyword in new[]{"PVTW","ROCK"})if(!Auxiliary(keyword,keyword=="PVTW"?4:2,keyword=="PVTW"?5:2))return null;
        var gravity=Find("GRAVITY");var density=Find("DENSITY");if(gravity.Length+density.Length!=1||!Auxiliary(gravity.Length==1?"GRAVITY":"DENSITY",3,3))return null;
        if(tablesByRegion.Any(t=>t[0].Rows[^1].LineNumber>=t[1].KeywordLineNumber))return null;
        return new("eclipse-multi-pvt-context/1",sourceFile,count,tablesByRegion.Select((t,i)=>new EclipsePvtRegion(i+1,cellCounts[i],t)).ToArray());

        bool Array(int start,int end,Func<double,int,bool> accept){
            long total=0;bool closed=false;
            for(var i=start+1;i<end;i++){
                if(lines[i].Length==0)continue;
                foreach(var token in Regex.Matches(lines[i],@"[^\s/]+|/",RegexOptions.CultureInvariant).Select(m=>m.Value)){
                    if(closed)return false;if(token=="/"){closed=true;continue;}
                    var split=token.Split('*');if(split.Length>2||split.Any(s=>s.Length==0||s.Length>64))return false;
                    int repeats=1;if(split.Length==2&&(!Regex.IsMatch(split[0],@"^[1-9][0-9]*$",RegexOptions.CultureInvariant)||!int.TryParse(split[0],out repeats)))return false;
                    if(!EclipsePvtwReader.Number(split[^1],out var v)||repeats>cells||total+repeats>cells||!accept(v,repeats))return false;total+=repeats;
                }
                if(closed)return total==cells;
            }return false;
        }
        List<List<EclipsePvtRow>>? Records(int start,int end,int minimum,int maximum,bool allowCvDefault=false){
            var records=new List<List<EclipsePvtRow>>();var rows=new List<EclipsePvtRow>();var last=start;
            for(var i=start+1;i<end;i++){
                if(lines[i].Length==0)continue;
                var values=Regex.Matches(lines[i],@"[^\s/]+|/",RegexOptions.CultureInvariant).Select(m=>m.Value).ToArray();
                if(Regex.IsMatch(lines[i],@"^[A-Za-z][A-Za-z0-9-]*(?:\s+=+)?$",RegexOptions.CultureInvariant))break;
                bool slash=values[^1]=="/";var numeric=slash?values[..^1]:values;
                if(numeric.Length>0){if(numeric.Length<minimum||numeric.Length>maximum||numeric.Where((v,i)=>!(allowCvDefault&&numeric.Length==5&&i==4&&v=="1*")).Any(v=>v.Length>64||!EclipsePvtwReader.Number(v,out _)))return null;rows.Add(new(i+1,numeric));if(rows.Count>64)return null;}
                if(slash){if(rows.Count==0)return null;records.Add(rows);rows=[];if(records.Count>count)return null;}
                last=i;
            }
            var next=lines.Skip(last+1).FirstOrDefault(l=>l.Length>0);
            return rows.Count==0&&records.Count==count&&next is not null&&Regex.IsMatch(next,@"^[A-Za-z][A-Za-z0-9-]*(?:\s+=+)?$",RegexOptions.CultureInvariant)?records:null;
        }
        bool Auxiliary(string keyword,int min,int max){var found=Find(keyword);if(found.Length!=1||found[0]<=props[0]||found[0]>=pvtnum[0])return false;var rows=Records(found[0],pvtnum[0],min,max,keyword=="PVTW");return rows is not null&&rows.All(r=>r.Count==1);}
    }
    internal static bool ValidRows(string keyword,IReadOnlyList<EclipsePvtRow> rows)=>rows.Count is >=2 and <=64&&rows.All(r=>r.Values.Count==3&&r.Values.All(v=>v.Length<=64))&&(keyword switch{
        "PVDO"=>EclipseOilPvtReader.ValidRows(rows.Select(r=>new EclipseOilPvtRow(r.LineNumber,r.Values)).ToArray()),
        "PVDG"=>EclipseGasPvtReader.ValidRows(rows.Select(r=>new EclipseGasPvtRow(r.LineNumber,r.Values)).ToArray()),_=>false});
}
