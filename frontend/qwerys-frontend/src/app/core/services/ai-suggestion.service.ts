import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpHeaders } from '@angular/common/http';
import { TranslateService } from '@ngx-translate/core';
import { Observable, of } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { AuthService } from './auth.service';
import type { ComplementAnalysisResponse } from '../models/complement-analysis.model';
import type { DatabaseConfig } from './query.service';

export interface AiResponse {
  success: boolean;
  result: string | null;
  error: string | null;
  aiAvailable: boolean;
  provider?: string | null;
  responseTimeMs?: number | null;
}

export interface TableInfo {
  name: string;
  columns: string[];
}

/** All engines supported by the native analyzer (parity with query-analyzer). */
export const AI_SUPPORTED_ENGINES = [
  'mysql',
  'postgresql',
  'sqlite',
  'sqlserver',
  'oracle',
  'mongodb',
  'redis',
  'cassandra',
  'dynamodb',
  'elasticsearch',
] as const;

@Injectable({ providedIn: 'root' })
export class AiSuggestionService {
  private readonly http = inject(HttpClient);
  private readonly authService = inject(AuthService);
  private readonly translate = inject(TranslateService);
  private readonly baseUrl = '/api/ai';

  private getHeaders(): HttpHeaders {
    const token = this.authService.getAccessToken();
    return token
      ? new HttpHeaders({ Authorization: `Bearer ${token}` })
      : new HttpHeaders();
  }

  currentLocale(): string {
    return this.translate.getCurrentLang()?.trim() || 'es';
  }

  suggestQuery(
    description: string,
    databaseType: string,
    schema: TableInfo[] = [],
    locale?: string
  ): Observable<AiResponse> {
    return this.postAi('/suggest', {
      description,
      databaseType,
      schema,
      locale: locale ?? this.currentLocale(),
    });
  }

  autocomplete(
    partialQuery: string,
    databaseType: string,
    schema: TableInfo[] = [],
    locale?: string
  ): Observable<AiResponse> {
    return this.postAi('/autocomplete', {
      partialQuery,
      databaseType,
      schema,
      locale: locale ?? this.currentLocale(),
    });
  }

  generateQuery(
    operation: string,
    tableName: string,
    columns: string[],
    condition: string,
    databaseType: string,
    locale?: string
  ): Observable<AiResponse> {
    return this.postAi('/generate', {
      operation,
      tableName,
      columns,
      condition,
      databaseType,
      locale: locale ?? this.currentLocale(),
    });
  }

  explainErrors(
    query: string,
    databaseType: string,
    errors: { code: string; message: string; suggestion: string }[],
    locale?: string
  ): Observable<AiResponse> {
    return this.postAi('/explain', {
      query,
      databaseType,
      errors,
      locale: locale ?? this.currentLocale(),
    });
  }

  /** Structured complement after native analyzer (independent findings + native review). */
  complementAnalysis(payload: {
    query: string;
    databaseType: string;
    locale?: string;
    nativeIsValid: boolean;
    expertMode?: boolean;
    queryType?: string;
    dialect?: string | null;
    customEngineBase?: string | null;
    connection?: DatabaseConfig | null;
    errors: { code: string; message: string; suggestion: string }[];
    warnings: { code: string; severity: string }[];
    optimizations: {
      ruleId?: string;
      impact: string;
      description: string;
      original?: string;
      optimized?: string;
    }[];
    liveSchemaNote?: string | null;
    analysisScope?: 'STATEMENT' | 'SCRIPT' | null;
    fullScript?: string | null;
    statementIndex?: number | null;
    statementCount?: number | null;
  }): Observable<ComplementAnalysisResponse> {
    return this.http
      .post<ComplementAnalysisResponse>(`${this.baseUrl}/complement-analysis`, {
        ...payload,
        locale: payload.locale ?? this.currentLocale(),
      }, { headers: this.getHeaders() })
      .pipe(
        catchError(() =>
          of({
            success: false,
            aiAvailable: false,
            error: this.translate.instant('ai.genericError'),
          })
        )
      );
  }

  improveMigration(
    originalCode: string,
    sourceLanguage: string,
    targetLanguage: string,
    currentMigration: string,
    warnings: string[],
    manualSteps: string[],
    locale?: string
  ): Observable<AiResponse> {
    return this.postAi('/improve-migration', {
      originalCode,
      sourceLanguage,
      targetLanguage,
      currentMigration,
      warnings,
      manualSteps,
      locale: locale ?? this.currentLocale(),
    });
  }

  explainSecurityFinding(
    query: string,
    databaseType: string,
    patternId: string,
    ruleKey: string,
    riskSummary: string,
    locale?: string
  ): Observable<AiResponse> {
    return this.postAi('/explain-security', {
      query,
      databaseType,
      patternId,
      ruleKey,
      riskSummary,
      locale: locale ?? this.currentLocale(),
    });
  }

  checkStatus(): Observable<{ available: boolean }> {
    return this.http
      .get<{ available: boolean }>(`${this.baseUrl}/status`, { headers: this.getHeaders() })
      .pipe(catchError(() => of({ available: false })));
  }

  private postAi(path: string, body: unknown): Observable<AiResponse> {
    return this.http
      .post<AiResponse>(`${this.baseUrl}${path}`, body, { headers: this.getHeaders() })
      .pipe(
        catchError(() =>
          of({
            success: false,
            result: null,
            error: this.translate.instant('ai.genericError'),
            aiAvailable: false,
          })
        )
      );
  }
}
