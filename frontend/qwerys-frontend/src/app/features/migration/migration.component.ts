import {
  Component,
  DestroyRef,
  OnInit,
  inject,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { BreakpointObserver } from '@angular/cdk/layout';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { finalize } from 'rxjs/operators';

import { MatFormFieldModule } from '@angular/material/form-field';
import { MatSelectModule } from '@angular/material/select';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MonacoEditorModule } from 'ngx-monaco-editor-v2';

/** Minimal Monaco editor surface used by this component (avoids direct monaco-editor dep). */
interface MonacoStandaloneEditor {
  getModel(): unknown;
  updateOptions(options: Record<string, unknown>): void;
  layout(): void;
}

interface MonacoEditorConstructionOptions {
  theme?: string;
  language?: string;
  readOnly?: boolean;
  automaticLayout?: boolean;
  minimap?: { enabled: boolean };
  scrollBeyondLastLine?: boolean;
  fontSize?: number;
  lineNumbers?: string;
  wordWrap?: string;
  accessibilitySupport?: string;
  ariaLabel?: string;
  touch?: boolean;
  scrollbar?: { handleMouseWheel?: boolean };
}

import { MigrationService } from '../../core/services/migration.service';
import { AiSuggestionService } from '../../core/services/ai-suggestion.service';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { AccessibilityService } from '../../core/services/accessibility.service';
import { ThemeService } from '../../core/services/theme.service';
import { CollapsibleSectionDirective } from '../../shared/directives/collapsible-section.directive';

/** API language ids (uppercase) sent to the backend — use CPP for C++, not C. */
export const SOURCE_LANGUAGES = ['CPP', 'PYTHON', 'JAVA', 'TYPESCRIPT'] as const;
export type SourceLanguage = (typeof SOURCE_LANGUAGES)[number];

/** Fallback when GET /targets is unavailable (9 strategy pairs). */
const FALLBACK_TARGETS: Record<SourceLanguage, string[]> = {
  CPP: ['JAVA', 'PYTHON', 'TYPESCRIPT'],
  PYTHON: ['JAVA', 'TYPESCRIPT'],
  JAVA: ['PYTHON', 'TYPESCRIPT'],
  TYPESCRIPT: ['JAVA', 'PYTHON'],
};

const MONACO_LANG: Record<string, string> = {
  CPP: 'cpp',
  PYTHON: 'python',
  JAVA: 'java',
  TYPESCRIPT: 'typescript',
};

const FILE_EXT: Record<string, string> = {
  JAVA: 'java',
  PYTHON: 'py',
  TYPESCRIPT: 'ts',
};

const SAMPLE_CODE: Record<SourceLanguage, string> = {
  CPP: `#include <iostream>

int main() {
    std::cout << "Hola" << std::endl;
    return 0;
}`,
  PYTHON: `def greet(name):
    print(f"Hola {name}")

if __name__ == '__main__':
    greet("mundo")`,
  JAVA: `public class Hello {
    public static void main(String[] args) {
        System.out.println("Hola");
    }
}`,
  TYPESCRIPT: `export function greet(name: string): void {
  console.log(\`Hola \${name}\`);
}

greet("mundo");`,
};

