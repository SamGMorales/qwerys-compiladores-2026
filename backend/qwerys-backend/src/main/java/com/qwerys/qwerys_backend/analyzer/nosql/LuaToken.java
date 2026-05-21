package com.qwerys.qwerys_backend.analyzer.nosql;

/** A single Lua token with 1-based source position. */
public record LuaToken(LuaTokenType type, String lexeme, int line, int column) {}
