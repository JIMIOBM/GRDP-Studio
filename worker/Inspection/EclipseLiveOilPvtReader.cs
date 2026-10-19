using System.Text.RegularExpressions;
using Grdp.SoftwareIntegration.Worker.Contracts;
namespace Grdp.SoftwareIntegration.Worker.Inspection;
internal static class EclipseLiveOilPvtReader
{
    internal static EclipseLiveOilPvtContext? Read(string text,string sourceFile)
    {
        var lines=Regex.Split(text,"\\r\\n|\\r|\\n").Select(l=>l.Split("--",2)[0].Trim()).ToArray();
        int[] Find(string key)=>lines.Select((line,i)=>(line,i)).Where(x=>Regex.IsMatch(x.line,"^"+key+@"(?:\s+=+)?$",RegexOptions.IgnoreCase|RegexOptions.CultureInvariant)).Select(x=>x.i).ToArray();
        if(new[]{"INCLUDE","LGR","ENDLGR","COMPS","THERMAL","VAPOIL","SOLVENT","PVTNUM","PVDO","PVCDO","PVCO","API"}.Any(k=>lines.Any(l=>Regex.IsMatch(l,"^"+k+@"(?:\s|$)",RegexOptions.IgnoreCase|RegexOptions.CultureInvariant)))||new[]{"OIL","GAS","DISGAS"}.Any(k=>Find(k).Length!=1))return null;
        var props=Find("PROPS");var solution=Find("SOLUTION");var table=Find("PVTO");var tabs=Find("TABDIMS");
        if(props.Length!=1||solution.Length!=1||table.Length!=1||tabs.Length!=1||!(tabs[0]<props[0]&&props[0]<table[0]&&table[0]<solution[0]))return null;
        if(lines.Skip(props[0]+1).Any(l=>Regex.IsMatch(l,@"^(EQUALS|COPY|MULTIPLY|BOX|ENDBOX)(?:\s|$)",RegexOptions.IgnoreCase|RegexOptions.CultureInvariant)))return null;
        // TABDIMS: item 3 is NSSFUN; pressure and Rs/Rv capacities are items 4 and 6.
        var tab=Regex.Match(lines.Skip(tabs[0]+1).FirstOrDefault(l=>l.Length>0)??"",@"^1\s+1\s+[1-9][0-9]*\s+([1-9][0-9]*)(?:\s+([1-9][0-9]*)(?:\s+([1-9][0-9]*)(?:\s+[1-9][0-9]*)*)?)?\s*/$",RegexOptions.CultureInvariant);
        if(!tab.Success||!int.TryParse(tab.Groups[1].Value,out var maxPressures)||maxPressures>100_000)return null;
        var maxRatios=20;
        if(tab.Groups[3].Success&&(!int.TryParse(tab.Groups[3].Value,out maxRatios)||maxRatios>100_000))return null;
        var records=new List<EclipseLiveOilPvtRecord>();var rows=new List<EclipseOilPvtRow>();var closed=false;var end=table[0];
        for(var i=table[0]+1;i<solution[0];i++) {
            var line=lines[i];if(line.Length==0)continue;
            if(line=="/"){if(rows.Count!=0)return null;closed=true;end=i;break;}
            var tokens=Regex.Matches(line,@"[^\s/]+|/",RegexOptions.CultureInvariant).Select(m=>m.Value).ToArray();
            var count=rows.Count==0?4:3;var slash=tokens.Length==count+1&&tokens[^1]=="/";
            if(tokens.Length!=count&&!slash||tokens[..count].Any(v=>v.Length>64||!EclipsePvtwReader.Number(v,out _)))return null;
            rows.Add(new(i+1,tokens[..count]));if(rows.Count>64||rows.Count>maxPressures)return null;
            if(slash){records.Add(new(rows.ToArray()));rows.Clear();if(records.Count>32||records.Count>maxRatios)return null;}
        }
        var next=lines.Skip(end+1).FirstOrDefault(l=>l.Length>0);
        if(!closed||next is null||!Regex.IsMatch(next,@"^[A-Za-z][A-Za-z0-9-]*(?:\s+=+)?$",RegexOptions.CultureInvariant)||!ValidRecords(records,table[0]+1))return null;
        return new("eclipse-live-oil-pvt-context/1",sourceFile,table[0]+1,records);
    }
    internal static bool ValidRecords(IReadOnlyList<EclipseLiveOilPvtRecord> records,int keywordLine) {
        if(records.Count is <2 or >32||records[^1].Rows.Count<2||records.Sum(r=>r.Rows.Count)>256)return false;
        double priorRs=-1,priorBubble=0;int priorLine=keywordLine;
        foreach(var record in records){if(record.Rows.Count is <1 or >64)return false;double priorP=0,priorB=double.PositiveInfinity,priorMu=0;
            for(var index=0;index<record.Rows.Count;index++){
                var row=record.Rows[index];var first=index==0;var count=first?4:3;
                if(row.LineNumber<=priorLine||row.LineNumber>2_000_000||row.Values.Count!=count)return false;
                var n=new double[count];for(var i=0;i<count;i++)if(row.Values[i].Length>64||!EclipsePvtwReader.Number(row.Values[i],out n[i]))return false;
                if(first){if(n[0]<0||n[0]<=priorRs||n[1]<=priorBubble)return false;priorRs=n[0];priorBubble=n[1];}
                var start=first?1:0;
                if(n[start]<=priorP||n[start+1]<=0||n[start+1]>=priorB||n[start+1]>10||n[start+2]<=0||n[start+2]<priorMu||n[start+2]>1000)return false;
                priorP=n[start];priorB=n[start+1];priorMu=n[start+2];priorLine=row.LineNumber;
            }
        }return true;
    }
}
