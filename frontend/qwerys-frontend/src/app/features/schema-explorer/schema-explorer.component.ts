import { Component, OnInit, inject, DestroyRef } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { BreakpointObserver } from '@angular/cdk/layout';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { TranslateModule } from '@ngx-translate/core';
import { finalize } from 'rxjs/operators';

import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatTableModule } from '@angular/material/table';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatListModule } from '@angular/material/list';

import {
  SchemaService,
  DatabaseConfig,
  TableSchema,
} from '../../core/services/schema.service';
import { CollapsibleSectionDirective } from '../../shared/directives/collapsible-section.directive';
import {
  readSchemaSnapshot,
  writeSchemaSnapshot,
  clearSchemaSnapshot,
  TableInfo,
} from '../../shared/utils/schema-snapshot.util';

type ConnectionStatus = 'none' | 'connecting' | 'connected' | 'error';

/** Values must match `schema.engines.*` keys in i18n. */
const DB_ENGINE_VALUES: readonly string[] = [
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
];

@Component({
  selector: 'app-schema-explorer',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    TranslateModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatButtonModule,
    MatIconModule,
    MatTableModule,
    MatProgressSpinnerModule,
    MatListModule,
    CollapsibleSectionDirective,
  ],
  templateUrl: './schema-explorer.component.html',
  styleUrls: ['./schema-explorer.component.scss'],
})
export class SchemaExplorerComponent implements OnInit {
  private readonly schemaService = inject(SchemaService);
  private readonly breakpointObserver = inject(BreakpointObserver);
  private readonly destroyRef = inject(DestroyRef);

  isMobile = false;
  showTableDetail = false;

  readonly engines = DB_ENGINE_VALUES;

  /** Column names per table from describeTable (for session restore). */
  private readonly columnCache = new Map<string, string[]>();

  config: DatabaseConfig = {
    host: 'localhost',
    port: 3306,
    database: '',
    username: '',
    password: '',
    dbType: 'mysql',
    connectionTimeoutSeconds: 30,
  };

  connectionStatus: ConnectionStatus = 'none';
  tableNames: string[] = [];
  selectedTable: string | null = null;
  selectedTableSchema: TableSchema | null = null;
  loadingSchema = false;
  /** Tables list failed after a successful connect probe — show `schema.tablesLoadError`. */
  tablesLoadFailed = false;
  /** describeTable failed — show `schema.columnsLoadError`. */
  columnDescribeFailed = false;

  readonly displayedColumns: string[] = [
    'columnName',
    'dataType',
    'nullable',
    'primaryKey',
  ];

  ngOnInit(): void {
    this.breakpointObserver
      .observe(['(max-width: 767.98px)'])
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((result) => {
        this.isMobile = result.matches;
        if (!this.isMobile) {
          this.showTableDetail = false;
        }
      });
    this.restoreFromSession();
  }

  get isSqlite(): boolean {
    return this.config.dbType === 'sqlite';
  }

  get isDynamoDb(): boolean {
    return this.config.dbType === 'dynamodb';
  }

  /** SQLite uses a file path; every other engine uses host + port in this form. */
  get showHostPort(): boolean {
    return !this.isSqlite;
  }

  /** Label for the database/keyspace/index/region field (matches query-analyzer). */
  get schemaDatabaseFieldKey(): string {
    switch (this.config.dbType) {
      case 'cassandra':
        return 'schema.keyspace';
      case 'elasticsearch':
        return 'schema.index';
      case 'redis':
        return 'schema.dbNumber';
      case 'dynamodb':
        return 'schema.region';
      case 'sqlite':
        return 'schema.filePath';
      default:
        return 'schema.database';
    }
  }

  get schemaUsernameLabel(): string {
    return this.config.dbType === 'dynamodb' ? 'schema.accessKeyId' : 'schema.username';
  }

  get schemaPasswordLabel(): string {
    return this.config.dbType === 'dynamodb'
      ? 'schema.secretAccessKey'
      : 'schema.password';
  }

  /** Redis / Elasticsearch commonly run without auth. */
  get schemaAuthOptional(): boolean {
    return this.config.dbType === 'redis'
      || this.config.dbType === 'elasticsearch';
  }

  onEngineChange(): void {
    this.config = {
      ...this.config,
      port: this.defaultPortFor(this.config.dbType),
    };
  }

