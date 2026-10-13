using System.Globalization;
using System.Text;
using System.Text.RegularExpressions;
namespace Grdp.SoftwareIntegration.Worker.Execution;

/** Validate numeric formatted grid/initialisation output before publishing the original bytes. */
internal static class EclipseFormattedGridOutput
{
    internal static void Validate(string path)
    {
        var info=new FileInfo(path);
        if(!EclipseRunRules.IsFormattedGridOutput(path)||!info.Exists||info.Length is <=0 or >128*1024*1024)throw new InvalidDataException("Bounded FINIT/FEGRID required.");
        var ascii=Encoding.GetEncoding("us-ascii",EncoderFallback.ExceptionFallback,DecoderFallback.ExceptionFallback);var text=ascii.GetString(File.ReadAllBytes(path));
        if(text.Split('\n').Any(l=>l.TrimEnd('\r').Length>80||l.TrimEnd('\r').Any(c=>c<32&&c!='\t')))throw new InvalidDataException("Invalid formatted grid card.");
        var headings=Regex.Matches(text,@"(?m)^ *'(?<key>[A-Z0-9_ -]{1,8})' +(?<count>[0-9]+) +'(?<type>INTE|REAL|DOUB|LOGI|CHAR|MESS)' *\r?$",RegexOptions.CultureInvariant).ToArray();
        if(headings.Length is <1 or >131072||!string.IsNullOrWhiteSpace(text[..headings[0].Index]))throw new InvalidDataException("Invalid formatted descriptors.");
        long total=0;
        for(var i=0;i<headings.Length;i++) {
            var h=headings[i];if(!int.TryParse(h.Groups["count"].Value,out var count)||count>4_000_000||(total+=count)>32_000_000)throw new InvalidDataException("Formatted value bound exceeded.");
            var type=h.Groups["type"].Value;var end=i+1<headings.Length?headings[i+1].Index:text.Length;var body=text[(h.Index+h.Length)..end];
            if(type=="CHAR") {
                var tokens=Regex.Matches(body,@"'[A-Za-z0-9_ ()+.-]{0,8}'",RegexOptions.CultureInvariant);
                if(tokens.Count!=count||!string.IsNullOrWhiteSpace(Regex.Replace(body,@"'[A-Za-z0-9_ ()+.-]{0,8}'","",RegexOptions.CultureInvariant)))throw new InvalidDataException("Invalid formatted character values.");
            }else {
                var tokens=Regex.Matches(body,@"\S+",RegexOptions.CultureInvariant).Select(m=>m.Value).ToArray();
                if(tokens.Length!=count||type=="MESS"&&count!=0)throw new InvalidDataException("Formatted count mismatch.");
                foreach(var token in tokens) {
                    if(type=="LOGI"){if(token is not("T" or "F"))throw new InvalidDataException("Invalid formatted logical.");}
                    else if(type=="INTE"){if(!int.TryParse(token,NumberStyles.AllowLeadingSign,CultureInfo.InvariantCulture,out _))throw new InvalidDataException("Invalid formatted integer.");}
                    else if(!FiniteNumber(token))throw new InvalidDataException("Invalid formatted finite number.");
                }
            }
        }
    }
    private static bool FiniteNumber(string token)=>Regex.IsMatch(token,@"^[+-]?(?:[0-9]+(?:\.[0-9]*)?|\.[0-9]+)(?:[eEdD][+-]?[0-9]+)?$",RegexOptions.CultureInvariant)
        &&double.TryParse(token.Replace('D','E').Replace('d','e'),NumberStyles.Float,CultureInfo.InvariantCulture,out var n)&&double.IsFinite(n);
}
