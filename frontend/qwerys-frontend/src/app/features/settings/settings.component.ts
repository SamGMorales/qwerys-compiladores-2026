import { Component, inject, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { TranslateModule, TranslateService } from '@ngx-translate/core';

import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatButtonModule } from '@angular/material/button';
import { MatRadioModule } from '@angular/material/radio';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatIconModule } from '@angular/material/icon';
import { MatExpansionModule } from '@angular/material/expansion';
import { MatChipsModule } from '@angular/material/chips';
import { FormsModule } from '@angular/forms';

import { AccessibilityService } from '../../core/services/accessibility.service';
import { AuthService } from '../../core/services/auth.service';
import { UserAccessibilityProfile } from '../../core/models/user-accessibility-profile.model';
import { CUSTOM_DATABASES_STORAGE_KEY } from '../../core/services/query.service';
import { AiSuggestionService } from '../../core/services/ai-suggestion.service';

type AccessibilityToggleKey =
  | 'blindMode'
  | 'lowVisionMode'
  | 'dyslexiaMode'
  | 'deafMode'
  | 'adhdMode'
  | 'studentMode'
  | 'expertMode'
  | 'highContrast';

export interface CustomEngineRow {
  name: string;
  base: string;
}

@Component({
  selector: 'app-settings',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    TranslateModule,
    MatSlideToggleModule,
    MatButtonModule,
    MatRadioModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatIconModule,
    MatExpansionModule,
    MatChipsModule,
  ],
  templateUrl: './settings.component.html',
  styleUrl: './settings.component.scss',
})
export class SettingsComponent implements OnInit {
  readonly accessibilityService = inject(AccessibilityService);
  private readonly translate = inject(TranslateService);
  private readonly authService = inject(AuthService);
  private readonly http = inject(HttpClient);
  private readonly aiSuggestionService = inject(AiSuggestionService);

  aiConfigured = false;

  readonly profile$ = this.accessibilityService.profile$;

  // Cambiar contraseña
  currentPwd = '';
  newPwd = '';
  confirmPwd = '';
  pwdError = '';
  pwdSuccess = '';

  // Cambiar email
  newEmail = '';
  emailPwd = '';
  emailError = '';
  emailSuccess = '';

  // Eliminar cuenta
  showDeleteConfirm = false;
  deleteError = '';

  readonly predefinedEnginesReadonly = [
    'MySQL',
    'PostgreSQL',
    'SQLite',
    'SQL Server',
    'Oracle',
    'MongoDB',
    'Redis',
    'Cassandra',
    'DynamoDB',
    'Elasticsearch',
  ] as const;

  customDatabases: CustomEngineRow[] = [];
  newEngineName = '';
  newEngineBase = 'mysql';

  backendEnginesSyncState: 'idle' | 'saving' | 'ok' | 'error' = 'idle';

  readonly accessibilityModes: ReadonlyArray<{
    key: AccessibilityToggleKey;
    emoji: string;
    labelKey: string;
    descKey: string;
  }> = [
    // PENDIENTE: perfeccionar antes de mostrar al usuario
    // {
    //   key: 'blindMode',
    //   emoji: '👁️',
    //   labelKey: 'settings.accessibility.blind.label',
    //   descKey: 'settings.accessibility.blind.desc',
    // },
    {
      key: 'lowVisionMode',
      emoji: '🔍',
      labelKey: 'settings.accessibility.lowVision.label',
      descKey: 'settings.accessibility.lowVision.desc',
    },
    {
      key: 'dyslexiaMode',
      emoji: '📖',
      labelKey: 'settings.accessibility.dyslexia.label',
      descKey: 'settings.accessibility.dyslexia.desc',
    },
    // {
    //   key: 'deafMode',
    //   emoji: '🔇',
    //   labelKey: 'settings.accessibility.deaf.label',
    //   descKey: 'settings.accessibility.deaf.desc',
    // },
    // {
    //   key: 'adhdMode',
    //   emoji: '🧩',
    //   labelKey: 'settings.accessibility.adhd.label',
    //   descKey: 'settings.accessibility.adhd.desc',
    // },
    {
      key: 'studentMode',
      emoji: '🎓',
      labelKey: 'settings.accessibility.student.label',
      descKey: 'settings.accessibility.student.desc',
    },
    {
      key: 'expertMode',
      emoji: '👨‍💻',
      labelKey: 'settings.accessibility.expert.label',
      descKey: 'settings.accessibility.expert.desc',
    },
    {
      key: 'highContrast',
      emoji: '🌑',
      labelKey: 'settings.accessibility.highContrast.label',
      descKey: 'settings.accessibility.highContrast.desc',
    },
  ];

