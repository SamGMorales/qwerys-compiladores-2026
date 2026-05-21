import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpErrorResponse, HttpHeaders } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError, map } from 'rxjs/operators';

import { AuthService } from './auth.service';
import { AccessibilityService } from './accessibility.service';
import { DatabaseConfig } from './schema.service';
import { SyntaxNode, LexicalToken } from '../models/query-analysis.model';
import { StudentExplanation } from '../../shared/services/student-explanations.service';

export type { DatabaseConfig };

export interface AnalysisMetrics {
  tokenCount?: number;
  astDepth?: number;
  lexingTimeMs?: number;
  parsingTimeMs?: number;
  semanticTimeMs?: number;
  rulesEvaluated?: number;
}

export interface AnalysisError {
  code: string;
  message: string;
  suggestion?: string;
  line?: number;
  column?: number;
  /** Present when student mode is on and the backend has a catalog entry (locale-aware). */
  education?: StudentExplanation;
}

export interface QueryIssue {
  type: 'error' | 'warning' | 'info';
  message: string;
  code?: string;
}

export interface QueryOptimization {
  ruleId?: string;
  impact: 'HIGH' | 'MEDIUM' | 'LOW';
  description: string;
  original?: string;
  optimized?: string;
}

export interface AnalysisMetadata {
  source?: 'native' | 'ai-custom' | 'native-approximate-custom' | string;
  declaredEngineLabel?: string;
  referenceBaseEngine?: string;
  referenceComparison?: string;
}

export interface QueryAnalysisResult {
  originalQuery: string;
  dbType: string;
  isValid: boolean;
  issues?: QueryIssue[];
  optimizations?: QueryOptimization[];
  optimizedQuery?: string;
  suggestions?: string[];
  complexity?: string;
  analysisTimeMs: number;
  errors?: AnalysisError[];
  ast?: SyntaxNode;
  tokens?: LexicalToken[];
  metrics?: AnalysisMetrics;
  metadata?: AnalysisMetadata;
  /** Present when signed in and backend persisted this analysis to history. */
  historyEntryId?: number;
}

/** Row from GET /api/queries/engines */
export interface SupportedEngineDto {
  id: string;
  name: string;
  /** Present for custom engines — underlying adapter key (mysql, mongodb, …). */
  base?: string;
  custom?: string;
}

export interface QueryRequest {
  query: string;
  databaseType: string;
  queryType?: string;
  dialect?: string | null;
  locale?: string | null;
  /** Live DB connection for schema-aware validation (SQL engines only). */
  connection?: DatabaseConfig;
  /** Sent when databaseType is custom::* — optional; backend also parses base from id. */
  customEngineBase?: string | null;
}

export interface MultiStatementAnalysisResult {
  statements: QueryAnalysisResult[];
  totalExecutionTimeMs: number;
  /** Whole-script panel: cross-statement findings + aggregate validity */
  scriptLevel?: QueryAnalysisResult | null;
  /** 0–100 holistic score (statements + script-level penalties) */
  scriptHealthPercent?: number;
  /** Present when signed in and backend persisted the full script to history. */
  historyEntryId?: number;
}

const API_BASE = '/api';

/** Same key Settings uses — guest engines appear in analyzer via merge logic. */
export const CUSTOM_DATABASES_STORAGE_KEY = 'customDatabases';

export function parseCustomDatabaseEntriesFromStorage(): Array<{ name: string; base: string }> {
  try {
    const raw = localStorage.getItem(CUSTOM_DATABASES_STORAGE_KEY);
    if (!raw?.trim()) return [];
    const parsed = JSON.parse(raw) as unknown;
    if (!Array.isArray(parsed)) return [];
    const rows: Array<{ name: string; base: string }> = [];
    for (const item of parsed) {
      if (item != null && typeof item === 'object' && !Array.isArray(item)) {
        const o = item as Record<string, unknown>;
        const name = typeof o['name'] === 'string' ? o['name'].trim() : '';
        const base = typeof o['base'] === 'string' ? o['base'].trim().toLowerCase() : '';
        if (name && !name.includes('::') && base) rows.push({ name, base });
        continue;
      }
      if (typeof item === 'string') {
        const s = item.trim();
        if (!s) continue;
        if (!s.includes('::')) {
          rows.push({ name: s, base: 'mysql' });
          continue;
        }
        const [name, ...rest] = s.split('::');
        const baseJoined = rest.join('::').trim().toLowerCase();
        rows.push({
          name: name.trim(),
          base: baseJoined || 'mysql',
        });
      }
    }
    return rows;
  } catch {
    return [];
  }
}

