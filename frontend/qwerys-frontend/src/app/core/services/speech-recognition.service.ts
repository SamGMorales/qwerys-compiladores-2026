import { Injectable } from '@angular/core';
import { BehaviorSubject, Observable, Subscriber } from 'rxjs';

/** Error codes surfaced to the UI (mic permission, hardware, silence, etc.). */
export type SpeechRecognitionErrorCode =
  | 'NO_SPEECH'
  | 'AUDIO_CAPTURE'
  | 'NOT_ALLOWED'
  | 'UNKNOWN';

export class SpeechRecognitionError extends Error {
  readonly code: SpeechRecognitionErrorCode;

  constructor(code: SpeechRecognitionErrorCode, message?: string) {
    super(message ?? code);
    this.name = 'SpeechRecognitionError';
    this.code = code;
  }
}

/** Minimal Web Speech API typing (not in default lib.dom for all TS targets). */
interface BrowserSpeechRecognition extends EventTarget {
  continuous: boolean;
  interimResults: boolean;
  lang: string;
  onresult: ((ev: BrowserSpeechRecognitionEvent) => void) | null;
  onend: (() => void) | void | null;
  onerror: ((ev: BrowserSpeechRecognitionErrorEvent) => void) | null;
  onstart: (() => void) | null;
  start(): void;
  stop(): void;
  abort(): void;
}

interface BrowserSpeechRecognitionEvent extends Event {
  resultIndex: number;
  results: SpeechRecognitionResultList;
}

interface BrowserSpeechRecognitionErrorEvent extends Event {
  error: string;
}

type SpeechRecognitionCtor = new () => BrowserSpeechRecognition;

interface ActiveRecognitionSession {
  recognition: BrowserSpeechRecognition;
  observer: Subscriber<string>;
  accumulatedFinal: string;
  hasEmitted: boolean;
  finished: boolean;
}

/**
 * Speech-to-text via the browser Web Speech API (no external SDKs).
 * Emits interim transcripts on {@link interimText$}; final text on {@link startListening}.
 */
@Injectable({ providedIn: 'root' })
export class SpeechRecognitionService {
  private readonly listeningState = new BehaviorSubject<boolean>(false);
  private readonly interimState = new BehaviorSubject<string>('');

  /** {@code true} while the recognizer is active. */
  readonly isListening$ = this.listeningState.asObservable();

  /** Partial transcript while the user is still speaking (not written to the editor). */
  readonly interimText$ = this.interimState.asObservable();

  private recognition: BrowserSpeechRecognition | null = null;
  private session: ActiveRecognitionSession | null = null;

  isSupported(): boolean {
    return typeof window !== 'undefined' && !!this.getRecognitionCtor();
  }

  /** Synchronous listening state (for keyboard toggle without subscribing). */
  isListening(): boolean {
    return this.listeningState.value;
  }

  /**
   * Starts recognition until the user calls {@link stopListening} or the engine ends.
   * Uses {@code continuous: true} so long SQL phrases are not cut off mid-sentence.
   */
  startListening(lang?: string): Observable<string> {
    return new Observable<string>((observer) => {
      const Ctor = this.getRecognitionCtor();
      if (!Ctor) {
        observer.error(new SpeechRecognitionError('UNKNOWN'));
        return;
      }

      this.stopListening();

      const recognition = new Ctor();
      this.recognition = recognition;

      const active: ActiveRecognitionSession = {
        recognition,
        observer,
        accumulatedFinal: '',
        hasEmitted: false,
        finished: false,
      };
      this.session = active;

      recognition.continuous = true;
      recognition.interimResults = true;
      recognition.lang = this.resolveLangTag(lang);

      this.listeningState.next(true);

      recognition.onresult = (event: BrowserSpeechRecognitionEvent): void => {
        if (active.finished) return;

        let interim = '';
        let allFinal = '';
        for (let i = 0; i < event.results.length; i++) {
          const piece = event.results[i].item(0)?.transcript ?? '';
          if (event.results[i].isFinal) {
            allFinal += piece;
          } else {
            interim += piece;
          }
        }

        active.accumulatedFinal = allFinal.trim();

        const preview = (interim || allFinal).trim();
        if (preview) {
          this.interimState.next(preview);
        }
      };

      recognition.onend = (): void => {
        this.flushSession(active, false);
      };

      recognition.onerror = (event: BrowserSpeechRecognitionErrorEvent): void => {
        if (active.finished) return;
        const code = this.mapNativeError(event.error);
        if (code === 'NO_SPEECH' && active.accumulatedFinal.trim()) {
          this.emitFinal(active);
          return;
        }
        this.endSession(active, () => observer.error(new SpeechRecognitionError(code)));
      };

      try {
        recognition.start();
      } catch {
        this.endSession(active, () => observer.error(new SpeechRecognitionError('UNKNOWN')));
      }

      return () => this.stopListening();
    });
  }

  stopListening(): void {
    const active = this.session;
    if (!active) {
      this.resetPublicState();
      return;
    }

    try {
      active.recognition.stop();
    } catch {
      try {
        active.recognition.abort();
      } catch {
        /* already stopped */
      }
    }

    this.flushSession(active, true);
  }

