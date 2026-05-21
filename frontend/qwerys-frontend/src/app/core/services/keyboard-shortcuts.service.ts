import { DestroyRef, Injectable, inject } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Subject, fromEvent } from 'rxjs';

/**
 * Global keyboard shortcuts for QWERYS (all accessibility modes).
 * Ctrl+Enter works even when focus is in the SQL textarea; other shortcuts
 * are suppressed while typing in editable fields to avoid hijacking input.
 */
@Injectable({ providedIn: 'root' })
export class KeyboardShortcutsService {
  private readonly destroyRef = inject(DestroyRef);

  private readonly analyzeSubject = new Subject<void>();
  private readonly clearSubject = new Subject<void>();
  private readonly toggleVoiceSubject = new Subject<void>();
  private readonly openHelpSubject = new Subject<void>();
  private readonly closeSubject = new Subject<void>();
  private readonly navAnalyzerSubject = new Subject<void>();
  private readonly navMigrationSubject = new Subject<void>();
  private readonly navSchemaSubject = new Subject<void>();

  readonly analyze$ = this.analyzeSubject.asObservable();
  readonly clear$ = this.clearSubject.asObservable();
  readonly toggleVoice$ = this.toggleVoiceSubject.asObservable();
  readonly openHelp$ = this.openHelpSubject.asObservable();
  readonly close$ = this.closeSubject.asObservable();
  readonly navAnalyzer$ = this.navAnalyzerSubject.asObservable();
  readonly navMigration$ = this.navMigrationSubject.asObservable();
  readonly navSchema$ = this.navSchemaSubject.asObservable();

  constructor() {
    fromEvent<KeyboardEvent>(document, 'keydown')
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((event) => this.handleKeydown(event));
  }

  private handleKeydown(event: KeyboardEvent): void {
    if (event.defaultPrevented || event.isComposing) {
      return;
    }

    const key = event.key;
    const inEditable = this.isEditableTarget(event.target);

    if (key === 'Escape') {
      event.preventDefault();
      this.closeSubject.next();
      return;
    }

    if (key === 'F1') {
      event.preventDefault();
      this.openHelpSubject.next();
      return;
    }

    if (this.isAnalyzeShortcut(event)) {
      event.preventDefault();
      this.analyzeSubject.next();
      return;
    }

    if (inEditable) {
      return;
    }

    if (this.isClearShortcut(event)) {
      event.preventDefault();
      this.clearSubject.next();
      return;
    }

    if (this.isToggleVoiceShortcut(event)) {
      event.preventDefault();
      this.toggleVoiceSubject.next();
      return;
    }

    if (this.isNavShortcut(event, '1')) {
      event.preventDefault();
      this.navAnalyzerSubject.next();
      return;
    }

    if (this.isNavShortcut(event, '2')) {
      event.preventDefault();
      this.navMigrationSubject.next();
      return;
    }

    if (this.isNavShortcut(event, '3')) {
      event.preventDefault();
      this.navSchemaSubject.next();
    }
  }

  private isAnalyzeShortcut(event: KeyboardEvent): boolean {
    return event.key === 'Enter' && (event.ctrlKey || event.metaKey);
  }

  private isClearShortcut(event: KeyboardEvent): boolean {
    return (event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'k';
  }

  private isToggleVoiceShortcut(event: KeyboardEvent): boolean {
    return (
      (event.ctrlKey || event.metaKey) &&
      event.shiftKey &&
      event.key.toLowerCase() === 'm'
    );
  }

  /** Alt+Shift+1/2/3 — avoids conflicts with screen readers that use Alt heavily. */
  private isNavShortcut(event: KeyboardEvent, digit: string): boolean {
    return event.altKey && event.shiftKey && event.key === digit;
  }

  private isEditableTarget(target: EventTarget | null): boolean {
    if (!(target instanceof HTMLElement)) {
      return false;
    }
    if (target.isContentEditable) {
      return true;
    }
    const tag = target.tagName;
    if (tag === 'TEXTAREA') {
      return true;
    }
    if (tag === 'SELECT') {
      return true;
    }
    if (tag !== 'INPUT') {
      return false;
    }
    const type = (target as HTMLInputElement).type.toLowerCase();
    return !['button', 'submit', 'reset', 'checkbox', 'radio', 'file', 'range', 'color'].includes(
      type
    );
  }
}
