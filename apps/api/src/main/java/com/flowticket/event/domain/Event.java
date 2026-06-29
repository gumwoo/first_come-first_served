package com.flowticket.event.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "events")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Event {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** KOPIS 공연ID(동기화 upsert 키). 시드/수동 등록은 null. */
    @Column(name = "kopis_id", unique = true, length = 50)
    private String kopisId;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(length = 200)
    private String venue;

    /** KOPIS area(시도). 지역 필터용. */
    @Column(length = 50)
    private String region;

    @Column(length = 50)
    private String genre;

    @Column(name = "poster_url", length = 500)
    private String posterUrl;

    @Column(name = "start_date")
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    @Column(name = "running_time", length = 50)
    private String runningTime;

    @Column(name = "age_limit", length = 50)
    private String ageLimit;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EventStatus status;

    /** 표시용 최소 가격(원). 등급별 가격/재고는 S04. */
    @Column(name = "base_price")
    private Integer basePrice;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Builder
    private Event(String kopisId, String title, String venue, String region, String genre,
                  String posterUrl, LocalDate startDate, LocalDate endDate, String runningTime,
                  String ageLimit, EventStatus status, Integer basePrice) {
        this.kopisId = kopisId;
        this.title = title;
        this.venue = venue;
        this.region = region;
        this.genre = genre;
        this.posterUrl = posterUrl;
        this.startDate = startDate;
        this.endDate = endDate;
        this.runningTime = runningTime;
        this.ageLimit = ageLimit;
        this.status = status != null ? status : EventStatus.SCHEDULED;
        this.basePrice = basePrice;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = this.createdAt;
    }

    /** KOPIS 동기화 시 변경 가능한 메타 갱신(upsert). */
    public void updateFromSync(String title, String venue, String region, String genre,
                               String posterUrl, LocalDate startDate, LocalDate endDate,
                               String runningTime, String ageLimit, EventStatus status) {
        this.title = title;
        this.venue = venue;
        this.region = region;
        this.genre = genre;
        this.posterUrl = posterUrl;
        this.startDate = startDate;
        this.endDate = endDate;
        this.runningTime = runningTime;
        this.ageLimit = ageLimit;
        if (status != null) {
            this.status = status;
        }
        this.updatedAt = LocalDateTime.now();
    }
}
