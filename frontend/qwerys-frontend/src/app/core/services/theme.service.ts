import { Injectable } from '@angular/core';
import { BehaviorSubject } from 'rxjs';

const THEME_KEY = 'qwerys-theme';

@Injectable({ providedIn: 'root' })
export class ThemeService {
  private readonly _isDarkTheme$ = new BehaviorSubject<boolean>(
    this.loadSavedTheme()
  );

  /** Observable público — emite true cuando el modo oscuro está activo */
  readonly isDarkTheme$ = this._isDarkTheme$.asObservable();

  constructor() {
    // Aplica el tema guardado apenas arranca la app
    this.applyTheme(this._isDarkTheme$.value);
  }

  /** Alterna entre claro y oscuro */
  toggle(): void {
    const next = !this._isDarkTheme$.value;
    this._isDarkTheme$.next(next);
    this.applyTheme(next);
    localStorage.setItem(THEME_KEY, JSON.stringify(next));
  }

  /**
   * Establece el tema directamente a un valor específico.
   * Usado al hacer login para sincronizar con la preferencia guardada en el servidor.
   */
  setTheme(isDark: boolean): void {
    this._isDarkTheme$.next(isDark);
    this.applyTheme(isDark);
    localStorage.setItem(THEME_KEY, JSON.stringify(isDark));
  }

  private applyTheme(isDark: boolean): void {
    const body = document.body;
    if (isDark) {
      body.classList.add('dark-theme');
    } else {
      body.classList.remove('dark-theme');
    }
  }

  private loadSavedTheme(): boolean {
    try {
      const saved = localStorage.getItem(THEME_KEY);
      return saved ? (JSON.parse(saved) as boolean) : false;
    } catch {
      return false;
    }
  }
}
