import type {
  AnalysisError,
  QueryAnalysisResult,
  QueryIssue,
} from '../../core/services/query.service';
import type { AiComplementState } from '../../core/models/complement-analysis.model';

/**
 * Native warnings that the AI explicitly DISAGREED with — these are false positives
 * and must not be counted as production-risk issues nor shown as warnings in the badge.
 */
function aiDisagreedCodes(
  complement: AiComplementState | null | undefined
): Set<string> {
  const set = new Set<string>();
  if (!complement?.nativeReviews) {
    return set;
  }
  for (const r of complement.nativeReviews) {
    if (r.verdict === 'DISAGREE' && r.referenceId) {
      set.add(r.referenceId.toUpperCase());
    }
  }
  return set;
}

/**
 * Native errors that should reduce the student practices score (-20 each).
 * Cleared when AI validity override applies; also drops errors the AI marked DISAGREE.
 */
export function effectiveErrorsForStudentScore(
  result: QueryAnalysisResult | null | undefined,
  complement: AiComplementState | null | undefined
): AnalysisError[] {
  if (!result?.errors?.length) {
    return [];
  }
  if (effectiveIsValid(result, complement) && !result.isValid) {
    return [];
  }
  const disagreed = aiDisagreedCodes(complement);
  return result.errors.filter(
    (e) => !(e.code && disagreed.has(e.code.toUpperCase()))
  );
}

/**
 * Native issues that should reduce the student practices score (-5 each: warning or error type).
 * Excludes INFO-only rows and warnings/errors the AI marked DISAGREE.
 */
export function effectiveIssuesForStudentScore(
  issues: QueryIssue[] | undefined | null,
  complement?: AiComplementState | null
): QueryIssue[] {
  if (!issues?.length) {
    return [];
  }
  const disagreed = aiDisagreedCodes(complement);
  return issues.filter(
    (i) =>
      (i.type === 'warning' || i.type === 'error') &&
      !(i.code && disagreed.has(i.code.toUpperCase()))
  );
}

/** Production-relevant warnings (not INFO-only advisories), excluding ones AI marked DISAGREE. */
export function hasProductionRiskIssues(
  issues: QueryIssue[] | undefined | null,
  complement?: AiComplementState | null
): boolean {
  if (!issues || issues.length === 0) {
    return false;
  }
  const disagreed = aiDisagreedCodes(complement);
  return issues.some(
    (i) =>
      i.type === 'warning' &&
      !(i.code && disagreed.has(i.code.toUpperCase()))
  );
}

/** Effective validity after optional AI override of a false native invalid flag. */
export function effectiveIsValid(
  result: QueryAnalysisResult | null | undefined,
  complement: AiComplementState | null | undefined
): boolean {
  if (!result) {
    return false;
  }
  const vc = complement?.validityCorrection;
  if (vc?.apply) {
    return vc.correctedIsValid;
  }
  return result.isValid;
}

export function hasAiBlockingFindings(complement: AiComplementState | null | undefined): boolean {
  return (complement?.additionalErrors?.length ?? 0) > 0;
}

export function hasAiSupplementalFindings(complement: AiComplementState | null | undefined): boolean {
  if (!complement) {
    return false;
  }
  return (
    (complement.additionalOptimizations?.length ?? 0) > 0 ||
    (complement.additionalWarnings?.length ?? 0) > 0 ||
    (complement.nativeReviews?.some((r) => r.verdict === 'DISAGREE' || r.verdict === 'PARTIAL') ?? false)
  );
}

/** True when the AI offers any optimization the UI should expose (used to power the "optional improvements" sub-section). */
export function hasOptionalOptimizations(
  result: QueryAnalysisResult | null | undefined,
  complement: AiComplementState | null | undefined
): boolean {
  if ((result?.optimizations?.length ?? 0) > 0) return true;
  if ((complement?.additionalOptimizations?.length ?? 0) > 0) return true;
  return false;
}

export function countOptionalOptimizations(
  result: QueryAnalysisResult | null | undefined,
  complement: AiComplementState | null | undefined
): number {
  return (
    (result?.optimizations?.length ?? 0) +
    (complement?.additionalOptimizations?.length ?? 0)
  );
}

/**
 * Whether the UI may show the "perfect query" message.
 * Perfect means: validity is true and there are no syntax/semantic errors or production warnings
 * that the AI didn't dismiss. Optional optimizations (performance hints) may still be present
 * and are displayed in a sub-section beside the banner instead of suppressing it.
 */
export function showsPerfectResult(
  result: QueryAnalysisResult | null | undefined,
  complement?: AiComplementState | null
): boolean {
  if (!effectiveIsValid(result, complement)) {
    return false;
  }
  if ((result?.errors?.length ?? 0) > 0) {
    return false;
  }
  if (hasAiBlockingFindings(complement)) {
    return false;
  }
  if (hasProductionRiskIssues(result?.issues, complement)) {
    return false;
  }
  if ((complement?.additionalWarnings?.length ?? 0) > 0) {
    return false;
  }
  // Disagreements/partial reviews from AI are user-facing signals that contradict native — not "perfect".
  if (
    complement?.nativeReviews?.some(
      (r) => r.verdict === 'DISAGREE' || r.verdict === 'PARTIAL'
    )
  ) {
    return false;
  }
  return true;
}

/**
 * Badge key — combinable variants are emitted when both signals coexist:
 * - validWithWarningsAndAi: native warnings (not dismissed by AI) + AI supplemental findings.
 *
 * Precedence:
 *   invalid → validAiReviewed → validWithWarningsAndAi → validWithWarnings → validWithAiFindings → valid
 */
export function validityBadgeKind(
  result: QueryAnalysisResult | null | undefined,
  complement: AiComplementState | null | undefined
):
  | 'invalid'
  | 'valid'
  | 'validWithWarnings'
  | 'validAiReviewed'
  | 'validWithAiFindings'
  | 'validWithWarningsAndAi' {
  if (!result) {
    return 'invalid';
  }
  const valid = effectiveIsValid(result, complement);
  if (!valid) {
    return 'invalid';
  }
  if (complement?.validityCorrection?.apply && complement.validityCorrection.correctedIsValid) {
    return 'validAiReviewed';
  }
  const hasWarnings = hasProductionRiskIssues(result.issues, complement);
  const hasAiExtras = hasAiBlockingFindings(complement) || hasAiSupplementalFindings(complement);
  if (hasWarnings && hasAiExtras) {
    return 'validWithWarningsAndAi';
  }
  if (hasWarnings) {
    return 'validWithWarnings';
  }
  if (hasAiExtras) {
    return 'validWithAiFindings';
  }
  return 'valid';
}
