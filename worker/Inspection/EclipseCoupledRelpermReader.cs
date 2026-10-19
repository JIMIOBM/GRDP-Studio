using System.Text.RegularExpressions;
using Grdp.SoftwareIntegration.Worker.Contracts;
namespace Grdp.SoftwareIntegration.Worker.Inspection;
internal static class EclipseCoupledRelpermReader
{
    internal static EclipseRelpermContext? Read(string text,string sourceFile)
    {
        var lines=Regex.Split(text,"\\r\\n|\\r|\\n").Select(l=>l.Split("--",2)[0].Trim()).ToArray();
        int[] Find(string key)=>lines.Select((line,i)=>(line,i)).Where(x=>Regex.IsMatch(x.line,"^"+key+@"(?:\s+=+)?$",RegexOptions.IgnoreCase|RegexOptions.CultureInvariant)).Select(x=>x.i).ToArray();
        string[] blocked=["INCLUDE","LGR","ENDLGR","COMPS","THERMAL","VAPOIL","SOLVENT","MISCIBLE","SWFN","SGFN","SOF3","SLGOF","SGWFN","SOF2","SOF32D","SGF32D","SWF32D","SWATINIT","ENDSCALE","SCALECRS","TOLCRIT","SATOPTS","EHYSTR","STONE","STONE1","STONE2","BAKER1","BAKER2","IKU3P","ODD3P","ISATNUM"];
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
        foreach(var keyword in new[]{"SWOF","SGOF"}){
            var found=Find(keyword);if(found.Length!=1||found[0]<=props[0]||found[0]>=satnum[0])return null;
            var rows=new List<EclipseRelpermRow>();closed=false;var end=found[0];
            for(var i=found[0]+1;i<satnum[0];i++){
                if(lines[i].Length==0)continue;if(lines[i]=="/"){closed=true;end=i;break;}
                var values=Regex.Matches(lines[i],@"[^\s/]+|/",RegexOptions.CultureInvariant).Select(m=>m.Value).ToArray();
                if(values.Length is not(4 or 5)||values.Length==5&&values[^1]!="/"||values[..4].Any(v=>v.Length>64||!EclipsePvtwReader.Number(v,out _)))return null;
                rows.Add(new(i+1,values[..4]));if(rows.Count>64)return null;if(values.Length==5){closed=true;end=i;break;}
            }
            var next=lines.Skip(end+1).FirstOrDefault(l=>l.Length>0);
            if(!closed||rows.Count<2||rows.Count>maxRows||next is null||!Regex.IsMatch(next,@"^[A-Za-z][A-Za-z0-9-]*(?:\s+=+)?$",RegexOptions.CultureInvariant)||!ValidRows(keyword,rows))return null;
            tables.Add(new(keyword,found[0]+1,rows));
        }
        if(!ValidTables(tables))return null;
        return new("eclipse-coupled-relperm-context/1",sourceFile,tables);
    }
    internal static bool ValidTables(IReadOnlyList<EclipseRelpermTable> tables) {
        if(tables.Count!=2||!tables.Select(t=>t.Keyword).ToHashSet(StringComparer.Ordinal).SetEquals(["SWOF","SGOF"]))return false;
        var last=0;
        foreach(var table in tables){if(table.KeywordLineNumber<=last||table.KeywordLineNumber>2_000_000||!ValidRows(table.Keyword,table.Rows,table.KeywordLineNumber))return false;last=table.Rows[^1].LineNumber;}
        var water=tables.Single(t=>t.Keyword=="SWOF");var gas=tables.Single(t=>t.Keyword=="SGOF");
        EclipsePvtwReader.Number(water.Rows[0].Values[2],out var oilWater);EclipsePvtwReader.Number(gas.Rows[0].Values[2],out var oilGas);
        return Math.Abs(oilWater-oilGas)<=1e-12;
    }
    internal static bool ValidRows(string keyword,IReadOnlyList<EclipseRelpermRow> rows,int keywordLine=0){
        if(keyword is not("SWOF" or "SGOF")||rows.Count is <2 or >64)return false;
        double saturation=-1,kr=-1,oil=double.PositiveInfinity,pc=keyword=="SWOF"?double.PositiveInfinity:double.NegativeInfinity;var line=keywordLine;
        foreach(var row in rows){
            if(row.LineNumber<=line||row.LineNumber>2_000_000||row.Values.Count!=4)return false;
            var n=new double[4];for(var i=0;i<4;i++)if(row.Values[i].Length>64||!EclipsePvtwReader.Number(row.Values[i],out n[i]))return false;
            if(n[0]<0||n[0]>1||n[0]<=saturation||n[1]<0||n[1]>1||n[1]<kr||n[2]<0||n[2]>1||n[2]>oil||keyword=="SWOF"&&n[3]>pc||keyword=="SGOF"&&n[3]<pc)return false;
            saturation=n[0];kr=n[1];oil=n[2];pc=n[3];line=row.LineNumber;
        }
        EclipsePvtwReader.Number(rows[0].Values[1],out var first);EclipsePvtwReader.Number(rows[^1].Values[2],out var last);
        if(first!=0||last!=0)return false;
        EclipsePvtwReader.Number(rows[0].Values[0],out var firstSat);return keyword!="SGOF"||firstSat==0;
    }
}
