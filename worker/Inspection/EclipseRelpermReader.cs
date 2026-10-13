using System.Text.RegularExpressions;
using Grdp.SoftwareIntegration.Worker.Contracts;
namespace Grdp.SoftwareIntegration.Worker.Inspection;
internal static class EclipseRelpermReader
{
    internal static EclipseRelpermContext? Read(string text,string sourceFile)
    {
        var lines=Regex.Split(text,"\\r\\n|\\r|\\n").Select(l=>l.Split("--",2)[0].Trim()).ToArray();
        int[] Find(string key)=>lines.Select((line,i)=>(line,i)).Where(x=>Regex.IsMatch(x.line,"^"+key+@"(?:\s+=+)?$",RegexOptions.IgnoreCase|RegexOptions.CultureInvariant)).Select(x=>x.i).ToArray();
        string[] blocked=["INCLUDE","LGR","ENDLGR","COMPS","THERMAL","DISGAS","VAPOIL","SOLVENT","MISCIBLE","SWOF","SGOF","SLGOF","SGWFN","SOF2","SOF32D","SWATINIT","ENDSCALE","SCALECRS","TOLCRIT","SATOPTS","EHYSTR","STONE","STONE1","STONE2","BAKER1","BAKER2","IKU3P","ODD3P","ISATNUM"];
        if(blocked.Any(k=>lines.Any(l=>Regex.IsMatch(l,"^"+k+@"(?:\s|$)",RegexOptions.IgnoreCase|RegexOptions.CultureInvariant)))||new[]{"OIL","GAS","WATER"}.Any(k=>Find(k).Length!=1))return null;
        var props=Find("PROPS");var solution=Find("SOLUTION");var tabs=Find("TABDIMS");var dimensions=Find("DIMENS");var satnum=Find("SATNUM");
        if(props.Length!=1||solution.Length!=1||tabs.Length!=1||dimensions.Length!=1||satnum.Length!=1||!(tabs[0]<props[0]&&props[0]<satnum[0]&&satnum[0]<solution[0]))return null;
        if(lines.Skip(props[0]+1).Any(l=>Regex.IsMatch(l,@"^(EQUALS|COPY|MULTIPLY|BOX|ENDBOX)(?:\s|$)",RegexOptions.IgnoreCase|RegexOptions.CultureInvariant)))return null;
        var tabRow=lines.Skip(tabs[0]+1).FirstOrDefault(l=>l.Length>0);var dimRow=lines.Skip(dimensions[0]+1).FirstOrDefault(l=>l.Length>0);
        if(tabRow is null||dimRow is null)return null;
        var tab=Regex.Match(tabRow,@"^1\s+1\s+([1-9][0-9]*)\s+[1-9][0-9]*(?:\s+[1-9][0-9]*)*\s*/$",RegexOptions.CultureInvariant);
        var dim=Regex.Match(dimRow,@"^([1-9][0-9]*)\s+([1-9][0-9]*)\s+([1-9][0-9]*)\s*/$",RegexOptions.CultureInvariant);
        if(!tab.Success||!dim.Success||!int.TryParse(tab.Groups[1].Value,out var maxRows)||maxRows>100000)return null;
        long cells=1;for(var i=1;i<=3;i++){if(!int.TryParse(dim.Groups[i].Value,out var n)||n>2000000||cells*n>2000000)return null;cells*=n;}
        long saturationCells=0;bool closed=false;
        for(var i=satnum[0]+1;i<solution[0];i++){
            if(lines[i].Length==0)continue;
            foreach(var token in Regex.Matches(lines[i],@"[^\s/]+|/",RegexOptions.CultureInvariant).Select(m=>m.Value)){
                if(closed)return null;if(token=="/"){closed=true;continue;}
                if(token=="1")saturationCells++;
                else {var repeated=Regex.Match(token,@"^([1-9][0-9]*)\*1$",RegexOptions.CultureInvariant);if(!repeated.Success||!int.TryParse(repeated.Groups[1].Value,out var count)||count>2000000)return null;saturationCells+=count;}
                if(saturationCells>cells)return null;
            }
            if(closed)break;
        }
        if(!closed||saturationCells!=cells)return null;
        var tables=new List<EclipseRelpermTable>();
        foreach(var keyword in new[]{"SWFN","SGFN","SOF3"}){
            var found=Find(keyword);if(found.Length!=1||found[0]<=props[0]||found[0]>=satnum[0])return null;
            var rows=new List<EclipseRelpermRow>();closed=false;var end=found[0];
            for(var i=found[0]+1;i<satnum[0];i++){
                if(lines[i].Length==0)continue;if(lines[i]=="/"){closed=true;end=i;break;}
                var values=Regex.Matches(lines[i],@"[^\s/]+|/",RegexOptions.CultureInvariant).Select(m=>m.Value).ToArray();
                if(values.Length is not(3 or 4)||values.Length==4&&values[^1]!="/"||values[..3].Any(v=>v.Length>64||!EclipsePvtwReader.Number(v,out _)))return null;
                rows.Add(new(i+1,values[..3]));if(rows.Count>64)return null;if(values.Length==4){closed=true;end=i;break;}
            }
            var next=lines.Skip(end+1).FirstOrDefault(l=>l.Length>0);
            if(!closed||rows.Count<2||rows.Count>maxRows||next is null||!Regex.IsMatch(next,@"^[A-Za-z][A-Za-z0-9-]*(?:\s+=+)?$",RegexOptions.CultureInvariant)||!ValidRows(keyword,rows))return null;
            tables.Add(new(keyword,found[0]+1,rows));
        }
        if(!ValidTables(tables))return null;
        return new("eclipse-relperm-context/1",sourceFile,tables);
    }
    internal static bool ValidRows(string keyword,IReadOnlyList<EclipseRelpermRow> rows){
        if(keyword is not("SWFN" or "SGFN" or "SOF3")||rows.Count is <2 or >64)return false;
        double saturation=-1,kr=-1,third=keyword=="SWFN"?double.PositiveInfinity:double.NegativeInfinity;int line=0;
        foreach(var row in rows){
            if(row.LineNumber<=line||row.Values.Count!=3)return false;var n=new double[3];for(var i=0;i<3;i++)if(row.Values[i].Length>64||!EclipsePvtwReader.Number(row.Values[i],out n[i]))return false;
            if(n[0]<0||n[0]>1||n[0]<=saturation||n[1]<0||n[1]>1||n[1]<kr||keyword=="SOF3"&&(n[2]<0||n[2]>1)||keyword=="SWFN"&&n[2]>third||keyword!="SWFN"&&n[2]<third)return false;
            saturation=n[0];kr=n[1];third=n[2];line=row.LineNumber;
        }
        EclipsePvtwReader.Number(rows[0].Values[1],out var firstKr);if(firstKr!=0)return false;
        if(keyword=="SGFN"){EclipsePvtwReader.Number(rows[0].Values[0],out var firstSat);if(firstSat!=0)return false;}
        if(keyword=="SOF3"){
            EclipsePvtwReader.Number(rows[0].Values[2],out var firstKrog);EclipsePvtwReader.Number(rows[^1].Values[1],out var lastKrow);EclipsePvtwReader.Number(rows[^1].Values[2],out var lastKrog);
            if(firstKrog!=0||Math.Abs(lastKrow-lastKrog)>1e-12)return false;
        }
        return true;
    }
    internal static bool ValidTables(IReadOnlyList<EclipseRelpermTable> tables){
        if(tables.Count!=3||!tables.Select(t=>t.Keyword).ToHashSet(StringComparer.Ordinal).SetEquals(["SWFN","SGFN","SOF3"])||tables.Any(t=>!ValidRows(t.Keyword,t.Rows)))return false;
        var water=tables.Single(t=>t.Keyword=="SWFN");var oil=tables.Single(t=>t.Keyword=="SOF3");
        EclipsePvtwReader.Number(water.Rows[0].Values[0],out var swco);EclipsePvtwReader.Number(oil.Rows[^1].Values[0],out var somax);
        return Math.Abs(swco+somax-1)<=1e-12;
    }
}