  readonly deviceTypes: ReadonlyArray<{
    value: UserAccessibilityProfile['deviceType'];
    labelKey: string;
  }> = [
    { value: 'mobile', labelKey: 'settings.device.mobile' },
    { value: 'tablet', labelKey: 'settings.device.tablet' },
    { value: 'laptop', labelKey: 'settings.device.laptop' },
    { value: 'desktop', labelKey: 'settings.device.desktop' },
  ];

  ngOnInit(): void {
    this.loadCustomEnginesFromLocalStorage();
    this.aiSuggestionService.checkStatus().subscribe((s) => {
      this.aiConfigured = s.available;
    });

    if (this.authService.isLoggedIn() && !this.authService.isGuest()) {
      this.authService.fetchProfile().subscribe({
        next: (p) => {
          this.hydrateCustomEnginesFromServerStrings(p.customDatabases ?? []);
          this.saveCustomEnginesToLocalStorageOnly();
        },
        error: () => {
          /* invalid session or offline — keep localStorage copy */
        },
      });
    }
  }

  /** Stable rows for `@for` when names/bases aren't unique IDs. */
  get customEnginesWithIndex(): ReadonlyArray<{ engine: CustomEngineRow; index: number }> {
    return this.customDatabases.map((engine, index) => ({ engine, index }));
  }

  get canSyncEnginesToAccount(): boolean {
    return this.authService.isLoggedIn() && !this.authService.isGuest();
  }

  private loadCustomEnginesFromLocalStorage(): void {
    try {
      const raw = localStorage.getItem(CUSTOM_DATABASES_STORAGE_KEY);
      if (!raw?.trim()) {
        this.customDatabases = [];
        return;
      }
      const parsed = JSON.parse(raw) as unknown;
      if (!Array.isArray(parsed)) {
        this.customDatabases = [];
        return;
      }
      const rows: CustomEngineRow[] = [];
      for (const item of parsed) {
        if (item != null && typeof item === 'object' && !Array.isArray(item)) {
          const o = item as Record<string, unknown>;
          const name = typeof o['name'] === 'string' ? o['name'].trim() : '';
          const base = typeof o['base'] === 'string' ? o['base'].trim().toLowerCase() : '';
          if (name && !name.includes('::') && base) {
            rows.push({ name, base });
          }
        }
      }
      this.customDatabases = rows;
    } catch {
      this.customDatabases = [];
    }
  }

  private hydrateCustomEnginesFromServerStrings(entries: string[]): void {
    this.customDatabases = entries.map((entry) => {
      const trimmed = entry.trim();
      if (!trimmed.includes('::')) {
        return { name: trimmed, base: 'mysql' };
      }
      const [name, base] = trimmed.split('::', 2);
      return {
        name: name.trim(),
        base: (base ?? 'mysql').trim().toLowerCase(),
      };
    });
  }

  private saveCustomEnginesToLocalStorageOnly(): void {
    try {
      localStorage.setItem(CUSTOM_DATABASES_STORAGE_KEY, JSON.stringify(this.customDatabases));
    } catch {
      /* ignore */
    }
  }

  private notifyAnalyzerEnginesChanged(): void {
    window.dispatchEvent(new Event('qwerys-custom-engines-changed'));
  }

  addCustomEngine(): void {
    const name = this.newEngineName.trim();
    if (!name || name.includes('::')) return;
    const base = this.newEngineBase;
    const key = `${name}::${base}`;
    if (this.customDatabases.some((r) => `${r.name.trim()}::${r.base}` === key)) {
      return;
    }
    this.customDatabases = [...this.customDatabases, { name, base }];
    this.newEngineName = '';
    this.persistCustomEngines();
  }

