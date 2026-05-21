import type { DatabaseConfig } from '../../core/services/schema.service';
import type { TableInfo } from '../../core/services/ai-suggestion.service';

export type { TableInfo };

const STORAGE_KEY = 'qwerys-schema-snapshot';

/** Dispatched on same tab when explorer connects, updates schema, or disconnects. */
export const SCHEMA_SNAPSHOT_CHANGED = 'qwerys-schema-snapshot-changed';

export interface SchemaSnapshot {
  databaseType: string;
  tables: TableInfo[];
  /** True while the user has an active Schema Explorer session (until manual disconnect). */
  connected?: boolean;
  config?: DatabaseConfig;
  selectedTable?: string | null;
}

export interface SchemaConnectionSummary {
  dbType: string;
  host: string;
  port: number;
  database: string;
  username: string;
  tableCount: number;
}

export function isExplorerConnected(snap: SchemaSnapshot | null): boolean {
  return snap?.connected === true && !!snap.config;
}

/** Full explorer config for copy into another form (e.g. query analyzer). */
export function explorerDatabaseConfig(): DatabaseConfig | null {
  const snap = readSchemaSnapshot();
  if (!snap || !isExplorerConnected(snap) || !snap.config) {
    return null;
  }
  return { ...snap.config };
}

export function schemaConnectionSummary(
  snap: SchemaSnapshot | null
): SchemaConnectionSummary | null {
  if (!isExplorerConnected(snap) || !snap?.config) {
    return null;
  }
  return {
    dbType: snap.databaseType,
    host: snap.config.host,
    port: snap.config.port,
    database: snap.config.database,
    username: snap.config.username,
    tableCount: snap.tables?.length ?? 0,
  };
}

export function readSchemaSnapshot(): SchemaSnapshot | null {
  try {
    const raw = sessionStorage.getItem(STORAGE_KEY);
    if (!raw) {
      return null;
    }
    return JSON.parse(raw) as SchemaSnapshot;
  } catch {
    return null;
  }
}

export function writeSchemaSnapshot(snapshot: SchemaSnapshot): void {
  try {
    sessionStorage.setItem(STORAGE_KEY, JSON.stringify(snapshot));
    notifySnapshotChanged();
  } catch {
    /* ignore quota errors */
  }
}

export function clearSchemaSnapshot(): void {
  sessionStorage.removeItem(STORAGE_KEY);
  notifySnapshotChanged();
}

function notifySnapshotChanged(): void {
  if (typeof window === 'undefined') {
    return;
  }
  window.dispatchEvent(new CustomEvent(SCHEMA_SNAPSHOT_CHANGED));
}
