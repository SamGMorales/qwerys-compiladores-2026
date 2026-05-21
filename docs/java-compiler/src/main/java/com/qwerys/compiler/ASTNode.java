package com.qwerys.compiler;

/**
 * Clase base abstracta del Arbol de Sintaxis Abstracta (AST)
 * Migrado de: class ASTNode en AST.h (C++)
 * Responsable: Mercedes Azucena Lopez Perez - Rama: feature/mercedes-lopez-parser-ast
 */
public abstract class ASTNode {
    public abstract void print(int indent);
}
