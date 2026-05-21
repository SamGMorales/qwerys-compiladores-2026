import {
  Component,
  DestroyRef,
  OnInit,
  inject,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { combineLatest, fromEvent, Subject, timer } from 'rxjs';
import { debounceTime, distinctUntilChanged, finalize, map } from 'rxjs/operators';

import { MatTabsModule } from '@angular/material/tabs';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatCardModule } from '@angular/material/card';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MonacoEditorModule } from 'ngx-monaco-editor-v2';

import {
  AI_SUPPORTED_ENGINES,
  AiResponse,
  AiSuggestionService,
  TableInfo,
} from '../../core/services/ai-suggestion.service';
import { AccessibilityService } from '../../core/services/accessibility.service';
import { AuthService } from '../../core/services/auth.service';
import { HistoryStateService } from '../../core/services/history-state.service';
import { QueryHistoryService, HistoryEntry } from '../../core/services/query-history.service';
import { ThemeService } from '../../core/services/theme.service';
import { CollapsibleSectionDirective } from '../../shared/directives/collapsible-section.directive';
import {
  readSchemaSnapshot,
  isExplorerConnected,
  schemaConnectionSummary,
  SCHEMA_SNAPSHOT_CHANGED,
  SchemaConnectionSummary,
} from '../../shared/utils/schema-snapshot.util';

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

export interface SecurityFinding {
  id: string;
  query: string;
  queryPreview: string;
  databaseType: string;
  patternId: string;
  riskKey: string;
  fixKey: string;
  ruleKey: string;
}

const SECURITY_AI_PREF_KEY = 'qwerys-ai-security-enrichment';

