package com.qwerys.compiler;

import java.util.*;
import java.io.*;
import java.nio.file.*;

/**
 * Punto de entrada del Compilador SQL
 * Integra las 3 fases: Lexico → Sintactico → Semantico
 * Migrado de: main.cpp (C++)
 * Responsable: Marjorie Samantha Giron Morales - Rama: feature/marjorie-giron-arquitectura
 */
public class Main {
    public static void main(String[] args) {
        System.out.println("============================================");
        System.out.println("  Compilador SQL - Mini Lenguaje");
        System.out.println("  Curso de Compiladores 2026 - UMG");
        System.out.println("============================================\n");

        String query;
        if (args.length >= 1) {
            try { query = new String(Files.readAllBytes(Paths.get(args[0]))); }
            catch (IOException e) { System.err.println("Error abriendo archivo: " + args[0]); return; }
        } else {
            Scanner sc = new Scanner(System.in);
            System.out.println("[MODO INTERACTIVO] Escriba su query SQL:");
            System.out.print("> ");
            query = sc.nextLine();
        }

        System.out.println("--- QUERY ---\n" + query + "\n-------------\n");

        // FASE 1: LEXICO
        System.out.println("===== FASE 1: ANALISIS LEXICO =====");
        Lexer lexer = new Lexer(query);
        List<Token> tokens = lexer.tokenize();
        System.out.println("Tokens: " + tokens.size());
        for (Token t : tokens) { System.out.print("  "); t.print(); System.out.println(); }

        // FASE 2 + 3: SINTACTICO + SEMANTICO
        System.out.println("\n===== FASE 2: ANALISIS SINTACTICO =====");
        try {
            Parser parser = new Parser(tokens);
            SelectNode ast = parser.parse();
            System.out.println("Parsing exitoso. AST:");
            ast.print(2);

            System.out.println("\n===== FASE 3: ANALISIS SEMANTICO =====");
            SymbolTable schema = new SymbolTable();
            schema.print();
            SemanticAnalyzer analyzer = new SemanticAnalyzer(schema);
            boolean valid = analyzer.analyze(ast);
            analyzer.printDiagnostics();

            System.out.println("\n===== RESULTADO =====");
            System.out.println(valid ? "✅ Query VALIDA" : "❌ Query con ERRORES");

        } catch (RuntimeException e) {
            System.out.println("\n❌ ERROR: " + e.getMessage());
        }
    }
}
