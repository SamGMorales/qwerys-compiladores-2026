import { Component, inject, signal, computed, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router, ActivatedRoute } from '@angular/router';
import { take } from 'rxjs';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { AccessibilityService } from '../../core/services/accessibility.service';
import { ThemeService } from '../../core/services/theme.service';
import { AuthService } from '../../core/services/auth.service';
import { UserAccessibilityProfile } from '../../core/models/user-accessibility-profile.model';

interface ModeCard {
  key: keyof UserAccessibilityProfile;
  icon: string;
  labelKey: string;
  descKey: string;
}

const MODE_CARDS: ModeCard[] = [
  // PENDIENTE: perfeccionar antes de mostrar al usuario
  // { key: 'blindMode', icon: '🦯', labelKey: 'onboarding.modes.blind.label', descKey: 'onboarding.modes.blind.desc' },
  { key: 'lowVisionMode', icon: '🔍', labelKey: 'onboarding.modes.lowVision.label',  descKey: 'onboarding.modes.lowVision.desc' },
  { key: 'dyslexiaMode',  icon: '📖', labelKey: 'onboarding.modes.dyslexia.label',   descKey: 'onboarding.modes.dyslexia.desc' },
  // { key: 'deafMode', icon: '🤟', labelKey: 'onboarding.modes.deaf.label', descKey: 'onboarding.modes.deaf.desc' },
  // { key: 'adhdMode', icon: '⚡', labelKey: 'onboarding.modes.adhd.label', descKey: 'onboarding.modes.adhd.desc' },
  { key: 'studentMode',   icon: '🎓', labelKey: 'onboarding.modes.student.label',    descKey: 'onboarding.modes.student.desc' },
  { key: 'expertMode',    icon: '🧑‍💻', labelKey: 'onboarding.modes.expert.label',     descKey: 'onboarding.modes.expert.desc' },
];

@Component({
  selector: 'app-onboarding',
  standalone: true,
  imports: [CommonModule, TranslateModule],
  templateUrl: './onboarding.component.html',
  styleUrls: ['./onboarding.component.scss'],
})
export class OnboardingComponent implements OnInit {
  private readonly accessibilityService = inject(AccessibilityService);
  private readonly themeService = inject(ThemeService);
  private readonly authService = inject(AuthService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly translate = inject(TranslateService);

  readonly modeCards = MODE_CARDS;
  readonly isGuest = signal(false);
  readonly isDark$ = this.themeService.isDarkTheme$;

  readonly selectedModes = signal<Partial<Record<keyof UserAccessibilityProfile, boolean>>>({});

  readonly subtitleKey = computed(() =>
    this.isGuest() ? 'onboarding.subtitleGuest' : 'onboarding.subtitleNew'
  );

  readonly currentLang = signal<'es' | 'en'>('es');

  ngOnInit(): void {
    const path = this.route.snapshot.url.map(s => s.path).join('/');
    this.isGuest.set(path.includes('guest') || this.authService.isGuest());
    this.currentLang.set(this.translate.currentLang as 'es' | 'en' ?? 'es');

    const profile = this.accessibilityService.getProfile();
    const initial: Partial<Record<keyof UserAccessibilityProfile, boolean>> = {};
    for (const card of MODE_CARDS) {
      initial[card.key] = profile[card.key] as boolean;
    }
    this.selectedModes.set(initial);
  }

  toggleMode(key: keyof UserAccessibilityProfile): void {
    const current = this.selectedModes();
    const next = { ...current, [key]: !current[key] };
    this.selectedModes.set(next);
    this.accessibilityService.updateProfile({ [key]: next[key] });
  }

  isSelected(key: keyof UserAccessibilityProfile): boolean {
    return !!this.selectedModes()[key];
  }

  setLanguage(lang: 'es' | 'en'): void {
    this.currentLang.set(lang);
    this.translate.use(lang);
    this.accessibilityService.updateProfile({ language: lang });
  }

  toggleTheme(): void {
    this.themeService.toggle();
    this.themeService.isDarkTheme$.pipe(take(1)).subscribe(isDark => {
      this.accessibilityService.updateProfile({ darkTheme: isDark });
    });
  }

  onStart(): void {
    if (!this.isGuest()) {
      const profile = this.accessibilityService.getProfile();
      this.authService
        .updateProfile({
          darkTheme: profile.darkTheme,
          language: profile.language,
          blindMode: profile.blindMode,
          lowVisionMode: profile.lowVisionMode,
          dyslexiaMode: profile.dyslexiaMode,
          deafMode: profile.deafMode,
          adhdMode: profile.adhdMode,
          studentMode: profile.studentMode,
          expertMode: profile.expertMode,
        })
        .subscribe({
          error: (err) => console.warn('[Onboarding] updateProfile failed:', err),
        });
    }
    this.router.navigate(['/analyzer']);
  }
}
