using System.Globalization;
using System.Text.RegularExpressions;
using Grdp.SoftwareIntegration.Worker.Contracts;
namespace Grdp.SoftwareIntegration.Worker.Execution;

// Existing literal water OPEN/RATE controls, including the complete initial and dated override chain.
internal static class EclipseWaterInjectionPlanReader
{
    internal sealed record Target(EclipseWaterInjectionPlanRecord Record,int Start,int Length);
    internal sealed record Snapshot(EclipseWaterInjectionControlPlan Plan,IReadOnlyList<Target> Targets);
    private sealed record Token(string Value,int Start,int Length,int Count=1);
    private sealed record Row(List<Token> Values,int Line);
    private sealed record Block(string Name,int Start,int BodyStart,int End);
    private static bool Name(string s)=>Regex.IsMatch(s,"^[A-Za-z0-9][A-Za-z0-9:_ .-]{0,7}$");
    private static bool Positive(string s,out double n)=>double.TryParse(s,NumberStyles.Float,CultureInfo.InvariantCulture,out n) && double.IsFinite(n) && n>0 && n<=100_000_000;
    internal static Snapshot? Read(string text,string source)
    {try{return ReadCore(text,source);}catch(FormatException){return null;}}
    private static Snapshot? ReadCore(string text,string source)
    {
        if(text.Length>4*1024*1024 || EclipseIncludeParser.Parse(text).Count!=0)return null;
        var headings=Regex.Matches(text,"(?im)^[ \\t]*([A-Z][A-Z0-9-]*)[ \\t]*(?:--[^\\r\\n]*|=+[ \\t]*(?:--[^\\r\\n]*)?)?\\r?$").Cast<Match>().ToArray();
        var blocks=headings.Select((m,n)=>new Block(m.Groups[1].Value.ToUpperInvariant(),m.Index,m.Index+m.Length,n+1<headings.Length?headings[n+1].Index:text.Length)).ToArray();
        var units=blocks.Where(b=>b.Name is "FIELD" or "METRIC").ToArray();var starts=blocks.Where(b=>b.Name=="START").ToArray();var schedules=blocks.Where(b=>b.Name=="SCHEDULE").ToArray();
        if(units.Length!=1 || starts.Length!=1 || schedules.Length!=1 || starts[0].Start>schedules[0].Start
            || blocks.Any(b=>b.Name is "PATHS" or "ENDINC" or "SKIP" or "SKIP100" or "SKIP300" or "ENDSKIP" or "LAB" or "PVT-M" or "RESTART" or "COMPS" or "THERMAL" or "TEMP" or "TSTEP" or "TIME" or "ACTION" or "ACTIONX" or "UDQ" or "UDADIMS"))return null;
        var first=Rows(text,starts[0],single:true);if(first.Count!=1 || !Date(first[0].Values,out var initial))return null;
        var schedule=blocks.Where(b=>b.Start>schedules[0].Start).ToArray();
        if(schedule.Any(b=>b.Name.StartsWith('G') || b.Name.StartsWith('W') && b.Name is not ("WELSPECS" or "WCONPROD" or "WCONINJE")
            || b.Name.StartsWith("COMP",StringComparison.Ordinal) && b.Name!="COMPDAT") || schedule.Count(b=>b.Name=="END")!=1)return null;
        var dates=schedule.Where(b=>b.Name=="DATES").ToArray();if(dates.Length<2)return null;
        var firstDate=dates[0].Start;
        if(schedule.Any(b=>b.Start>firstDate && b.Name is "WELSPECS" or "COMPDAT" or "WCONPROD"))return null;
        var specs=schedule.Where(b=>b.Name=="WELSPECS").SelectMany(b=>Rows(text,b)).ToArray();
        var completions=schedule.Where(b=>b.Name=="COMPDAT").SelectMany(b=>Rows(text,b)).ToArray();
        var producers=schedule.Where(b=>b.Name=="WCONPROD").SelectMany(b=>Rows(text,b)).ToArray();
        if(specs.Length==0 || specs.Any(r=>r.Values.Count<2 || !Name(r.Values[0].Value)) || specs.Select(r=>r.Values[0].Value).Distinct().Count()!=specs.Length
            || producers.Any(r=>r.Values.Count==0 || !Name(r.Values[0].Value)))return null;
        var wells=specs.Select(r=>r.Values[0].Value).ToHashSet();var productionNames=producers.Select(r=>r.Values[0].Value).ToHashSet();
        var result=new List<Target>();var seen=new HashSet<(string,int)>();var initialWells=new HashSet<string>();var datedTimes=new HashSet<int>();var current=initial;
        foreach(var block in schedule)
        {
            if(block.Name=="DATES")
            {
                var rows=Rows(text,block);if(rows.Count==0)return null;
                foreach(var row in rows){if(!Date(row.Values,out var d) || d<=current)return null;current=d;}
            }
            if(block.Name!="WCONINJE")continue;
            var records=Rows(text,block);if(records.Count==0)return null;
            foreach(var r in records)
            {
                var v=r.Values;var time=current.DayNumber-initial.DayNumber;
                if(v.Count<5 || !Name(v[0].Value) || !wells.Contains(v[0].Value) || productionNames.Contains(v[0].Value)
                    || !seen.Add((v[0].Value,time)))return null;
                if(v[1].Value is not ("WAT" or "WATER"))
                {
                    if(time!=0 || v[1].Value is not ("GAS" or "OIL") || v[2].Value!="OPEN" || v[3].Value!="RATE"
                        || !Positive(v[4].Value,out _))return null;
                    continue;
                }
                if(v.Count<7 || v.Count>10 || v[2].Value!="OPEN" || v[3].Value!="RATE" || v[4].Count!=1
                    || !Positive(v[4].Value,out var rate) || v[5].Value.Length!=0 || v[6].Count!=1
                    || !Positive(v[6].Value,out var bhp) || v.Skip(7).Any(t=>t.Value.Length!=0 && t.Value!="0")
                    || time>0 && !initialWells.Contains(v[0].Value))return null;
                var cs=completions.Where(c=>c.Values.Count>0 && c.Values[0].Value==v[0].Value).ToArray();
                if(cs.Length==0 || cs.Any(c=>c.Values.Count<6 || c.Values[5].Value!="OPEN"))return null;
                if(time==0)initialWells.Add(v[0].Value);else datedTimes.Add(time);
                result.Add(new(new(v[0].Value,source,r.Line,current.ToString("yyyy-MM-dd",CultureInfo.InvariantCulture),time,
                    units[0].Name=="FIELD"?"STB/day":"sm3/day",rate,bhp),v[4].Start,v[4].Length));
            }
        }
        var end=current.DayNumber-initial.DayNumber;
        if(end is < 1 or > 3_000_000 || initialWells.Count==0 || initialWells.Count>1000 || datedTimes.Count<2 || result.Count>1000 || result.Count==0
            || result.Any(r=>r.Record.TimeDays>=end) || result.Select(r=>r.Record.LineNumber).Distinct().Count()!=result.Count)return null;
        return new(new(initial.ToString("yyyy-MM-dd",CultureInfo.InvariantCulture),end,result.Select(t=>t.Record).ToArray()),result);
    }
    private static bool Date(List<Token> v,out DateOnly date)
    {
        date=default;
        if(v.Count is not (3 or 4) || v.Any(t=>t.Count!=1) || v.Count==4 && v[3].Value is not ("" or "00:00:00")
            || !int.TryParse(v[0].Value,out var day) || !int.TryParse(v[2].Value,out var year) || v[2].Value.Length!=4 || year<1000 || year>9999)return false;
        var month=Array.IndexOf(new[]{"JAN","FEB","MAR","APR","MAY","JUN","JUL","AUG","SEP","OCT","NOV","DEC"},v[1].Value=="JLY"?"JUL":v[1].Value)+1;
        if(month==0 || day<1 || day>DateTime.DaysInMonth(year,month))return false;date=new(year,month,day);return true;
    }
    private static List<Row> Rows(string text,Block block,bool single=false)
    {
        var result=new List<Row>();var tokens=new List<Token>();var line=0;var terminated=false;
        foreach(Match m in Regex.Matches(text[block.BodyStart..block.End],"""--[^\r\n]*|'(?:[^']|'')*'|"(?:[^"]|"")*"|/|[^\s/]+"""))
        {
            if(m.Value.StartsWith("--",StringComparison.Ordinal))continue;
            if(m.Value=="/")
            {
                if(tokens.Count==0){terminated=true;break;}
                result.Add(new(tokens,line));tokens=[];
                if(single){terminated=true;break;}
                if(result.Count>4000)throw new FormatException();continue;
            }
            var value=m.Value;if(value.Length>=2 && value[0] is '\'' or '"' && value[^1]==value[0])value=value[1..^1];
            if(tokens.Count==0)line=1+Regex.Matches(text[..(block.BodyStart+m.Index)],"\r\n|\r|\n").Count;
            var count=1;
            if(tokens.Count>0 && Regex.Match(value,"^(\\d+)\\*(.*)$") is {Success:true} repeated)
            {if(!int.TryParse(repeated.Groups[1].Value,out count) || count<1 || count>128)throw new FormatException();value=repeated.Groups[2].Value;}
            if(tokens.Count+count>128)throw new FormatException();for(var n=0;n<count;n++)tokens.Add(new(value,block.BodyStart+m.Index,m.Length,count));
        }
        if(!terminated || tokens.Count!=0)throw new FormatException();return result;
    }
}
