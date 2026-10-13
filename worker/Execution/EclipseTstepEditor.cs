using System.Globalization;
using System.Text;
using System.Text.RegularExpressions;
namespace Grdp.SoftwareIntegration.Worker.Execution;
internal static class EclipseTstepEditor
{
    internal static async Task<string> ApplyAsync(EclipseDeckPackage package,EclipseTstepParameters p,CancellationToken cancellation)
    {
        if(package.Files.Count!=1 || Path.GetFileName(package.MainFile)!=p.SourceFile || !EclipseTstepParameters.ValidSteps(p.OriginalSteps) || !EclipseTstepParameters.ValidSteps(p.TargetSteps)
            || p.OriginalSteps.Sum()!=p.TargetSteps.Sum() || p.OriginalSteps.SequenceEqual(p.TargetSteps))Fail();
        var bytes=await File.ReadAllBytesAsync(package.MainFile,cancellation);var encoding=new UTF8Encoding(false,true);var text=encoding.GetString(bytes);
        if(!bytes.SequenceEqual(encoding.GetBytes(text)) || EclipseIncludeParser.Parse(text).Count!=0 || Keyword(text,"FIELD|METRIC|PVT-M").Count!=1
            || Keyword(text,"LAB|DATES|TIME|RESTART|ACTION|ACTIONX").Count!=0)Fail();
        var schedule=Keyword(text,"SCHEDULE");var end=Keyword(text,"END");
        if(schedule.Count!=1 || end.Count!=1 || schedule[0].Index>=end[0].Index)Fail();
        int offset=-1,length=0;var total=0;
        foreach(Match block in Keyword(text,"TSTEP")) {
            if(block.Index<=schedule[0].Index || block.Index>=end[0].Index)Fail();
            var body=Regex.Match(text[(block.Index+block.Length)..],"\\A\\s*(?<values>(?:[+]?[0-9]+(?:\\*[+]?[0-9]+)?\\s+)*[+]?[0-9]+(?:\\*[+]?[0-9]+)?)\\s*/");
            if(!body.Success)Fail();var steps=new List<int>();
            foreach(var token in body.Groups["values"].Value.Split((char[]?)null,StringSplitOptions.RemoveEmptyEntries)) {
                var parts=token.Split('*');if(!int.TryParse(parts[^1],NumberStyles.AllowLeadingSign,CultureInfo.InvariantCulture,out var value) || value<=0 || value>100000)Fail();
                var count=1;if(parts.Length>1 && (!int.TryParse(parts[0],out count) || count<=0 || count>1000))Fail();
                if(steps.Count+count>1000)Fail();for(var n=0;n<count;n++)steps.Add(value);
            }
            if(!EclipseTstepParameters.ValidSteps(steps))Fail();total+=steps.Sum();if(total>100000)Fail();
            var line=1+Regex.Matches(text[..block.Index],"\\r\\n|\\r|\\n").Count;
            if(line!=p.LineNumber)continue;
            if(!steps.SequenceEqual(p.OriginalSteps) || offset>=0)Fail();offset=block.Index+block.Length+body.Groups["values"].Index;length=body.Groups["values"].Length;
        }
        if(offset<0)Fail();var updated=text[..offset]+string.Join(" ",p.TargetSteps)+text[(offset+length)..];
        var temporary=package.MainFile+"."+Guid.NewGuid().ToString("N")+".tmp";
        try { await File.WriteAllBytesAsync(temporary,encoding.GetBytes(updated),cancellation);cancellation.ThrowIfCancellationRequested();File.Move(temporary,package.MainFile,true); }
        finally { if(File.Exists(temporary))File.Delete(temporary); }
        return $"Changed TSTEP reports at {p.SourceFile}:{p.LineNumber}; duration {p.TargetSteps.Sum()} days and following control times retained.";
    }
    private static MatchCollection Keyword(string text,string names)=>Regex.Matches(text,"(?im)^[ \\t]*("+names+")[ \\t]*(?:--[^\\r\\n]*|=+[ \\t]*(?:--[^\\r\\n]*)?)?\\r?$",RegexOptions.CultureInvariant);
    private static void Fail()=>throw new EclipseDeckPackageException("ECLIPSE_TSTEP_MISMATCH","TSTEP editing requires one DATA in day units, positive integer report intervals, exact original source binding and unchanged block duration. Mixed DATES, includes, restart, dynamic actions and nonnumeric records are not supported.");
}
