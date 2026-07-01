"use client";

import { useCallback, useEffect, useState } from "react";
import Link from "next/link";
import useEmblaCarousel from "embla-carousel-react";
import type { EventSummary } from "@/features/event/api/event";
import { Button } from "@/components/ui/button";

/** 대표 공연 자동 회전 캐러셀(5초). dot로 수동 이동. */
export function HeroCarousel({ items }: { items: EventSummary[] }) {
  const [emblaRef, emblaApi] = useEmblaCarousel({ loop: true });
  const [selected, setSelected] = useState(0);

  const onSelect = useCallback(() => {
    if (emblaApi) setSelected(emblaApi.selectedScrollSnap());
  }, [emblaApi]);

  useEffect(() => {
    if (!emblaApi) return;
    emblaApi.on("select", onSelect);
    onSelect();
    const timer = setInterval(() => emblaApi.scrollNext(), 5000);
    return () => {
      clearInterval(timer);
      emblaApi.off("select", onSelect);
    };
  }, [emblaApi, onSelect]);

  if (items.length === 0) return null;

  return (
    <section className="relative mb-10 overflow-hidden rounded-xl">
      <div className="overflow-hidden" ref={emblaRef}>
        <div className="flex">
          {items.map((e) => (
            <div key={e.id} className="relative min-w-0 flex-[0_0_100%] bg-foreground text-primary-foreground">
              {e.posterUrl && (
                // eslint-disable-next-line @next/next/no-img-element
                <img src={e.posterUrl} alt="" className="absolute inset-0 h-full w-full object-cover opacity-40" />
              )}
              <div className="relative flex flex-col justify-end gap-2 p-10" style={{ minHeight: 240 }}>
                <p className="text-sm opacity-80">{e.genre ?? "지금 가장 핫한 공연"}</p>
                <h2 className="line-clamp-2 text-3xl font-bold">{e.title}</h2>
                <p className="line-clamp-1 opacity-90">{[e.venue, e.startDate].filter(Boolean).join(" · ")}</p>
                <Link href={`/events/${e.id}`} className="mt-2">
                  <Button className="bg-primary">예매하기</Button>
                </Link>
              </div>
            </div>
          ))}
        </div>
      </div>

      {items.length > 1 && (
        <div className="absolute bottom-3 left-1/2 flex -translate-x-1/2 gap-1.5">
          {items.map((_, i) => (
            <button
              key={i}
              aria-label={`슬라이드 ${i + 1}`}
              onClick={() => emblaApi?.scrollTo(i)}
              className={`h-1.5 rounded-full transition-all ${i === selected ? "w-4 bg-primary" : "w-1.5 bg-white/50"}`}
            />
          ))}
        </div>
      )}
    </section>
  );
}
