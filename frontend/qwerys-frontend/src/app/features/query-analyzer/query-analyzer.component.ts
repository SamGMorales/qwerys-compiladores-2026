import {
  Component,
  DestroyRef,
  ElementRef,
  inject,
  OnDestroy,
  OnInit,
  ViewChild,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { BreakpointObserver } from '@angular/cdk/layout';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { combineLatest, Subscription } from 'rxjs';
import { distinctUntilChanged, map } from 'rxjs/operators';

import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatExpansionModule } from '@angular/material/expansion';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatTooltipModule } from '@angular/material/tooltip';
import {
  AiSuggestionService,
} from '../../core/services/ai-suggestion.service';
import { Router } from '@angular/router';
import { take } from 'rxjs/operators';

import { AuthService } from '../../core/services/auth.service';
import { HistoryStateService } from '../../core/services/history-state.service';
import { QueryHistoryService } from '../../core/services/query-history.service';
import {
  QueryService,
  QueryAnalysisResult,
  MultiStatementAnalysisResult,
  DatabaseConfig,
  SupportedEngineDto,
  AnalysisError,
  AnalysisMetrics,
  QueryIssue,
  QueryOptimization,
  parseCustomDatabaseEntriesFromStorage,
  toSupportedEngineDtoFromCustomRow,
} from '../../core/services/query.service';
import { SchemaService } from '../../core/services/schema.service';
import { AccessibilityService } from '../../core/services/accessibility.service';
import { KeyboardShortcutsService } from '../../core/services/keyboard-shortcuts.service';
import { CollapsibleSectionDirective } from '../../shared/directives/collapsible-section.directive';
import { StepModeService } from '../../core/services/step-mode.service';
import { SpeechSynthesisService } from '../../core/services/speech-synthesis.service';
import {
  SpeechRecognitionError,
  SpeechRecognitionService,
} from '../../core/services/speech-recognition.service';
import {
  StudentExplanationsService,
  StudentExplanation,
} from '../../shared/services/student-explanations.service';
import { AstTreeComponent } from '../../shared/components/ast-tree/ast-tree.component';
import { StudentExplanationBlockComponent } from '../../shared/components/student-explanation-block/student-explanation-block.component';
import { StudentProgressBarComponent } from '../../shared/components/student-progress-bar/student-progress-bar.component';
import { SqlTutorialTooltipComponent } from '../../shared/components/sql-tutorial-tooltip/sql-tutorial-tooltip.component';
import {
  QueryDiffComponent,
  QueryDiffData,
} from '../../shared/components/query-diff/query-diff.component';
import { getStudentKeywordPatterns, keywordToI18nKey } from '../../shared/services/student-keyword-patterns';
import {
  countOptionalOptimizations,
  effectiveErrorsForStudentScore,
  effectiveIsValid,
  effectiveIssuesForStudentScore,
  hasProductionRiskIssues,
  showsPerfectResult,
  validityBadgeKind,
} from '../../shared/utils/analysis-display.util';
import type {
  AiComplementOptimization,
  AiComplementState,
  ComplementAnalysisResponse,
  SyntaxCorrection,
} from '../../core/models/complement-analysis.model';
import {
  explorerDatabaseConfig,
  isExplorerConnected,
  readSchemaSnapshot,
  schemaConnectionSummary,
  SchemaConnectionSummary,
} from '../../shared/utils/schema-snapshot.util';

interface DbOption {
  value: string;
  label: string;
  type: string;
  custom?: boolean;
  /** Resolved motor base when custom (postgresql, mongodb, …). */
  base?: string;
}

const DATABASES: DbOption[] = [
  { value: 'mysql',         label: 'MySQL',         type: 'sql'           },
  { value: 'postgresql',    label: 'PostgreSQL',     type: 'sql'           },
  { value: 'sqlite',        label: 'SQLite',         type: 'sql'           },
  { value: 'sqlserver',     label: 'SQL Server',     type: 'sql'           },
  { value: 'oracle',        label: 'Oracle',         type: 'sql'           },
  { value: 'mongodb',       label: 'MongoDB',        type: 'mongodb'       },
  { value: 'redis',         label: 'Redis',          type: 'redis'         },
  { value: 'cassandra',     label: 'Cassandra',      type: 'cassandra'     },
  { value: 'dynamodb',      label: 'DynamoDB',       type: 'dynamodb'      },
  { value: 'elasticsearch', label: 'Elasticsearch',  type: 'elasticsearch' },
];

@Component({
  selector: 'app-query-analyzer',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    TranslateModule,
    MatIconModule,
    MatProgressSpinnerModule,
    MatExpansionModule,
    MatSlideToggleModule,
    MatFormFieldModule,
    MatInputModule,
    MatSnackBarModule,
    MatButtonModule,
    MatTooltipModule,
    CollapsibleSectionDirective,
    AstTreeComponent,
    StudentExplanationBlockComponent,
    StudentProgressBarComponent,
    SqlTutorialTooltipComponent,
  ],
  templateUrl: './query-analyzer.component.html',
  styleUrls: ['./query-analyzer.component.scss'],
})
export class QueryAnalyzerComponent implements OnInit, OnDestroy {
  private readonly queryService = inject(QueryService);
  private readonly translate = inject(TranslateService);
  private readonly destroyRef = inject(DestroyRef);
  readonly accessibilityService = inject(AccessibilityService);
  readonly stepMode = inject(StepModeService);
  /** Web Speech API TTS — expuesto para la plantilla (@if / async). */
  readonly speechService = inject(SpeechSynthesisService);
  /** Web Speech API STT — expuesto para la plantilla (@if / async). */
  readonly speechRecognitionService = inject(SpeechRecognitionService);
  readonly studentExplanations = inject(StudentExplanationsService);
  private readonly keyboardShortcuts = inject(KeyboardShortcutsService);
  private readonly historyState = inject(HistoryStateService);
  private readonly queryHistoryService = inject(QueryHistoryService);
  /** Latest history row id from native analyze (single-statement path). */
  private lastHistoryEntryId: number | null = null;
  private readonly authService = inject(AuthService);
  private readonly snackBar = inject(MatSnackBar);
  private readonly router = inject(Router);
  private readonly aiSuggestionService = inject(AiSuggestionService);
  private readonly schemaService = inject(SchemaService);
  private readonly dialog = inject(MatDialog);
  private readonly breakpointObserver = inject(BreakpointObserver);

  copySuccess = false;

  /** Shown when live DB pre-check fails (validation toggle ON). */
  schemaValidationError: string | null = null;
  /** Map key: statement index (0..n-1), or {@link SCRIPT_AI_INDEX} for whole-script complement. */
  static readonly SCRIPT_AI_INDEX = -1;

  private readonly aiComplementsByIndex = new Map<number, AiComplementState>();
  private readonly aiComplementLoadingIndices = new Set<number>();

  /** Active complement for single-result or selected multi statement. */
  get aiComplement(): AiComplementState | null {
    return this.aiComplementAt(this.resolveActiveAiIndex());
  }

  get scriptAiComplement(): AiComplementState | null {
    return this.aiComplementsByIndex.get(QueryAnalyzerComponent.SCRIPT_AI_INDEX) ?? null;
  }

  get aiComplementLoading(): boolean {
    return this.aiComplementLoadingIndices.size > 0;
  }

  get scriptAiComplementLoading(): boolean {
    return this.aiComplementLoadingIndices.has(QueryAnalyzerComponent.SCRIPT_AI_INDEX);
  }

  get aiComplementPedagogy(): string {
    return this.aiComplement?.pedagogy ?? '';
  }

  get aiOptimizationNotes(): string {
    return this.aiComplement?.optimizationNotes ?? '';
  }

  get aiComplementProvider(): string | null {
    return this.aiComplement?.provider ?? null;
  }

  aiComplementAt(index: number): AiComplementState | null {
    return this.aiComplementsByIndex.get(index) ?? null;
  }

  aiComplementLoadingAt(index: number): boolean {
    return this.aiComplementLoadingIndices.has(index);
  }

  aiComplementPedagogyAt(index: number): string {
    return this.aiComplementAt(index)?.pedagogy ?? '';
  }

  aiOptimizationNotesAt(index: number): string {
    return this.aiComplementAt(index)?.optimizationNotes ?? '';
  }

  /** AI complement warnings for student practices score (per statement / script index). */
  aiStudentWarningCount(statementIndex: number): number {
    return this.aiComplementAt(statementIndex)?.additionalWarnings?.length ?? 0;
  }

  /** AI complement errors for student practices score (per statement / script index). */
  aiStudentErrorCount(statementIndex: number): number {
    return this.aiComplementAt(statementIndex)?.additionalErrors?.length ?? 0;
  }

  /** Native errors for student score, aligned with badges (validity override + DISAGREE). */
  studentProgressErrors(
    result: QueryAnalysisResult | null | undefined,
    statementIndex: number
  ): AnalysisError[] {
    return effectiveErrorsForStudentScore(result, this.aiComplementAt(statementIndex));
  }

  /** Native issues for student score, excluding AI-dismissed warnings/errors. */
  studentProgressIssues(
    issues: QueryIssue[] | undefined | null,
    statementIndex: number
  ): QueryIssue[] {
    return effectiveIssuesForStudentScore(issues, this.aiComplementAt(statementIndex));
  }

  aiComplementProviderAt(index: number): string | null {
    return this.aiComplementAt(index)?.provider ?? null;
  }

  private resolveActiveAiIndex(): number {
    if (this.result) {
      return 0;
    }
    if (this.multiResult?.statements.length) {
      return this.activeExpertStatementIndex ?? 0;
    }
    return 0;
  }

  private clearAiComplements(): void {
    this.aiComplementsByIndex.clear();
    this.aiComplementLoadingIndices.clear();
  }

  @ViewChild('queryInput') private queryInputRef?: ElementRef<HTMLTextAreaElement>;

  /** Perfil de accesibilidad + paso TDAH para la plantilla. */
  readonly analyzerStepVm$ = combineLatest([
    this.accessibilityService.profile$,
    this.stepMode.currentStep$,
  ]).pipe(
    map(([profile, currentStep]) => ({
      adhdMode: profile.adhdMode,
      blindMode: profile.blindMode,
      deafMode: profile.deafMode,
      studentMode: profile.studentMode,
      expertMode: profile.expertMode,
      currentStep,
    }))
  );