  private defaultPortFor(dbType: string): number {
    switch (dbType) {
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

  onConnect(): void {
    this.tablesLoadFailed = false;
    this.columnDescribeFailed = false;
    this.connectionStatus = 'connecting';
    this.tableNames = [];
    this.selectedTable = null;
    this.selectedTableSchema = null;
    this.showTableDetail = false;

    this.schemaService.testConnection(this.config).subscribe({
      next: (res) => {
        if (!res.success) {
          console.warn(
            '[SchemaExplorer] testConnection reported failure:',
            res.message
          );
          this.connectionStatus = 'error';
          return;
        }
        this.schemaService.getTableNames(this.config).subscribe({
          next: (names) => {
            this.tableNames = names ?? [];
            this.columnCache.clear();
            this.connectionStatus = 'connected';
            this.persistSnapshot();
          },
          error: (e: Error) => {
            console.error('[SchemaExplorer] getTableNames failed:', e);
            this.connectionStatus = 'error';
            this.tablesLoadFailed = true;
          },
        });
      },
      error: (e: Error) => {
        console.warn('[SchemaExplorer] testConnection HTTP error:', e);
        this.connectionStatus = 'error';
      },
    });
  }

  onTableListKeydown(event: KeyboardEvent): void {
    if (!this.tableNames.length) {
      return;
    }
    const currentIndex = this.selectedTable
      ? this.tableNames.indexOf(this.selectedTable)
      : -1;
    let nextIndex = currentIndex;

    switch (event.key) {
      case 'ArrowDown':
        nextIndex = currentIndex < this.tableNames.length - 1 ? currentIndex + 1 : 0;
        break;
      case 'ArrowUp':
        nextIndex = currentIndex > 0 ? currentIndex - 1 : this.tableNames.length - 1;
        break;
      case 'Home':
        nextIndex = 0;
        break;
      case 'End':
        nextIndex = this.tableNames.length - 1;
        break;
      default:
        return;
    }

    event.preventDefault();
    const name = this.tableNames[nextIndex];
    this.onSelectTable(name);
    this.focusTableAtIndex(nextIndex);
  }

  private focusTableAtIndex(index: number): void {
    const el = document.querySelector<HTMLButtonElement>(
      `.table-nav [data-table-index="${index}"]`
    );
    el?.focus();
  }

  backToList(): void {
    this.showTableDetail = false;
  }

  onSelectTable(name: string): void {
    this.selectedTable = name;
    if (this.isMobile) {
      this.showTableDetail = true;
    }
    this.persistSnapshot();
    this.selectedTableSchema = null;
    this.columnDescribeFailed = false;
    this.loadingSchema = true;
    this.schemaService
      .describeTable(this.config, name)
      .pipe(finalize(() => (this.loadingSchema = false)))
      .subscribe({
        next: (schema) => {
          this.selectedTableSchema = schema;
          this.persistTableColumns(schema);
        },
        error: (e: Error) => {
          console.error('[SchemaExplorer] describeTable failed:', e);
          this.selectedTableSchema = null;
          this.columnDescribeFailed = true;
        },
      });
  }

  private persistTableColumns(schema: TableSchema): void {
    const columns = (schema.columns ?? []).map((c) => c.columnName);
    this.columnCache.set(schema.tableName, columns);
    this.persistSnapshot();
  }

  onDisconnect(): void {
    clearSchemaSnapshot();
    this.columnCache.clear();
    this.connectionStatus = 'none';
    this.tablesLoadFailed = false;
    this.columnDescribeFailed = false;
    this.tableNames = [];
    this.selectedTable = null;
    this.selectedTableSchema = null;
    this.loadingSchema = false;
    this.showTableDetail = false;
  }

  private restoreFromSession(): void {
    const snap = readSchemaSnapshot();
    if (!snap?.connected || !snap.config) {
      return;
    }

    this.config = { ...snap.config };
    this.connectionStatus = 'connected';
    this.tableNames = (snap.tables ?? []).map((t) => t.name);
    for (const t of snap.tables ?? []) {
      if (t.columns?.length) {
        this.columnCache.set(t.name, t.columns);
      }
    }

    const selected = snap.selectedTable ?? null;
    this.selectedTable = selected;
    if (selected) {
      const cached = this.columnCache.get(selected) ?? [];
      if (cached.length) {
        this.applyCachedTableSchema(selected, cached);
      } else {
        this.onSelectTable(selected);
      }
    }
  }

  private applyCachedTableSchema(tableName: string, columnNames: string[]): void {
    this.selectedTableSchema = {
      tableName,
      columns: columnNames.map((columnName) => ({
        columnName,
        dataType: '',
        nullable: true,
        primaryKey: false,
      })),
      primaryKeys: [],
      foreignKeys: [],
    };
  }

  private buildTableInfos(): TableInfo[] {
    return this.tableNames.map((n) => ({
      name: n,
      columns:
        n === this.selectedTable && this.selectedTableSchema
          ? (this.selectedTableSchema.columns ?? []).map((c) => c.columnName)
          : (this.columnCache.get(n) ?? []),
    }));
  }

  private persistSnapshot(): void {
    if (this.connectionStatus !== 'connected') {
      return;
    }
    writeSchemaSnapshot({
      databaseType: this.config.dbType,
      tables: this.buildTableInfos(),
      connected: true,
      config: { ...this.config },
      selectedTable: this.selectedTable,
    });
  }
}
