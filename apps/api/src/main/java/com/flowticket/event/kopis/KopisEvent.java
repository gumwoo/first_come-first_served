package com.flowticket.event.kopis;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;

/** KOPIS 공연목록(pblprfr) 단일 항목. XML 필드 매핑. */
@JsonIgnoreProperties(ignoreUnknown = true)
public class KopisEvent {

    @JacksonXmlProperty(localName = "mt20id")
    public String kopisId;

    @JacksonXmlProperty(localName = "prfnm")
    public String title;

    @JacksonXmlProperty(localName = "fcltynm")
    public String venue;

    @JacksonXmlProperty(localName = "genrenm")
    public String genre;

    @JacksonXmlProperty(localName = "poster")
    public String posterUrl;

    @JacksonXmlProperty(localName = "prfpdfrom")
    public String startDate; // "yyyy.MM.dd"

    @JacksonXmlProperty(localName = "prfpdto")
    public String endDate;   // "yyyy.MM.dd"

    @JacksonXmlProperty(localName = "prfstate")
    public String state;     // 공연예정/공연중/공연완료
}
