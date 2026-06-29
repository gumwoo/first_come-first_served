import { api } from "@/lib/apiClient";

export type EventSummary = {
  id: number;
  title: string;
  venue: string | null;
  genre: string | null;
  posterUrl: string | null;
  startDate: string | null;
  status: string;
  basePrice: number | null;
};

export type EventDetail = EventSummary & {
  endDate: string | null;
  runningTime: string | null;
  ageLimit: string | null;
  priceText: string | null;
  cast: string | null;
  synopsis: string | null;
  schedule: string | null;
};

export type Page<T> = { items: T[]; page: number; size: number; total: number };

export const listEvents = (params: { page?: number; size?: number; genre?: string } = {}) => {
  const q = new URLSearchParams();
  if (params.page != null) q.set("page", String(params.page));
  if (params.size != null) q.set("size", String(params.size));
  if (params.genre) q.set("genre", params.genre);
  return api<Page<EventSummary>>(`/events?${q.toString()}`);
};

export const getPopular = () => api<EventSummary[]>("/events/popular");

export const getEvent = (id: number) => api<EventDetail>(`/events/${id}`);

export const searchEvents = (
  q: string,
  opts: { genre?: string; status?: string; page?: number; size?: number } = {}
) => {
  const p = new URLSearchParams();
  if (q) p.set("q", q);
  if (opts.genre) p.set("genre", opts.genre);
  if (opts.status) p.set("status", opts.status);
  p.set("page", String(opts.page ?? 0));
  p.set("size", String(opts.size ?? 20));
  return api<Page<EventSummary>>(`/search?${p.toString()}`);
};
