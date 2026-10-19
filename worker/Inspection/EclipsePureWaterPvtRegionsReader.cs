using System.Text.RegularExpressions;
namespace Grdp.SoftwareIntegration.Worker.Inspection;
// Explicit full-cell region maps and initial pressures for a water-only deck.
internal static class EclipsePureWaterPvtRegionsReader
{
    internal static int[]? Read(string text)
    {
        var lines=Regex.Split(text,"\r\n|\r|\n").Select(l=>l.Split("--",2)[0].Trim()).ToArray();
        int[] Find(string key)=>lines.Select((line,i)=>(line,i)).Where(x=>Regex.IsMatch(x.line,"^"+key+@"(?:\s+=+)?$",RegexOptions.IgnoreCase|RegexOptions.CultureInvariant)).Select(x=>x.i).ToArray();
        string[] blocked=["INCLUDE","LGR","ENDLGR","COMPS","THERMAL","OIL","GAS","DISGAS","VAPOIL","PVTWSALT","PVTWALT","PVTWAT","PVTWEND","EQUIL","EQLNUM","PVTO","PVDG","PVTG","PVDO","ROCKCOMP","ROCKTAB","ROCKTABH","ROCKTABW","ROCKOPTS","EQUALREG","COPYREG","ADDREG","OPERATER","MULTIREG","MULTREGT","MULTREGP","MULTREGD","SATNUM"];
        if(Find("WATER").Length!=1||blocked.Any(k=>lines.Any(l=>Regex.IsMatch(l,"^"+k+@"(?:\s|$)",RegexOptions.IgnoreCase|RegexOptions.CultureInvariant))))return null;
        var tabs=Find("TABDIMS");var dims=Find("DIMENS");var props=Find("PROPS");var regions=Find("REGIONS");var solution=Find("SOLUTION");var summary=Find("SUMMARY");var map=Find("PVTNUM");var pressure=Find("PRESSURE");var water=Find("PVTW");
        if(new[]{tabs,dims,props,regions,solution,summary,map,pressure,water}.Any(v=>v.Length!=1)||!(tabs[0]<props[0]&&dims[0]<props[0]&&props[0]<water[0]&&water[0]<regions[0]&&regions[0]<map[0]&&map[0]<solution[0]&&solution[0]<pressure[0]&&pressure[0]<summary[0]))return null;
        if(lines.Skip(props[0]+1).Any(l=>Regex.IsMatch(l,@"^(EQUALS|COPY|MULTIPLY|ADD|OPERATE|BOX|ENDBOX)(?:\s|$)",RegexOptions.IgnoreCase|RegexOptions.CultureInvariant)))return null;
        string First(int start)=>lines.Skip(start+1).FirstOrDefault(l=>l.Length>0)??"";
        var tab=Regex.Match(First(tabs[0]),@"^1\s+([2-8])(?:\s+[1-9][0-9]*){4}\s*/$",RegexOptions.CultureInvariant);
        var dim=Regex.Match(First(dims[0]),@"^([1-9][0-9]*)\s+([1-9][0-9]*)\s+([1-9][0-9]*)\s*/$",RegexOptions.CultureInvariant);
        if(!tab.Success||!dim.Success)return null;
        long cells=1;for(int i=1;i<=3;i++){if(!int.TryParse(dim.Groups[i].Value,out var n)||n>2000000||cells*n>2000000)return null;cells*=n;}
        int count=int.Parse(tab.Groups[1].Value,System.Globalization.CultureInfo.InvariantCulture);var counts=new int[count];
        if(!Array(map[0],solution[0],(v,n)=>{if(v!=Math.Truncate(v)||v<1||v>count)return false;counts[(int)v-1]+=n;return true;})||counts.Any(n=>n==0)||!Array(pressure[0],summary[0],(v,_)=>v>0))return null;
        foreach(var keyword in new[]{"ROCK","DENSITY"}){
            var found=Find(keyword);if(found.Length!=1||found[0]<=props[0]||found[0]>=regions[0])return null;int total=0;
            for(int i=found[0]+1;i<regions[0];i++){
                if(lines[i].Length==0)continue;if(Regex.IsMatch(lines[i],@"^[A-Za-z][A-Za-z0-9-]*(?:\s+=+)?$",RegexOptions.CultureInvariant))break;
                var tokens=Regex.Matches(lines[i],@"[^\s/]+|/",RegexOptions.CultureInvariant).Select(m=>m.Value).ToArray();
                int columns=keyword=="ROCK"?2:3;if(tokens.Length!=columns+1||tokens[^1]!="/"||tokens[..columns].Any(v=>v.Length>64||!EclipsePvtwReader.Number(v,out var n)||n<0)||(keyword=="DENSITY"&&tokens[..columns].Any(v=>double.Parse(v.Replace('D','E').Replace('d','e'),System.Globalization.CultureInfo.InvariantCulture)<=0))||++total>count)return null;
            }if(total!=count)return null;
        }return counts;
        bool Array(int start,int end,Func<double,int,bool> accept){
            long total=0;bool closed=false;
            for(int i=start+1;i<end;i++){
                if(lines[i].Length==0)continue;
                foreach(var token in Regex.Matches(lines[i],@"[^\s/]+|/",RegexOptions.CultureInvariant).Select(m=>m.Value)){
                    if(closed)return false;if(token=="/"){closed=true;continue;}
                    var parts=token.Split('*');if(parts.Length>2||parts.Any(v=>v.Length==0||v.Length>64))return false;int repeat=1;
                    if(parts.Length==2&&(!Regex.IsMatch(parts[0],@"^[1-9][0-9]*$",RegexOptions.CultureInvariant)||!int.TryParse(parts[0],out repeat)))return false;
                    if(!EclipsePvtwReader.Number(parts[^1],out var n)||repeat>cells||total+repeat>cells||!accept(n,repeat))return false;total+=repeat;
                }if(closed)return total==cells;
            }return false;
        }
    }
}
