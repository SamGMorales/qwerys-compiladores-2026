package com.qwerys.compiler;

/**
 * Operadores de comparacion para condiciones WHERE
 * Migrado de: enum CompOperator en AST.h (C++)
 * Responsable: Mercedes Azucena Lopez Perez - Rama: feature/mercedes-lopez-parser-ast
 */
public enum CompOperator {
    EQUAL, GREATER, LESS, GREATER_EQUAL, LESS_EQUAL, NOT_EQUAL;

    public String toSymbol() {
        switch (this) {
            case EQUAL:         return "=";
            case GREATER:       return ">";
            case LESS:          return "<";
            case GREATER_EQUAL: return ">=";
            case LESS_EQUAL:    return "<=";
            case NOT_EQUAL:     return "!=";
            default:            return "?";
        }
    }
}
