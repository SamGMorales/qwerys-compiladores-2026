package com.qwerys.compiler;

import java.util.ArrayList;
import java.util.List;

/**
 * Representa una tabla del schema de la base de datos
 * Migrado de: struct Table en SymbolTable.h (C++)
 * Responsable: Josue David Morales Ramirez - Rama: feature/josue-morales-semantic
 */
public class Table {
    public String name;
    public List<Column> columns = new ArrayList<>();

    public Table(String name) { this.name = name; }

    public void addColumn(String name, DataType type) { columns.add(new Column(name, type)); }

    public Column findColumn(String colName) {
        for (Column c : columns)
            if (c.name.equalsIgnoreCase(colName)) return c;
        return null;
    }

    public void print() {
        System.out.println("Tabla: " + name);
        for (Column c : columns)
            System.out.println("  - " + c.name + " (" + c.type + ")");
    }
}
