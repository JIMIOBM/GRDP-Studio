using System.Globalization;
using System.Text.RegularExpressions;
using Grdp.SoftwareIntegration.Worker.Contracts;

namespace Grdp.SoftwareIntegration.Worker.Inspection;

// A single explicit E100 water region. Unsupported defaults/overlays never gain editing metadata.
internal static class EclipsePvtwReader
{
    internal static EclipsePvtwContext? Read(string text, string sourceFile)
    {
        var lines = Regex.Split(text, "\\r\\n|\\r|\\n").Select(l => l.Split("--", 2)[0].Trim()).ToArray();
        int[] Find(string key) => lines.Select((line,i)=>(line,i)).Where(x=>Regex.IsMatch(x.line,"^"+key+@"(?:\s+=+)?$",RegexOptions.IgnoreCase|RegexOptions.CultureInvariant)).Select(x=>x.i).ToArray();
        if (new[] {"INCLUDE","LGR","ENDLGR","COMPS","THERMAL","PVTWSALT","PVTWALT","PVTNUM","EDIT","EQUALS","MULTIPLY","COPY","BOX","ENDBOX","PVTO","PVTG","PVTWEND"}.Any(k=>lines.Any(line=>Regex.IsMatch(line,"^"+k+@"(?:\s|$)",RegexOptions.IgnoreCase|RegexOptions.CultureInvariant)))
            || Find("WATER").Length!=1 || Find("OIL").Length!=0 || Find("GAS").Length!=0) return null;
        var pvt=Find("PVTW");var tabs=Find("TABDIMS");var props=Find("PROPS");var regions=Find("REGIONS");
        if(pvt.Length!=1 || tabs.Length!=1 || props.Length!=1 || regions.Length!=1 || !lines[pvt[0]].Equals("PVTW",StringComparison.OrdinalIgnoreCase) || !lines[tabs[0]].Equals("TABDIMS",StringComparison.OrdinalIgnoreCase) || !(tabs[0]<props[0] && props[0]<pvt[0] && pvt[0]<regions[0]))return null;
        string? Row(int index,out int line) { line=0;for(var i=index+1;i<lines.Length;i++) { if(lines[i].Length==0)continue;line=i+1;return lines[i]; }return null; }
        var tab=Row(tabs[0],out _);if(tab is null || !Regex.IsMatch(tab,@"^1\s+1\s+[1-9][0-9]*(?:\s+[1-9][0-9]*){3}\s*/$",RegexOptions.CultureInvariant))return null;
        var row=Row(pvt[0],out var sourceLine);if(row is null)return null;
        var parts=Regex.Matches(row,@"[^\s/]+|/",RegexOptions.CultureInvariant).Select(m=>m.Value).ToArray();
        if(parts.Length!=6 || parts[^1]!="/" || parts[..5].Any(v=>!Number(v,out _)))return null;
        var values=parts[..5];var n=values.Select(v=>{Number(v,out var x);return x;}).ToArray();
        if(n[0]<0 || n[1]<=0 || n[2]<0 || n[3]<=0 || n[4]<0)return null;
        // Exactly one record: a numeric continuation is a second region, not an ignored row.
        var next=Row(sourceLine-1,out _);if(next is null || !Regex.IsMatch(next,@"^[A-Za-z][A-Za-z0-9-]*(?:\s+=+)?$",RegexOptions.CultureInvariant))return null;
        return new("eclipse-pvtw-context/1",sourceFile,sourceLine,values);
    }
    internal static bool Number(string s,out double v) { v=0;return Regex.IsMatch(s,@"^[+-]?(?:[0-9]+(?:\.[0-9]*)?|\.[0-9]+)(?:[eEdD][+-]?[0-9]+)?$",RegexOptions.CultureInvariant)&&double.TryParse(s.Replace('D','E').Replace('d','e'),NumberStyles.Float,CultureInfo.InvariantCulture,out v)&&double.IsFinite(v); }
}
