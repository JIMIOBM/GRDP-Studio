using System.Globalization;
using System.Text;
using System.Text.RegularExpressions;
using Grdp.SoftwareIntegration.Worker.Contracts;

namespace Grdp.SoftwareIntegration.Worker.Execution;

internal static class EclipseThermalRockReader
{
    internal sealed record Target(EclipseThermalRockRecord Record, int Start, int Length);
    internal sealed record Snapshot(EclipseThermalRockContext Context, byte[] SourceBytes, IReadOnlyList<Target> Targets);
    private sealed record Block(string Name, string Section, int Start, int Body, int End);
    internal static Snapshot? Read(EclipseDeckPackage package)
    {
        var injection = EclipseThermalInjectionReader.Read(package);
        if (injection is null) return null;
        var bytes = injection.SourceBytes;
        var text = new UTF8Encoding(false,true).GetString(bytes);
        var headers = Regex.Matches(text,"(?im)^[ \\t]*([A-Z][A-Z0-9-]*)[ \\t]*(?:--[^\\r\\n]*|=+[ \\t]*(?:--[^\\r\\n]*)?)?\\r?$").ToArray();
        var section = ""; var blocks = new List<Block>();
        string[] sections = ["RUNSPEC","GRID","EDIT","PROPS","REGIONS","SOLUTION","SUMMARY","SCHEDULE"];
        for (var j=0;j<headers.Length;j++)
        {
            var h=headers[j];var name=h.Groups[1].Value.ToUpperInvariant();
            if (sections.Contains(name)) section=name;
            blocks.Add(new(name,section,h.Index,h.Index+h.Length,j+1<headers.Length?headers[j+1].Index:text.Length));
        }
        if (blocks.Any(b=>b.Name is "BOX" or "ENDBOX" or "THCROCK" or "THCOIL" or "THCGAS" or "THCWATER" or "THCSOLID" or "THCONSF" or "HEATCRT")) return null;
        var dimens=blocks.Where(b=>b.Name=="DIMENS").ToArray();
        if (dimens.Length!=1 || dimens[0].Section!="RUNSPEC") return null;
        var dimensionText=Regex.Replace(text[dimens[0].Body..dimens[0].End],"--[^\\r\\n]*","");
        var dims=Regex.Match(dimensionText,"^\\s*([1-9][0-9]{0,5})\\s+([1-9][0-9]{0,5})\\s+([1-9][0-9]{0,5})\\s*/\\s*$");
        if (!dims.Success) return null;
        long cells=1;for(var j=1;j<=3;j++) cells*=int.Parse(dims.Groups[j].Value,CultureInfo.InvariantCulture);
        if (cells is < 1 or > 250000) return null;
        var nx=int.Parse(dims.Groups[1].Value,CultureInfo.InvariantCulture);var ny=int.Parse(dims.Groups[2].Value,CultureInfo.InvariantCulture);var nz=int.Parse(dims.Groups[3].Value,CultureInfo.InvariantCulture);
        var mask=Enumerable.Repeat(1,(int)cells).ToArray();var masks=blocks.Where(b=>b.Name=="ACTNUM").ToArray();
        if(masks.Length>1)return null;
        if(masks.Length==1){
            var b=masks[0];if(b.Section!="GRID")return null;
            var tokens=Regex.Matches(text[b.Body..b.End],"--[^\\r\\n]*|/|[^\\s/]+").Cast<Match>().Where(m=>!m.Value.StartsWith("--",StringComparison.Ordinal)).ToArray();
            if(tokens.Length<2||tokens[^1].Value!="/")return null;
            int ordinal=0;
            foreach(var token in tokens[..^1]){
                var value=Regex.Match(token.Value,"^(?:(?<count>[1-9][0-9]{0,5})\\*)?(?<value>[01])$");if(!value.Success)return null;
                var count=value.Groups["count"].Success?int.Parse(value.Groups["count"].Value,CultureInfo.InvariantCulture):1;
                if(ordinal+count>cells)return null;for(int j=0;j<count;j++)mask[ordinal++]=int.Parse(value.Groups["value"].Value,CultureInfo.InvariantCulture);
            }
            if(ordinal!=cells)return null;
        }
        if(!mask.Any(v=>v==1))return null;
        // Other operators and output syntax must not hide an unqualified property or activity override.
        foreach(var b in blocks.Where(b=>b.Name is not ("EQUALS" or "HEATCR" or "THCONR" or "ACTNUM")))
            if(Regex.IsMatch(Regex.Replace(text[b.Body..b.End],"--[^\\r\\n]*",""),"(?i)(?<![A-Z0-9_])(HEATCR|THCONR|ACTNUM)(?![A-Z0-9_])"))return null;
        foreach (var b in blocks.Where(b=>b.Name is "EQUALS" or "COPY" or "MULTIPLY" or "ADD" or "OPERATE" or "OPERATER" or "MULTIREG" or "EQUALREG" or "ADDREG")){
            var body=Regex.Replace(text[b.Body..b.End],"--[^\\r\\n]*","");
            if(Regex.IsMatch(body,"(?i)(?<![A-Z0-9_])ACTNUM(?![A-Z0-9_])"))return null;
            if(!Regex.IsMatch(body,"(?i)(?<![A-Z0-9_])(HEATCR|THCONR)(?![A-Z0-9_])"))continue;
            if(b.Name!="EQUALS"||b.Section is not ("GRID" or "EDIT"))return null;
            foreach(var row in body.Split('/')){
                var tokens=Regex.Matches(row,"'[^']*'|\"[^\"]*\"|[^\\s]+").Cast<Match>().Select(m=>m.Value.Trim('\'','"').Trim().ToUpperInvariant()).ToArray();
                if(!tokens.Any(v=>v is "HEATCR" or "THCONR"))continue;
                if(tokens.Length!=8||tokens[0] is not ("HEATCR" or "THCONR")||!double.TryParse(tokens[1],NumberStyles.Float,CultureInfo.InvariantCulture,out var v)||v!=0)return null;
                var bounds=new int[6];for(int j=0;j<6;j++)if(!int.TryParse(tokens[j+2],NumberStyles.None,CultureInfo.InvariantCulture,out bounds[j]))return null;
                if(bounds[0]<1||bounds[1]<bounds[0]||bounds[1]>nx||bounds[2]<1||bounds[3]<bounds[2]||bounds[3]>ny||bounds[4]<1||bounds[5]<bounds[4]||bounds[5]>nz)return null;
                for(int k=bounds[4];k<=bounds[5];k++)for(int j=bounds[2];j<=bounds[3];j++)for(int i=bounds[0];i<=bounds[1];i++)if(mask[(k-1)*nx*ny+(j-1)*nx+i-1]!=0)return null;
            }
        }
        var targets=new List<Target>();
        foreach(var field in new[]{"HEATCR","THCONR"})
        {
            var candidates=blocks.Where(b=>b.Name==field).ToArray();
            if(candidates.Length!=1 || candidates[0].Section!="GRID") return null;
            var b=candidates[0];var tokens=Regex.Matches(text[b.Body..b.End],"--[^\\r\\n]*|/|[^\\s/]+").Cast<Match>().Where(m=>!m.Value.StartsWith("--",StringComparison.Ordinal)).ToArray();
            if(tokens.Length!=2 || tokens[1].Value!="/") return null;
            var token=tokens[0];var repeated=Regex.Match(token.Value,"^(?<n>[1-9][0-9]{0,5})\\*(?<v>[+]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[Ee][+-]?[0-9]+)?)$");
            if(!repeated.Success || long.Parse(repeated.Groups["n"].Value,CultureInfo.InvariantCulture)!=cells
                || !double.TryParse(repeated.Groups["v"].Value,NumberStyles.Float,CultureInfo.InvariantCulture,out var value)
                || !EclipseThermalRockParameters.Value(field,value)) return null;
            var fieldUnits=injection.Context.Records[0].RateUnit=="STB/day";
            var unit=field=="HEATCR"?(fieldUnits?"Btu/ft3/degR":"kJ/m3/K"):(fieldUnits?"Btu/ft/day/degR":"kJ/m/day/K");
            var start=b.Body+token.Index+repeated.Groups["v"].Index;
            var line=1+Regex.Matches(text[..(b.Body+token.Index)],"\\r\\n|\\r|\\n").Count;
            targets.Add(new(new(field,line,unit,value),start,repeated.Groups["v"].Length));
        }
        return new(new("eclipse-thermal-rock-context/1",injection.Context.SourceFile,injection.Context.SourceSha256,bytes.Length,(int)cells,mask.Count(v=>v==1),targets.Select(t=>t.Record).ToArray()),bytes,targets);
    }
}
