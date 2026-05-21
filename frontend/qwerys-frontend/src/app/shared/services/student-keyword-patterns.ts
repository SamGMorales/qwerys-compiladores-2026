/** Patrones de palabras clave para el panel educativo (modo estudiante). */

const SQL_PATTERNS: readonly string[] = [
  'GROUP BY',
  'ORDER BY',
  'LEFT JOIN',
  'RIGHT JOIN',
  'INNER JOIN',
  'FULL JOIN',
  'INSERT INTO',
  'DELETE FROM',
  'SELECT',
  'FROM',
  'WHERE',
  'JOIN',
  'HAVING',
  'LIMIT',
  'OFFSET',
  'INSERT',
  'UPDATE',
  'DELETE',
  'CREATE',
  'DROP',
  'ALTER',
  'DISTINCT',
  'UNION',
  'EXCEPT',
  'INTERSECT',
  'BEGIN',
  'COMMIT',
  'ROLLBACK',
  'GRANT',
  'REVOKE',
];

const NOSQL_COMMON: readonly string[] = [
  'FIND',
  'AGGREGATE',
  'INSERT',
  'UPDATE',
  'DELETE',
  'CREATE',
  'DROP',
];

const MONGODB_PATTERNS: readonly string[] = [
  ...NOSQL_COMMON,
  'FINDONE',
  'INSERTONE',
  'INSERTMANY',
  'UPDATEONE',
  'UPDATEMANY',
  'DELETEONE',
  'DELETEMANY',
  '$MATCH',
  '$GROUP',
  '$PROJECT',
  '$SORT',
  '$LIMIT',
  '$LOOKUP',
];

const REDIS_PATTERNS: readonly string[] = [
  'GET',
  'SET',
  'HGET',
  'HGETALL',
  'HSET',
  'DEL',
  'EXPIRE',
  'KEYS',
  'SCAN',
  'INCR',
  'LPUSH',
  'RPUSH',
  'SADD',
  'ZADD',
];

const ELASTICSEARCH_PATTERNS: readonly string[] = [
  'MATCH',
  'TERM',
  'BOOL',
  'RANGE',
  'QUERY',
  'FILTER',
  'MUST',
  'SHOULD',
  '_SOURCE',
];

const DYNAMODB_PATTERNS: readonly string[] = [
  'SELECT',
  'INSERT',
  'UPDATE',
  'DELETE',
  'PARTIQL',
  'BEGINS_WITH',
  'CONTAINS',
];

const CASSANDRA_PATTERNS: readonly string[] = [
  'SELECT',
  'INSERT',
  'UPDATE',
  'DELETE',
  'FROM',
  'WHERE',
  'ALLOW FILTERING',
  'USING TTL',
];

/**
 * Devuelve patrones a detectar según categoría y motor (incluye custom::* vía base).
 */
export function getStudentKeywordPatterns(
  category: 'sql' | 'nosql' | string,
  engineKey: string
): readonly string[] {
  const engine = engineKey.toLowerCase().replace(/^custom::/, '');
  const base = engine.includes('::') ? engine.split('::').pop() ?? engine : engine;

  if (category === 'sql' || category !== 'nosql') {
    if (['mysql', 'postgresql', 'sqlite', 'sqlserver', 'oracle'].includes(base)) {
      return SQL_PATTERNS;
    }
    return SQL_PATTERNS;
  }

  switch (base) {
    case 'mongodb':
      return MONGODB_PATTERNS;
    case 'redis':
      return REDIS_PATTERNS;
    case 'elasticsearch':
      return ELASTICSEARCH_PATTERNS;
    case 'dynamodb':
      return DYNAMODB_PATTERNS;
    case 'cassandra':
      return CASSANDRA_PATTERNS;
    default:
      return [...NOSQL_COMMON, ...SQL_PATTERNS];
  }
}

export function keywordToI18nKey(keyword: string): string {
  return keyword.replace(/\s+/g, '_').replace(/^\$/, '');
}
