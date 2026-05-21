import {
  Component,
  OnInit,
  OnDestroy,
  ViewChild,
  inject,
  DestroyRef,
} from '@angular/core';
import { BreakpointObserver } from '@angular/cdk/layout';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { CommonModule, AsyncPipe } from '@angular/common';
import { Router, RouterOutlet, RouterLink, RouterLinkActive, NavigationEnd, NavigationStart } from '@angular/router';
import { Subject, takeUntil, filter } from 'rxjs';

import { MatSidenavModule, MatSidenav } from '@angular/material/sidenav';
import { MatToolbarModule } from '@angular/material/toolbar';
import { MatIconModule } from '@angular/material/icon';
import { MatListModule } from '@angular/material/list';
import { MatButtonModule } from '@angular/material/button';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatDividerModule } from '@angular/material/divider';
import { MatDialog, MatDialogModule, MatDialogRef } from '@angular/material/dialog';

import { TranslateModule, TranslateService } from '@ngx-translate/core';

import { AccessibilityService } from './core/services/accessibility.service';
import { KeyboardShortcutsService } from './core/services/keyboard-shortcuts.service';
import { KeyboardHelpComponent } from './shared/components/keyboard-help/keyboard-help.component';
import { DeafModeService } from './core/services/deaf-mode.service';
import { SpeechSynthesisService } from './core/services/speech-synthesis.service';
import { ThemeService } from './core/services/theme.service';
import { AuthService } from './core/services/auth.service';
import { PwaService } from './core/services/pwa.service';
import { UserAccessibilityProfile } from './core/models/user-accessibility-profile.model';

interface NavItem {
  icon: string;
  label: string;
  route: string;
  colorClass?: string;
}

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [
    CommonModule,
    AsyncPipe,
    RouterOutlet,
    RouterLink,
    RouterLinkActive,
    MatSidenavModule,
    MatToolbarModule,
    MatIconModule,
    MatListModule,
    MatButtonModule,
    MatTooltipModule,
    MatDividerModule,
    MatDialogModule,
    TranslateModule,
  ],
  templateUrl: './app.component.html',
  styleUrl: './app.component.scss',
})
export class AppComponent implements OnInit, OnDestroy {
  @ViewChild('sidenav') sidenav!: MatSidenav;

  private readonly accessibilityService = inject(AccessibilityService);
  /** Arranca DeafModeService (root): escucha perfil y gestiona media / data-deaf-mode */
  private readonly deafModeService = inject(DeafModeService);
  private readonly themeService = inject(ThemeService);
  private readonly authService = inject(AuthService);
  private readonly translate = inject(TranslateService);
  private readonly router = inject(Router);
  private readonly speechSynthesis = inject(SpeechSynthesisService);
  private readonly keyboardShortcuts = inject(KeyboardShortcutsService);
  private readonly dialog = inject(MatDialog);
  private readonly destroyRef = inject(DestroyRef);
  private readonly breakpointObserver = inject(BreakpointObserver);
  private readonly destroy$ = new Subject<void>();
  private keyboardHelpRef: MatDialogRef<KeyboardHelpComponent> | null = null;

  isMobile = false;
  currentLanguage: 'es' | 'en' = 'es';
  sidenavMode: 'side' | 'over' = 'side';
  sidenavOpened = true;
  isPublicRoute = false;

  /** Observable del estado oscuro — usado con async pipe en el template */
  readonly isDark$ = this.themeService.isDarkTheme$;

  readonly pwaService = inject(PwaService);

  readonly navItems: NavItem[] = [
    { icon: 'analytics',  label: 'nav.analyzer',  route: '/analyzer' },
    { icon: 'swap_horiz', label: 'nav.migration',  route: '/migration',       colorClass: 'nav-migration' },
    { icon: 'schema',     label: 'nav.schema',     route: '/schema-explorer' },
    { icon: 'history',    label: 'nav.history',    route: '/history' },
    { icon: 'psychology', label: 'nav.ai',         route: '/ai-suggestions',  colorClass: 'nav-ai' },
    { icon: 'settings',   label: 'nav.settings',   route: '/settings' },
  ];

  ngOnInit(): void {
    this.breakpointObserver
      .observe(['(max-width: 767.98px)'])
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((result) => {
        const wasMobile = this.isMobile;
        this.isMobile = result.matches;
        this.sidenavMode = this.isMobile ? 'over' : 'side';
        // Solo ajustar abierto/cerrado al cruzar el breakpoint (no en cada emisión)
        if (this.isMobile && !wasMobile) {
          this.sidenavOpened = false;
        } else if (!this.isMobile && wasMobile) {
          this.sidenavOpened = true;
        }
      });

    this.accessibilityService.profile$
      .pipe(takeUntil(this.destroy$))
      .subscribe((profile: UserAccessibilityProfile) => {
        this.currentLanguage = profile.language as 'es' | 'en';
        this.translate.use(profile.language);
      });

    this.router.events.pipe(
      filter(e => e instanceof NavigationEnd),
      takeUntil(this.destroy$)
    ).subscribe((e: any) => {
      this.isPublicRoute = e.url.startsWith('/auth') || e.url.startsWith('/onboarding');
    });

    this.router.events.pipe(
      filter((e): e is NavigationStart => e instanceof NavigationStart),
      takeUntil(this.destroy$)
    ).subscribe(() => this.speechSynthesis.stop());

    this.isPublicRoute = this.router.url.startsWith('/auth') || this.router.url.startsWith('/onboarding');

    this.keyboardShortcuts.navAnalyzer$
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => void this.router.navigate(['/analyzer']));

    this.keyboardShortcuts.navMigration$
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => void this.router.navigate(['/migration']));

    this.keyboardShortcuts.navSchema$
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => void this.router.navigate(['/schema-explorer']));

    this.keyboardShortcuts.openHelp$
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => this.openKeyboardHelp());

    this.keyboardShortcuts.close$
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => this.closeOverlays());
  }

  ngOnDestroy(): void {
    this.speechSynthesis.stop();
    this.destroy$.next();
    this.destroy$.complete();
  }

  toggleSidenav(): void {
    this.sidenavOpened = !this.sidenavOpened;
  }

  toggleLanguage(): void {
    const next = this.currentLanguage === 'es' ? 'en' : 'es';
    this.accessibilityService.updateProfile({ language: next });
    this.translate.use(next);
  }

  toggleTheme(): void {
    this.themeService.toggle();
  }

  isGuestSession(): boolean {
    return this.authService.isGuest();
  }

  goToRegister(): void {
    this.router.navigate(['/auth'], { queryParams: { tab: 'register' } });
  }

  logout(): void {
    this.authService.logout();
  }

  private openKeyboardHelp(): void {
    if (this.keyboardHelpRef) {
      return;
    }
    this.keyboardHelpRef = this.dialog.open(KeyboardHelpComponent, {
      width: 'min(520px, 96vw)',
      panelClass: 'keyboard-help-dialog',
      autoFocus: 'first-tabbable',
      restoreFocus: true,
      ariaLabelledBy: 'keyboard-help-title',
    });
    this.keyboardHelpRef.afterClosed().subscribe(() => {
      this.keyboardHelpRef = null;
    });
  }

  private closeOverlays(): void {
    if (this.keyboardHelpRef) {
      this.keyboardHelpRef.close();
      this.keyboardHelpRef = null;
    }
    if (this.isMobile && this.sidenavOpened) {
      this.sidenavOpened = false;
    }
  }
}
