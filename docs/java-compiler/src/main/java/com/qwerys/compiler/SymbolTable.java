package com.qwerys.compiler;

import java.util.HashMap;
import java.util.Map;

/**
 * FASE 4 - Tabla de Simbolos (Schema de la base de datos)
 * Migrado de: SymbolTable.h + SymbolTable.cpp (C++)
 * Responsable: Josue David Morales Ramirez - Rama: feature/josue-morales-semantic
 */
public class SymbolTable {
    private Map<String, Table> tables = new HashMap<>();

    public SymbolTable() {
        // Tabla: usuarios
        Table usuarios = new Table("usuarios");
        usuarios.addColumn("id",     DataType.INT);
        usuarios.addColumn("nombre", DataType.VARCHAR);
        usuarios.addColumn("edad",   DataType.INT);
        usuarios.addColumn("ciudad", DataType.VARCHAR);
        tables.put("usuarios", usuarios);

        // Tabla: productos
        Table productos = new Table("productos");
        productos.addColumn("id",        DataType.INT);
        productos.addColumn("nombre",    DataType.VARCHAR);
        productos.addColumn("precio",    DataType.FLOAT);
        productos.addColumn("categoria", DataType.VARCHAR);
        tables.put("productos", productos);
    }

    public Table findTable(String name) {
        for (Map.Entry<String, Table> e : tables.entrySet())
            if (e.getKey().equalsIgnoreCase(name)) return e.getValue();
        return null;
    }

    public void print() {
        System.out.println("========== SCHEMA ==========");
        for (Table t : tables.values()) { t.print(); System.out.println(); }
        System.out.println("============================");
    }
}
