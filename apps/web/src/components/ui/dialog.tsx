"use client";

import { X } from "lucide-react";
import * as React from "react";
import { cn } from "@/lib/utils";

type DialogProps = {
  open: boolean;
  onClose: () => void;
  title: string;
  children: React.ReactNode;
  footer?: React.ReactNode;
};

/** 간단한 모달(약관 상세보기 등). radix 미사용, 추가 의존성 없음. */
export function Dialog({ open, onClose, title, children, footer }: DialogProps) {
  if (!open) return null;
  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
      <div className="absolute inset-0 bg-black/40" onClick={onClose} aria-hidden />
      <div className={cn("relative z-10 flex max-h-[80vh] w-full max-w-lg flex-col rounded-lg border border-border bg-card shadow-lg")}>
        <div className="flex items-center justify-between border-b border-border px-5 py-3">
          <h2 className="font-bold">{title}</h2>
          <button onClick={onClose} aria-label="닫기" className="text-muted-foreground hover:text-foreground">
            <X className="h-5 w-5" />
          </button>
        </div>
        <div className="overflow-y-auto px-5 py-4 text-sm leading-relaxed text-muted-foreground">
          {children}
        </div>
        {footer && <div className="flex justify-end gap-2 border-t border-border px-5 py-3">{footer}</div>}
      </div>
    </div>
  );
}
