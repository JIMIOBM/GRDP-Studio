using System.Text.RegularExpressions;
using Grdp.SoftwareIntegration.Worker.Contracts;
namespace Grdp.SoftwareIntegration.Worker.Inspection;
internal static class EclipseGasPvtReader
{
    internal static EclipseGasPvtContext? Read(string text,string sourceFile)
    {
        var lines=Regex.Split(text,"\\r\\n|\\r|\\n").Select(l=>l.Split("--",2)[0].Trim()).ToArray();
        int[] Find(string key)=>lines.Select((line,i)=>(line,i)).Where(x=>Regex.IsMatch(x.line,"^"+key+@"(?:\s+=+)?$",RegexOptions.IgnoreCase|RegexOptions.CultureInvariant)).Select(x=>x.i).ToArray();
        if(new[]{"INCLUDE","LGR","ENDLGR","COMPS","THERMAL","DISGAS","VAPOIL","SOLVENT","PVTNUM","PVTG","PVZG","PVTO","PVCDO"}.Any(k=>lines.Any(l=>Regex.IsMatch(l,"^"+k+@"(?:\s|$)",RegexOptions.IgnoreCase|RegexOptions.CultureInvariant)))||Find("GAS").Length!=1)return null;
        var props=Find("PROPS");var solution=Find("SOLUTION");var table=Find("PVDG");var tabs=Find("TABDIMS");
        if(props.Length!=1||solution.Length!=1||table.Length!=1||tabs.Length!=1||!(tabs[0]<props[0]&&props[0]<table[0]&&table[0]<solution[0]))return null;
        if(lines.Skip(props[0]+1).Any(l=>Regex.IsMatch(l,@"^(EQUALS|COPY|MULTIPLY|BOX|ENDBOX)(?:\s|$)",RegexOptions.IgnoreCase|RegexOptions.CultureInvariant)))return null;
        var tabRow=lines.Skip(tabs[0]+1).FirstOrDefault(l=>l.Length>0);if(tabRow is null)return null;
        var tab=Regex.Match(tabRow,@"^[1-9][0-9]*\s+1\s+[1-9][0-9]*\s+([1-9][0-9]*)(?:\s+[1-9][0-9]*)*\s*/$",RegexOptions.CultureInvariant);
        if(!tab.Success||!int.TryParse(tab.Groups[1].Value,out var maxRows)||maxRows>100_000)return null;
        var rows=new List<EclipseGasPvtRow>();var closed=false;var end=table[0];
        for(var i=table[0]+1;i<solution[0];i++) {
            var line=lines[i];if(line.Length==0)continue;
            if(line=="/"){closed=true;end=i;break;}
            var tokens=Regex.Matches(line,@"[^\s/]+|/",RegexOptions.CultureInvariant).Select(m=>m.Value).ToArray();
            if(tokens.Length is not (3 or 4)||tokens.Length==4&&tokens[^1]!="/"||tokens[..3].Any(v=>v.Length>64||!EclipsePvtwReader.Number(v,out _)))return null;
            rows.Add(new(i+1,tokens[..3]));if(rows.Count>64)return null;
            if(tokens.Length==4){closed=true;end=i;break;}
        }
        var next=lines.Skip(end+1).FirstOrDefault(l=>l.Length>0);
        if(!closed||rows.Count<2||rows.Count>maxRows||next is null||!Regex.IsMatch(next,@"^[A-Za-z][A-Za-z0-9-]*(?:\s+=+)?$",RegexOptions.CultureInvariant)||!ValidRows(rows))return null;
        return new("eclipse-gas-pvt-context/1",sourceFile,table[0]+1,rows);
    }
    internal static bool ValidRows(IReadOnlyList<EclipseGasPvtRow> rows) {
        double priorP=double.NegativeInfinity,priorB=double.PositiveInfinity,priorMu=0;int priorLine=0;
        foreach(var row in rows){if(row.Values.Count!=3||row.LineNumber<=priorLine)return false;var n=new double[3];for(var i=0;i<3;i++)if(!EclipsePvtwReader.Number(row.Values[i],out n[i]))return false;
            if(n[0]<0||n[0]<=priorP||n[1]<=0||n[1]>=priorB||n[1]>100000||n[2]<=0||n[2]<priorMu||n[2]>1000)return false;
            priorP=n[0];priorB=n[1];priorMu=n[2];priorLine=row.LineNumber;
        }return true;
    }
}
