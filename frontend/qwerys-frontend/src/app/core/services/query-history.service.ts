import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpHeaders, HttpErrorResponse } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError, map } from 'rxjs/operators';

import { AuthService } from './auth.service';
import type { AiComplementState } from '../models/complement-analysis.model';
import { QueryAnalysisResult, QueryOptimization } from './query.service';

function normalizeImpact(value: unknown): QueryOptimization['impact'] {
  const u = String(value ?? 'MEDIUM').toUpperCase();
  if (u === 'HIGH' || u === 'LOW') {
    return u;
  }
  return 'MEDIUM';
}

export interface HistoryEntry {
  id: number;
  query: string;
  databaseType: string;
  /** Native analyzer validity. */
  valid: boolean;
  /** Effective validity after AI complement, when recorded. */
  aiAssistedValid?: boolean | null;
  aiProvider?: string | null;
  /** Locale used at analysis time (es, en). */
  analysisLocale?: string | null;
  errorCount: number;
  warningCount: number;
  optimizationCount: number;
  /** ISO string from API; legacy rows may arrive as Jackson array [y, m, d, h, min, sec]. */
  analyzedAt: string | number[];
  resultJson: string;
  aiComplementJson?: string | null;
  favorite: boolean;
}

export interface AiHistorySupplementPayload {
  aiAssistedValid: boolean;
  aiProvider: string;
  analysisLocale: string;
  effectiveResultJson?: string;
  aiComplementJson?: string;
  optimizationCount?: number;
  warningCount?: number;
}

export interface PageResponse<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
}

const API_BASE = '/api/history';

@Injectable({ providedIn: 'root' })
export class QueryHistoryService {
  private readonly http = inject(HttpClient);
  private readonly authService = inject(AuthService);

  getHistory(page: number, size: number): Observable<PageResponse<HistoryEntry>> {
    return this.http
      .get<PageResponse<HistoryEntry>>(`${API_BASE}?page=${page}&size=${size}`, {
        headers: this.authHeaders(),
      })
      .pipe(catchError(this.handleError));
  }

  getById(id: number): Observable<HistoryEntry> {
    return this.http
      .get<HistoryEntry>(`${API_BASE}/${id}`, { headers: this.authHeaders() })
      .pipe(catchError(this.handleError));
  }

  deleteEntry(id: number): Observable<void> {
    return this.http
      .delete<void>(`${API_BASE}/${id}`, { headers: this.authHeaders() })
      .pipe(catchError(this.handleError));
  }

  deleteAll(): Observable<void> {
    return this.http
      .delete(API_BASE, {
        headers: this.authHeaders(),
        observe: 'response',
        responseType: 'text',
      })
      .pipe(
        map(() => undefined),
        catchError(this.handleError),
      );
  }

  patchAiSupplement(id: number, body: AiHistorySupplementPayload): Observable<HistoryEntry> {
    return this.http
      .patch<HistoryEntry>(`${API_BASE}/${id}/ai-supplement`, body, { headers: this.authHeaders() })
      .pipe(catchError(this.handleError));
  }

  toggleFavorite(id: number): Observable<HistoryEntry> {
    return this.http
      .patch<HistoryEntry>(`${API_BASE}/${id}/favorite`, null, { headers: this.authHeaders() })
      .pipe(catchError(this.handleError));
  }

  getFavorites(): Observable<HistoryEntry[]> {
    return this.http
      .get<HistoryEntry[]>(`${API_BASE}/favorites`, { headers: this.authHeaders() })
      .pipe(catchError(this.handleError));
  }

  search(keyword: string): Observable<HistoryEntry[]> {
    return this.http
      .get<HistoryEntry[]>(`${API_BASE}/search`, {
        headers: this.authHeaders(),
        params: { keyword },
      })
      .pipe(catchError(this.handleError));
  }

  getValidOnly(): Observable<HistoryEntry[]> {
    return this.http
      .get<HistoryEntry[]>(`${API_BASE}/valid`, { headers: this.authHeaders() })
      .pipe(catchError(this.handleError));
  }

  getInvalidOnly(): Observable<HistoryEntry[]> {
    return this.http
      .get<HistoryEntry[]>(`${API_BASE}/invalid`, { headers: this.authHeaders() })
      .pipe(catchError(this.handleError));
  }

  parseResult(entry: HistoryEntry): QueryAnalysisResult | null {
    if (!entry.resultJson?.trim()) {
      return null;
    }
    try {
      const raw = JSON.parse(entry.resultJson) as Record<string, unknown>;
      const effectiveValid =
        entry.aiAssistedValid != null ? entry.aiAssistedValid : entry.valid;
      const opts = Array.isArray(raw['optimizations']) ? raw['optimizations'] : [];
      return {
        originalQuery: entry.query,
        dbType: entry.databaseType,
        isValid: effectiveValid,
        analysisTimeMs: typeof raw['executionTimeMs'] === 'number' ? raw['executionTimeMs'] : 0,
        errors: Array.isArray(raw['errors']) ? (raw['errors'] as QueryAnalysisResult['errors']) : [],
        issues: Array.isArray(raw['warnings'])
          ? (raw['warnings'] as { code?: string; severity?: string }[]).map((w) => ({
              type: (w.severity ?? 'WARNING').toUpperCase() === 'INFO' ? ('info' as const) : ('warning' as const),
              code: w.code ?? '',
              message: w.code ?? '',
            }))
          : [],
        optimizations: opts.map((o: Record<string, unknown>): QueryOptimization => ({
          ruleId: String(o['ruleId'] ?? ''),
          impact: normalizeImpact(o['impact']),
          description: String(o['description'] ?? ''),
          original: String(o['originalFragment'] ?? o['original'] ?? ''),
          optimized: String(o['optimizedFragment'] ?? o['optimized'] ?? ''),
        })),
      };
    } catch {
      return null;
    }
  }

  parseAiComplement(entry: HistoryEntry): AiComplementState | null {
    const rawJson = entry.aiComplementJson?.trim();
    if (!rawJson) {
      return null;
    }
    try {
      const raw = JSON.parse(rawJson) as Record<string, unknown>;
      return {
        pedagogy: String(raw['pedagogy'] ?? ''),
        optimizationNotes: String(raw['optimizationNotes'] ?? ''),
        validityCorrection: (raw['validityCorrection'] as AiComplementState['validityCorrection']) ?? null,
        nativeReviews: (raw['nativeReviews'] as AiComplementState['nativeReviews']) ?? [],
        additionalErrors: (raw['additionalErrors'] as AiComplementState['additionalErrors']) ?? [],
        additionalWarnings: (raw['additionalWarnings'] as AiComplementState['additionalWarnings']) ?? [],
        additionalOptimizations:
          (raw['additionalOptimizations'] as AiComplementState['additionalOptimizations']) ?? [],
        syntaxCorrections: (raw['syntaxCorrections'] as AiComplementState['syntaxCorrections']) ?? [],
        secondPassOverlay: null,
        provider: raw['provider'] != null ? String(raw['provider']) : entry.aiProvider ?? null,
      };
    } catch {
      return null;
    }
  }

  private authHeaders(): HttpHeaders {
    const token = this.authService.getAccessToken();
    let headers = new HttpHeaders();
    if (token?.trim()) {
      headers = headers.set('Authorization', `Bearer ${token.trim()}`);
    }
    return headers;
  }

  private handleError(err: HttpErrorResponse): Observable<never> {
    const msg =
      err.status === 401
        ? 'history.errors.authRequired'
        : 'history.errors.generic';
    return throwError(() => new Error(msg));
  }
}
