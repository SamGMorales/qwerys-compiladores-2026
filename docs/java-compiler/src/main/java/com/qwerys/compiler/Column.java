package com.qwerys.compiler;

/**
 * Representa una columna de una tabla en el schema
 * Migrado de: struct Column en SymbolTable.h (C++)
 * Responsable: Josue David Morales Ramirez - Rama: feature/josue-morales-semantic
 */
public class Column {
    public String name;
    public DataType type;
    public Column(String name, DataType type) { this.name = name; this.type = type; }
}
