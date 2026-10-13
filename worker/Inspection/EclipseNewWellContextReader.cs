using System.Globalization;
using System.Text.RegularExpressions;
using Grdp.SoftwareIntegration.Worker.Contracts;

namespace Grdp.SoftwareIntegration.Worker.Inspection;

// Deliberately small source grammar: single water deck, initial direct groups,
// one time-step block, no subsequent well overrides or implicit group controls.
internal static class EclipseNewWellContextReader
{
    internal static EclipseNewWellContext? Read(string text, string sourceFile)
    {
        var lines = Regex.Split(text, "\\r\\n|\\r|\\n").Select(l => l.Split("--", 2)[0].Trim()).ToArray();
        int[] Find(string key) => lines.Select((line, i) => (line, i)).Where(x => Regex.IsMatch(x.line, "^"+key+@"(?:\s+=+)?$", RegexOptions.IgnoreCase | RegexOptions.CultureInvariant)).Select(x => x.i).ToArray();
        if (new[] { "INCLUDE","LGR","ENDLGR","COMPS","THERMAL","GRUPTREE","WELSPECL","WELSEGS","WSEGITER","COMPORD" }.Any(k => Find(k).Length > 0)) return null;
        if (Find("WATER").Length != 1 || Find("OIL").Length != 0 || Find("GAS").Length != 0) return null;
        var schedule=Find("SCHEDULE");var steps=Find("TSTEP");var dims=Find("WELLDIMS");var specs=Find("WELSPECS");var completions=Find("COMPDAT");
        if (schedule.Length!=1 || steps.Length!=1 || dims.Length!=1 || specs.Length!=1 || completions.Length!=1
            || !(dims[0]<schedule[0] && schedule[0]<specs[0] && specs[0]<completions[0] && completions[0]<steps[0])) return null;
        var allowed=new HashSet<string>(["RPTSCHED","WELSPECS","COMPDAT","WCONPROD","WCONINJE","WTRACER","TSTEP","END"],StringComparer.OrdinalIgnoreCase);
        for(var i=schedule[0]+1;i<lines.Length;i++)
            if(Regex.IsMatch(lines[i],"^[A-Za-z][A-Za-z0-9-]*$",RegexOptions.CultureInvariant)
                && (!allowed.Contains(lines[i]) || i>steps[0] && !lines[i].Equals("END",StringComparison.OrdinalIgnoreCase))) return null;
        string Body(int start) {
            var result="";
            for(var i=start+1;i<lines.Length;i++) {result+=" "+lines[i];if(lines[i].Contains('/'))return result;}
            return "";
        }
        // New wells must be observable in actual outputs; requesting only an
        // existing well cannot be used as proof of a newly created producer.
        foreach(var key in new[] { "WWPR","WBHP","WWPT","WSTAT" }) {
            var found=Find(key);
            if(found.Length!=1 || found[0]>=schedule[0] || !Regex.IsMatch(Body(found[0]),@"^\s*'\*'\s*/\s*$",RegexOptions.CultureInvariant))return null;
        }
        var capacity=Regex.Match(Body(dims[0]),@"^\s*([1-9][0-9]*)\s+([1-9][0-9]*)\s+([1-9][0-9]*)\s+([1-9][0-9]*)\s*/\s*$",RegexOptions.CultureInvariant);
        if(!capacity.Success)return null;
        var numbers=new int[4];
        for(var i=0;i<4;i++)if(!int.TryParse(capacity.Groups[i+1].Value,NumberStyles.None,CultureInfo.InvariantCulture,out numbers[i]) || numbers[i]>100_000)return null;
        return new("eclipse-new-well-context/1",sourceFile,steps[0]+1,numbers[0],numbers[1],numbers[2],numbers[3]);
    }
}