  /** SQL keywords detectados en el textarea (modo estudiante). Orden: frases largas primero. */
  private prevAdhd = this.accessibilityService.getProfile().adhdMode;
  private adhdProfileSub?: Subscription;
  /** Fuerza recálculo de keywords al cambiar idioma (ngx-translate). */
  private keywordsLangTick = 0;

  // ── Form state ──────────────────────────────────────────────────────────────
  query:               string                             = '';
  selectedDb:          string                             = 'mysql';
  selectedCategory:    string                             = 'sql';
  selectedDialect:     string | null                      = null;
  isLoading:           boolean                            = false;
  isMobile = false;
  result:              QueryAnalysisResult | null         = null;
  multiResult:         MultiStatementAnalysisResult | null = null;
  error:               string | null                      = null;
  /** Mic / STT feedback (permission, silence, hardware). */
  dictationMessage:    string | null                      = null;
  expandedStatements = new Set<number>();
  /** Sentencia activa en el panel AST (modo experto + multi-sentencia). */
  activeExpertStatementIndex: number | null = null;

  /** Motores con pipeline SQL/PartiQL que pueden devolver astTree y metrics del backend. */
  private static readonly EXPERT_PIPELINE_ENGINES = new Set([
    'mysql',
    'postgresql',
    'sqlite',
    'sqlserver',
    'oracle',
    'dynamodb',
  ]);

  /** When true, sends {@link schemaConnection} to validate tables/columns against a live DB. */
  useSchemaValidation = false;
  schemaConnection: DatabaseConfig | null = null;
  /** Offer to copy Schema Explorer credentials when validation is enabled. */
  showExplorerImportPrompt = false;
  explorerImportSummary: SchemaConnectionSummary | null = null;

  // ── Engine lists ────────────────────────────────────────────────────────────
  /** Populated from GET /api/queries/engines (fallback: built-ins only). */
  engineCatalog: DbOption[] = DATABASES.slice();

  private readonly reloadEnginesBound = (): void => this.reloadEngineCatalog();

  ngOnInit(): void {
    this.breakpointObserver
      .observe(['(max-width: 767.98px)'])
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((result) => {
        this.isMobile = result.matches;
      });

    this.reloadEngineCatalog();
    window.addEventListener('qwerys-custom-engines-changed', this.reloadEnginesBound as EventListener);

    this.translate.onLangChange
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => {
        this.keywordsLangTick++;
        this.clearAiComplements();
        this.refreshAnalysisForLocale();
      });

    this.adhdProfileSub = this.accessibilityService.profile$.subscribe((p) => {
      if (p.adhdMode && !this.prevAdhd) {
        if (this.result || this.multiResult) {
          this.stepMode.goTo('results');
        } else {
          this.stepMode.reset();
        }
      }
      this.prevAdhd = p.adhdMode;
    });

