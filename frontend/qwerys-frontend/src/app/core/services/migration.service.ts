import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError } from 'rxjs/operators';

export interface MigrationResult {
  success: boolean;
  migratedCode: string;
  warnings: string[];
  manualSteps: string[];
}

export interface MigrationRequest {
  sourceCode: string;
  /** Uppercase id, e.g. CPP, JAVA, PYTHON, TYPESCRIPT (CPP = C++, not C). */
  sourceLanguage: string;
  targetLanguage: string;
}

const API_BASE = '/api/migration';

@Injectable({ providedIn: 'root' })
export class MigrationService {
  private readonly http = inject(HttpClient);

  convert(
    sourceCode: string,
    sourceLanguage: string,
    targetLanguage: string
  ): Observable<MigrationResult> {
    const body: MigrationRequest = { sourceCode, sourceLanguage, targetLanguage };
    return this.http
      .post<MigrationResult>(`${API_BASE}/convert`, body)
      .pipe(catchError((err) => this.handleError(err)));
  }

  getAvailableTargets(sourceLanguage: string): Observable<string[]> {
    return this.http
      .get<string[]>(`${API_BASE}/targets/${encodeURIComponent(sourceLanguage)}`)
      .pipe(catchError((err) => this.handleError(err)));
  }

  private handleError(err: HttpErrorResponse): Observable<never> {
    const body = err.error as { message?: string; error?: string } | undefined;
    const msg =
      body?.message ??
      body?.error ??
      (err.status === 0
        ? 'No se pudo conectar con el servidor de migración.'
        : `Error del servidor (${err.status}).`);
    return throwError(() => new Error(msg));
  }
}
