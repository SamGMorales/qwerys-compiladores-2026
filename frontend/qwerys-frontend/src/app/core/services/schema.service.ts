import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError } from 'rxjs/operators';

export interface DatabaseConfig {
  host: string;
  port: number;
  database: string;
  username: string;
  password: string;
  dbType: string;
  connectionTimeoutSeconds: number;
}

export interface SchemaConnectResponse {
  success: boolean;
  message: string;
}

export interface ColumnSchema {
  columnName: string;
  dataType: string;
  nullable: boolean;
  primaryKey: boolean;
  defaultValue?: string | null;
}

export interface TableSchema {
  tableName: string;
  columns: ColumnSchema[];
  primaryKeys: string[];
  foreignKeys: string[];
}

export interface DatabaseSchema {
  dbType: string;
  databaseName: string;
  tables: TableSchema[];
}

const API_BASE = '/api';

@Injectable({ providedIn: 'root' })
export class SchemaService {
  private readonly http = inject(HttpClient);

  private handleError(err: HttpErrorResponse): Observable<never> {
    const body = err.error as { error?: string } | undefined;
    const msg =
      typeof body?.error === 'string'
        ? body.error
        : err.status === 0
          ? 'Network error'
          : err.message;
    return throwError(() => new Error(msg));
  }

  testConnection(config: DatabaseConfig): Observable<SchemaConnectResponse> {
    return this.http
      .post<SchemaConnectResponse>(`${API_BASE}/schema/connect`, config)
      .pipe(catchError((e) => this.handleError(e)));
  }

  getTableNames(config: DatabaseConfig): Observable<string[]> {
    return this.http
      .post<string[]>(`${API_BASE}/schema/tables`, config)
      .pipe(catchError((e) => this.handleError(e)));
  }

  describeTable(
    config: DatabaseConfig,
    tableName: string
  ): Observable<TableSchema> {
    return this.http
      .post<TableSchema>(`${API_BASE}/schema/describe`, {
        connection: config,
        tableName,
      })
      .pipe(catchError((e) => this.handleError(e)));
  }

  getFullSchema(config: DatabaseConfig): Observable<DatabaseSchema> {
    return this.http
      .post<DatabaseSchema>(`${API_BASE}/schema/full`, config)
      .pipe(catchError((e) => this.handleError(e)));
  }
}