    this.keyboardShortcuts.analyze$
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => {
        if (this.query.trim() && !this.isLoading) {
          this.analyzeQuery();
        }
      });

    this.keyboardShortcuts.clear$
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => this.clearQuery());

    this.keyboardShortcuts.toggleVoice$
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => this.toggleVoiceDictation());

    this.keyboardShortcuts.close$
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => this.closeAnalyzerOverlays());

    this.historyState.pendingReanalyze$
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((pending) => {
        if (!pending?.query?.trim()) {
          return;
        }
        this.query = pending.query;
        if (pending.databaseType) {
          this.applyEngineFromHistory(pending.databaseType);
        }
        this.historyState.clearPendingReanalyze();
        this.stepMode.goTo('write');
      });
  }

  /** Pre-select engine when navigating from query history (Re-analyze). */
  private applyEngineFromHistory(databaseType: string): void {
    const db = databaseType.trim();
    const match = this.engineCatalog.find((e) => e.value === db);
    if (match) {
      this.selectedCategory = match.type === 'sql' ? 'sql' : 'nosql';
      this.selectedDb = match.value;
      this.syncSchemaConnectionDbType();
      return;
    }
    if (db.startsWith('custom::')) {
      const custom = this.engineCatalog.find((e) => e.value === db);
      if (custom) {
        this.selectedCategory = custom.type === 'sql' ? 'sql' : 'nosql';
        this.selectedDb = custom.value;
        this.syncSchemaConnectionDbType();
      }
    }
  }

  ngOnDestroy(): void {
    this.speechService.stop();
    this.speechRecognitionService.stopListening();
    window.removeEventListener('qwerys-custom-engines-changed', this.reloadEnginesBound as EventListener);
    this.adhdProfileSub?.unsubscribe();
  }

  /** GET /engines + localhost-only custom engines (guests + same-tab Settings updates). */
  private reloadEngineCatalog(): void {
    this.queryService.getEngines().subscribe({
      next: rows => this.applyEngineCatalogFromResponse(rows ?? null),
      error: () => this.applyEngineCatalogFromResponse(null),
    });
  }

  private applyEngineCatalogFromResponse(rows: SupportedEngineDto[] | null): void {
    const source: SupportedEngineDto[] =
      rows && rows.length > 0
        ? rows
        : DATABASES.map(
            (d): SupportedEngineDto => ({
              id: d.value,
              name: d.label,
            })
          );
    this.engineCatalog = source.map(r => this.mapSupportedEngineRow(r));
    for (const { name, base } of parseCustomDatabaseEntriesFromStorage()) {
      const dto = toSupportedEngineDtoFromCustomRow(name, base);
      if (this.engineCatalog.some(e => e.value === dto.id)) continue;
      this.engineCatalog = [...this.engineCatalog, this.mapSupportedEngineRow(dto)];
    }
    if (!this.engineCatalog.some(e => e.value === this.selectedDb)) {
      const firstSql = this.engineCatalog.find(e => e.type === 'sql');
      const fallback = firstSql ?? this.engineCatalog[0];
      this.selectedDb = fallback?.value ?? 'mysql';
      this.syncSchemaConnectionDbType();
    }
  }

  private mapSupportedEngineRow(row: SupportedEngineDto): DbOption {
    const id = (row.id ?? '').trim();
    const isCustom = row.custom === 'true';
    const baseRaw = (row.base ?? row.id ?? '').trim().toLowerCase();
    const categoryType = this.inferCategoryType(isCustom ? baseRaw : id.toLowerCase());
    return {
      value: id,
      label: (row.name ?? id).trim(),
      type: categoryType,
      custom: isCustom,
      base: isCustom ? baseRaw : undefined,
    };
  }

  private inferCategoryType(engineKey: string): string {
    const sql = new Set(['mysql', 'postgresql', 'sqlite', 'sqlserver', 'oracle']);
    return sql.has(engineKey) ? 'sql' : engineKey;
  }

  private selectedEngineOption(): DbOption | undefined {
    return this.engineCatalog.find(e => e.value === this.selectedDb);
  }

  /** Motor base for labels/hints (SQLite path vs host, Dynamo keys, …). */
  effectiveSchemaDbKey(): string {
    const opt = this.selectedEngineOption();
    if (opt?.custom && opt.base) return opt.base;
    return this.selectedDb;
  }

  get availableEngines(): DbOption[] {
    return this.selectedCategory === 'sql'
      ? this.engineCatalog.filter(e => e.type === 'sql')
      : this.engineCatalog.filter(e => e.type !== 'sql');
  }

  get builtinEnginesForCategory(): DbOption[] {
    return this.availableEngines.filter(e => !e.custom);
  }

  get customEnginesForCategory(): DbOption[] {
    return this.availableEngines.filter(e => e.custom);
  }

  // ── Derived ─────────────────────────────────────────────────────────────────
  get queryType(): string {
    const opt = this.selectedEngineOption();
    if (!opt) return 'sql';
    if (opt.type === 'sql') return 'sql';
    return opt.type;
  }

  get isSqlCategory(): boolean {
    return this.selectedCategory === 'sql';
  }

  get schemaIsSqlite(): boolean {
    return this.effectiveSchemaDbKey() === 'sqlite';
  }

  get schemaShowHostPort(): boolean {
    return this.schemaConnection != null && !this.schemaIsSqlite;
  }

  /** Label for the 'database' field depending on the selected engine. */
  get schemaDatabaseFieldKey(): string {
    switch (this.effectiveSchemaDbKey()) {
      case 'cassandra':      return 'schema.keyspace';
      case 'elasticsearch':  return 'schema.index';
      case 'redis':          return 'schema.dbNumber';
      case 'dynamodb':       return 'schema.region';
      case 'sqlite':         return 'schema.filePath';
      default:               return 'schema.database';
    }
  }

  /** For DynamoDB, username = Access Key ID and password = Secret Access Key. */
  get schemaUsernameLabel(): string {
    return this.effectiveSchemaDbKey() === 'dynamodb' ? 'schema.accessKeyId' : 'schema.username';
  }

  get schemaPasswordLabel(): string {
    return this.effectiveSchemaDbKey() === 'dynamodb' ? 'schema.secretAccessKey' : 'schema.password';
  }

  /** Redis and Elasticsearch don't require authentication by default. */
  get schemaAuthOptional(): boolean {
    const k = this.effectiveSchemaDbKey();
    return k === 'redis' || k === 'elasticsearch';
  }

  get schemaIsDynamodb(): boolean {
    return this.effectiveSchemaDbKey() === 'dynamodb';
  }

  get isValid(): boolean | undefined {
    return this.result?.isValid;
  }

  /** Motor actual soporta AST/métricas de lexer-parser en modo experto. */
  get expertPipelineSupported(): boolean {
    const engine = this.effectiveSchemaDbKey().toLowerCase();
    if (!QueryAnalyzerComponent.EXPERT_PIPELINE_ENGINES.has(engine)) {
      return false;
    }
    if (engine === 'dynamodb' && this.queryType === 'dynamodb-expression') {
      return false;
    }
    return true;
  }

  get hasExpertMultiStatements(): boolean {
    return (this.multiResult?.statements.length ?? 0) > 1;
  }

  /** Resultado activo para panel AST y métricas globales (simple o sentencia elegida). */
  get activeExpertAnalysis(): QueryAnalysisResult | null {
    if (this.result) {
      return this.result;
    }
    const stmts = this.multiResult?.statements;
    if (!stmts?.length) {
      return null;
    }
    const idx =
      this.activeExpertStatementIndex != null &&
      this.activeExpertStatementIndex >= 0 &&
      this.activeExpertStatementIndex < stmts.length
        ? this.activeExpertStatementIndex
        : 0;
    return stmts[idx] ?? null;
  }

  get activeAst() {
    const ast = this.activeExpertAnalysis?.ast ?? null;
    if (!ast) {
      return null;
    }
    if (this.expertPipelineSupported || this.aiSupportedAstActive()) {
      return ast;
    }
    return null;
  }

  /** True when the AST currently shown was supplied by AI (native reparse failed). */
  aiSupportedAstActive(): boolean {
    const overlay = this.aiComplement?.secondPassOverlay;
    return !!overlay && overlay.suppressNativeErrors === true && overlay.reparseSucceeded === false;
  }

  get activeMetrics(): AnalysisMetrics | undefined {
    if (!this.expertPipelineSupported) {
      return undefined;
    }
    return this.activeExpertAnalysis?.metrics;
  }

  get expertAstEmptyKey(): string {
    if (!this.result && !this.multiResult) {
      return 'expert.astEmpty';
    }
    if (this.expertPipelineSupported && !this.activeAst) {
      return 'expert.astEmptyStatement';
    }
    return 'expert.astEmpty';
  }

  get detectedKeywords(): { keyword: string; description: string }[] {
    void this.keywordsLangTick;
    const text = this.query.trim();
    if (!text) return [];
    const upper = text.toUpperCase();
    const found = new Set<string>();
    const items: { keyword: string; description: string }[] = [];

    const patterns = getStudentKeywordPatterns(
      this.selectedCategory,
      this.effectiveSchemaDbKey()
    );
    for (const kw of patterns) {
      const escaped = kw.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
      const pattern = kw.startsWith('$')
        ? new RegExp(escaped, 'i')
        : new RegExp(`\\b${escaped.replace(/\s+/g, '\\s+')}\\b`, 'i');
      if (pattern.test(upper) && !found.has(kw)) {
        found.add(kw);
        const key = `student.keywords.${keywordToI18nKey(kw)}`;
        const desc = this.translate.instant(key);
        items.push({
          keyword: kw,
          description: desc !== key ? desc : kw,
        });
      }
    }
    return items;
  }

  getStudentExplanation(
    code: string | undefined,
    education?: StudentExplanation | null
  ): StudentExplanation | null {
    return this.studentExplanations.getExplanation(code, education);
  }

  getProgress(
    errors: AnalysisError[] | undefined,
    issues: QueryIssue[] | undefined
  ): number {
    const errN = errors?.length ?? 0;
    const warnN =
      issues?.filter((i) => i.type === 'warning' || i.type === 'error').length ??
      0;
    if (errN === 0 && warnN === 0) return 100;
    return Math.max(0, 100 - errN * 20 - warnN * 5);
  }

  formatMetric(value: number | undefined): string {
    return value != null && !Number.isNaN(value) ? String(value) : '—';
  }

  // ── Helpers ─────────────────────────────────────────────────────────────────
  impactKey(impact: string): string {
    return `analyzer.impact.${impact.toLowerCase()}`;
  }

  descriptionKey(opt: { ruleId?: string; description?: string }): string {
    return opt.ruleId
      ? `analyzer.optimizations.${opt.ruleId}.description`
      : (opt.description ?? '');
  }

  /** i18n keys for optimization snippet boxes (localized original/optimized blurbs). */
  optimizationSnippetKey(opt: { ruleId?: string }, kind: 'original' | 'optimized'): string | null {
    const rid = opt.ruleId;
    if (!rid) return null;
    if (rid.startsWith('OPT-PROC-') || rid === 'OPT-CURSOR-LOOP') {
      return `analyzer.optimizations.${rid}.${kind}`;
    }
    return null;
  }

  /** Prefer nested title (e.g. MongoDB MGO-*), then flat string entry (SQL codes). */
  /** True when err.message duplicates the flat i18n chip text (avoid showing it twice). */
  errorMessageRedundantWithChip(err: AnalysisError, code: string): boolean {
    const flat = this.translate.instant(`analyzer.issues.${code}`);
    if (!flat || flat === `analyzer.issues.${code}`) {
      return false;
    }
    return (err.message?.trim() ?? '') === flat.trim();
  }

  issueChipTitleKey(code: string): string {
    return `analyzer.issues.${code}.title`;
  }

  issueChipFlatKey(code: string): string {
    return `analyzer.issues.${code}`;
  }

  issueChipDescriptionKey(code: string): string {
    return `analyzer.issues.${code}.description`;
  }

  issueChipSuggestionKey(code: string): string {
    return `analyzer.issues.${code}.suggestion`;
  }

  /**
   * Locale for analyzer API = idioma efectivo del JSON (ngx-translate), no un canal paralelo al perfil.
   * Preferencia: (1) {@link TranslateService#getCurrentLang} — refleja el catálogo activo (en.json / es.json);
   * (2) {@link TranslateService#getBrowserLang} — detección automática del navegador;
   * (3) perfil de accesibilidad como respaldo antes del default del app ({@code es}).
   */
  private currentUiLang(): 'en' | 'es' {
    const tag = this.translate.getCurrentLang();
    if (tag?.trim()) {
      return this.normalizeUiLang(tag);
    }
    const browser = this.translate.getBrowserLang();
    if (browser?.trim()) {
      return this.normalizeUiLang(browser);
    }
    const profile = this.accessibilityService.getProfile().language;
    if (profile) {
      return this.normalizeUiLang(profile);
    }
    return 'es';
  }

  /** QWERYS solo expone inglés y español en assets/i18n. */
  private normalizeUiLang(lang: string): 'en' | 'es' {
    const lower = lang.trim().toLowerCase();
    return lower.startsWith('es') ? 'es' : 'en';
  }

  // ── UI events ───────────────────────────────────────────────────────────────
  onCategoryChange(category: string): void {
    this.selectedCategory = category;
    const engines =
      category === 'sql'
        ? this.engineCatalog.filter(e => e.type === 'sql')
        : this.engineCatalog.filter(e => e.type !== 'sql');
    this.selectedDb = engines[0]?.value ?? 'mysql';
    this.syncSchemaConnectionDbType();
  }

  onEngineChange(): void {
    this.syncSchemaConnectionDbType();
  }

  onSchemaValidationToggle(enabled: boolean): void {
    this.useSchemaValidation = enabled;
    if (!enabled) {
      this.showExplorerImportPrompt = false;
      this.explorerImportSummary = null;
      return;
    }
    if (!this.schemaConnection) {
      this.schemaConnection = this.defaultSchemaConnection();
    }
    this.maybeOfferExplorerImport();
    if (!this.showExplorerImportPrompt) {
      this.syncSchemaConnectionDbType();
    }
  }

  acceptExplorerConnectionForValidation(): void {
    const config = explorerDatabaseConfig();
    if (!config) {
      this.showExplorerImportPrompt = false;
      return;
    }
    this.applyEngineFromHistory(config.dbType);
    this.schemaConnection = { ...config };
    this.showExplorerImportPrompt = false;
    this.explorerImportSummary = null;
  }

  declineExplorerConnectionForValidation(): void {
    this.schemaConnection = this.defaultSchemaConnection();
    this.syncSchemaConnectionDbType();
    this.showExplorerImportPrompt = false;
    this.explorerImportSummary = null;
  }

  private maybeOfferExplorerImport(): void {
    const snap = readSchemaSnapshot();
    if (!isExplorerConnected(snap)) {
      this.showExplorerImportPrompt = false;
      this.explorerImportSummary = null;
      return;
    }
    this.explorerImportSummary = schemaConnectionSummary(snap);
    this.showExplorerImportPrompt = !!this.explorerImportSummary;
  }

  private syncSchemaConnectionDbType(): void {
    if (this.schemaConnection) {
      this.schemaConnection = {
        ...this.schemaConnection,
        dbType: this.selectedDb,
        port: this.defaultPortFor(this.selectedDb),
      };
    }
  }

  private defaultSchemaConnection(): DatabaseConfig {
    return {
      host: 'localhost',
      port: this.defaultPortFor(this.selectedDb),
      database: '',
      username: '',
      password: '',
      dbType: this.selectedDb,
      connectionTimeoutSeconds: 30,
    };
  }

  private defaultPortFor(dbType: string): number {
    const opt = this.engineCatalog.find(e => e.value === dbType);
    let engineKey = dbType.toLowerCase();
    if (opt?.custom && opt.base) {
      engineKey = opt.base;
    } else if (dbType.toLowerCase().startsWith('custom::')) {
      const parts = dbType.split('::');
      engineKey = (parts[parts.length - 1] ?? 'mysql').toLowerCase();
    }
    switch (engineKey) {
      case 'mysql':
        return 3306;
      case 'postgresql':
        return 5432;
      case 'sqlserver':
        return 1433;
      case 'oracle':
        return 1521;
      case 'sqlite':
        return 0;
      case 'mongodb':
        return 27017;
      case 'redis':
        return 6379;
      case 'cassandra':
        return 9042;
      case 'elasticsearch':
        return 9200;
      case 'dynamodb':
        return 8000;
      default:
        return 3306;
    }
  }

  /** Included in analyze requests only when schema validation is enabled. */
  private connectionForAnalysis(): DatabaseConfig | undefined {
    if (!this.useSchemaValidation || !this.schemaConnection) {
      return undefined;
    }
    return { ...this.schemaConnection, dbType: this.selectedDb };
  }

  adhdStepPrevious(): void {
    this.stepMode.previous();
  }

  adhdStepNext(): void {
    this.stepMode.next();
  }

  selectExpertStatement(index: number): void {
    this.activeExpertStatementIndex = index;
    this.expandedStatements.add(index);
    this.ensureAiComplementForStatement(index);
  }

  toggleStatement(index: number): void {
    if (this.accessibilityService.getProfile().expertMode) {
      this.activeExpertStatementIndex = index;
    }
    const willExpand = !this.expandedStatements.has(index);
    if (this.expandedStatements.has(index)) {
      this.expandedStatements.delete(index);
    } else {
      this.expandedStatements.add(index);
    }
    if (willExpand) {
      this.ensureAiComplementForStatement(index);
    }
  }

  /** True when the UI may show the celebratory "perfect query" banner (no production WARNINGs). */
  showsPerfect(
    result: QueryAnalysisResult | null | undefined,
    statementIndex?: number
  ): boolean {
    const idx = statementIndex ?? this.resolveActiveAiIndex();
    return showsPerfectResult(result, this.aiComplementAt(idx));
  }

  /** Number of optional improvements (native + AI optimizations) attached to a result. */
  optionalImprovementsCount(
    result: QueryAnalysisResult | null | undefined,
    statementIndex?: number
  ): number {
    const idx = statementIndex ?? this.resolveActiveAiIndex();
    return countOptionalOptimizations(result, this.aiComplementAt(idx));
  }

  displayValidity(
    result: QueryAnalysisResult | null | undefined,
    statementIndex?: number
  ): ReturnType<typeof validityBadgeKind> {
    const idx = statementIndex ?? this.resolveActiveAiIndex();
    return validityBadgeKind(result, this.aiComplementAt(idx));
  }

  isEffectivelyValid(
    result: QueryAnalysisResult | null | undefined,
    statementIndex?: number
  ): boolean {
    const idx = statementIndex ?? this.resolveActiveAiIndex();
    return effectiveIsValid(result, this.aiComplementAt(idx));
  }

  syntaxCorrectionFor(code: string, statementIndex?: number): SyntaxCorrection | undefined {
    const idx = statementIndex ?? this.resolveActiveAiIndex();
    return this.aiComplementAt(idx)?.syntaxCorrections?.find((s) => s.forErrorCode === code);
  }

  private ensureAiComplementForStatement(index: number): void {
    const stmt = this.multiResult?.statements[index];
    if (!stmt) {
      return;
    }
    if (
      !this.aiComplementsByIndex.has(index) &&
      !this.aiComplementLoadingIndices.has(index)
    ) {
      this.fetchAiComplementForIndex(index, stmt);
    }
  }

  aiOptOriginal(opt: AiComplementOptimization): string | undefined {
    return opt.originalFragment ?? opt.original;
  }

  aiOptOptimized(opt: AiComplementOptimization): string | undefined {
    return opt.optimizedFragment ?? opt.optimized;
  }

  hasProductionWarnings(issues: QueryIssue[] | undefined | null): boolean {
    return hasProductionRiskIssues(issues, this.aiComplement);
  }

  scriptLevelAllClear(
    scriptLevel: QueryAnalysisResult | null | undefined,
    healthPercent: number | undefined
  ): boolean {
    const health = healthPercent ?? 100;
    if (health < 100) {
      return false;
    }
    if (!scriptLevel) {
      return true;
    }
    return (
      !scriptLevel.errors?.length && !hasProductionRiskIssues(scriptLevel.issues)
    );
  }

  private refreshAnalysisForLocale(): void {
    const q = this.query.trim();
    if (!q || this.isLoading) {
      return;
    }
    if (!this.result && !this.multiResult) {
      return;
    }
    this.analyzeQuery(true);
  }

  analyzeQuery(preservePanel = false): void {
    if (!this.query.trim()) return;
    this.speechService.stop();
    if (this.accessibilityService.getProfile().adhdMode && !preservePanel) {
      this.stepMode.goTo('analyze');
    }
    this.isLoading = true;
    if (!preservePanel) {
      this.result = null;
      this.multiResult = null;
      this.clearAiComplements();
    }
    this.error = null;
    this.expandedStatements         = new Set<number>();
    this.activeExpertStatementIndex = null;

    const runAnalysis = (): void => this.executeAnalysis(preservePanel);

    if (this.useSchemaValidation && this.schemaConnection) {
      this.schemaValidationError = null;
      const cfg = this.connectionForAnalysis();
      if (!cfg) {
        this.isLoading = false;
        return;
      }
      this.schemaService.testConnection(cfg).subscribe({
        next: (res) => {
          if (!res.success) {
            this.schemaValidationError =
              res.message || this.translate.instant('analyzer.schemaConnectFailed');
            this.isLoading = false;
            return;
          }
          runAnalysis();
        },
        error: (err: Error) => {
          this.schemaValidationError = err.message;
          this.isLoading = false;
        },
      });
    } else {
      this.schemaValidationError = null;
      runAnalysis();
    }
  }

  private executeAnalysis(preservePanel: boolean): void {
    const trimmed = this.query.trim();
    const hasMultipleStatements =
      trimmed.includes(';') && trimmed.replace(/;+\s*$/, '').includes(';');

    const uiLang = this.currentUiLang();
    const connection = this.connectionForAnalysis();
    const opt = this.selectedEngineOption();
    const customBase =
      opt?.custom && opt.base?.trim() ? opt.base.trim().toLowerCase() : null;

    if (hasMultipleStatements) {
      this.queryService
        .analyzeMultiStatement({
          query: this.query,
          databaseType: this.selectedDb,
          queryType: this.queryType,
          locale: uiLang,
          ...(connection ? { connection } : {}),
          ...(customBase ? { customEngineBase: customBase } : {}),
        })
        .subscribe({
          next: (res) => {
            this.multiResult = res;
            this.result      = null;
            this.lastHistoryEntryId = res.historyEntryId ?? null;
            this.activeExpertStatementIndex = 0;
            if (this.accessibilityService.getProfile().expertMode && res.statements.length > 0) {
              this.expandedStatements = new Set([0]);
            }
            this.isLoading   = false;
            this.maybeAutoSpeakBlind(null, res);
            this.notifyHistorySaved();
            this.fetchAiComplementsForMulti(res);
            if (this.accessibilityService.getProfile().adhdMode) {
              this.stepMode.goTo('results');
            }
          },
          error: (err: Error) => {
            this.error     = err.message;
            this.isLoading = false;
          },
        });
    } else {
      this.queryService
        .analyzeQuery(this.query, this.selectedDb, this.queryType, uiLang, connection, customBase)
        .subscribe({
        next: (res) => {
          this.result                     = res;
          this.multiResult                = null;
          this.activeExpertStatementIndex = null;
          this.lastHistoryEntryId         = res.historyEntryId ?? null;
          this.isLoading                  = false;
          this.maybeAutoSpeakBlind(res, null);
          this.notifyHistorySaved();
          this.fetchAiComplementForIndex(0, res);
          if (this.accessibilityService.getProfile().adhdMode) {
            this.stepMode.goTo('results');
          }
        },
        error: (err: Error) => {
          this.error     = err.message;
          this.isLoading = false;
        },
      });
    }
  }

  refreshAiComplement(statementIndex?: number): void {
    if (statementIndex === QueryAnalyzerComponent.SCRIPT_AI_INDEX) {
      if (this.multiResult) {
        this.fetchScriptAiComplement(this.multiResult);
      }
      return;
    }
    if (this.result) {
      this.fetchAiComplementForIndex(0, this.result);
      return;
    }
    if (this.multiResult?.statements.length) {
      const idx = statementIndex ?? this.activeExpertStatementIndex ?? 0;
      const stmt = this.multiResult.statements[idx];
      if (stmt) {
        this.fetchAiComplementForIndex(idx, stmt);
      }
    }
  }

  private fetchAiComplementsForMulti(multi: MultiStatementAnalysisResult): void {
    const n = multi.statements.length;
    if (n === 0) {
      this.fetchScriptAiComplement(multi);
      return;
    }
    multi.statements.forEach((stmt, i) =>
      this.fetchAiComplementForIndex(i, stmt, () => this.maybeFetchScriptAiComplement(multi, n))
    );
  }

  /** Script-level IA runs after all per-statement complements finish so synthesis can be injected. */
  private maybeFetchScriptAiComplement(multi: MultiStatementAnalysisResult, statementCount: number): void {
    for (let i = 0; i < statementCount; i++) {
      if (this.aiComplementLoadingIndices.has(i) || !this.aiComplementsByIndex.has(i)) {
        return;
      }
    }
    const scriptIdx = QueryAnalyzerComponent.SCRIPT_AI_INDEX;
    if (
      !this.aiComplementLoadingIndices.has(scriptIdx) &&
      !this.aiComplementsByIndex.has(scriptIdx)
    ) {
      this.fetchScriptAiComplement(multi);
    }
  }

  /**
   * Whole-script AI pass (same endpoint/prompt as per-statement). Sends the full editor text
   * plus native script-level findings — mirrors native {@link MultiStatementAnalysisResult#scriptLevel}.
   */
  private fetchScriptAiComplement(multi: MultiStatementAnalysisResult): void {
    const sl = multi.scriptLevel;
    if (!sl) {
      return;
    }
    const idx = QueryAnalyzerComponent.SCRIPT_AI_INDEX;
    this.aiComplementLoadingIndices.add(idx);
    this.aiComplementsByIndex.delete(idx);
    const n = multi.statements.length;
    const synthesis = this.buildPerStatementAiSynthesisNote(n);
    const scriptNote =
      (this.buildLiveSchemaNote(sl) ?? '') +
      synthesis +
      (n > 1
        ? ` [${this.currentUiLang() === 'es' ? 'Análisis IA del script completo' : 'AI whole-script analysis'}: ${n} statements.]`
        : '');
    const payload = this.buildComplementPayload(sl, {
      queryOverride: this.query.trim(),
      liveSchemaNoteOverride: scriptNote || null,
      analysisScope: 'SCRIPT',
      fullScript: this.query.trim(),
      statementCount: n,
    });
    this.runComplementRequest(idx, payload, sl);
  }

  private fetchAiComplementForIndex(
    index: number,
    analysis: QueryAnalysisResult,
    onSettled?: () => void
  ): void {
    this.aiComplementLoadingIndices.add(index);
    this.aiComplementsByIndex.delete(index);
    const stmtCount = this.multiResult?.statements.length ?? 0;
    const payload = this.buildComplementPayload(analysis, {
      analysisScope: stmtCount > 1 ? 'STATEMENT' : undefined,
      statementIndex: stmtCount > 1 ? index + 1 : undefined,
      statementCount: stmtCount > 1 ? stmtCount : undefined,
    });
    this.runComplementRequest(index, payload, analysis, onSettled);
  }

  private buildPerStatementAiSynthesisNote(statementCount: number): string {
    if (statementCount <= 0) {
      return '';
    }
    const es = this.currentUiLang() === 'es';
    const lines: string[] = [
      es
        ? '\n--- Resumen IA por sentencia (síntesis para análisis de script) ---'
        : '\n--- Per-statement AI summary (synthesis for script-level pass) ---',
    ];
    for (let i = 0; i < statementCount; i++) {
      const c = this.aiComplementAt(i);
      const stmt = this.multiResult?.statements[i];
      const nativeValid = stmt?.isValid ?? false;
      const aiValid = stmt ? effectiveIsValid(stmt, c) : nativeValid;
      lines.push(
        es
          ? `Sentencia ${i + 1}: nativo=${nativeValid ? 'válido' : 'inválido'}, IA asistida=${aiValid ? 'válido' : 'inválido'}.`
          : `Statement ${i + 1}: native=${nativeValid ? 'valid' : 'invalid'}, AI-assisted=${aiValid ? 'valid' : 'invalid'}.`
      );
      if (c?.validityCorrection?.apply && c.validityCorrection.reason) {
        lines.push(`  ${c.validityCorrection.reason}`);
      }
      if (c?.pedagogy) {
        const shortPed =
          c.pedagogy.length > 220 ? `${c.pedagogy.slice(0, 217)}…` : c.pedagogy;
        lines.push(`  ${es ? 'Perspectiva' : 'Pedagogy'}: ${shortPed}`);
      }
      const topWarn = c?.additionalWarnings?.[0]?.message;
      if (topWarn) {
        lines.push(`  ${es ? 'Advertencia IA' : 'AI warning'}: ${topWarn}`);
      }
      const topOpt = c?.additionalOptimizations?.[0]?.description;
      if (topOpt) {
        lines.push(`  ${es ? 'Optimización IA' : 'AI optimization'}: ${topOpt}`);
      }
    }
    return lines.join('\n');
  }

  /** Native-first complement payload; scope fields guide the model without replacing core prompt rules. */
  private buildComplementPayload(
    analysis: QueryAnalysisResult,
    options?: {
      queryOverride?: string;
      liveSchemaNoteOverride?: string | null;
      analysisScope?: 'STATEMENT' | 'SCRIPT';
      fullScript?: string;
      statementIndex?: number | null;
      statementCount?: number | null;
    }
  ): Parameters<AiSuggestionService['complementAnalysis']>[0] {
    const liveNote = options?.liveSchemaNoteOverride ?? this.buildLiveSchemaNote(analysis);
    const connection = this.connectionForAnalysis();
    const opt = this.selectedEngineOption();
    const customBase =
      opt?.custom && opt.base?.trim() ? opt.base.trim().toLowerCase() : null;
    return {
      query: options?.queryOverride ?? analysis.originalQuery ?? this.query,
      databaseType: analysis.dbType ?? this.selectedDb,
      locale: this.currentUiLang(),
      nativeIsValid: analysis.isValid,
      expertMode:
        this.accessibilityService.getProfile().expertMode &&
        !this.accessibilityService.getProfile().studentMode,
      queryType: this.queryType,
      dialect: this.selectedDialect,
      ...(customBase ? { customEngineBase: customBase } : {}),
      ...(connection ? { connection } : {}),
      errors: (analysis.errors ?? []).map((e) => ({
        code: e.code ?? '',
        message: e.message ?? '',
        suggestion: e.suggestion ?? '',
      })),
      warnings: this.warningsForComplement(analysis),
      optimizations: (analysis.optimizations ?? []).map((o) => ({
        ruleId: o.ruleId,
        impact: o.impact,
        description: o.description,
        original: o.original,
        optimized: o.optimized,
      })),
      liveSchemaNote: liveNote,
      ...(options?.analysisScope ? { analysisScope: options.analysisScope } : {}),
      ...(options?.fullScript ? { fullScript: options.fullScript } : {}),
      ...(options?.statementIndex != null ? { statementIndex: options.statementIndex } : {}),
      ...(options?.statementCount != null ? { statementCount: options.statementCount } : {}),
    };
  }

  private runComplementRequest(
    index: number,
    payload: Parameters<AiSuggestionService['complementAnalysis']>[0],
    analysisForOverlay: QueryAnalysisResult | null,
    onSettled?: () => void
  ): void {
    if (!this.authService.isLoggedIn()) {
      this.applyComplementResponse(index, this.buildLocalComplement(payload), analysisForOverlay);
      this.aiComplementLoadingIndices.delete(index);
      onSettled?.();
      return;
    }

    this.aiSuggestionService.complementAnalysis(payload).subscribe({
      next: (res) => {
        this.aiComplementLoadingIndices.delete(index);
        if (res.success) {
          this.applyComplementResponse(index, res, analysisForOverlay);
        } else {
          this.applyComplementResponse(index, this.buildLocalComplement(payload), analysisForOverlay);
        }
        onSettled?.();
      },
      error: () => {
        this.aiComplementLoadingIndices.delete(index);
        this.applyComplementResponse(index, this.buildLocalComplement(payload, true), analysisForOverlay);
        onSettled?.();
      },
    });
  }

  private applyComplementResponse(
    index: number,
    res: ComplementAnalysisResponse,
    analysisForOverlay: QueryAnalysisResult | null
  ): void {
    const overlay = res.secondPassOverlay ?? null;
    this.aiComplementsByIndex.set(index, {
      pedagogy: res.pedagogy?.trim() ?? '',
      optimizationNotes: res.optimizationNotes?.trim() ?? '',
      validityCorrection: res.validityCorrection?.apply ? res.validityCorrection : null,
      nativeReviews: res.nativeReviews ?? [],
      additionalErrors: res.additionalErrors ?? [],
      additionalWarnings: res.additionalWarnings ?? [],
      additionalOptimizations: res.additionalOptimizations ?? [],
      syntaxCorrections: res.syntaxCorrections ?? [],
      secondPassOverlay: overlay,
      provider: res.provider ?? (res.aiAvailable ? 'ai' : 'rule-based'),
    });
    if (overlay?.suppressNativeErrors && analysisForOverlay) {
      if (
        index === QueryAnalyzerComponent.SCRIPT_AI_INDEX &&
        this.multiResult?.scriptLevel
      ) {
        this.multiResult = {
          ...this.multiResult,
          scriptLevel: this.queryService.mergeSecondPassOverlay(
            this.multiResult.scriptLevel,
            overlay
          ),
        };
      } else {
        this.applySecondPassOverlay(overlay, index);
      }
    }
    if (this.result) {
      this.persistHistoryAiSupplement(res);
    } else if (
      this.multiResult &&
      index === QueryAnalyzerComponent.SCRIPT_AI_INDEX
    ) {
      this.persistHistoryAiSupplementMulti(res);
    }
  }

  /** Augments the history row saved at native analyze time with AI second-pass outcome. */
  private persistHistoryAiSupplement(res: ComplementAnalysisResponse): void {
    if (!this.authService.isLoggedIn() || this.lastHistoryEntryId == null || !this.result) {
      return;
    }
    const complement = this.aiComplementAt(0);
    const aiValid = effectiveIsValid(this.result, complement);
    const provider = res.provider ?? (res.aiAvailable ? 'ai' : 'rule-based');
    const nativeOpts = this.result.optimizations ?? [];
    const aiOpts = complement?.additionalOptimizations ?? [];
    const mergedOpts = [
      ...nativeOpts.map((o) => ({
        ruleId: o.ruleId,
        impact: o.impact,
        description: o.description,
        originalFragment: o.original ?? o.ruleId,
        optimizedFragment: o.optimized ?? '',
        source: 'native',
      })),
      ...aiOpts.map((o) => ({
        ruleId: o.ruleId ?? 'AI-OPT',
        impact: o.impact,
        description: o.description,
        originalFragment: o.originalFragment ?? o.original ?? '',
        optimizedFragment: o.optimizedFragment ?? o.optimized ?? '',
        source: 'ai',
      })),
    ];
    const displayErrors = aiValid ? [] : (this.result.errors ?? []);
    const effectiveResultJson = JSON.stringify({
      isValid: aiValid,
      errors: displayErrors,
      warnings: (this.result.issues ?? [])
        .filter((i) => i.type === 'warning' || i.type === 'info')
        .map((i) => ({ code: i.code, severity: i.type === 'info' ? 'INFO' : 'WARNING' })),
      optimizations: mergedOpts,
      analyzedQuery: this.result.originalQuery,
      executionTimeMs: this.result.analysisTimeMs,
      astTree: this.result.ast ?? null,
      metrics: this.result.metrics ?? null,
      aiAssistedValid: aiValid,
      aiProvider: provider,
    });
    const aiComplementJson = complement
      ? JSON.stringify({
          pedagogy: complement.pedagogy,
          optimizationNotes: complement.optimizationNotes,
          validityCorrection: complement.validityCorrection,
          nativeReviews: complement.nativeReviews,
          additionalErrors: complement.additionalErrors,
          additionalWarnings: complement.additionalWarnings,
          additionalOptimizations: complement.additionalOptimizations,
          syntaxCorrections: complement.syntaxCorrections,
          provider: complement.provider,
        })
      : undefined;
    const warningCount =
      (this.result.issues?.filter((i) => i.type === 'warning').length ?? 0) +
      (complement?.additionalWarnings?.length ?? 0);
    this.queryHistoryService
      .patchAiSupplement(this.lastHistoryEntryId, {
        aiAssistedValid: aiValid,
        aiProvider: provider,
        analysisLocale: this.currentUiLang(),
        effectiveResultJson,
        aiComplementJson,
        optimizationCount: mergedOpts.length,
        warningCount,
      })
      .subscribe({ error: () => { /* history enrich is best-effort */ } });
  }

  /** PATCH history after whole-script IA completes (multi-statement scripts). */
  private persistHistoryAiSupplementMulti(res: ComplementAnalysisResponse): void {
    if (!this.authService.isLoggedIn() || this.lastHistoryEntryId == null || !this.multiResult) {
      return;
    }
    const scriptComp = this.scriptAiComplement;
    const stmts = this.multiResult.statements;
    const sl = this.multiResult.scriptLevel;
    const aiValidStatements = stmts.every((s, i) => effectiveIsValid(s, this.aiComplementAt(i)));
    const aiValidScript = sl ? effectiveIsValid(sl, scriptComp) : true;
    const aiValid = aiValidStatements && aiValidScript;
    const provider = res.provider ?? scriptComp?.provider ?? (res.aiAvailable ? 'ai' : 'rule-based');
    const nativeOpts = [
      ...stmts.flatMap((s) => s.optimizations ?? []),
      ...(sl?.optimizations ?? []),
    ];
    const aiOpts = [
      ...stmts.flatMap((_, i) => this.aiComplementAt(i)?.additionalOptimizations ?? []),
      ...(scriptComp?.additionalOptimizations ?? []),
    ];
    const mergedOpts = [
      ...nativeOpts.map((o) => ({
        ruleId: o.ruleId,
        impact: o.impact,
        description: o.description,
        originalFragment: o.original ?? o.ruleId,
        optimizedFragment: o.optimized ?? '',
        source: 'native' as const,
      })),
      ...aiOpts.map((o) => ({
        ruleId: o.ruleId ?? 'AI-OPT',
        impact: o.impact,
        description: o.description,
        originalFragment: o.originalFragment ?? o.original ?? '',
        optimizedFragment: o.optimizedFragment ?? o.optimized ?? '',
        source: 'ai' as const,
      })),
    ];
    const displayErrors = aiValid
      ? []
      : [
          ...stmts.flatMap((s) => s.errors ?? []),
          ...(sl?.errors ?? []),
        ];
    const effectiveResultJson = JSON.stringify({
      isValid: aiValid,
      errors: displayErrors,
      warnings: stmts.flatMap((s) =>
        (s.issues ?? [])
          .filter((i) => i.type === 'warning' || i.type === 'info')
          .map((i) => ({ code: i.code, severity: i.type === 'info' ? 'INFO' : 'WARNING' }))
      ),
      optimizations: mergedOpts,
      analyzedQuery: this.query,
      executionTimeMs: this.multiResult.totalExecutionTimeMs,
      scriptHealthPercent: this.multiResult.scriptHealthPercent,
      statementCount: stmts.length,
      aiAssistedValid: aiValid,
      aiProvider: provider,
    });
    const complements = {
      script: scriptComp,
      statements: stmts.map((_, i) => this.aiComplementAt(i)),
    };
    const aiComplementJson = JSON.stringify(complements);
    const warningCount =
      stmts.reduce(
        (n, s) => n + (s.issues?.filter((i) => i.type === 'warning').length ?? 0),
        0
      ) +
      stmts.reduce((n, _, i) => n + (this.aiComplementAt(i)?.additionalWarnings?.length ?? 0), 0) +
      (scriptComp?.additionalWarnings?.length ?? 0);
    this.queryHistoryService
      .patchAiSupplement(this.lastHistoryEntryId, {
        aiAssistedValid: aiValid,
        aiProvider: provider,
        analysisLocale: this.currentUiLang(),
        effectiveResultJson,
        aiComplementJson,
        optimizationCount: mergedOpts.length,
        warningCount,
      })
      .subscribe({ error: () => { /* history enrich is best-effort */ } });
  }

  private applySecondPassOverlay(
    overlay: NonNullable<ComplementAnalysisResponse['secondPassOverlay']>,
    statementIndex: number
  ): void {
    if (this.result && statementIndex === 0) {
      this.result = this.queryService.mergeSecondPassOverlay(this.result, overlay);
      return;
    }
    if (this.multiResult && statementIndex >= 0) {
      const stmts = [...this.multiResult.statements];
      if (stmts[statementIndex]) {
        stmts[statementIndex] = this.queryService.mergeSecondPassOverlay(
          stmts[statementIndex],
          overlay
        );
        this.multiResult = { ...this.multiResult, statements: stmts };
      }
    }
  }

  private buildLiveSchemaNote(analysis: QueryAnalysisResult): string | null {
    if (this.schemaValidationError) {
      return this.schemaValidationError;
    }
    const connCodes = (analysis.errors ?? []).filter(
      (e) => e.code === 'SCH-CONN' || e.code === 'SCH-EMPTY'
    );
    if (connCodes.length) {
      return connCodes.map((e) => e.message).join(' ');
    }
    return null;
  }

  private warningsForComplement(
    analysis: QueryAnalysisResult
  ): { code: string; severity: string }[] {
    const fromIssues = (analysis.issues ?? [])
      .filter((i) => i.type === 'warning' || i.type === 'info')
      .map((i) => ({
        code: i.code ?? 'WARN',
        severity: i.type === 'info' ? 'INFO' : 'WARNING',
      }));
    return fromIssues;
  }

  private buildLocalComplement(
    payload: Parameters<AiSuggestionService['complementAnalysis']>[0],
    apiFailed = false
  ): ComplementAnalysisResponse {
    const es = this.currentUiLang() === 'es';
    const loggedIn = this.authService.isLoggedIn();
    let pedagogy: string;
    if (!loggedIn) {
      pedagogy =
        (es
          ? 'Perspectiva complementaria (sin sesión): revisa errores nativos y hallazgos básicos abajo. '
          : 'Complementary perspective (no session): review native errors and basic findings below. ') +
        (es
          ? 'Inicia sesión para análisis IA completo con ejemplos de código.'
          : 'Sign in for full AI analysis with code examples.');
    } else if (apiFailed) {
      pedagogy = es
        ? 'No se pudo contactar al proveedor IA; se muestra el respaldo local básico. Revisa los hallazgos nativos y vuelve a pulsar Actualizar análisis IA.'
        : 'Could not reach the AI provider; showing basic local fallback. Review native findings and tap Refresh AI analysis.';
    } else {
      pedagogy = es
        ? 'Perspectiva IA complementaria (respaldo local): revisa errores nativos y hallazgos abajo.'
        : 'Complementary AI perspective (local fallback): review native errors and findings below.';
    }
    const addErrors: { code: string; message: string; suggestion: string }[] = [];
    const syntax: SyntaxCorrection[] = [];
    if (/\bWHERF\b/i.test(payload.query)) {
      const fixed = payload.query.replace(/\bWHERF\b/gi, 'WHERE');
      addErrors.push({
        code: 'AI-ERR-TYPO-WHERE',
        message: es ? 'Posible typo: WHERF → WHERE.' : 'Likely typo: WHERF → WHERE.',
        suggestion: es ? 'Reemplaza WHERF por WHERE.' : 'Replace WHERF with WHERE.',
      });
      syntax.push({
        forErrorCode: 'SYN-001-SQL',
        correctedQuery: fixed,
        explanation: es ? 'Corrección de palabra clave.' : 'Keyword fix.',
      });
    }
    return {
      success: true,
      pedagogy,
      optimizationNotes: '',
      validityCorrection: null,
      nativeReviews: [],
      additionalErrors: addErrors,
      additionalWarnings: [],
      additionalOptimizations: [],
      syntaxCorrections: syntax,
      secondPassOverlay: null,
      aiAvailable: false,
      provider: 'local',
    };
  }

  /** Informs signed-in users that the analysis was stored in query history. */
  private notifyHistorySaved(): void {
    if (!this.authService.isLoggedIn()) {
      return;
    }
    const message = this.translate.instant('analyzer.historySaved');
    const action = this.translate.instant('analyzer.historySavedAction');
    const ref = this.snackBar.open(message, action, {
      duration: 6000,
      politeness: 'polite',
      horizontalPosition: 'end',
      verticalPosition: 'bottom',
      panelClass: ['history-saved-snackbar'],
    });
    ref.onAction().pipe(take(1)).subscribe(() => {
      void this.router.navigate(['/history']);
    });
  }

  issuesHasErrorSeverity(issues: QueryIssue[] | undefined | null): boolean {
    return (issues?.some((i) => i.type === 'error') ?? false);
  }

  toggleVoiceDictation(): void {
    if (!this.speechRecognitionService.isSupported()) {
      return;
    }
    if (this.speechRecognitionService.isListening()) {
      this.speechRecognitionService.stopListening();
      return;
    }
    this.startDictation();
  }

  closeAnalyzerOverlays(): void {
    this.speechService.stop();
    if (this.speechRecognitionService.isListening()) {
      this.speechRecognitionService.stopListening();
    }
    this.expandedStatements = new Set<number>();
  }

  clearQuery(): void {
    this.speechService.stop();
    this.query              = '';
    this.result             = null;
    this.multiResult        = null;
    this.error              = null;
    this.selectedDialect    = null;
    this.expandedStatements         = new Set<number>();
    this.activeExpertStatementIndex = null;
    this.useSchemaValidation        = false;
    this.schemaConnection    = null;
    this.stepMode.reset();
  }

  // ── Text-to-speech (Web Speech API) ─────────────────────────────────────────
  speakText(text: string): void {
    const lang = this.translate.currentLang ?? this.translate.getFallbackLang() ?? undefined;
    this.speechService.speak(text, lang);
  }

  stopSpeaking(): void {
    this.speechService.stop();
  }

  /** Voice dictation into the query editor (stops TTS first to avoid feedback). */
  startDictation(): void {
    this.speechService.stop();
    this.dictationMessage = null;

    if (!this.speechRecognitionService.isSupported()) return;

    const lang =
      this.translate.currentLang ??
      this.translate.getFallbackLang() ??
      this.accessibilityService.getProfile().language ??
      'es';

    this.speechRecognitionService
      .startListening(lang)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (text) => this.insertDictatedText(text),
        error: (err: unknown) => this.handleDictationError(err),
      });
  }

  private insertDictatedText(text: string): void {
    const trimmed = text.trim();
    if (!trimmed) return;

    this.dictationMessage = null;
    const el = this.queryInputRef?.nativeElement;

    if (el) {
      const start = el.selectionStart ?? this.query.length;
      const end = el.selectionEnd ?? this.query.length;
      const before = this.query.slice(0, start);
      const needsSpace = before.length > 0 && !/\s$/.test(before);
      const insert = (needsSpace ? ' ' : '') + trimmed;
      this.query = before + insert + this.query.slice(end);
      const caret = start + insert.length;
      window.setTimeout(() => {
        el.focus();
        el.setSelectionRange(caret, caret);
      }, 0);
    } else {
      this.query = this.query.trim() ? `${this.query.trimEnd()} ${trimmed}` : trimmed;
    }
  }

  private handleDictationError(err: unknown): void {
    const code =
      err instanceof SpeechRecognitionError
        ? err.code
        : 'UNKNOWN';
    const keyMap: Record<string, string> = {
      NO_SPEECH: 'stt.errorNoSpeech',
      AUDIO_CAPTURE: 'stt.errorAudioCapture',
      NOT_ALLOWED: 'stt.errorNotAllowed',
      UNKNOWN: 'stt.errorUnknown',
    };
    this.dictationMessage = this.translate.instant(keyMap[code] ?? keyMap['UNKNOWN']);
  }

  fullSingleAnalysisSpeechText(): string {
    return this.result ? this.buildFullSpeech(this.result) : '';
  }

  fullMultiAnalysisSpeechText(): string {
    return this.multiResult ? this.buildMultiFullSpeech(this.multiResult) : '';
  }

  speechTextIssues(): string {
    return this.result ? this.buildIssuesSpeech(this.result) : '';
  }

  speechTextErrors(): string {
    return this.result ? this.buildErrorsSpeech(this.result) : '';
  }

  speechTextOptimizations(): string {
    return this.result ? this.buildOptimizationsSpeech(this.result) : '';
  }

  speechTextIssuesFor(r: QueryAnalysisResult): string {
    return this.buildIssuesSpeech(r);
  }

  speechTextErrorsFor(r: QueryAnalysisResult): string {
    return this.buildErrorsSpeech(r);
  }

  speechTextOptimizationsFor(r: QueryAnalysisResult): string {
    return this.buildOptimizationsSpeech(r);
  }

  speechTextForStatement(r: QueryAnalysisResult, index1Based: number): string {
    return this.buildFullSpeech(r, index1Based);
  }

  private maybeAutoSpeakBlind(
    single: QueryAnalysisResult | null,
    multi: MultiStatementAnalysisResult | null
  ): void {
    if (!this.accessibilityService.getProfile().blindMode) return;
    if (single) {
      this.speakText(this.buildBlindSummarySingle(single));
    } else if (multi) {
      this.speakText(this.buildBlindSummaryMulti(multi));
    }
  }

  private chipMainFromCode(code: string | undefined, fallback: string): string {
    if (!code?.trim()) return fallback;
    const c = code.trim();
    const titleKey = this.issueChipTitleKey(c);
    const tTitle = this.translate.instant(titleKey);
    if (tTitle !== titleKey) return tTitle;
    const flatKey = this.issueChipFlatKey(c);
    const tFlat = this.translate.instant(flatKey);
    if (tFlat !== flatKey) return tFlat;
    return fallback;
  }

  private pluralBlindErrorsFirst(count: number, message: string): string {
    return count === 1
      ? this.translate.instant('tts.blindErrorsFirstOne', { message })
      : this.translate.instant('tts.blindErrorsFirstOther', { count, message });
  }

  private pluralBlindValidOpts(count: number): string {
    return count === 1
      ? this.translate.instant('tts.blindValidOptimizationsOne')
      : this.translate.instant('tts.blindValidOptimizationsOther', { count });
  }

  private pluralBlindWarningsOnly(count: number): string {
    return count === 1
      ? this.translate.instant('tts.blindWarningsOnlyOne')
      : this.translate.instant('tts.blindWarningsOnlyOther', { count });
  }

  private pluralBlindMultiDone(count: number): string {
    return count === 1
      ? this.translate.instant('tts.blindMultiDoneOne')
      : this.translate.instant('tts.blindMultiDoneOther', { count });
  }

  private pluralMultiScriptIntro(count: number, ms: number): string {
    return count === 1
      ? this.translate.instant('tts.multiScriptIntroOne', { ms })
      : this.translate.instant('tts.multiScriptIntroOther', { count, ms });
  }

  private buildBlindSummarySingle(result: QueryAnalysisResult): string {
    const structErr = result.errors?.length ?? 0;
    if (structErr > 0) {
      const first = result.errors![0];
      return this.pluralBlindErrorsFirst(
        structErr,
        this.chipMainFromCode(first.code, first.message)
      );
    }
    const issueErr =
      result.issues?.filter((i) => i.type === 'error').length ?? 0;
    if (issueErr > 0) {
      const first = result.issues!.find((i) => i.type === 'error')!;
      return this.pluralBlindErrorsFirst(
        issueErr,
        this.chipMainFromCode(first.code, first.message)
      );
    }
    const optN = result.optimizations?.length ?? 0;
    if (optN > 0) {
      return this.pluralBlindValidOpts(optN);
    }
    if (showsPerfectResult(result, this.aiComplement)) {
      return this.translate.instant('analyzer.perfect');
    }
    const warnN =
      result.issues?.filter((i) => i.type === 'warning').length ?? 0;
    if (warnN > 0) {
      return this.pluralBlindWarningsOnly(warnN);
    }
    return this.translate.instant('tts.blindAnalysisDone');
  }

  private buildBlindSummaryMulti(multi: MultiStatementAnalysisResult): string {
    let totalErr = 0;
    let totalOpt = 0;
    let firstMsg = '';

    const consume = (r: QueryAnalysisResult | null | undefined): void => {
      if (!r) return;
      const ne = r.errors?.length ?? 0;
      totalErr += ne;
      if (!firstMsg && ne > 0 && r.errors?.[0]) {
        const e = r.errors[0];
        firstMsg = this.chipMainFromCode(e.code, e.message);
      }
      totalOpt += r.optimizations?.length ?? 0;
      const nie = r.issues?.filter((i) => i.type === 'error').length ?? 0;
      totalErr += nie;
      if (!firstMsg && nie > 0) {
        const ie = r.issues!.find((i) => i.type === 'error')!;
        firstMsg = this.chipMainFromCode(ie.code, ie.message);
      }
    };

    consume(multi.scriptLevel);
    for (const s of multi.statements) consume(s);

    if (totalErr > 0) {
      return this.pluralBlindErrorsFirst(
        totalErr,
        firstMsg || this.translate.instant('analyzer.invalid')
      );
    }
    if (totalOpt > 0) {
      return this.pluralBlindValidOpts(totalOpt);
    }
    return this.pluralBlindMultiDone(multi.statements.length);
  }

  private buildIssuesSpeech(result: QueryAnalysisResult): string {
    const parts: string[] = [this.translate.instant('analyzer.issuesTitle')];
    for (const issue of result.issues ?? []) {
      const main = issue.code
        ? this.chipMainFromCode(issue.code, issue.message)
        : issue.message;
      parts.push(main);
      if (issue.code) {
        const dk = this.issueChipDescriptionKey(issue.code);
        const dt = this.translate.instant(dk);
        if (dt !== dk) parts.push(dt);
        const sk = this.issueChipSuggestionKey(issue.code);
        const st = this.translate.instant(sk);
        if (st !== sk) parts.push(st);
        this.appendStudentSpeech(parts, issue.code);
      }
    }
    return parts.join('. ');
  }

  private appendStudentSpeech(
    parts: string[],
    code: string | undefined,
    education?: StudentExplanation | null
  ): void {
    if (!this.accessibilityService.getProfile().studentMode || !code) return;
    const edu = this.studentExplanations.getExplanation(code, education);
    if (!edu) return;
    parts.push(
      `${this.translate.instant('student.whatIsThis')} ${edu.what}`
    );
    if (edu.why) parts.push(edu.why);
    if (edu.correctedExample) {
      parts.push(
        `${this.translate.instant('student.correctedExample')}: ${edu.correctedExample}`
      );
    }
  }

  private appendErrorSpeechParts(parts: string[], err: AnalysisError): void {
    const main = err.code
      ? this.chipMainFromCode(err.code, err.message)
      : err.message;
    parts.push(main);
    if (err.line != null) {
      parts.push(
        err.column != null
          ? this.translate.instant('tts.lineCol', { line: err.line, col: err.column })
          : this.translate.instant('tts.lineOnly', { line: err.line })
      );
    }
    if (err.code) {
      const dk = this.issueChipDescriptionKey(err.code);
      const dt = this.translate.instant(dk);
      if (dt !== dk) parts.push(dt);
      const sk = this.issueChipSuggestionKey(err.code);
      const st = this.translate.instant(sk);
      if (st !== sk) parts.push(st);
      this.appendStudentSpeech(parts, err.code, err.education);
    }
    if (err.suggestion?.trim()) parts.push(err.suggestion.trim());
  }

  private buildErrorsSpeech(result: QueryAnalysisResult): string {
    const parts: string[] = [this.translate.instant('analyzer.errorsTitle')];
    for (const err of result.errors ?? []) this.appendErrorSpeechParts(parts, err);
    return parts.join('. ');
  }

  private buildOptimizationsSpeech(result: QueryAnalysisResult): string {
    const parts: string[] = [
      this.translate.instant('analyzer.optimizationsTitle'),
    ];
    for (const opt of result.optimizations ?? []) {
      const descKey = this.descriptionKey(opt);
      const tr = this.translate.instant(descKey);
      const desc =
        opt.ruleId && tr !== descKey ? tr : opt.description;
      parts.push(desc);
      parts.push(
        this.translate.instant(this.impactKey(opt.impact))
      );
      if (opt.original?.trim()) {
        parts.push(
          `${this.translate.instant('analyzer.original')}. ${opt.original.trim()}`
        );
      }
      if (opt.optimized?.trim()) {
        parts.push(
          `${this.translate.instant('analyzer.optimizedLabel')}. ${opt.optimized.trim()}`
        );
      }
    }
    return parts.join('. ');
  }

  private buildFullSpeech(
    result: QueryAnalysisResult,
    statementIndex?: number
  ): string {
    const parts: string[] = [];
    if (statementIndex != null) {
      parts.push(
        this.translate.instant('tts.statementHeading', {
          index: statementIndex,
        })
      );
    }
    parts.push(this.translate.instant('analyzer.panelResult'));
    if (!result.isValid) {
      parts.push(this.translate.instant('analyzer.invalid'));
    } else if (result.issues?.length) {
      parts.push(this.translate.instant('analyzer.validWithWarnings'));
    } else {
      parts.push(this.translate.instant('analyzer.valid'));
    }
    parts.push(
      this.translate.instant('tts.timingMs', {
        ms: result.analysisTimeMs,
      })
    );
    if (result.issues?.length) parts.push(this.buildIssuesSpeech(result));
    if (result.errors?.length) parts.push(this.buildErrorsSpeech(result));
    if (result.optimizations?.length)
      parts.push(this.buildOptimizationsSpeech(result));
    if (showsPerfectResult(result, this.aiComplement)) {
      parts.push(this.translate.instant('analyzer.perfect'));
    }
    return parts.join('. ');
  }

  private buildMultiFullSpeech(multi: MultiStatementAnalysisResult): string {
    const parts: string[] = [
      this.pluralMultiScriptIntro(
        multi.statements.length,
        multi.totalExecutionTimeMs
      ),
    ];
    if (multi.scriptHealthPercent != null) {
      parts.push(
        this.translate.instant('tts.scriptHealthHint', {
          pct: multi.scriptHealthPercent,
        })
      );
    }
    if (multi.scriptLevel) {
      parts.push(this.translate.instant('analyzer.scriptLevelTitle'));
      parts.push(this.buildFullSpeech(multi.scriptLevel));
    }
    multi.statements.forEach((stmt, i) => {
      parts.push(this.buildFullSpeech(stmt, i + 1));
    });
    return parts.join('. ');
  }

  isLoggedIn(): boolean {
    return this.authService.isLoggedIn();
  }

  isCustomAnalysis(result: QueryAnalysisResult | null): boolean {
    const src = result?.metadata?.source;
    return src === 'ai-custom' || src === 'native-approximate-custom';
  }

  /** Download analysis payload as JSON (single result, one statement, or full multi-script). */
  exportAsJson(
    analysis?: QueryAnalysisResult | null,
    statementIndex = 0
  ): void {
    const payload = analysis
      ? this.buildSingleExportPayload(analysis, statementIndex)
      : this.buildFullExportPayload();
    if (!payload) {
      return;
    }
    const data = JSON.stringify(payload, null, 2);
    const blob = new Blob([data], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    const anchor = document.createElement('a');
    anchor.href = url;
    anchor.download = `qwerys-analysis-${new Date().toISOString().slice(0, 10)}.json`;
    anchor.click();
    URL.revokeObjectURL(url);
  }

  /** Plain-text summary for clipboard (locale-aware via i18n). */
  copyReport(analysis?: QueryAnalysisResult | null, statementIndex = 0): void {
    if (!analysis && this.multiResult) {
      this.copyMultiScriptReport();
      return;
    }
    const r = analysis ?? this.result;
    if (!r) {
      return;
    }
    const valid = this.isEffectivelyValid(r, statementIndex);
    const status = this.translate.instant(
      valid ? 'export.report.valid' : 'export.report.invalid'
    );
    const errCount = r.errors?.length ?? 0;
    const optCount = r.optimizations?.length ?? 0;
    const errores =
      r.errors
        ?.map((e) =>
          this.translate.instant('export.report.errorLine', {
            code: e.code,
            message: e.message,
          })
        )
        .join('\n') || this.translate.instant('export.report.none');
    const opts =
      r.optimizations
        ?.map((o) => {
          const descKey = this.descriptionKey(o);
          const tr = this.translate.instant(descKey);
          const desc =
            o.ruleId && tr !== descKey ? tr : o.description;
          return this.translate.instant('export.report.optLine', { description: desc });
        })
        .join('\n') || this.translate.instant('export.report.none');
    const texto = [
      this.translate.instant('export.report.header', {
        date: new Date().toLocaleString(this.currentUiLang()),
      }),
      this.translate.instant('export.report.status', { status }),
      this.translate.instant('export.report.counts', { errors: errCount, optimizations: optCount }),
      '',
      this.translate.instant('export.report.errorsSection'),
      errores,
      '',
      this.translate.instant('export.report.optsSection'),
      opts,
    ].join('\n');
    navigator.clipboard.writeText(texto).then(() => {
      this.copySuccess = true;
      window.setTimeout(() => (this.copySuccess = false), 3000);
    });
  }

  openDiff(
    optimization: QueryOptimization | AiComplementOptimization,
    analysis: QueryAnalysisResult | null | undefined,
    statementIndex = 0
  ): void {
    const origFrag = (
      optimization.original?.trim() ??
      this.aiOptOriginal(optimization as AiComplementOptimization)?.trim() ??
      ''
    );
    const optFrag = (
      optimization.optimized?.trim() ??
      this.aiOptOptimized(optimization as AiComplementOptimization)?.trim() ??
      ''
    );
    const fullQuery = (analysis?.originalQuery ?? this.query).trim() || this.query;
    const descKey = optimization.ruleId
      ? this.descriptionKey(optimization)
      : '';
    const tr = descKey ? this.translate.instant(descKey) : '';
    const suggestionText =
      optimization.ruleId && tr !== descKey
        ? tr
        : (optimization.description ?? '');
    const ref = this.dialog.open(QueryDiffComponent, {
      width: this.isMobile ? '100vw' : '85vw',
      maxWidth: this.isMobile ? '100vw' : '1100px',
      height: this.isMobile ? '100vh' : undefined,
      maxHeight: this.isMobile ? '100vh' : undefined,
      panelClass: this.isMobile
        ? ['qwerys-diff-dialog', 'fullscreen-dialog']
        : ['qwerys-diff-dialog'],
      data: {
        fullQuery,
        databaseType: analysis?.dbType ?? this.selectedDb,
        suggestionText,
        suggestionCode: optimization.ruleId,
        nativeFindingsSummary: this.buildNativeFindingsForDiff(
          analysis,
          statementIndex
        ),
        originalFragment: origFrag || undefined,
        optimizedFragment: optFrag || undefined,
        optimizedQueryFromAnalysis: analysis?.optimizedQuery?.trim() || undefined,
      } satisfies QueryDiffData,
    });
    ref.afterClosed().subscribe((result: { useQuery?: string } | undefined) => {
      if (result?.useQuery) {
        this.query = result.useQuery;
      }
    });
  }

  /** Contexto de hallazgos visibles para el diff IA (sin tocar servicios del backend). */
  private buildNativeFindingsForDiff(
    analysis: QueryAnalysisResult | null | undefined,
    statementIndex: number
  ): string {
    if (!analysis) {
      return '';
    }
    const lines: string[] = [];
    for (const err of analysis.errors ?? []) {
      const code = err.code?.trim() || 'ERROR';
      lines.push(`ERROR [${code}]: ${err.message?.trim() ?? ''}`);
      if (err.suggestion?.trim()) {
        lines.push(`  → ${err.suggestion.trim()}`);
      }
    }
    for (const issue of analysis.issues ?? []) {
      if (issue.type === 'warning' || issue.type === 'error') {
        const code = issue.code?.trim() || issue.type.toUpperCase();
        lines.push(`${issue.type.toUpperCase()} [${code}]: ${issue.message?.trim() ?? ''}`);
      }
    }
    const comp = this.aiComplementAt(statementIndex);
    for (const aerr of comp?.additionalErrors ?? []) {
      lines.push(`AI-ERROR [${aerr.code}]: ${aerr.message?.trim() ?? ''}`);
    }
    for (const awarn of comp?.additionalWarnings ?? []) {
      lines.push(`AI-WARNING [${awarn.code}]: ${awarn.message?.trim() ?? ''}`);
    }
    return lines.join('\n');
  }

  private buildSingleExportPayload(
    analysis: QueryAnalysisResult,
    statementIndex: number
  ): Record<string, unknown> {
    return {
      exportedAt: new Date().toISOString(),
      locale: this.currentUiLang(),
      query: analysis.originalQuery ?? this.query,
      databaseType: analysis.dbType ?? this.selectedDb,
      analysis,
      aiComplement: this.aiComplementAt(statementIndex),
    };
  }

  private copyMultiScriptReport(): void {
    const multi = this.multiResult;
    if (!multi) {
      return;
    }
    const lines: string[] = [
      this.translate.instant('export.report.header', {
        date: new Date().toLocaleString(this.currentUiLang()),
      }),
      this.translate.instant('export.report.multiSummary', {
        count: multi.statements.length,
        ms: multi.totalExecutionTimeMs,
      }),
    ];
    multi.statements.forEach((stmt, i) => {
      const valid = this.isEffectivelyValid(stmt, i);
      const status = this.translate.instant(
        valid ? 'export.report.valid' : 'export.report.invalid'
      );
      lines.push(
        this.translate.instant('export.report.statementLine', {
          index: i + 1,
          status,
          errors: stmt.errors?.length ?? 0,
          optimizations: stmt.optimizations?.length ?? 0,
        })
      );
    });
    navigator.clipboard.writeText(lines.join('\n')).then(() => {
      this.copySuccess = true;
      window.setTimeout(() => (this.copySuccess = false), 3000);
    });
  }

  private buildFullExportPayload(): Record<string, unknown> | null {
    if (this.result) {
      return this.buildSingleExportPayload(this.result, 0);
    }
    if (this.multiResult) {
      return {
        exportedAt: new Date().toISOString(),
        locale: this.currentUiLang(),
        query: this.query,
        databaseType: this.selectedDb,
        multiAnalysis: this.multiResult,
        scriptAiComplement: this.scriptAiComplement,
        statementAiComplements: this.multiResult.statements.map((_, i) =>
          this.aiComplementAt(i)
        ),
      };
    }
    return null;
  }

  analysisSourceLabel(result: QueryAnalysisResult | null): string {
    const src = result?.metadata?.source;
    if (src === 'ai-custom') {
      return this.translate.instant('analyzer.metadata.aiCustom', {
        engine: result?.metadata?.declaredEngineLabel ?? '',
      });
    }
    if (src === 'native-approximate-custom') {
      return this.translate.instant('analyzer.metadata.approximate', {
        engine: result?.metadata?.declaredEngineLabel ?? '',
        base: result?.metadata?.referenceBaseEngine ?? '',
      });
    }
    return '';
  }

}
