package com.flowticket.event.kopis;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;

/** KOPIS 공연상세(pblprfr/{id}) — 목록에 없는 디테일. */
@JsonIgnoreProperties(ignoreUnknown = true)
public class KopisEventDetail {

    @JacksonXmlProperty(localName = "mt20id")
    public String kopisId;

    @JacksonXmlProperty(localName = "prfruntime")
    public String runningTime;   // "1시간 30분"

    @JacksonXmlProperty(localName = "prfage")
    public String ageLimit;      // "만 12세 이상"

    @JacksonXmlProperty(localName = "pcseguidance")
    public String priceText;     // "전석 30,000원"

    @JacksonXmlProperty(localName = "prfcast")
    public String cast;          // 출연진

    @JacksonXmlProperty(localName = "sty")
    public String synopsis;      // 줄거리

    @JacksonXmlProperty(localName = "dtguidance")
    public String schedule;      // 공연시간 안내
}