export function toSupportedEngineDtoFromCustomRow(name: string, base: string): SupportedEngineDto {
  const n = name.trim();
  const b = base.trim().toLowerCase();
  return {
    id: `custom::${n}::${b}`,
    name: n,
    base: b,
    custom: 'true',
  };
}

@Injectable({ providedIn: 'root' })
export class QueryService {
  private readonly http = inject(HttpClient);
  private readonly authService = inject(AuthService);
  private readonly accessibilityService = inject(AccessibilityService);

  /**
   * Built-in catalog + JWT user's custom aliases (guests rely on merge from {@link CUSTOM_DATABASES_STORAGE_KEY}).
   */
  getEngines(): Observable<SupportedEngineDto[]> {
    return this.getSupportedEngines(this.authService.getAccessToken());
  }

  /**
   * Engines exposed by the backend (built-ins + authenticated user's custom motors).
   */
  getSupportedEngines(authToken: string | null): Observable<SupportedEngineDto[]> {
    let headers = new HttpHeaders();
    if (authToken?.trim()) {
      headers = headers.set('Authorization', `Bearer ${authToken.trim()}`);
    }
    return this.http
      .get<SupportedEngineDto[]>(`${API_BASE}/queries/engines`, { headers })
      .pipe(catchError(this.handleError));
  }

  analyzeQuery(
    query: string,
    dbType: string,
    queryType?: string,
    locale?: string,
    connection?: DatabaseConfig,
    customEngineBase?: string | null
  ): Observable<QueryAnalysisResult> {
    const body: QueryRequest = {
      query,
      databaseType: dbType,
      queryType,
      dialect: null,
      locale: locale ?? null,
    };
    if (customEngineBase != null && String(customEngineBase).trim()) {
      body.customEngineBase = customEngineBase.trim();
    }
    if (connection) {
      body.connection = connection;
    }
    return this.http
      .post<any>(`${API_BASE}/queries/analyze`, body, {
        headers: this.buildAnalyzeHeaders(),
      })
      .pipe(
        map((raw: any): QueryAnalysisResult =>
          this.mapQueryAnalysisRaw(raw, {
            query,
            databaseType: dbType,
            queryType,
          })
        ),
        catchError(this.handleError)
      );
  }

  analyzeMultiStatement(request: QueryRequest): Observable<MultiStatementAnalysisResult> {
    const body: QueryRequest = {
      ...request,
      dialect: request.dialect ?? null,
      locale: request.locale ?? null,
    };
    if (
      body.customEngineBase == null ||
      (typeof body.customEngineBase === 'string' && !body.customEngineBase.trim())
    ) {
      delete body.customEngineBase;
    }
    if (!request.connection) {
      delete body.connection;
    }
    return this.http
      .post<any>(`${API_BASE}/queries/analyze-multi`, body, {
        headers: this.buildAnalyzeHeaders(),
      })
      .pipe(
        map((raw: any): MultiStatementAnalysisResult => ({
          statements: (raw.statements ?? []).map((s: any): QueryAnalysisResult =>
            this.mapQueryAnalysisRaw(s, request)
          ),
          totalExecutionTimeMs: raw.totalExecutionTimeMs ?? 0,
          scriptLevel: raw.scriptLevel ? this.mapQueryAnalysisRaw(raw.scriptLevel, request) : null,
          scriptHealthPercent:
            raw.scriptHealthPercent != null ? Number(raw.scriptHealthPercent) : undefined,
          ...(raw.historyEntryId != null ? { historyEntryId: Number(raw.historyEntryId) } : {}),
        })),
        catchError(this.handleError)
      );
  }

