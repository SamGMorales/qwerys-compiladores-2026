package com.qwerys.compiler;

import java.util.ArrayList;
import java.util.List;

/**
 * Nodo raiz del AST que representa una query SELECT completa
 * Migrado de: class SelectNode en AST.h (C++)
 * Responsable: Mercedes Azucena Lopez Perez - Rama: feature/mercedes-lopez-parser-ast
 */
public class SelectNode extends ASTNode {
    public List<String> columns = new ArrayList<>();
    public boolean selectAll = false;
    public String tableName = "";
    public ConditionNode whereCondition = null;

    @Override
    public void print(int indent) {
        String sp = " ".repeat(indent);
        System.out.println(sp + "SELECT Query:");
        System.out.print(sp + "  Columns: ");
        System.out.println(selectAll ? "*" : String.join(", ", columns));
        System.out.println(sp + "  FROM: " + tableName);
        if (whereCondition != null) {
            System.out.println(sp + "  WHERE:");
            whereCondition.print(indent + 4);
        }
    }
}
