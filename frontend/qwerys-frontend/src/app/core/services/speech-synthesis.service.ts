import { Injectable } from '@angular/core';
import { BehaviorSubject } from 'rxjs';

/**
 * Text-to-speech via the browser {@link SpeechSynthesis} API (no external SDKs).
 * Respects deaf mode ({@code data-deaf-mode="true"} on body): never speaks aloud.
 */
@Injectable({ providedIn: 'root' })
export class SpeechSynthesisService {
  private readonly speakingState = new BehaviorSubject<boolean>(false);

  /** Emits {@code true} while an utterance is active. */
  readonly speaking$ = this.speakingState.asObservable();

  constructor() {
    if (!this.isSupported()) return;
    const synth = window.speechSynthesis;
    const refresh = (): void => {
      synth.getVoices();
    };
    refresh();
    synth.onvoiceschanged = refresh;
  }

  isSupported(): boolean {
    return typeof window !== 'undefined' && !!window.speechSynthesis;
  }

  /**
   * Speaks text. Cancels any active utterance first (no queue).
   * @param lang Optional BCP-47 tag or short code ({@code es} / {@code en}); defaults from {@code document.documentElement.lang} or {@code es-ES}.
   */
  speak(text: string, lang?: string): void {
    if (document.body.getAttribute('data-deaf-mode') === 'true') return;
    if (!this.isSupported()) return;
    const trimmed = text?.trim();
    if (!trimmed) return;

    const synth = window.speechSynthesis;
    synth.cancel();

    window.setTimeout(() => {
      if (document.body.getAttribute('data-deaf-mode') === 'true') return;

      const utter = new SpeechSynthesisUtterance(trimmed);
      const resolved = this.resolveLangTag(lang);
      utter.lang = resolved;
      utter.rate = 0.9;
      utter.pitch = 1;

      const voice = this.pickVoice(resolved);
      if (voice) {
        utter.voice = voice;
        utter.lang = voice.lang || resolved;
      }

      utter.onstart = (): void => this.speakingState.next(true);
      utter.onend = (): void => this.speakingState.next(false);
      utter.onerror = (ev): void => {
        console.warn('[SpeechSynthesis]', ev?.error ?? ev);
        this.speakingState.next(false);
      };

      synth.speak(utter);
    }, 0);
  }

  stop(): void {
    if (!this.isSupported()) return;
    window.speechSynthesis.cancel();
    this.speakingState.next(false);
  }

  isSpeaking(): boolean {
    if (!this.isSupported()) return false;
    return window.speechSynthesis.speaking;
  }

  private resolveLangTag(lang?: string): string {
    const raw = (lang ?? document.documentElement.lang ?? 'es').trim();
    if (!raw) return 'es-ES';
    const lower = raw.toLowerCase();
    if (lower === 'es' || lower.startsWith('es-')) return lower.startsWith('es') ? raw.replace(/^es$/i, 'es-ES') : raw;
    if (lower === 'en' || lower.startsWith('en-')) return lower === 'en' ? 'en-US' : raw;
    return raw.includes('-') ? raw : `${lower}-${lower.toUpperCase()}`;
  }

  private pickVoice(preferredLang: string): SpeechSynthesisVoice | null {
    if (!this.isSupported()) return null;
    const voices = window.speechSynthesis.getVoices();
    if (!voices.length) return null;

    const prefix = preferredLang.split('-')[0]?.toLowerCase() ?? 'es';
    const candidates = voices.filter((v) => v.lang?.toLowerCase().startsWith(prefix));
    const pool = candidates.length ? candidates : voices;

    const rankLocal = (v: SpeechSynthesisVoice): number =>
      v.localService === true ? 1 : v.localService === false ? 0 : 0;

    const sorted = [...pool].sort((a, b) => {
      const lr = rankLocal(b) - rankLocal(a);
      if (lr !== 0) return lr;
      const exact =
        Number(b.lang?.toLowerCase() === preferredLang.toLowerCase()) -
        Number(a.lang?.toLowerCase() === preferredLang.toLowerCase());
      if (exact !== 0) return exact;
      return (a.name ?? '').localeCompare(b.name ?? '');
    });

    return sorted[0] ?? null;
  }
}
