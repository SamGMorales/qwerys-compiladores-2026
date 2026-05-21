#!/usr/bin/env node
/**
 * Ensures every OptimizationRule#getRuleId() value starting with OPT- has matching
 * analyzer.optimizations.<id> entries in es.json and en.json (description required;
 * original + optimized required for procedural-style rules shown as localized snippets).
 *
 * Usage (from frontend repo root):
 *   node scripts/check-optimization-i18n.mjs
 *
 * Override backend optimization dir:
 *   BACKEND_OPTIMIZATION_DIR=/abs/path/to/optimization node scripts/check-optimization-i18n.mjs
 */

import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const FRONTEND_ROOT = path.resolve(__dirname, '..');

const DEFAULT_BACKEND_OPT = path.resolve(
  FRONTEND_ROOT,
  '..',
  '..',
  'backend',
  'qwerys-backend',
  'src',
  'main',
  'java',
  'com',
  'qwerys',
  'qwerys_backend',
  'optimization'
);

const BACKEND_OPT_DIR = process.env.BACKEND_OPTIMIZATION_DIR
  ? path.resolve(process.env.BACKEND_OPTIMIZATION_DIR)
  : DEFAULT_BACKEND_OPT;

const ES_JSON = path.join(FRONTEND_ROOT, 'src', 'assets', 'i18n', 'es.json');
const EN_JSON = path.join(FRONTEND_ROOT, 'src', 'assets', 'i18n', 'en.json');

const RULE_FILE_SUFFIX = 'Rule.java';

/** Same heuristic as query-analyzer: these rules use localized original/optimized blurbs. */
function needsSnippetKeys(ruleId) {
  return ruleId.startsWith('OPT-PROC-') || ruleId === 'OPT-CURSOR-LOOP';
}

function extractOptRuleIds(javaDir) {
  if (!fs.existsSync(javaDir)) {
    console.error(`Backend optimization directory not found:\n  ${javaDir}`);
    console.error('Set BACKEND_OPTIMIZATION_DIR or clone the backend next to this repo.');
    process.exit(2);
  }

  const files = fs.readdirSync(javaDir).filter((f) => f.endsWith(RULE_FILE_SUFFIX));
  const ids = new Set();
  const optReturn = /return\s+"(OPT-[^"]+)"\s*;/g;

  for (const file of files) {
    const content = fs.readFileSync(path.join(javaDir, file), 'utf8');
    let m;
    while ((m = optReturn.exec(content)) !== null) {
      ids.add(m[1]);
    }
  }

  return [...ids].sort();
}

function loadOptimizations(jsonPath) {
  const raw = JSON.parse(fs.readFileSync(jsonPath, 'utf8'));
  const opts = raw?.analyzer?.optimizations;
  if (!opts || typeof opts !== 'object') {
    throw new Error(`${jsonPath}: missing analyzer.optimizations object`);
  }
  return opts;
}

function nonEmptyString(v) {
  return typeof v === 'string' && v.trim().length > 0;
}

function validateLocale(ruleIds, opts, label) {
  const errors = [];

  for (const id of ruleIds) {
    const entry = opts[id];
    if (!entry || typeof entry !== 'object') {
      errors.push(`  ${label}: missing analyzer.optimizations.${id}`);
      continue;
    }
    if (!nonEmptyString(entry.description)) {
      errors.push(`  ${label}: analyzer.optimizations.${id}.description must be a non-empty string`);
    }
    if (needsSnippetKeys(id)) {
      if (!nonEmptyString(entry.original)) {
        errors.push(`  ${label}: analyzer.optimizations.${id}.original required for procedural cursor rules`);
      }
      if (!nonEmptyString(entry.optimized)) {
        errors.push(`  ${label}: analyzer.optimizations.${id}.optimized required for procedural cursor rules`);
      }
    }
  }

  return errors;
}

function main() {
  const ruleIds = extractOptRuleIds(BACKEND_OPT_DIR);
  if (ruleIds.length === 0) {
    console.error('No OPT-* rule IDs found in backend *.java — check BACKEND_OPTIMIZATION_DIR.');
    process.exit(2);
  }

  let esOpts;
  let enOpts;
  try {
    esOpts = loadOptimizations(ES_JSON);
    enOpts = loadOptimizations(EN_JSON);
  } catch (e) {
    console.error(e.message || e);
    process.exit(1);
  }

  const problems = [
    ...validateLocale(ruleIds, esOpts, 'es.json'),
    ...validateLocale(ruleIds, enOpts, 'en.json'),
  ];

  const extraEs = Object.keys(esOpts).filter((k) => k.startsWith('OPT-') && !ruleIds.includes(k));
  const extraEn = Object.keys(enOpts).filter((k) => k.startsWith('OPT-') && !ruleIds.includes(k));

  if (extraEs.length || extraEn.length) {
    console.warn(
      'Warning: i18n contains OPT-* keys not returned by any *Rule.java getRuleId() (orphaned translations):'
    );
    for (const k of [...new Set([...extraEs, ...extraEn])].sort()) {
      console.warn(`  ${k}`);
    }
  }

  if (problems.length) {
    console.error('Optimization i18n check failed.\n');
    console.error(`Backend rule IDs (${ruleIds.length}): ${ruleIds.join(', ')}\n`);
    problems.forEach((p) => console.error(p));
    process.exit(1);
  }

  console.log(
    `OK: ${ruleIds.length} OPT-* rule(s) have matching analyzer.optimizations entries in es.json and en.json.`
  );
}

main();
