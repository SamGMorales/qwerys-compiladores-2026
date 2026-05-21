import {
  Component,
  Inject,
  OnInit,
  inject,
  signal,
} from '@angular/core';
import {
  MAT_DIALOG_DATA,
  MatDialogModule,
  MatDialogRef,
} from '@angular/material/dialog';
import { CommonModule } from '@angular/common';
import { MatButtonModule } from '@angular/material/button';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { TranslateModule } from '@ngx-translate/core';
import {
  DiffEditorModel,
  MonacoEditorModule,
} from 'ngx-monaco-editor-v2';
import { AccessibilityService } from '../../../core/services/accessibility.service';
import { AiSuggestionService } from '../../../core/services/ai-suggestion.service';

export interface QueryDiffData {
  /** Full query from the editor (left pane). */
  fullQuery: string;
  databaseType: string;
  suggestionText: string;
  suggestionCode?: string;
  /** Native + visible findings for IA (no toca prompts del backend). */
  nativeFindingsSummary?: string;
  originalFragment?: string;
  optimizedFragment?: string;
  /** Solo si el analizador nativo devolvió una query completa alternativa. */
  optimizedQueryFromAnalysis?: string;
}

interface DiffEditorConstructionOptions {
  theme?: string;
  language?: string;
  readOnly?: boolean;
  renderSideBySide?: boolean;
  automaticLayout?: boolean;
  minimap?: { enabled: boolean };
  scrollBeyondLastLine?: boolean;
  fontSize?: number;
  accessibilitySupport?: string;
  touch?: boolean;
  scrollbar?: { handleMouseWheel?: boolean };
}

const MONACO_LANG: Record<string, string> = {
  mysql: 'sql',
  postgresql: 'sql',
  sqlite: 'sql',
  sqlserver: 'sql',
  oracle: 'sql',
  mongodb: 'javascript',
  redis: 'plaintext',
  cassandra: 'sql',
  dynamodb: 'sql',
  elasticsearch: 'json',
};

@Component({
  selector: 'app-query-diff',
  standalone: true,
  imports: [
    CommonModule,
    MonacoEditorModule,
    MatDialogModule,
    MatButtonModule,
    MatProgressSpinnerModule,
    TranslateModule,
  ],
  templateUrl: './query-diff.component.html',
  styleUrls: ['./query-diff.component.scss'],
})
export class QueryDiffComponent implements OnInit {
  readonly dialogRef = inject(MatDialogRef<QueryDiffComponent>);
  readonly data = inject<QueryDiffData>(MAT_DIALOG_DATA);
  private readonly accessibility = inject(AccessibilityService);
  private readonly aiService = inject(AiSuggestionService);
  readonly loading = signal(true);
  /** IA no disponible: se muestra aviso pero puede haber diff con texto de sugerencia. */
  readonly aiUnavailable = signal(false);
  /** Panel derecho generado por IA (habilita “Usar query optimizada”). */
  readonly aiGenerated = signal(false);
  readonly similarity = signal(0);
  readonly hasDiffView = signal(false);
  /** Right pane text (full optimized query or fragment fallback). */
  private modifiedText = '';

  diffOptions: DiffEditorConstructionOptions = this.buildDiffOptions();
  originalModel: DiffEditorModel = { code: '', language: 'sql' };
  modifiedModel: DiffEditorModel = { code: '', language: 'sql' };

  ngOnInit(): void {
    const fullQuery = (this.data.fullQuery ?? '').trim();
    if (!fullQuery) {
      this.loading.set(false);
      this.aiUnavailable.set(true);
      return;
    }

    // Solo atajo si el nativo ya devolvió otra query completa (poco frecuente).
    const nativeComplete = this.data.optimizedQueryFromAnalysis?.trim();
    if (
      nativeComplete &&
      nativeComplete !== fullQuery &&
      nativeComplete.length >= fullQuery.length * 0.85
    ) {
      this.aiGenerated.set(true);
      this.showDiff(fullQuery, nativeComplete);
      this.loading.set(false);
      return;
    }

    // Caso habitual Día 44: IA reescribe la query COMPLETA (optimización + hallazgos nativos/IA).
    this.requestAiOptimizedQuery(fullQuery);
  }