  /**
   * Maps common Spanish spoken SQL to editor-friendly tokens (SELECT, *, WHERE, …).
   */
  normalizeSqlDictation(text: string): string {
    let t = text.trim();
    if (!t) return t;

    const phraseRules: [RegExp, string][] = [
      [/\bpunto y coma\b/gi, ';'],
      [/\bcoma\b/gi, ','],
      [/\bparéntesis izquierdo\b/gi, '('],
      [/\bparéntesis derecho\b/gi, ')'],
      [/\bparentesis izquierdo\b/gi, '('],
      [/\bparentesis derecho\b/gi, ')'],
      [/\basterisco\b/gi, '*'],
      [/\basterístico\b/gi, '*'],
      [/\basteristo\b/gi, '*'],
      [/\bestrella\b/gi, '*'],
      [/\btodos\b/gi, '*'],
      [/\bdistinto de\b/gi, '<>'],
      [/\bmayor o igual\b/gi, '>='],
      [/\bmenor o igual\b/gi, '<='],
    ];

    for (const [pattern, replacement] of phraseRules) {
      t = t.replace(pattern, replacement);
    }

    const wordRules: [RegExp, string][] = [
      [/\bseleccionar\b/gi, 'SELECT'],
      [/\bselecciona\b/gi, 'SELECT'],
      [/\bselect\b/gi, 'SELECT'],
      [/\bdesde\b/gi, 'FROM'],
      [/\bfrom\b/gi, 'FROM'],
      [/\bdonde\b/gi, 'WHERE'],
      [/\bwhere\b/gi, 'WHERE'],
      [/\bunir\b/gi, 'JOIN'],
      [/\bjoin\b/gi, 'JOIN'],
      [/\bordenar por\b/gi, 'ORDER BY'],
      [/\bgroup by\b/gi, 'GROUP BY'],
      [/\bagrupar por\b/gi, 'GROUP BY'],
      [/\binsertar en\b/gi, 'INSERT INTO'],
      [/\bactualizar\b/gi, 'UPDATE'],
      [/\beliminar de\b/gi, 'DELETE FROM'],
      [/\bcrear tabla\b/gi, 'CREATE TABLE'],
    ];

    for (const [pattern, replacement] of wordRules) {
      t = t.replace(pattern, replacement);
    }

    return t.replace(/\s+/g, ' ').trim();
  }

  private flushSession(active: ActiveRecognitionSession, fromManualStop: boolean): void {
    if (active.finished) return;

    const text = active.accumulatedFinal.trim();
    if (text) {
      this.emitFinal(active);
      return;
    }

    if (fromManualStop) {
      this.endSession(active, () =>
        active.observer.error(new SpeechRecognitionError('NO_SPEECH'))
      );
      return;
    }

    this.endSession(active, () => active.observer.complete());
  }

  private emitFinal(active: ActiveRecognitionSession): void {
    if (active.finished || active.hasEmitted) return;
    const normalized = this.normalizeSqlDictation(active.accumulatedFinal);
    if (!normalized) {
      this.endSession(active, () =>
        active.observer.error(new SpeechRecognitionError('NO_SPEECH'))
      );
      return;
    }
    active.hasEmitted = true;
    active.finished = true;
    active.observer.next(normalized);
    active.observer.complete();
    this.clearRecognition();
  }

  private endSession(active: ActiveRecognitionSession, fn: () => void): void {
    if (active.finished) return;
    active.finished = true;
    fn();
    this.clearRecognition();
  }

  private clearRecognition(): void {
    this.session = null;
    this.recognition = null;
    this.resetPublicState();
  }

  private resetPublicState(): void {
    this.listeningState.next(false);
    this.interimState.next('');
  }

  private getRecognitionCtor(): SpeechRecognitionCtor | null {
    if (typeof window === 'undefined') return null;
    const w = window as Window & {
      SpeechRecognition?: SpeechRecognitionCtor;
      webkitSpeechRecognition?: SpeechRecognitionCtor;
    };
    return w.SpeechRecognition ?? w.webkitSpeechRecognition ?? null;
  }

  private mapNativeError(error: string): SpeechRecognitionErrorCode {
    switch (error) {
      case 'no-speech':
        return 'NO_SPEECH';
      case 'audio-capture':
        return 'AUDIO_CAPTURE';
      case 'not-allowed':
        return 'NOT_ALLOWED';
      default:
        return 'UNKNOWN';
    }
  }

  private resolveLangTag(lang?: string): string {
    const raw = (lang ?? document.documentElement.lang ?? 'es').trim();
    if (!raw) return 'es-ES';
    const lower = raw.toLowerCase();
    if (lower === 'es' || lower.startsWith('es-')) {
      return lower === 'es' ? 'es-ES' : raw;
    }
    if (lower === 'en' || lower.startsWith('en-')) {
      return lower === 'en' ? 'en-US' : raw;
    }
    return raw.includes('-') ? raw : `${lower}-${lower.toUpperCase()}`;
  }
}
