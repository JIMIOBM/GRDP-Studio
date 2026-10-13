using System.Globalization;
using System.Text.RegularExpressions;
using Grdp.SoftwareIntegration.Worker.Contracts;
namespace Grdp.SoftwareIntegration.Worker.Execution;

// One ORAT node with literal FLD production leaves; existing fixed OIL guides or one leaf downtime factor.
internal static class EclipseGroupStructureReader
{
    internal sealed record Target(EclipseGroupStructureControl Control,int Start,int Length);
    private sealed record Token(string Value,int Start,int Length,int Count=1);
    private sealed record Record(List<Token> Values,int Line);
    private static MatchCollection Keywords(string text,string names)=>Regex.Matches(text,
        "(?im)^[ \\t]*("+names+")[ \\t]*(?:--[^\\r\\n]*|=+[ \\t]*(?:--[^\\r\\n]*)?)?\\r?$",RegexOptions.CultureInvariant);
    private static bool Name(string s)=>Regex.IsMatch(s,"^[A-Za-z0-9][A-Za-z0-9:_ .-]{0,7}$");
    private static bool Number(string s,out double n)=>double.TryParse(s,NumberStyles.Float,CultureInfo.InvariantCulture,out n) && double.IsFinite(n) && n>0 && n<=100_000_000;
    internal static IReadOnlyList<Target> Read(string text,string source)
    {try{return ReadCore(text,source);}catch(FormatException){return [];}}
    private static IReadOnlyList<Target> ReadCore(string text,string source)
    {
        if(text.Length>4*1024*1024 || EclipseIncludeParser.Parse(text).Count!=0 || Keywords(text,"FIELD|METRIC").Count!=1
            || Keywords(text,"LAB|PVT-M|RESTART|COMPS|THERMAL|TEMP|WELSPECL|GUIDERAT|WEFAC|GCONINJE|GCONSUMP|GCONSALE|GCONPRI|GCONCAL|GECON|GCONTOL|GRUPTARG|WELTARG|WELOPEN|WCONHIST|WCONINJH|WELCNTL|WELPI|WHISTCTL|ACTION|ACTIONX|UDQ|UDADIMS|PRORDER|WLIST|GRUPRIG|WDRILTIM|WELSEGS|VFPPROD|VFPINJ|NETWORK|BRANPROP|NODEPROP|GRUPNET|GNETPUMP|GLIFTOPT|WLIFTOPT|GSSCPTST|GCUTBACK|WCUTBACK|CECON|COMPLUMP|WPLUG|WORKLIM").Count!=0)return [];
        var schedule=Keywords(text,"SCHEDULE");if(schedule.Count!=1)return [];
        var start=schedule[0].Index+schedule[0].Length;
        if(Keywords(text[start..],"G[A-Z0-9-]+|COMPDATM|WECON|WECONINJ|WECONCMF|WTEST|WELDRAW|WMULT|WSEGITER|WELLOPEN").Cast<Match>()
            .Any(m=>m.Groups[1].Value.ToUpperInvariant() is not ("GRUPTREE" or "GCONPROD" or "GEFAC")))return [];
        var times=Keywords(text[start..],"DATES|TSTEP|TIME|END");var end=times.Count==0?text.Length:start+times[0].Index;
        foreach(var keyword in new[]{"WELSPECS","COMPDAT","WCONPROD","WCONINJE","GRUPTREE","GCONPROD","WGRUPCON","GEFAC"})
            if(Keywords(text[end..],keyword).Count!=0)return [];
        var specs=Records(text,start,end,"WELSPECS");var producers=Records(text,start,end,"WCONPROD");var injectors=Records(text,start,end,"WCONINJE");
        var completions=Records(text,start,end,"COMPDAT");var tree=Records(text,start,end,"GRUPTREE");var groups=Records(text,start,end,"GCONPROD");
        var guides=Records(text,start,end,"WGRUPCON");var factors=Records(text,start,end,"GEFAC");
        if(specs.Count==0 || specs.Any(r=>r.Values.Count<2 || !Name(r.Values[0].Value) || !Name(r.Values[1].Value))
            || specs.Select(r=>r.Values[0].Value).Distinct().Count()!=specs.Count
            || tree.Count<2 || tree.Count>500 || tree.Any(r=>r.Values.Count!=2 || !Name(r.Values[0].Value) || !Name(r.Values[1].Value) || r.Values[0].Value=="FIELD" || r.Values[1].Value=="FIELD")
            || tree.Select(r=>r.Values[0].Value).Distinct().Count()!=tree.Count || tree.Select(r=>r.Values[1].Value).Distinct().Count()!=1)return [];
        var parent=tree[0].Values[1].Value;var leafNames=tree.Select(r=>r.Values[0].Value).ToArray();
        if(leafNames.Contains(parent) || specs.Any(r=>r.Values[1].Value==parent) || groups.Count!=tree.Count+1
            || groups.Any(r=>r.Values.Count<2 || !Name(r.Values[0].Value)) || groups.Select(r=>r.Values[0].Value).Distinct().Count()!=groups.Count)return [];
        var parents=groups.Where(r=>r.Values[0].Value==parent).ToArray();if(parents.Length!=1)return [];
        var pv=parents[0].Values;
        if(pv.Count<3 || pv.Count>21 || pv[1].Value!="ORAT" || pv[2].Count!=1 || !Number(pv[2].Value,out var target) || pv.Skip(3).Any(t=>t.Value.Length!=0))return [];
        if(guides.Count>1000 || factors.Count>1 || guides.Count>0 && factors.Count>0)return [];
        var guideMap=new Dictionary<string,(double Value,Record Source)>();
        foreach(var guide in guides)
        {
            var v=guide.Values;
            if(v.Count<4 || v.Count>5 || !Name(v[0].Value) || v[1].Value!="YES" || v[2].Count!=1 || !Number(v[2].Value,out var value)
                || v[3].Value!="OIL" || v.Skip(4).Any(t=>t.Value.Length!=0 && t.Value!="1") || !guideMap.TryAdd(v[0].Value,(value,guide)))return [];
        }
        var factorMap=new Dictionary<string,(double Value,Record Source)>();
        foreach(var factor in factors)
        {
            var v=factor.Values;
            if(v.Count<2 || v.Count>3 || !leafNames.Contains(v[0].Value) || v[1].Count!=1 || !Number(v[1].Value,out var value) || value>1
                || v.Skip(2).Any(t=>t.Value.Length!=0 && t.Value!="YES") || !factorMap.TryAdd(v[0].Value,(value,factor)))return [];
        }
        var leaves=new List<EclipseGroupStructureLeaf>();var names=new HashSet<string>();
        foreach(var leaf in leafNames)
        {
            var controls=groups.Where(r=>r.Values[0].Value==leaf).ToArray();
            if(controls.Length!=1 || controls[0].Values[1].Value!="FLD" || controls[0].Values.Skip(2).Any(t=>t.Value.Length!=0))return [];
            var members=specs.Where(r=>r.Values[1].Value==leaf).ToArray();if(members.Length<2 || members.Length>1000)return [];
            var entries=new List<EclipseGroupStructureMember>();
            foreach(var member in members)
            {
                var well=member.Values[0].Value;var wells=producers.Where(r=>r.Values.Count>0 && r.Values[0].Value==well).ToArray();
                if(wells.Length!=1 || injectors.Any(r=>r.Values.Count>0 && r.Values[0].Value==well))return [];
                var c=wells[0].Values;
                if(c.Count<9 || c.Count>12 || c[1].Value!="OPEN" || c[2].Value!="GRUP" || !Number(c[3].Value,out var oil)
                    || c.Skip(4).Take(4).Any(t=>t.Value.Length!=0) || !Number(c[8].Value,out var bhp) || c.Skip(9).Any(t=>t.Value.Length!=0 && t.Value!="0"))return [];
                var cs=completions.Where(r=>r.Values.Count>0 && r.Values[0].Value==well).ToArray();
                if(cs.Length==0 || cs.Any(r=>r.Values.Count<6 || r.Values[5].Value!="OPEN"))return [];
                entries.Add(new(well,bhp,oil,guideMap.TryGetValue(well,out var guide)?guide.Value:0));if(!names.Add(well))return [];
            }
            leaves.Add(new(leaf,factorMap.TryGetValue(leaf,out var factor)?factor.Value:1,entries));
        }
        if(names.Count>1000 || producers.Count!=names.Count || guides.Count>0 && (guides.Count!=names.Count || guideMap.Keys.Any(k=>!names.Contains(k))))return [];
        var result=new List<Target>();
        var rateUnit=Keywords(text,"FIELD").Count==1?"STB/day":"sm3/day";
        result.Add(new(new(parent,parent,"GCONPROD",source,parents[0].Line,rateUnit,target,target,leaves),pv[2].Start,pv[2].Length));
        foreach(var guide in guides)
        {
            var v=guide.Values;var value=guideMap[v[0].Value].Value;
            result.Add(new(new(parent,v[0].Value,"WGRUPCON",source,guide.Line,"dimensionless",value,target,leaves),v[2].Start,v[2].Length));
        }
        foreach(var factor in factors)
        {
            var v=factor.Values;var value=factorMap[v[0].Value].Value;
            result.Add(new(new(parent,v[0].Value,"GEFAC",source,factor.Line,"dimensionless",value,target,leaves),v[1].Start,v[1].Length));
        }
        return result;
    }
    private static List<Record> Records(string text, int start, int end, string keyword)
    {
        var result = new List<Record>();
        foreach (Match block in Keywords(text[start..end], keyword))
        {
            var offset = start + block.Index + block.Length;
            var tokens = new List<Token>(); bool terminated = false; int line = 0;
            foreach (Match lex in Regex.Matches(text[offset..end], """--[^\r\n]*|'(?:[^']|'')*'|"(?:[^"]|"")*"|/|[^\s/]+"""))
            {
                if (lex.Value.StartsWith("--", StringComparison.Ordinal)) continue;
                if (lex.Value == "/")
                {
                    if (tokens.Count == 0) { terminated = true; break; }
                    result.Add(new(tokens, line)); tokens = []; if (result.Count > 4000) throw new FormatException();
                    continue;
                }
                var value = lex.Value;
                if (value.Length >= 2 && value[0] is '\'' or '"' && value[^1] == value[0]) value = value[1..^1];
                if (tokens.Count == 0) line = 1 + Regex.Matches(text[..(offset + lex.Index)], "\r\n|\r|\n").Count;
                int count = 1;
                if (tokens.Count > 0 && Regex.Match(value, "^(\\d+)\\*(.*)$") is { Success: true } repeat)
                {
                    if (!int.TryParse(repeat.Groups[1].Value, out count) || count < 1 || count > 128) throw new FormatException();
                    value = repeat.Groups[2].Value;
                }
                if (tokens.Count + count > 128) throw new FormatException();
                for (int i = 0; i < count; i++) tokens.Add(new(value, offset + lex.Index, lex.Length, count));
            }
            if (!terminated) throw new FormatException();
        }
        return result;
    }
}
