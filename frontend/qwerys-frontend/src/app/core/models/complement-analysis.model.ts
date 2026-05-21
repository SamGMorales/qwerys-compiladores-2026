/** Structured complement from POST /api/ai/complement-analysis */
export interface ComplementValidityCorrection {
  apply: boolean;
  correctedIsValid: boolean;
  reason: string;
}

export interface NativeFindingReview {
  referenceId: string;
  verdict: 'AGREE' | 'PARTIAL' | 'DISAGREE' | string;
  comment: string;
}

export interface AiComplementError {
  code: string;
  message: string;
  suggestion?: string;
}

export interface AiComplementWarning {
  code: string;
  severity: string;
  message?: string;
}

export interface AiComplementOptimization {
  ruleId?: string;
  impact: 'HIGH' | 'MEDIUM' | 'LOW' | string;
  description: string;
  originalFragment?: string;
  optimizedFragment?: string;
  original?: string;
  optimized?: string;
}

export interface SyntaxCorrection {
  forErrorCode: string;
  correctedQuery: string;
  explanation?: string;
}

export interface AiSecondPassOverlay {
  suppressNativeErrors: boolean;
  reparseSucceeded: boolean;
  reparseIsValid: boolean;
  astTree?: unknown;
  metrics?: unknown;
}

export interface ComplementAnalysisResponse {
  success: boolean;
  pedagogy?: string | null;
  optimizationNotes?: string | null;
  validityCorrection?: ComplementValidityCorrection | null;
  nativeReviews?: NativeFindingReview[];
  additionalErrors?: AiComplementError[];
  additionalWarnings?: AiComplementWarning[];
  additionalOptimizations?: AiComplementOptimization[];
  syntaxCorrections?: SyntaxCorrection[];
  secondPassOverlay?: AiSecondPassOverlay | null;
  aiAvailable: boolean;
  provider?: string | null;
  responseTimeMs?: number | null;
  error?: string | null;
}

/** UI state attached after each analysis */
export interface AiComplementState {
  pedagogy: string;
  optimizationNotes: string;
  validityCorrection: ComplementValidityCorrection | null;
  nativeReviews: NativeFindingReview[];
  additionalErrors: AiComplementError[];
  additionalWarnings: AiComplementWarning[];
  additionalOptimizations: AiComplementOptimization[];
  syntaxCorrections: SyntaxCorrection[];
  secondPassOverlay: AiSecondPassOverlay | null;
  provider: string | null;
}
