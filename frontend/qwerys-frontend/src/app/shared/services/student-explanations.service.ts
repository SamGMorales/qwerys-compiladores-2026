import { Injectable, inject } from '@angular/core';
import { TranslateService } from '@ngx-translate/core';

export interface StudentExplanation {
  what: string;
  why: string;
  example?: string;
  correctedExample?: string;
}

/** Maps prompt / legacy codes to analyzer.issues keys used in i18n. */
const CODE_ALIASES: Record<string, string> = {
  'SEM-DELETE-NOWHERE': 'SEM-001',
  'SEM-UPDATE-NOWHERE': 'SEM-002',
  'SEM-SELECT-COLS': 'SE001',
  'SEM-FROM-MISSING': 'SE002',
  'SEM-WHERE-INCOMPLETE': 'SE003',
  'SQL-INJECTION': 'SE007',
  'SQL-INJ': 'SE007',
  'REDIS-KEYS-STAR': 'RDS-KEYS-001',
  'REDIS-KEYS': 'RDS-KEYS-001',
  'REDIS-KEYS-PATTERN': 'RDS-KEYS-002',
  'MGO-001': 'MGO-NOFILTER-001',
};

@Injectable({ providedIn: 'root' })
export class StudentExplanationsService {
  private readonly translate = inject(TranslateService);

  /**
   * Resolves a student explanation: API payload first (locale from last analyze), then i18n fallback.
   */
  getExplanation(
    errorCode: string | undefined | null,
    fromApi?: StudentExplanation | null
  ): StudentExplanation | null {
    const fromServer = this.normalizeApiEducation(fromApi);
    if (fromServer) {
      return fromServer;
    }

    const code = errorCode?.trim();
    if (!code) return null;

    const resolved = CODE_ALIASES[code] ?? code;

    const dedicated = this.fromDedicatedKeys(resolved);
    if (dedicated) return dedicated;

    const fromIssues = this.fromAnalyzerIssues(resolved);
    if (fromIssues) return fromIssues;

    if (resolved !== code) {
      const aliasDedicated = this.fromDedicatedKeys(code);
      if (aliasDedicated) return aliasDedicated;
      const aliasIssues = this.fromAnalyzerIssues(code);
      if (aliasIssues) return aliasIssues;
    }

    return null;
  }

  private normalizeApiEducation(
    fromApi?: StudentExplanation | null
  ): StudentExplanation | null {
    if (!fromApi) {
      return null;
    }
    const what = fromApi.what?.trim() ?? '';
    const why = fromApi.why?.trim() ?? '';
    if (!what && !why) {
      return null;
    }
    return {
      what: what || why,
      why: why || what,
      ...(fromApi.example?.trim() ? { example: fromApi.example.trim() } : {}),
      ...(fromApi.correctedExample?.trim()
        ? { correctedExample: fromApi.correctedExample.trim() }
        : {}),
    };
  }

  private resolveKey(key: string): string {
    const t = this.translate.instant(key);
    return t !== key ? t : '';
  }

  private fromDedicatedKeys(code: string): StudentExplanation | null {
    const base = `student.explanations.${code}`;
    const what = this.resolveKey(`${base}.what`);
    const why = this.resolveKey(`${base}.why`);
    if (!what && !why) return null;

    const example = this.resolveKey(`${base}.example`);
    const correctedExample = this.resolveKey(`${base}.correctedExample`);

    return {
      what: what || why,
      why: why || what,
      ...(example ? { example } : {}),
      ...(correctedExample ? { correctedExample } : {}),
    };
  }

  private fromAnalyzerIssues(code: string): StudentExplanation | null {
    const titleKey = `analyzer.issues.${code}.title`;
    const flatKey = `analyzer.issues.${code}`;
    const descKey = `analyzer.issues.${code}.description`;
    const studentKey = `analyzer.issues.${code}.studentExplanation`;
    const suggestionKey = `analyzer.issues.${code}.suggestion`;

    const title = this.resolveKey(titleKey);
    const flat = this.resolveKey(flatKey);
    const description = this.resolveKey(descKey);
    const studentWhy = this.resolveKey(studentKey);
    const suggestion = this.resolveKey(suggestionKey);

    const what = title || description || flat;
    const why = studentWhy || description || flat;

    if (!what && !why) return null;

    return {
      what,
      why: why !== what ? why : studentWhy || description || '',
      ...(suggestion ? { correctedExample: suggestion } : {}),
    };
  }
}