@Component({
  selector: 'app-migration',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    TranslateModule,
    MatFormFieldModule,
    MatSelectModule,
    MatButtonModule,
    MatIconModule,
    MatProgressSpinnerModule,
    MatSnackBarModule,
    MonacoEditorModule,
    CollapsibleSectionDirective,
  ],
  templateUrl: './migration.component.html',
  styleUrls: ['./migration.component.scss'],
})
export class MigrationComponent implements OnInit {
  private readonly migrationService = inject(MigrationService);
  private readonly aiSuggestionService = inject(AiSuggestionService);
  private readonly snackBar = inject(MatSnackBar);
  private readonly accessibility = inject(AccessibilityService);
  private readonly themeService = inject(ThemeService);
  private readonly translate = inject(TranslateService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly breakpointObserver = inject(BreakpointObserver);

  isMobile = false;
  readonly sourceLanguages = SOURCE_LANGUAGES;

  sourceLanguage: SourceLanguage = 'JAVA';
  targetLanguage = 'PYTHON';
  sourceCode = SAMPLE_CODE.JAVA;
  migratedCode = '';
  warnings: string[] = [];
  manualSteps: string[] = [];
  isConverting = false;
  availableTargets: string[] = [...FALLBACK_TARGETS.JAVA];
  error: string | null = null;
  copyFeedback: string | null = null;
  aiImproving = false;
  lastMigrationSuccess = false;

  sourceEditorOptions = this.buildEditorOptions(false, 'java');
  targetEditorOptions = this.buildEditorOptions(true, 'python');

  private sourceEditor?: MonacoStandaloneEditor;
  private targetEditor?: MonacoStandaloneEditor;

  /** Shown to screen readers after a successful migration. */
  resultAnnouncement = '';

  ngOnInit(): void {
    this.breakpointObserver
      .observe(['(max-width: 767.98px)'])
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((result) => {
        this.isMobile = result.matches;
        this.sourceEditor?.layout();
        this.targetEditor?.layout();
      });

    this.loadAvailableTargets();

    this.themeService.isDarkTheme$
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((isDark) => this.patchEditorTheme(isDark ? 'vs-dark' : 'vs'));

    this.accessibility.profile$
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((profile) => {
        const a11y = profile.blindMode ? 'on' : 'auto';
        this.patchAccessibilitySupport(a11y);
      });
  }

  languageLabelKey(lang: string): string {
    return `migration.languages.${lang.toLowerCase()}`;
  }

  onSourceLanguageChange(): void {
    this.error = null;
    this.migratedCode = '';
    this.warnings = [];
    this.manualSteps = [];
    this.resultAnnouncement = '';
    this.sourceCode = SAMPLE_CODE[this.sourceLanguage];
    this.refreshSourceEditorLanguage();
    this.loadAvailableTargets();
  }

  onTargetLanguageChange(): void {
    this.refreshTargetEditorLanguage();
  }

  migrate(): void {
    const code = this.sourceCode?.trim();
    if (!code || this.isConverting) {
      return;
    }

    this.isConverting = true;
    this.error = null;
    this.copyFeedback = null;
    this.resultAnnouncement = '';

    this.migrationService
      .convert(code, this.sourceLanguage, this.targetLanguage)
      .pipe(
        finalize(() => {
          this.isConverting = false;
        })
      )
      .subscribe({
        next: (result) => {
          this.migratedCode = result.migratedCode ?? '';
          this.warnings = result.warnings ?? [];
          this.manualSteps = result.manualSteps ?? [];
          this.lastMigrationSuccess = result.success;

          if (!result.success) {
            const msg =
              result.warnings?.[0] ??
              this.translate.instant('migration.errors.conversionFailed');
            this.error = msg;
            this.resultAnnouncement = msg;
            return;
          }

          this.error = null;
          this.resultAnnouncement = this.translate.instant(
            'migration.resultReady',
            { language: this.translate.instant(this.languageLabelKey(this.targetLanguage)) }
          );
          this.refreshTargetEditorLanguage();
        },
        error: (err: Error) => {
          this.error = err.message;
          this.migratedCode = '';
          this.warnings = [];
          this.manualSteps = [];
          this.resultAnnouncement = err.message;
        },
      });
  }

  async copyCode(): Promise<void> {
    if (!this.migratedCode?.trim()) {
      return;
    }
    try {
      await navigator.clipboard.writeText(this.migratedCode);
      this.copyFeedback = this.translate.instant('migration.copied');
      setTimeout(() => {
        this.copyFeedback = null;
      }, 2500);
    } catch {
      this.copyFeedback = this.translate.instant('migration.copyFailed');
    }
  }

  downloadCode(): void {
    if (!this.migratedCode?.trim()) {
      return;
    }
    const ext = FILE_EXT[this.targetLanguage] ?? 'txt';
    const blob = new Blob([this.migratedCode], { type: 'text/plain;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const anchor = document.createElement('a');
    anchor.href = url;
    anchor.download = `migrated.${ext}`;
    anchor.click();
    URL.revokeObjectURL(url);
  }

  onSourceEditorInit(editor: MonacoStandaloneEditor): void {
    this.sourceEditor = editor;
    this.applyProfileToEditor(editor);
    queueMicrotask(() => editor.layout());
  }

  onTargetEditorInit(editor: MonacoStandaloneEditor): void {
    this.targetEditor = editor;
    this.applyProfileToEditor(editor);
    queueMicrotask(() => editor.layout());
  }

  private loadAvailableTargets(): void {
    this.migrationService.getAvailableTargets(this.sourceLanguage).subscribe({
      next: (targets) => {
        this.availableTargets =
          targets?.length > 0 ? targets : [...FALLBACK_TARGETS[this.sourceLanguage]];
        this.ensureValidTarget();
      },
      error: () => {
        this.availableTargets = [...FALLBACK_TARGETS[this.sourceLanguage]];
        this.ensureValidTarget();
      },
    });
  }

  private ensureValidTarget(): void {
    if (!this.availableTargets.includes(this.targetLanguage)) {
      this.targetLanguage = this.availableTargets[0] ?? 'PYTHON';
      this.refreshTargetEditorLanguage();
    }
  }

  private buildEditorOptions(readOnly: boolean, language: string): MonacoEditorConstructionOptions {
    const profile = this.accessibility.getProfile();
    const isDark =
      document.body.classList.contains('dark-theme') || profile.darkTheme;
    return {
      theme: isDark ? 'vs-dark' : 'vs',
      language,
      readOnly,
      automaticLayout: true,
      minimap: { enabled: false },
      scrollBeyondLastLine: false,
      fontSize: profile.lowVisionMode ? 18 : 14,
      lineNumbers: 'on',
      wordWrap: 'on',
      touch: true,
      scrollbar: { handleMouseWheel: false },
      accessibilitySupport: profile.blindMode ? 'on' : 'auto',
      ariaLabel: readOnly
        ? this.translate.instant('migration.targetEditorLabel')
        : this.translate.instant('migration.sourceEditorLabel'),
    };
  }

  private refreshSourceEditorLanguage(): void {
    const lang = MONACO_LANG[this.sourceLanguage] ?? 'plaintext';
    this.sourceEditorOptions = { ...this.sourceEditorOptions, language: lang };
    this.setModelLanguage(this.sourceEditor, lang);
  }

  private refreshTargetEditorLanguage(): void {
    const lang = MONACO_LANG[this.targetLanguage] ?? 'plaintext';
    this.targetEditorOptions = { ...this.targetEditorOptions, language: lang };
    this.setModelLanguage(this.targetEditor, lang);
  }

  private setModelLanguage(ed: MonacoStandaloneEditor | undefined, lang: string): void {
    const model = ed?.getModel();
    const monaco = (window as unknown as { monaco?: { editor: { setModelLanguage: (m: unknown, l: string) => void } } }).monaco;
    if (model && monaco?.editor?.setModelLanguage) {
      monaco.editor.setModelLanguage(model, lang);
    }
  }

  private patchEditorTheme(theme: 'vs' | 'vs-dark'): void {
    this.sourceEditorOptions = { ...this.sourceEditorOptions, theme };
    this.targetEditorOptions = { ...this.targetEditorOptions, theme };
    this.sourceEditor?.updateOptions({ theme });
    this.targetEditor?.updateOptions({ theme });
  }

  private patchAccessibilitySupport(support: 'on' | 'auto'): void {
    this.sourceEditorOptions = { ...this.sourceEditorOptions, accessibilitySupport: support };
    this.targetEditorOptions = { ...this.targetEditorOptions, accessibilitySupport: support };
    this.sourceEditor?.updateOptions({ accessibilitySupport: support });
    this.targetEditor?.updateOptions({ accessibilitySupport: support });
  }

  showImproveWithAi(): boolean {
    return (
      !!this.migratedCode?.trim() &&
      (!this.lastMigrationSuccess ||
        this.warnings.length > 0 ||
        this.manualSteps.length > 0)
    );
  }

  improveWithAI(): void {
    if (!this.migratedCode?.trim() || this.aiImproving) {
      return;
    }
    this.aiImproving = true;
    this.aiSuggestionService
      .improveMigration(
        this.sourceCode,
        this.sourceLanguage,
        this.targetLanguage,
        this.migratedCode,
        this.warnings,
        this.manualSteps
      )
      .subscribe({
        next: (res) => {
          this.aiImproving = false;
          if (res.success && res.result) {
            this.migratedCode = res.result;
            this.resultAnnouncement = this.translate.instant('ai.migrationImproved');
            this.refreshTargetEditorLanguage();
          } else {
            this.snackBar.open(
              res.error ?? this.translate.instant('ai.genericError'),
              undefined,
              { duration: 5000, panelClass: ['error-snackbar'] }
            );
          }
        },
        error: () => {
          this.aiImproving = false;
        },
      });
  }

  private applyProfileToEditor(ed: MonacoStandaloneEditor): void {
    const profile = this.accessibility.getProfile();
    const isDark =
      document.body.classList.contains('dark-theme') || profile.darkTheme;
    ed.updateOptions({
      theme: isDark ? 'vs-dark' : 'vs',
      fontSize: profile.lowVisionMode ? 18 : 14,
      accessibilitySupport: profile.blindMode ? 'on' : 'auto',
    });
  }
}
