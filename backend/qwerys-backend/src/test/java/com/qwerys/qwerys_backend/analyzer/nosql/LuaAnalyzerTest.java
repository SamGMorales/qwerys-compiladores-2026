package com.qwerys.qwerys_backend.analyzer.nosql;

import com.qwerys.qwerys_backend.analyzer.RedisAnalyzer;
import com.qwerys.qwerys_backend.analyzer.SemanticError;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertTrue;

class LuaAnalyzerTest {

    @Test
    void lua001_redisCall_emitsWarning() {
        String lua = "return redis.call('GET', KEYS[1])";
        assertTrue(LuaAnalyzer.analyze(lua, 1, false).stream().anyMatch(e -> "LUA-001".equals(e.code())));
    }

    @Test
    void lua002_whileTrueWithoutBreak_emitsError() {
        String lua = "while true do\nredis.call('PING')\nend";
        assertTrue(LuaAnalyzer.analyze(lua, 0, false).stream().anyMatch(e -> "LUA-002".equals(e.code())));
    }

    @Test
    void lua003_globalAssign_emitsWarning() {
        String lua = "x = 1\nreturn x";
        assertTrue(LuaAnalyzer.analyze(lua, 0, false).stream().anyMatch(e -> "LUA-003".equals(e.code())));
    }

    @Test
    void lua004_keysWithZeroNumkeys_emitsWarning() {
        String lua = "return redis.call('GET', KEYS[1])";
        List<SemanticError> e = LuaAnalyzer.analyze(lua, 0, false);
        assertTrue(e.stream().anyMatch(x -> "LUA-004".equals(x.code())));
    }

    @Test
    void lua005_argvArithmetic_emitsWarning() {
        String lua = "return ARGV[1] + 1";
        List<SemanticError> r = LuaAnalyzer.analyze(lua, 0, false);
        assertTrue(r.stream().anyMatch(e -> "LUA-005".equals(e.code())), () -> String.valueOf(r));
    }

    @Test
    void lua006_longScript_emitsInfo() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 55; i++) {
            sb.append("local _").append(i).append(" = 1\n");
        }
        assertTrue(LuaAnalyzer.analyze(sb.toString(), 0, false).stream().anyMatch(e -> "LUA-006".equals(e.code())));
    }

    @Test
    void lua007_redisCallInLoop_emitsWarning() {
        String lua = "while false do\nredis.call('PING')\nend";
        assertTrue(LuaAnalyzer.analyze(lua, 0, false).stream().anyMatch(e -> "LUA-007".equals(e.code())));
    }

    @Test
    void redisAnalyzer_eval_mergesLuaFindings() {
        RedisAnalyzer ra = new RedisAnalyzer();
        List<SemanticError> out = ra.analyze(
                "EVAL \"return redis.call('GET', KEYS[1])\" 1 mykey", Locale.ENGLISH);
        assertTrue(out.stream().anyMatch(e -> "LUA-001".equals(e.code())));
    }
}