  private mapQueryAnalysisRaw(s: any, request: QueryRequest): QueryAnalysisResult {
    return {
      originalQuery: s.analyzedQuery ?? request.query,
      dbType: request.databaseType,
      isValid: s.isValid,
      errors: (s.errors ?? []).map((e: any) => {
        if (typeof e === 'string') {
          return { code: e, message: e };
        }
        const education = this.mapEducation(e.education);
        return {
          code: e.code,
          message: e.message,
          suggestion: e.suggestion,
          ...(e.line != null ? { line: Number(e.line) } : {}),
          ...(e.column != null ? { column: Number(e.column) } : {}),
          ...(education ? { education } : {}),
        };
      }),
      issues: (s.warnings ?? []).map((w: string | { code?: string; severity?: string }) => {
        if (typeof w === 'string') {
          return { type: 'warning' as const, code: w, message: w };
        }
        const sev = (w.severity ?? 'WARNING').toUpperCase();
        const type =
          sev === 'INFO' ? ('info' as const) : ('warning' as const);
        const code = w.code ?? '';
        return { type, code, message: code };
      }),
      optimizations: (s.optimizations ?? []).map((opt: any) => ({
        ...opt,
        original: opt.originalFragment,
        optimized: opt.optimizedFragment,
      })),
      analysisTimeMs: s.executionTimeMs ?? 0,
      ast: this.mapAstNode(s.astTree ?? s.ast ?? s.syntaxTree),
      tokens: this.mapTokens(s.tokens),
      metrics: this.mapMetrics(s),
      metadata: s.metadata
        ? {
            source: s.metadata.source,
            declaredEngineLabel: s.metadata.declaredEngineLabel,
            referenceBaseEngine: s.metadata.referenceBaseEngine,
            referenceComparison: s.metadata.referenceComparison,
          }
        : undefined,
      ...(s.historyEntryId != null ? { historyEntryId: Number(s.historyEntryId) } : {}),
    };
  }

  private buildAnalyzeHeaders(): HttpHeaders {
    const profile = this.accessibilityService.getProfile();
    let headers = new HttpHeaders();
    const token = this.authService.getAccessToken();
    if (token?.trim()) {
      headers = headers.set('Authorization', `Bearer ${token.trim()}`);
    }
    if (profile.studentMode) {
      headers = headers.set('X-Student-Mode', 'true');
    }
    if (profile.expertMode) {
      headers = headers.set('X-Expert-Mode', 'true');
    }
    return headers;
  }

  private mapEducation(raw: unknown): StudentExplanation | undefined {
    if (!raw || typeof raw !== 'object') {
      return undefined;
    }
    const e = raw as Record<string, unknown>;
    const what = typeof e['what'] === 'string' ? e['what'].trim() : '';
    const why = typeof e['why'] === 'string' ? e['why'].trim() : '';
    if (!what && !why) {
      return undefined;
    }
    const example =
      typeof e['example'] === 'string' && e['example'].trim()
        ? e['example'].trim()
        : undefined;
    const correctedExample =
      typeof e['correctedExample'] === 'string' && e['correctedExample'].trim()
        ? e['correctedExample'].trim()
        : undefined;
    return {
      what: what || why,
      why: why || what,
      ...(example ? { example } : {}),
      ...(correctedExample ? { correctedExample } : {}),
    };
  }

  private mapAstNode(raw: unknown): SyntaxNode | undefined {
    if (!raw || typeof raw !== 'object') return undefined;
    const node = raw as Record<string, unknown>;
    const childrenRaw = (node['children'] as unknown[]) ?? [];
    return {
      type: String(node['type'] ?? node['nodeType'] ?? 'NODE'),
      value:
        node['value'] != null
          ? String(node['value'])
          : node['text'] != null
            ? String(node['text'])
            : undefined,
      children: childrenRaw
        .map((c) => this.mapAstNode(c))
        .filter((c): c is SyntaxNode => c != null),
    };
  }

  private mapTokens(raw: unknown): LexicalToken[] | undefined {
    if (!Array.isArray(raw)) return undefined;
    return raw.map((t) => {
      const tok = t as Record<string, unknown>;
      return {
        type: String(tok['type'] ?? 'TOKEN'),
        value: String(tok['value'] ?? tok['text'] ?? ''),
        line: Number(tok['line'] ?? 0),
        column: Number(tok['column'] ?? 0),
      };
    });
  }

