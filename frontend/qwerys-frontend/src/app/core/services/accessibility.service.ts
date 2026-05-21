import { Injectable } from '@angular/core';
import { BehaviorSubject } from 'rxjs';
import { UserAccessibilityProfile } from '../models/user-accessibility-profile.model';

const STORAGE_KEY = 'qwerys-accessibility-profile';

const DEFAULT_PROFILE: UserAccessibilityProfile = {
  blindMode: false,
  lowVisionMode: false,
  dyslexiaMode: false,
  deafMode: false,
  adhdMode: false,
  studentMode: false,
  expertMode: false,
  highContrast: false,
  deviceType: 'desktop',
  language: 'es',
  darkTheme: false,
};

const MODE_CLASS_MAP: Partial<Record<keyof UserAccessibilityProfile, string>> = {
  blindMode: 'mode-blind',
  lowVisionMode: 'mode-low-vision',
  dyslexiaMode: 'mode-dyslexia',
  deafMode: 'mode-deaf',
  adhdMode: 'mode-adhd',
  studentMode: 'mode-student',
  expertMode: 'mode-expert',
  highContrast: 'mode-high-contrast',
};

@Injectable({ providedIn: 'root' })
export class AccessibilityService {
  private readonly _profile$ = new BehaviorSubject<UserAccessibilityProfile>(
    this.loadFromStorage()
  );

  readonly profile$ = this._profile$.asObservable();

  constructor() {
    this.applyBodyClasses(this._profile$.value);
  }

  getProfile(): UserAccessibilityProfile {
    return this._profile$.value;
  }

  updateProfile(partial: Partial<UserAccessibilityProfile>): void {
    const updated: UserAccessibilityProfile = { ...this._profile$.value, ...partial };
    // Student and expert modes are mutually exclusive — different analysis UX.
    if (partial.studentMode === true) {
      updated.expertMode = false;
    }
    if (partial.expertMode === true) {
      updated.studentMode = false;
    }
    this.persist(updated);
    this.applyBodyClasses(updated);
    this._profile$.next(updated);
  }

  resetProfile(): void {
    const reset = { ...DEFAULT_PROFILE, deviceType: this.detectDeviceType() };
    this.persist(reset);
    this.applyBodyClasses(reset);
    this._profile$.next(reset);
  }

  toggleMode(modeName: keyof UserAccessibilityProfile): void {
    const current = this._profile$.value;
    if (typeof current[modeName] !== 'boolean') return;
    const next = !current[modeName];
    if (modeName === 'studentMode' && next) {
      this.updateProfile({ studentMode: true, expertMode: false });
      return;
    }
    if (modeName === 'expertMode' && next) {
      this.updateProfile({ expertMode: true, studentMode: false });
      return;
    }
    this.updateProfile({ [modeName]: next });
  }

  detectDeviceType(): UserAccessibilityProfile['deviceType'] {
    const width = window.innerWidth;
    if (width < 480) return 'mobile';
    if (width < 768) return 'tablet';
    if (width < 1024) return 'laptop';
    return 'desktop';
  }

  private loadFromStorage(): UserAccessibilityProfile {
    try {
      const raw = localStorage.getItem(STORAGE_KEY);
      if (raw) {
        const merged = { ...DEFAULT_PROFILE, ...JSON.parse(raw) } as UserAccessibilityProfile;
        if (merged.studentMode && merged.expertMode) {
          merged.expertMode = false;
        }
        return merged;
      }
    } catch {
      // storage not available or corrupted — fall through to default
    }
    return { ...DEFAULT_PROFILE };
  }

  private persist(profile: UserAccessibilityProfile): void {
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(profile));
    } catch {
      // storage quota exceeded or unavailable — silently ignore
    }
  }

  private applyBodyClasses(profile: UserAccessibilityProfile): void {
    const body = document.body;
    for (const [key, cssClass] of Object.entries(MODE_CLASS_MAP) as [
      keyof UserAccessibilityProfile,
      string
    ][]) {
      body.classList.toggle(cssClass, profile[key] as boolean);
    }
  }
}
