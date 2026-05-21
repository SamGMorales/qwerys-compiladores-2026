import fs from "fs";
import path from "path";
import { fileURLToPath } from "url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
// tools/ -> qwerys-frontend/ -> frontend/ -> qwerys-project/
const repo = path.resolve(__dirname, "..", "..", "..");
const fe = path.resolve(__dirname, "..");
const enPath = path.join(fe, "src", "assets", "i18n", "en.json");
if (!fs.existsSync(enPath)) {
  console.error("en.json not at", enPath);
  process.exit(1);
}
const issues = JSON.parse(fs.readFileSync(enPath, "utf8")).analyzer.issues;
const issueKeys = new Set(Object.keys(issues));

const javaRoots = [
  path.join(repo, "backend", "qwerys-backend", "src", "main", "java", "com", "qwerys", "qwerys_backend", "analyzer", "MongoDbAnalyzer.java"),
  path.join(repo, "backend", "qwerys-backend", "src", "main", "java", "com", "qwerys", "qwerys_backend", "analyzer", "nosql", "MongoJsParser.java"),
  path.join(repo, "backend", "qwerys-backend", "src", "main", "java", "com", "qwerys", "qwerys_backend", "analyzer", "RedisAnalyzer.java"),
  path.join(repo, "backend", "qwerys-backend", "src", "main", "java", "com", "qwerys", "qwerys_backend", "analyzer", "nosql", "LuaAnalyzer.java"),
  path.join(repo, "backend", "qwerys-backend", "src", "main", "java", "com", "qwerys", "qwerys_backend", "analyzer", "CqlAnalyzer.java"),
  path.join(repo, "backend", "qwerys-backend", "src", "main", "java", "com", "qwerys", "qwerys_backend", "analyzer", "DynamoDbAnalyzer.java"),
  path.join(repo, "backend", "qwerys-backend", "src", "main", "java", "com", "qwerys", "qwerys_backend", "analyzer", "DynamoDbExpressionAnalyzer.java"),
  path.join(repo, "backend", "qwerys-backend", "src", "main", "java", "com", "qwerys", "qwerys_backend", "analyzer", "ElasticsearchAnalyzer.java"),
  path.join(repo, "backend", "qwerys-backend", "src", "main", "java", "com", "qwerys", "qwerys_backend", "analyzer", "nosql", "PainlessAnalyzer.java"),
  path.join(repo, "backend", "qwerys-backend", "src", "main", "java", "com", "qwerys", "qwerys_backend", "analyzer", "nosql", "CassandraJsParser.java"),
];

const codeRe = /"((?:MGO|RDS|CQL|CAS|DDB|ES|LUA|PNL)-[A-Z0-9-]+)"/g;
const found = new Set();
for (const p of javaRoots) {
  if (!fs.existsSync(p)) {
    console.error("missing file", p);
    continue;
  }
  const s = fs.readFileSync(p, "utf8");
  let m;
  while ((m = codeRe.exec(s))) {
    found.add(m[1]);
  }
}

const missing = [...found].filter((c) => !issueKeys.has(c)).sort();
console.log("Backend codes matched:", found.size);
console.log("Missing i18n keys:", missing.length);
for (const c of missing) console.log(c);
