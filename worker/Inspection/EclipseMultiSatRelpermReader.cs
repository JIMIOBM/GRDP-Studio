using System.Text.RegularExpressions;
using Grdp.SoftwareIntegration.Worker.Contracts;
namespace Grdp.SoftwareIntegration.Worker.Inspection;
internal static class EclipseMultiSatRelpermReader
{
    internal static EclipseMultiSatRelpermContext? Read(string text,string sourceFile)=>ReadCore(text,sourceFile,false,false);
    internal static EclipseMultiSatRelpermContext? ReadFamilyII(string text,string sourceFile)=>ReadCore(text,sourceFile,true,false);
    internal static EclipseMultiSatRelpermContext? ReadCombined(string text,string sourceFile)=>ReadCore(text,sourceFile,false,true);
    internal static EclipseMultiSatRelpermContext? ReadFamilyIICombined(string text,string sourceFile)=>ReadCore(text,sourceFile,true,true);
    internal static EclipseMultiSatRelpermContext? ReadFamilyIICombinedLiveOil(string text,string sourceFile)=>ReadCore(text,sourceFile,true,true,true);
    internal static EclipseMultiSatRelpermContext? ReadFamilyIICombinedWetGas(string text,string sourceFile)=>ReadCore(text,sourceFile,true,true,true,true);
    internal static EclipseMultiSatRelpermContext? ReadCombinedWetGas(string text,string sourceFile)=>ReadCore(text,sourceFile,false,true,true,true);
    private static EclipseMultiSatRelpermContext? ReadCore(string text,string sourceFile,bool familyII,bool combined,bool liveOil=false,bool wetGas=false)
    {
        var lines=Regex.Split(text,"\\r\\n|\\r|\\n").Select(l=>l.Split("--",2)[0].Trim()).ToArray();
        int[] Find(string key)=>lines.Select((line,i)=>(line,i)).Where(x=>Regex.IsMatch(x.line,"^"+key+@"(?:\s+=+)?$",RegexOptions.IgnoreCase|RegexOptions.CultureInvariant)).Select(x=>x.i).ToArray();
        string[] blocked=["INCLUDE","LGR","ENDLGR","COMPS","THERMAL","VAPOIL","SOLVENT","MISCIBLE","SLGOF","SGWFN","SOF2","SOF32D","SGF32D","SWF32D","SWATINIT","ENDSCALE","SCALECRS","TOLCRIT","SATOPTS","EHYSTR","STONE","STONE1","STONE2","BAKER1","BAKER2","IKU3P","ODD3P","ISATNUM","PSTNUM","ISTNUM","DSTNUM"];
        var alternatives=familyII?(liveOil?new[]{"SWOF","SGOF"}:new[]{"SWOF","SGOF","DISGAS"}):new[]{"SWFN","SGFN","SOF3"};
        if(blocked.Where(k=>!wetGas||k!="VAPOIL").Concat(alternatives).Any(k=>lines.Any(l=>Regex.IsMatch(l,"^"+k+@"(?:\s|$)",RegexOptions.IgnoreCase|RegexOptions.CultureInvariant)))||new[]{"OIL","GAS","WATER"}.Any(k=>Find(k).Length!=1))return null;
        var props=Find("PROPS");var solution=Find("SOLUTION");var tabs=Find("TABDIMS");var dimensions=Find("DIMENS");var satnum=Find("SATNUM");
        if(props.Length!=1||solution.Length!=1||tabs.Length!=1||dimensions.Length!=1||satnum.Length!=1||!(tabs[0]<props[0]&&props[0]<satnum[0]&&satnum[0]<solution[0]))return null;
        if(lines.Skip(props[0]+1).Any(l=>Regex.IsMatch(l,@"^(EQUALS|COPY|MULTIPLY|BOX|ENDBOX)(?:\s|$)",RegexOptions.IgnoreCase|RegexOptions.CultureInvariant)))return null;
        var tabRow=lines.Skip(tabs[0]+1).FirstOrDefault(l=>l.Length>0);var dimRow=lines.Skip(dimensions[0]+1).FirstOrDefault(l=>l.Length>0);
        if(tabRow is null||dimRow is null)return null;
        var tab=Regex.Match(tabRow,@"^([2-8])\s+"+(combined?"[2-8]":"1")+@"\s+([1-9][0-9]*)\s+[1-9][0-9]*(?:\s+[1-9][0-9]*)*\s*/$",RegexOptions.CultureInvariant);
        var dim=Regex.Match(dimRow,@"^([1-9][0-9]*)\s+([1-9][0-9]*)\s+([1-9][0-9]*)\s*/$",RegexOptions.CultureInvariant);
        if(!tab.Success||!dim.Success||!int.TryParse(tab.Groups[2].Value,out var maxRows)||maxRows>100000)return null;
        long cells=1;for(var i=1;i<=3;i++){if(!int.TryParse(dim.Groups[i].Value,out var n)||n>2000000||cells*n>2000000)return null;cells*=n;}
        var regionCount=int.Parse(tab.Groups[1].Value,System.Globalization.CultureInfo.InvariantCulture);var cellCounts=new int[regionCount];long saturationCells=0;bool closed=false;
        for(var i=satnum[0]+1;i<solution[0];i++){
            if(lines[i].Length==0)continue;
            foreach(var token in Regex.Matches(lines[i],@"[^\s/]+|/",RegexOptions.CultureInvariant).Select(m=>m.Value)){
                if(closed)return null;if(token=="/"){closed=true;continue;}
                var repeated=Regex.Match(token,@"^(?:([1-9][0-9]*)\*)?([1-9][0-9]*)$",RegexOptions.CultureInvariant);
                if(!repeated.Success||!int.TryParse(repeated.Groups[2].Value,out var number)||number<1||number>regionCount)return null;
                var count=1;if(repeated.Groups[1].Success&&(!int.TryParse(repeated.Groups[1].Value,out count)||count>2_000_000))return null;
                saturationCells+=count;if(saturationCells>cells)return null;cellCounts[number-1]+=count;
            }
            if(closed)break;
        }
        if(!closed||saturationCells!=cells||cellCounts.Any(n=>n==0))return null;
        var tablesByRegion=Enumerable.Range(0,regionCount).Select(_=>new List<EclipseRelpermTable>()).ToArray();
        var columns=familyII?3:4;
        foreach(var keyword in familyII?new[]{"SWFN","SGFN","SOF3"}:new[]{"SWOF","SGOF"}){
            var found=Find(keyword);if(found.Length!=1||found[0]<=props[0]||found[0]>=satnum[0])return null;
            var rows=new List<EclipseRelpermRow>();var region=0;var end=found[0];
            for(var i=found[0]+1;i<satnum[0];i++){
                if(lines[i].Length==0)continue;
                var values=Regex.Matches(lines[i],@"[^\s/]+|/",RegexOptions.CultureInvariant).Select(m=>m.Value).ToArray();
                var slash=values.Length==columns+1&&values[^1]=="/";var standalone=values.Length==1&&values[0]=="/";
                if(!standalone){
                    if(values.Length!=columns&&!slash||values[..columns].Any(v=>v.Length>64||!EclipsePvtwReader.Number(v,out _)))return null;
                    rows.Add(new(i+1,values[..columns]));if(rows.Count>64||rows.Count>maxRows)return null;
                }
                if(slash||standalone){
                    if(familyII?!EclipseRelpermReader.ValidRows(keyword,rows):!EclipseCoupledRelpermReader.ValidRows(keyword,rows,found[0]+1))return null;
                    tablesByRegion[region].Add(new(keyword,found[0]+1,rows.ToArray()));rows.Clear();region++;end=i;if(region==regionCount)break;
                }
            }
            var next=lines.Skip(end+1).FirstOrDefault(l=>l.Length>0);
            if(region!=regionCount||next is null||!Regex.IsMatch(next,@"^[A-Za-z][A-Za-z0-9-]*(?:\s+=+)?$",RegexOptions.CultureInvariant))return null;
        }
        if(tablesByRegion.Any(t=>familyII?!EclipseRelpermReader.ValidTables(t):!EclipseCoupledRelpermReader.ValidTables(t)))return null;
        if(tablesByRegion.Any(t=>t.Zip(t.Skip(1)).Any(pair=>pair.First.KeywordLineNumber>=pair.Second.KeywordLineNumber)))return null;
        return new(familyII?"eclipse-multi-sat-relperm-context/2":"eclipse-multi-sat-relperm-context/1",sourceFile,regionCount,tablesByRegion.Select((t,i)=>new EclipseSatRelpermRegion(i+1,cellCounts[i],t)).ToArray());
    }
}