  useOptimized(): void {
    const next = this.buildFullOptimizedQuery();
    if (next.trim()) {
      this.dialogRef.close({ useQuery: next });
    }
  }

  canUseOptimized(): boolean {
    return (
      this.hasDiffView() &&
      this.aiGenerated() &&
      !this.aiUnavailable() &&
      !!this.buildFullOptimizedQuery().trim()
    );
  }

  private requestAiOptimizedQuery(fullQuery: string): void {
    const locale = this.aiService.currentLocale();
    const description = this.buildCompleteRewritePrompt(fullQuery, locale);

    this.aiService
      .suggestQuery(description, this.data.databaseType, [], locale)
      .subscribe({
        next: (res) => {
          this.loading.set(false);
          const aiText = this.normalizeAiQuery(res.result);
          if (
            res.success &&
            res.aiAvailable !== false &&
            aiText &&
            aiText !== fullQuery
          ) {
            this.aiGenerated.set(true);
            this.showDiff(fullQuery, aiText);
            return;
          }
          this.applyFallbackAfterAiFailure(fullQuery);
        },
        error: () => {
          this.loading.set(false);
          this.applyFallbackAfterAiFailure(fullQuery);
        },
      });
  }

  private applyFallbackAfterAiFailure(_fullQuery: string): void {
    this.aiUnavailable.set(true);
    this.aiGenerated.set(false);
    this.hasDiffView.set(false);
  }

  private mergeFragmentIntoFull(full: string, orig: string, opt: string): string {
    return full.includes(orig) ? full.replace(orig, opt) : opt;
  }

  /** Prompt solo en frontend (/api/ai/suggest); no modifica prompts del complement ni analizadores. */
  private buildCompleteRewritePrompt(fullQuery: string, locale: string): string {
    const suggestion = this.data.suggestionText?.trim() || '';
    const code = this.data.suggestionCode?.trim() || '';
    const engine = this.data.databaseType;
    const findings = this.data.nativeFindingsSummary?.trim();
    const orig = this.data.originalFragment?.trim();
    const opt = this.data.optimizedFragment?.trim();
    const fragmentHint =
      orig && opt ? `\nFragmento sugerido: "${orig}" → "${opt}"` : '';

    if (locale.startsWith('es')) {
      return [
        `Reescribe la query COMPLETA para ${engine}.`,
        `1) Aplica esta optimización: "${suggestion}"${code ? ` (${code})` : ''}.`,
        '2) Corrige TODOS los errores y advertencias del análisis (sintaxis incompleta, WHERE sin condición, etc.).',
        '3) La respuesta debe ser UNA sentencia completa lista para ejecutar, no un fragmento ni solo el cambio parcial.',
        '4) Si falta condición en WHERE, añade una condición válida coherente (p. ej. WHERE 1=1 o un filtro sobre columnas existentes).',
        '5) Mantén la intención original del usuario.',
        'Devuelve SOLO la query corregida completa, sin markdown ni explicaciones.',
        findings ? `\nHallazgos del análisis:\n${findings}` : '',
        fragmentHint,
        `\nQuery original:\n${fullQuery}`,
      ].join('\n');
    }

    return [
      `Rewrite the COMPLETE query for ${engine}.`,
      `1) Apply this optimization: "${suggestion}"${code ? ` (${code})` : ''}.`,
      '2) Fix ALL analyzer errors and warnings (incomplete syntax, WHERE without a condition, etc.).',
      '3) Return ONE full executable statement, not a fragment or partial edit only.',
      '4) If WHERE lacks a condition, add a valid one (e.g. WHERE 1=1 or a filter on existing columns).',
      '5) Preserve the user’s original intent.',
      'Return ONLY the full corrected query, no markdown or explanations.',
      findings ? `\nAnalysis findings:\n${findings}` : '',
      fragmentHint,
      `\nOriginal query:\n${fullQuery}`,
    ].join('\n');
  }

