import { Injectable, OnDestroy } from '@angular/core';
import { AccessibilityService } from './accessibility.service';
import { Subscription } from 'rxjs';
import { distinctUntilChanged, map } from 'rxjs/operators';

/** Estado guardado antes de forzar silencio en modo sordo (solo elementos tocados por este servicio). */
const DEAF_MEDIA_BACKUP_ATTR = 'data-qwerys-deaf-media-backup';

/**
 * Modo sordo: silencia y pausa audio/video del DOM al activar el interruptor,
 * restaura el estado previo al desactivar. Hook para Day 34 — `data-deaf-mode` en body.
 *
 * Nota vs. snippet genérico: no recorre todo el árbol en cada mutación ni fuerza
 * `muted = false` en todos los media al salir (eso rompería vídeos ya silenciados por el usuario).
 */
@Injectable({ providedIn: 'root' })
export class DeafModeService implements OnDestroy {
  private observer: MutationObserver | null = null;
  private sub: Subscription;
  /** Evita observer duplicado si el perfil emite sin cambiar deafMode */
  private armed = false;

  constructor(private accessibility: AccessibilityService) {
    this.sub = this.accessibility.profile$
      .pipe(
        map((p) => p.deafMode),
        distinctUntilChanged()
      )
      .subscribe((deafMode) => {
        if (deafMode) {
          this.activate();
        } else {
          this.deactivate();
        }
      });
  }

  private activate(): void {
    if (typeof document === 'undefined') return;
    if (this.armed) return;
    this.armed = true;

    document.body.setAttribute('data-deaf-mode', 'true');

    this.applyMuteToSubtree(document.body);

    if (typeof MutationObserver === 'undefined') return;
    this.observer = new MutationObserver((records) => {
      for (const rec of records) {
        const nodes = Array.from(rec.addedNodes);
        for (const n of nodes) {
          if (n.nodeType !== Node.ELEMENT_NODE) continue;
          const el = n as Element;
          if (el.matches('audio, video')) {
            this.muteMedia(el as HTMLMediaElement);
          }
          el.querySelectorAll?.('audio, video').forEach((child) => {
            this.muteMedia(child as HTMLMediaElement);
          });
        }
      }
    });
    this.observer.observe(document.body, { childList: true, subtree: true });
  }

  private deactivate(): void {
    if (!this.armed) return;
    this.armed = false;

    this.observer?.disconnect();
    this.observer = null;

    document.body.removeAttribute('data-deaf-mode');

    this.restoreMutedMedia();
  }

  private applyMuteToSubtree(root: Document | Element): void {
    root.querySelectorAll('audio, video').forEach((node) => {
      this.muteMedia(node as HTMLMediaElement);
    });
  }

  private muteMedia(el: HTMLMediaElement): void {
    if (el.nodeName !== 'AUDIO' && el.nodeName !== 'VIDEO') return;
    if (el.hasAttribute(DEAF_MEDIA_BACKUP_ATTR)) return;
    el.setAttribute(
      DEAF_MEDIA_BACKUP_ATTR,
      JSON.stringify({
        muted: el.muted,
        volume: el.volume,
        paused: el.paused,
      })
    );
    el.muted = true;
    el.volume = 0;
    void el.pause();
  }

  private restoreMutedMedia(): void {
    document.querySelectorAll(`audio[${DEAF_MEDIA_BACKUP_ATTR}], video[${DEAF_MEDIA_BACKUP_ATTR}]`).forEach((node) => {
      const el = node as HTMLMediaElement;
      const raw = el.getAttribute(DEAF_MEDIA_BACKUP_ATTR);
      el.removeAttribute(DEAF_MEDIA_BACKUP_ATTR);
      if (!raw) return;
      try {
        const s = JSON.parse(raw) as { muted: boolean; volume: number; paused: boolean };
        el.muted = s.muted;
        el.volume = s.volume;
        if (!s.paused) {
          void el.play().catch(() => {
            /* políticas de autoplay */
          });
        }
      } catch {
        /* backup corrupto */
      }
    });
  }

  ngOnDestroy(): void {
    this.sub.unsubscribe();
    this.observer?.disconnect();
    this.observer = null;
    document.body.removeAttribute('data-deaf-mode');
    this.restoreMutedMedia();
    this.armed = false;
  }
}
