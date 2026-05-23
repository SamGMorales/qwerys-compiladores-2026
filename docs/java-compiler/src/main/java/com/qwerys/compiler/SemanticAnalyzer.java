package com.qwerys.compiler;

import java.util.ArrayList;
import java.util.List;

/**
 * FASE 4 - Analizador Semantico
 * Valida que el AST tenga sentido contra el schema real de la BD
 * Migrado de: SemanticAnalyzer.h + SemanticAnalyzer.cpp (C++)
 * Responsable: Josue David Morales Ramirez - Rama: feature/josue-morales-semantic
 */
public class SemanticAnalyzer {
    private final SymbolTable symbolTable;
    private List<String> errors   = new ArrayList<>();
    private List<String> warnings = new ArrayList<>();

    public SemanticAnalyzer(SymbolTable symbolTable) { this.symbolTable = symbolTable; }

    private Table validateTable(String name) {
        Table t = symbolTable.findTable(name);
        if (t == null) errors.add("Tabla '" + name + "' no existe en el schema");
        return t;
    }

    private void validateColumns(SelectNode node, Table table) {
        if (table == null || node.selectAll) return;
        for (String col : node.columns)
            if (table.findColumn(col) == null)
                errors.add("Columna '" + col + "' no existe en tabla '" + table.name + "'");
    }

    private DataType getExprType(ExpressionNode expr, Table table) {
        switch (expr.type) {
            case NUMBER: return DataType.INT;
            case STRING: return DataType.VARCHAR;
            case IDENTIFIER:
                if (table == null) return DataType.VARCHAR;
                Column col = table.findColumn(expr.value);
                if (col == null) { errors.add("Columna '" + expr.value + "' no encontrada"); return DataType.VARCHAR; }
                return col.type;
            default: return DataType.VARCHAR;
        }
    }

    private boolean typesCompatible(DataType a, DataType b) {
        if (a == b) return true;
        return (a == DataType.FLOAT && b == DataType.INT) || (a == DataType.INT && b == DataType.FLOAT);
    }

    private void validateCondition(ConditionNode cond, Table table) {
        if (cond == null || table == null) return;
        DataType l = getExprType(cond.left, table);
        DataType r = getExprType(cond.right, table);
        if (!typesCompatible(l, r))
            errors.add("Tipos incompatibles: " + l + " " + cond.op.toSymbol() + " " + r);
    }

    public boolean analyze(SelectNode ast) {
        if (ast == null) { errors.add("AST vacio"); return false; }
        errors.clear(); warnings.clear();
        Table table = validateTable(ast.tableName);
        validateColumns(ast, table);
        if (ast.whereCondition != null) validateCondition(ast.whereCondition, table);
        return errors.isEmpty();
    }

    public void printDiagnostics() {
        if (!errors.isEmpty()) {
            System.out.println("\n❌ ERRORES SEMANTICOS:");
            errors.forEach(e -> System.out.println("  - " + e));
        }
        if (!warnings.isEmpty()) {
            System.out.println("\n⚠️  ADVERTENCIAS:");
            warnings.forEach(w -> System.out.println("  - " + w));
        }
        if (errors.isEmpty() && warnings.isEmpty())
            System.out.println("\n✅ Analisis semantico exitoso");
    }

    public List<String> getErrors()   { return errors; }
    public List<String> getWarnings() { return warnings; }
}