const INJECTION_CHECKS: { id: string; pattern: RegExp; riskKey: string; fixKey: string; ruleKey: string }[] = [
  {
    id: 'string-concat',
    pattern: /['"][^'"]*['"]\s*\+|\+\s*['"][^'"]*['"]/i,
    riskKey: 'ai.security.risks.concat',
    fixKey: 'ai.security.fixes.concat',
    ruleKey: 'ai.security.rules.concat',
  },
  {
    id: 'dynamic-exec',
    pattern: /\b(EXEC|EXECUTE)\s*\(\s*@|\bsp_executesql\b/i,
    riskKey: 'ai.security.risks.dynamicExec',
    fixKey: 'ai.security.fixes.dynamicExec',
    ruleKey: 'ai.security.rules.dynamicExec',
  },
  {
    id: 'unsafe-drop',
    pattern: /\bDROP\s+(TABLE|DATABASE|COLLECTION|INDEX)\b/i,
    riskKey: 'ai.security.risks.drop',
    fixKey: 'ai.security.fixes.drop',
    ruleKey: 'ai.security.rules.drop',
  },
  {
    id: 'mongo-where',
    pattern: /\$where\s*:/i,
    riskKey: 'ai.security.risks.mongoWhere',
    fixKey: 'ai.security.fixes.mongoWhere',
    ruleKey: 'ai.security.rules.mongoWhere',
  },
  {
    id: 'redis-eval',
    pattern: /\bEVAL\s+['"]/i,
    riskKey: 'ai.security.risks.redisEval',
    fixKey: 'ai.security.fixes.redisEval',
    ruleKey: 'ai.security.rules.redisEval',
  },
  {
    id: 'es-script',
    pattern: /"script"\s*:\s*\{|"inline"\s*:/i,
    riskKey: 'ai.security.risks.esScript',
    fixKey: 'ai.security.fixes.esScript',
    ruleKey: 'ai.security.rules.esScript',
  },
];

@Component({
  selector: 'app-ai-suggestions',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    TranslateModule,
    MatTabsModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatButtonModule,
    MatIconModule,
    MatProgressSpinnerModule,
    MatSnackBarModule,
    MatCardModule,
    MatSlideToggleModule,
    MonacoEditorModule,
    CollapsibleSectionDirective,
    RouterLink,
  ],
  templateUrl: './ai-suggestions.component.html',
  styleUrls: ['./ai-suggestions.component.scss'],
})
export class AiSuggestionsComponent implements OnInit {
  private readonly aiService = inject(AiSuggestionService);
  private readonly accessibility = inject(AccessibilityService);
  private readonly authService = inject(AuthService);
  private readonly historyState = inject(HistoryStateService);
  private readonly historyService = inject(QueryHistoryService);
  private readonly themeService = inject(ThemeService);
  private readonly snackBar = inject(MatSnackBar);
  private readonly translate = inject(TranslateService);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);

  readonly engines = AI_SUPPORTED_ENGINES;
  readonly operations = ['SELECT', 'INSERT', 'UPDATE', 'DELETE'] as const;

  readonly vm$ = combineLatest([this.accessibility.profile$]).pipe(
    map(([profile]) => ({
      studentMode: profile.studentMode,
      expertMode: profile.expertMode,
    }))
  );

  aiStatusAvailable = false;
  databaseType = 'mysql';
  schemaTables: TableInfo[] = [];
  /** Active Schema Explorer connection (shared session; independent from Query Analyzer). */
  schemaConnection: SchemaConnectionSummary | null = null;

  // Tab 1 — Generate
  naturalDescription = '';
  generatedQuery = '';
  generating = false;
  lastMeta: Pick<AiResponse, 'provider' | 'responseTimeMs' | 'aiAvailable'> | null = null;

  // Tab 2 — Autocomplete
  partialQuery = '';
  completedQuery = '';
  autocompleteLoading = false;
  private readonly autocompleteInput$ = new Subject<string>();

  // Tab 3 — Builder
  operation: (typeof this.operations)[number] = 'SELECT';
  tableName = '';
  columnsCsv = '';
  whereCondition = '';
  builtQuery = '';
  building = false;

  // Tab 4 — Security (rule-based scan + optional AI enrichment)
  securityFindings: SecurityFinding[] = [];
  securityLoading = false;
  securityScanned = false;
  /** User opt-in: AI explanations for security findings (off by default). */
  securityAiEnrichment = false;
  securityAiExplanations: Record<string, string> = {};
  securityAiLoadingId: string | null = null;

  monacoOptions: MonacoEditorConstructionOptions = this.buildMonacoOptions();

  /** Cuenta registrada (JWT); invitados ven pantalla de acceso. */
  isRegisteredUser = false;

  ngOnInit(): void {
    this.syncRegisteredAccess();
    this.loadSchemaFromSession();
    this.loadSecurityAiPreference();
    this.refreshAiStatus();

    this.authService.isAuthenticated$
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => {
        this.syncRegisteredAccess();
        if (this.isRegisteredUser) {
          this.refreshAiStatus();
        } else {
          this.aiStatusAvailable = false;
        }
      });

    // Si el backend arrancó después del frontend, reintentar una vez
    timer(2500)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => {
        if (!this.aiStatusAvailable && this.authService.isLoggedIn()) {
          this.refreshAiStatus();
        }
      });

    this.themeService.isDarkTheme$
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((isDark) => {
        this.monacoOptions = {
          ...this.monacoOptions,
          theme: isDark ? 'vs-dark' : 'vs',
        };
      });

    this.translate.onLangChange
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => {
        this.securityAiExplanations = {};
        this.completedQuery = '';
        this.generatedQuery = '';
        this.builtQuery = '';
      });

    this.autocompleteInput$
      .pipe(debounceTime(800), distinctUntilChanged(), takeUntilDestroyed(this.destroyRef))
      .subscribe(() => this.runAutocomplete());

    if (typeof window !== 'undefined') {
      fromEvent(window, SCHEMA_SNAPSHOT_CHANGED)
        .pipe(takeUntilDestroyed(this.destroyRef))
        .subscribe(() => this.loadSchemaFromSession());
    }
  }

  openSchemaExplorer(): void {
    void this.router.navigate(['/schema-explorer']);
  }

  onPartialQueryChange(): void {
    this.autocompleteInput$.next(this.partialQuery);
  }

  /** Consulta si Groq está configurado en el backend (requiere sesión). */
  refreshAiStatus(): void {
    if (!this.authService.isLoggedIn()) {
      this.aiStatusAvailable = false;
      return;
    }
    this.aiService.checkStatus().subscribe((s) => {
      this.aiStatusAvailable = s.available;
    });
  }

  get continuationHighlight(): string {
    if (!this.completedQuery || !this.partialQuery) {
      return '';
    }
    if (this.completedQuery.startsWith(this.partialQuery)) {
      return this.completedQuery.slice(this.partialQuery.length);
    }
    return this.completedQuery;
  }

  generateFromDescription(): void {
    if (!this.naturalDescription.trim() || this.generating) {
      return;
    }
    this.generating = true;
    this.aiService
      .suggestQuery(this.naturalDescription, this.databaseType, this.schemaTables)
      .pipe(finalize(() => (this.generating = false)))
      .subscribe((res) => this.handleAiResponse(res, (text) => {
        this.generatedQuery = text;
        this.patchMonaco(text);
      }));
  }

  runAutocomplete(): void {
    if (!this.partialQuery.trim() || this.autocompleteLoading) {
      return;
    }
    this.autocompleteLoading = true;
    this.aiService
      .autocomplete(this.partialQuery, this.databaseType, this.schemaTables)
      .pipe(finalize(() => (this.autocompleteLoading = false)))
      .subscribe((res) =>
        this.handleAiResponse(res, (text) => {
          this.completedQuery = text;
        })
      );
  }

  buildStructuredQuery(): void {
    if (!this.tableName.trim() || this.building) {
      return;
    }
    const columns = this.columnsCsv
      .split(',')
      .map((c) => c.trim())
      .filter(Boolean);
    this.building = true;
    this.aiService
      .generateQuery(
        this.operation,
        this.tableName.trim(),
        columns,
        this.whereCondition.trim(),
        this.databaseType
      )
      .pipe(finalize(() => (this.building = false)))
      .subscribe((res) =>
        this.handleAiResponse(res, (text) => {
          this.builtQuery = text;
          this.patchMonaco(text);
        })
      );
  }

  onSecurityAiEnrichmentChange(enabled: boolean): void {
    this.securityAiEnrichment = enabled;
    try {
      localStorage.setItem(SECURITY_AI_PREF_KEY, enabled ? '1' : '0');
    } catch {
      /* ignore */
    }
    if (!enabled) {
      this.securityAiExplanations = {};
      this.securityAiLoadingId = null;
    }
  }

  explainSecurityWithAi(finding: SecurityFinding): void {
    if (!this.securityAiEnrichment || this.securityAiLoadingId) {
      return;
    }
    this.securityAiLoadingId = finding.id;
    const riskSummary = this.translate.instant(finding.riskKey);
    this.aiService
      .explainSecurityFinding(
        finding.query,
        finding.databaseType,
        finding.patternId,
        finding.ruleKey,
        riskSummary,
        this.aiService.currentLocale()
      )
      .pipe(finalize(() => (this.securityAiLoadingId = null)))
      .subscribe((res) => {
        if (res.success && res.result) {
          this.securityAiExplanations = {
            ...this.securityAiExplanations,
            [finding.id]: res.result,
          };
          this.lastMeta = {
            provider: res.provider,
            responseTimeMs: res.responseTimeMs,
            aiAvailable: res.aiAvailable,
          };
        } else {
          this.showError(res.error ?? this.translate.instant('ai.genericError'));
        }
      });
  }

  scanSecurityHistory(): void {
    if (!this.authService.isLoggedIn()) {
      return;
    }
    this.securityLoading = true;
    this.securityFindings = [];
    this.securityAiExplanations = {};
    this.historyService
      .getHistory(0, 50)
      .pipe(finalize(() => {
        this.securityLoading = false;
        this.securityScanned = true;
      }))
      .subscribe({
        next: (page) => {
          this.securityFindings = this.detectInjectionPatterns(page.content ?? []);
        },
        error: () => {
          this.showError(this.translate.instant('ai.security.loadError'));
        },
      });
  }

  sendToAnalyzer(query: string): void {
    const q = query?.trim();
    if (!q) {
      return;
    }
    this.historyState.setPendingReanalyze({
      query: q,
      databaseType: this.databaseType,
    });
    void this.router.navigate(['/analyzer']);
  }

  async copyText(text: string): Promise<void> {
    if (!text?.trim()) {
      return;
    }
    try {
      await navigator.clipboard.writeText(text);
      this.snackBar.open(this.translate.instant('ai.copied'), undefined, {
        duration: 2500,
        politeness: 'polite',
      });
    } catch {
      this.showError(this.translate.instant('ai.copyFailed'));
    }
  }

  truncateQuery(q: string, max = 80): string {
    const t = q.replace(/\s+/g, ' ').trim();
    return t.length <= max ? t : t.slice(0, max) + '…';
  }

  private syncRegisteredAccess(): void {
    this.isRegisteredUser = this.authService.isLoggedIn();
  }

  private loadSecurityAiPreference(): void {
    try {
      this.securityAiEnrichment = localStorage.getItem(SECURITY_AI_PREF_KEY) === '1';
    } catch {
      this.securityAiEnrichment = false;
    }
  }

  private loadSchemaFromSession(): void {
    const snap = readSchemaSnapshot();
    this.schemaConnection = schemaConnectionSummary(snap);
    if (isExplorerConnected(snap)) {
      this.databaseType = snap!.databaseType;
      this.schemaTables = snap!.tables ?? [];
    } else {
      this.schemaTables = [];
    }
  }

  private handleAiResponse(res: AiResponse, onSuccess: (text: string) => void): void {
    this.lastMeta = {
      provider: res.provider,
      responseTimeMs: res.responseTimeMs,
      aiAvailable: res.aiAvailable,
    };
    if (res.success && res.result) {
      onSuccess(res.result);
      return;
    }
    this.showError(res.error ?? this.translate.instant('ai.genericError'));
  }

  private showError(message: string): void {
    this.snackBar.open(message, undefined, {
      duration: 5000,
      panelClass: ['error-snackbar'],
      politeness: 'assertive',
    });
  }

  private patchMonaco(text: string): void {
    this.monacoOptions = {
      ...this.monacoOptions,
      ariaLabel: this.translate.instant('ai.resultEditorLabel'),
    };
    if (text) {
      this.generatedQuery = text;
    }
  }

  private buildMonacoOptions(): MonacoEditorConstructionOptions {
    const profile = this.accessibility.getProfile();
    const isDark =
      document.body.classList.contains('dark-theme') || profile.darkTheme;
    return {
      theme: isDark ? 'vs-dark' : 'vs',
      language: 'sql',
      readOnly: true,
      automaticLayout: true,
      minimap: { enabled: false },
      scrollBeyondLastLine: false,
      fontSize: profile.lowVisionMode ? 16 : 13,
      lineNumbers: 'on',
      wordWrap: 'on',
      touch: true,
      scrollbar: { handleMouseWheel: false },
      accessibilitySupport: profile.blindMode ? 'on' : 'auto',
      ariaLabel: this.translate.instant('ai.resultEditorLabel'),
    };
  }

  private detectInjectionPatterns(entries: HistoryEntry[]): SecurityFinding[] {
    const findings: SecurityFinding[] = [];
    for (const entry of entries) {
      const q = entry.query ?? '';
      for (const check of INJECTION_CHECKS) {
        if (check.pattern.test(q)) {
          findings.push({
            id: `${entry.id}-${check.id}`,
            query: q,
            queryPreview: this.truncateQuery(q),
            databaseType: entry.databaseType ?? 'mysql',
            patternId: check.id,
            riskKey: check.riskKey,
            fixKey: check.fixKey,
            ruleKey: check.ruleKey,
          });
          break;
        }
      }
    }
    return findings;
  }
}
