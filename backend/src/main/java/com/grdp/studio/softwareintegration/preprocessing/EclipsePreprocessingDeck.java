package com.grdp.studio.softwareintegration.preprocessing;

import tools.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

public final class EclipsePreprocessingDeck {
    private EclipsePreprocessingDeck() { }

    public static byte[] render(JsonNode template, Map<String, String> values, boolean visualOutputs) {
        var source = new StringBuilder(template.path("source").asText());
        var fields = new ArrayList<JsonNode>();
        template.path("fields").forEach(fields::add);
        fields.sort(Comparator.comparingInt((JsonNode field) -> field.path("start").asInt()).reversed());
        for (var field : fields) {
            int start = field.path("start").asInt();
            if (start < 0) continue;
            String value = values.get(field.path("id").asText());
            if (new BigDecimal(value).compareTo(new BigDecimal(field.path("baseValue").asText())) == 0) continue;
            String literal = new BigDecimal(value).stripTrailingZeros().toPlainString();
            if (field.path("repeat").asInt() > 1) literal = field.path("repeat").asInt() + "*" + literal;
            source.replace(start, field.path("end").asInt(), literal);
        }
        String text = source.toString();
        if (!template.path("startDate").asText().equals(values.get("project.startDate"))) {
            var date = LocalDate.parse(values.get("project.startDate"));
            String record = "START\n " + date.getDayOfMonth() + " '" + date.getMonth().name().substring(0, 3) + "' " + date.getYear() + " /\n";
            var existing = Pattern.compile("(?m)^START[ \\t]*\\n[^/]+/[^\\n]*\\n").matcher(text);
            text = existing.find() ? existing.replaceFirst(java.util.regex.Matcher.quoteReplacement(record))
                    : text.replaceFirst("(?m)^GRID[^\\n]*", java.util.regex.Matcher.quoteReplacement(record + "\nGRID"));
        }
        if (visualOutputs) {
            if ("e100".equals(template.path("id").asText())) {
                var wellNames = new java.util.LinkedHashSet<String>();
                template.path("wells").forEach(well -> wellNames.add(well.path("values").get(0).asText()));
                text = Pattern.compile("'([^'\\r\\n]+)'").matcher(text).replaceAll(match -> {
                    String name = match.group(1).trim();
                    return wellNames.contains(name) ? "'" + name + "'" : match.group();
                });
                // The E100 result contract identifies field/well vectors. Spatial vectors are
                // supplied by native restart fields instead of ambiguous repeated Summary names.
                var summary = new StringBuilder("SUMMARY\nFOPR\nFWPR\nFGPR\nFOPT\nFWIT\nFGPT\nFPR\nFWPT\n");
                for (var keyword : java.util.List.of("WOPR", "WWPR", "WGPR", "WOPT", "WWIT", "WBHP", "WWIR")) {
                    summary.append(keyword).append('\n');
                    int index = 0;
                    for (var name : wellNames) {
                        if (index++ > 0 && (index - 1) % 6 == 0) summary.append('\n');
                        summary.append(" '").append(name).append("'");
                    }
                    summary.append(" /\n\n");
                }
                text = Pattern.compile("(?ms)^SUMMARY[^\\n]*\\n.*?(?=^SCHEDULE)").matcher(text).replaceFirst(summary.toString());
            }
            if (!Pattern.compile("(?m)^UNIFOUT[ \\t]*$").matcher(text).find()) text = text.replaceFirst("(?m)^GRID[^\\n]*", "UNIFOUT\n\nGRID");
            if (!Pattern.compile("(?m)^INIT[ \\t]*$").matcher(text).find()) text = text.replaceFirst("(?m)^GRID[^\\n]*", "GRID\n\nINIT");
            if ("e100".equals(template.path("id").asText())) {
                text = text.replaceFirst("(?m)^SCHEDULE[^\\n]*", "SCHEDULE\n\nRPTRST\n BASIC=2 FREQ=1 /");
            }
            if ("thermal".equals(template.path("id").asText())) {
                text = text.replaceFirst("(?m)^SCHEDULE[^\\n]*", "BTEMP\n 1 1 1 /\n 9 1 4 /\n/\nWTEMP\n I PNEAR PFAR /\n\nSCHEDULE");
            }
        }
        return text.getBytes(StandardCharsets.US_ASCII);
    }
}