  removeCustomEngine(index: number): void {
    this.customDatabases = this.customDatabases.filter((_, i) => i !== index);
    this.persistCustomEngines();
  }

  /**
   * Always mirror to localStorage; registered (non-guest) users also PUT /api/auth/profile.
   */
  private persistCustomEngines(): void {
    this.saveCustomEnginesToLocalStorageOnly();
    this.notifyAnalyzerEnginesChanged();

    if (!this.canSyncEnginesToAccount) {
      this.backendEnginesSyncState = 'idle';
      return;
    }

    for (const r of this.customDatabases) {
      const n = r.name.trim();
      if (!n || n.includes('::')) {
        this.backendEnginesSyncState = 'error';
        return;
      }
    }

    this.backendEnginesSyncState = 'saving';
    const payload = this.customDatabases.map((r) => `${r.name.trim()}::${r.base}`);
    this.authService.updateProfile({ customDatabases: payload }).subscribe({
      next: () => {
        this.backendEnginesSyncState = 'ok';
      },
      error: () => {
        this.backendEnginesSyncState = 'error';
      },
    });
  }

  onModeToggle(key: AccessibilityToggleKey, checked: boolean): void {
    this.accessibilityService.updateProfile({ [key]: checked });
  }

  onDeviceChange(type: UserAccessibilityProfile['deviceType']): void {
    this.accessibilityService.updateProfile({ deviceType: type });
  }

  detectDevice(): void {
    const deviceType = this.accessibilityService.detectDeviceType();
    this.accessibilityService.updateProfile({ deviceType });
  }

  setLanguage(lang: 'es' | 'en'): void {
    this.accessibilityService.updateProfile({ language: lang });
    this.translate.use(lang);
  }

  changePassword(): void {
    this.pwdError = '';
    this.pwdSuccess = '';
    const token = this.authService.getAccessToken();
    if (!token) {
      this.pwdError = this.translate.instant('settings.account.sessionRequired');
      return;
    }
    if (this.newPwd !== this.confirmPwd) {
      this.pwdError = this.translate.instant('settings.account.passwordMismatch');
      return;
    }
    if (this.newPwd.length < 6) {
      this.pwdError = this.translate.instant('settings.account.passwordMinLength');
      return;
    }
    this.http
      .put('/api/auth/change-password', { currentPassword: this.currentPwd, newPassword: this.newPwd }, {
        headers: { Authorization: `Bearer ${token}` },
      })
      .subscribe({
        next: () => {
          this.pwdSuccess = this.translate.instant('settings.account.passwordUpdated');
          this.currentPwd = '';
          this.newPwd = '';
          this.confirmPwd = '';
        },
        error: (err: { error?: { error?: string } }) => {
          this.pwdError = err.error?.error ?? this.translate.instant('settings.account.passwordChangeFailed');
        },
      });
  }

  changeEmail(): void {
    this.emailError = '';
    this.emailSuccess = '';
    const token = this.authService.getAccessToken();
    if (!token) {
      this.emailError = this.translate.instant('settings.account.sessionRequired');
      return;
    }
    this.http
      .put('/api/auth/change-email', { newEmail: this.newEmail, currentPassword: this.emailPwd }, {
        headers: { Authorization: `Bearer ${token}` },
      })
      .subscribe({
        next: () => {
          this.emailSuccess = this.translate.instant('settings.account.emailUpdated');
          this.newEmail = '';
          this.emailPwd = '';
        },
        error: (err: { error?: { error?: string } }) => {
          this.emailError = err.error?.error ?? this.translate.instant('settings.account.emailChangeFailed');
        },
      });
  }

  deleteAccount(): void {
    this.deleteError = '';
    const token = this.authService.getAccessToken();
    if (!token) {
      this.deleteError = this.translate.instant('settings.account.sessionRequired');
      return;
    }
    this.http
      .delete('/api/auth/account', {
        headers: { Authorization: `Bearer ${token}` },
      })
      .subscribe({
        next: () => {
          this.showDeleteConfirm = false;
          this.authService.logout();
        },
        error: () => {
          this.deleteError = this.translate.instant('settings.account.deleteFailed');
        },
      });
  }
}