  private mapMetrics(s: Record<string, unknown>): AnalysisMetrics | undefined {
    const m = (s['metrics'] as Record<string, unknown>) ?? s;
    const tokenCount =
      m['tokenCount'] != null
        ? Number(m['tokenCount'])
        : m['totalTokens'] != null
          ? Number(m['totalTokens'])
          : Array.isArray(s['tokens'])
            ? s['tokens'].length
            : undefined;
    const astDepth = m['astDepth'] != null ? Number(m['astDepth']) : undefined;
    const lexingTimeMs =
      m['lexingTimeMs'] != null ? Number(m['lexingTimeMs']) : undefined;
    const parsingTimeMs =
      m['parsingTimeMs'] != null ? Number(m['parsingTimeMs']) : undefined;
    const semanticTimeMs =
      m['semanticTimeMs'] != null
        ? Number(m['semanticTimeMs'])
        : m['semanticAnalysisTimeMs'] != null
          ? Number(m['semanticAnalysisTimeMs'])
          : undefined;
    const rulesEvaluated =
      m['rulesEvaluated'] != null
        ? Number(m['rulesEvaluated'])
        : m['optimizationRulesEvaluated'] != null
          ? Number(m['optimizationRulesEvaluated'])
          : undefined;

    if (
      tokenCount == null &&
      astDepth == null &&
      lexingTimeMs == null &&
      parsingTimeMs == null &&
      semanticTimeMs == null &&
      rulesEvaluated == null
    ) {
      return undefined;
    }

    return {
      tokenCount,
      astDepth,
      lexingTimeMs,
      parsingTimeMs,
      semanticTimeMs,
      rulesEvaluated,
    };
  }

  /** Apply native re-parse from AI second pass (clears false syntax errors, attaches AST). */
  mergeSecondPassOverlay(
    base: QueryAnalysisResult,
    overlay: {
      suppressNativeErrors: boolean;
      reparseIsValid?: boolean;
      astTree?: unknown;
      metrics?: unknown;
    }
  ): QueryAnalysisResult {
    if (!overlay.suppressNativeErrors) {
      return base;
    }
    const ast = overlay.astTree
      ? this.mapAstNode(overlay.astTree)
      : base.ast;
    let metrics =
      overlay.metrics != null
        ? this.mapMetrics(overlay.metrics as Record<string, unknown>)
        : base.metrics;
    // When the native re-parse failed (metrics missing) but the AI provided an AST,
    // recompute astDepth from that tree so the metrics panel reflects reality
    // instead of showing 0 next to a visibly non-empty tree.
    if (ast && (!metrics || metrics.astDepth == null || metrics.astDepth === 0)) {
      const aiDepth = this.computeAstDepth(ast);
      if (aiDepth > 0) {
        metrics = { ...(metrics ?? {}), astDepth: aiDepth };
      }
    }
    return {
      ...base,
      isValid: overlay.reparseIsValid ?? true,
      errors: [],
      ast: ast ?? base.ast,
      metrics: metrics ?? base.metrics,
    };
  }

  /** Depth = 1 for a leaf node, otherwise 1 + max depth of children. */
  private computeAstDepth(node: SyntaxNode | undefined | null): number {
    if (!node) return 0;
    const children = node.children ?? [];
    if (children.length === 0) return 1;
    let max = 0;
    for (const c of children) {
      const d = this.computeAstDepth(c);
      if (d > max) max = d;
    }
    return 1 + max;
  }

  checkHealth(): Observable<string> {
    return this.http
      .get(`${API_BASE}/queries/health`, { responseType: 'text' })
      .pipe(catchError(this.handleError));
  }

  private handleError(error: HttpErrorResponse): Observable<never> {
    let message = 'An unexpected error occurred. Please try again.';

    if (error.status === 0) {
      message = 'Cannot connect to the server. Please check your connection.';
    } else if (error.status === 400) {
      message = 'Invalid query or database type.';
    } else if (error.status === 401) {
      message = 'Your session has expired. Please log in again.';
    } else if (error.status === 500) {
      message = 'Server error while analyzing your query. Please try again later.';
    } else if (error.error?.message) {
      message = error.error.message;
    }

    return throwError(() => new Error(message));
  }
}
