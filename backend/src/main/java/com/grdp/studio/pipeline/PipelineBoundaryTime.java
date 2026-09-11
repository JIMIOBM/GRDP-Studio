package com.grdp.studio.pipeline;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;

/** Local operating timestamps have minute precision; no timezone conversion or minute truncation. */
final class PipelineBoundaryTime {
    private PipelineBoundaryTime() {}
    private static final DateTimeFormatter ISO=DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm").withResolverStyle(ResolverStyle.STRICT);
    static LocalDateTime parse(String value) {
        if(value==null)throw invalid(value);
        String normalized;
        if(value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}[T ][0-9]{2}:[0-9]{2}(?::00)?"))
            normalized=value.substring(0,16).replace(' ','T');
        else if(value.matches("[0-9]{4}/[0-9]{2}/[0-9]{2} [0-9]{2}:[0-9]{2}"))
            normalized=value.replace('/','-').replace(' ','T');
        else throw invalid(value);
        LocalDateTime time=LocalDateTime.parse(normalized,ISO);
        if(time.getYear()<1)throw invalid(value);
        return time;
    }
    static String canonical(String value) {return parse(value).format(ISO);}
    private static DateTimeParseException invalid(String value) {
        return new DateTimeParseException("请填写有效的工况时间（YYYY/MM/DD HH:mm），精确到分钟",String.valueOf(value),0);
    }
}
