/**
 * PWA install prompt (Chrome/Edge). iOS Safari no expone `beforeinstallprompt`.
 *
 * IMPORTANTE — pruebas: con `ng serve` (localhost:4200) el Service Worker está
 * desactivado (`provideServiceWorker` + `isDevMode()`). El botón de instalar NO
 * aparece en desarrollo; no es un bug. Probar con: `npm run serve:pwa`
 */
import { Injectable, OnDestroy } from '@angular/core';
import { BehaviorSubject } from 'rxjs';

// BeforeInstallPromptEvent no existe en los tipos estándar de TypeScript/DOM.
// Chrome y Edge lo disparan ANTES de mostrar el diálogo de instalación nativo.
// iOS Safari NO soporta este evento — en iPhone el botón de instalar nunca aparecerá.
// Esto es una limitación de Apple, no un bug del código.
interface BeforeInstallPromptEvent extends Event {
  readonly platforms: string[];
  readonly userChoice: Promise<{ outcome: 'accepted' | 'dismissed'; platform: string }>;
  prompt(): Promise<void>;
}

@Injectable({ providedIn: 'root' })
export class PwaService implements OnDestroy {

  private installPromptEvent: BeforeInstallPromptEvent | null = null;

  // canInstall emite true cuando el navegador está listo para mostrar el diálogo de instalación.
  // Emite false en iOS Safari y cuando ya está instalado.
  readonly canInstall = new BehaviorSubject<boolean>(false);

  // isInstalled emite true después de que el usuario acepta instalar la app.
  readonly isInstalled = new BehaviorSubject<boolean>(false);

  private readonly boundHandler = this.onBeforeInstallPrompt.bind(this);
  private readonly boundAppInstalled = this.onAppInstalled.bind(this);

  constructor() {
    window.addEventListener('beforeinstallprompt', this.boundHandler as EventListener);
    window.addEventListener('appinstalled', this.boundAppInstalled);
  }

  private onBeforeInstallPrompt(event: BeforeInstallPromptEvent): void {
    // Prevenir que el navegador muestre el diálogo automáticamente.
    // Lo mostraremos solo cuando el usuario haga clic en nuestro botón.
    event.preventDefault();
    this.installPromptEvent = event;
    this.canInstall.next(true);
  }

  private onAppInstalled(): void {
    this.installPromptEvent = null;
    this.canInstall.next(false);
    this.isInstalled.next(true);
  }

  async installApp(): Promise<void> {
    if (!this.installPromptEvent) {
      return;
    }
    await this.installPromptEvent.prompt();
    const choice = await this.installPromptEvent.userChoice;
    if (choice.outcome === 'accepted') {
      this.installPromptEvent = null;
      this.canInstall.next(false);
    }
  }

  ngOnDestroy(): void {
    window.removeEventListener('beforeinstallprompt', this.boundHandler as EventListener);
    window.removeEventListener('appinstalled', this.boundAppInstalled);
  }
}
