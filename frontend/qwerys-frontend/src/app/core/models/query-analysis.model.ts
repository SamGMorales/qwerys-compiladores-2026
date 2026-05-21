export interface LexicalToken {
  type: string;
  value: string;
  line: number;
  column: number;
}

export interface SyntaxNode {
  type: string;
  children: SyntaxNode[];
  value?: string;
}

export interface SemanticError {
  code: string;
  message: string;
  suggestion: string;
  severity: 'error' | 'warning' | 'info';
}

export interface OptimizationSuggestion {
  ruleId: string;
  description: string;
  originalFragment: string;
  optimizedFragment: string;
  impact: 'high' | 'medium' | 'low';
}

export interface QueryAnalysisResult {
  isValid: boolean;
  tokens: LexicalToken[];
  ast: SyntaxNode;
  errors: SemanticError[];
  optimizations: OptimizationSuggestion[];
  executionTimeMs: number;
}