  private showDiff(original: string, modified: string): void {
    this.modifiedText = modified;
    const lang = this.monacoLanguage(this.data.databaseType);
    this.originalModel = { code: original, language: lang };
    this.modifiedModel = { code: modified, language: lang };
    this.hasDiffView.set(!!modified.trim() && modified.trim() !== original.trim());
    if (this.hasDiffView()) {
      this.similarity.set(this.calcLineSimilarity(original, modified));
    }
    this.diffOptions = this.buildDiffOptions();
  }

  private normalizeAiQuery(raw: string | null | undefined): string {
    if (!raw?.trim()) {
      return '';
    }
    let text = raw.trim();
    const fenced = text.match(/^```[\w]*\n?([\s\S]*?)```$/);
    if (fenced?.[1]) {
      text = fenced[1].trim();
    }
    return text;
  }

  private buildFullOptimizedQuery(): string {
    const full = this.data.fullQuery ?? '';
    const orig = this.data.originalFragment?.trim();
    const opt = this.data.optimizedFragment?.trim();
    const modified = this.modifiedText.trim();

    if (modified && modified !== orig) {
      const looksFull =
        modified.includes('\n') ||
        modified.length >= full.length * 0.5 ||
        !orig ||
        !full.includes(orig);
      if (looksFull) {
        return modified;
      }
    }
    if (orig && opt && full.includes(orig)) {
      return full.replace(orig, opt);
    }
    return modified || full;
  }

  /** Similitud: líneas idénticas + solapamiento de palabras (más útil en queries de una línea). */
  private calcLineSimilarity(a: string, b: string): number {
    const linesA = a.split(/\r?\n/).map((l) => l.trim()).filter(Boolean);
    const linesB = b.split(/\r?\n/).map((l) => l.trim()).filter(Boolean);
    if (linesA.length === 0 && linesB.length === 0) {
      return 100;
    }
    const setB = new Set(linesB);
    const lineCommon = linesA.filter((l) => setB.has(l)).length;
    const lineTotal = Math.max(linesA.length, linesB.length, 1);
    const lineScore = (lineCommon / lineTotal) * 100;

    const wordsA = new Set(
      a
        .toLowerCase()
        .split(/\s+/)
        .map((w) => w.replace(/[^a-z0-9_*$.-]/gi, ''))
        .filter(Boolean)
    );
    const wordsB = new Set(
      b
        .toLowerCase()
        .split(/\s+/)
        .map((w) => w.replace(/[^a-z0-9_*$.-]/gi, ''))
        .filter(Boolean)
    );
    const wordCommon = [...wordsA].filter((w) => wordsB.has(w)).length;
    const wordTotal = Math.max(wordsA.size, wordsB.size, 1);
    const wordScore = (wordCommon / wordTotal) * 100;

    return Math.round(Math.max(lineScore, wordScore));
  }

  private monacoLanguage(databaseType: string): string {
    const key = databaseType.toLowerCase();
    if (key.startsWith('custom::')) {
      const base = key.split('::').pop() ?? 'sql';
      return MONACO_LANG[base] ?? 'sql';
    }
    return MONACO_LANG[key] ?? 'sql';
  }

  private buildDiffOptions(): DiffEditorConstructionOptions {
    const profile = this.accessibility.getProfile();
    const isDark =
      document.body.classList.contains('dark-theme') || profile.darkTheme;
    return {
      theme: isDark ? 'vs-dark' : 'vs',
      language: this.monacoLanguage(this.data.databaseType),
      readOnly: true,
      renderSideBySide: true,
      automaticLayout: true,
      minimap: { enabled: false },
      scrollBeyondLastLine: false,
      fontSize: profile.lowVisionMode ? 16 : 13,
      touch: true,
      scrollbar: { handleMouseWheel: false },
      accessibilitySupport: profile.blindMode ? 'on' : 'auto',
    };
  }
}
