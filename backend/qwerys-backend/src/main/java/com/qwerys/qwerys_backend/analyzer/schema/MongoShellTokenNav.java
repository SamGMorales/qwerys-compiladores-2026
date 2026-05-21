package com.qwerys.qwerys_backend.analyzer.schema;

import com.qwerys.qwerys_backend.analyzer.NoSqlToken;
import com.qwerys.qwerys_backend.analyzer.NoSqlTokenType;

import java.util.ArrayList;
import java.util.List;

/** Token navigation helpers for MongoDB shell schema extraction (mirrors {@code MongoDbAnalyzer} navigation). */
final class MongoShellTokenNav {

    record KeyVal(String key, int valStart, int valEnd) {}

    private MongoShellTokenNav() {
    }

    static List<KeyVal> parseTopLevelObjectPairs(List<NoSqlToken> tokens, int braceOpenIdx) {
        List<KeyVal> pairs = new ArrayList<>();
        int i = braceOpenIdx + 1;
        if (i >= tokens.size() || tokens.get(braceOpenIdx).type() != NoSqlTokenType.BRACE_OPEN) {
            return pairs;
        }
        if (tokens.get(i).type() == NoSqlTokenType.BRACE_CLOSE) {
            return pairs;
        }
        while (i < tokens.size()) {
            if (tokens.get(i).type() == NoSqlTokenType.COMMA) {
                i++;
                continue;
            }
            if (tokens.get(i).type() != NoSqlTokenType.FIELD_NAME
                    && tokens.get(i).type() != NoSqlTokenType.OPERATOR) {
                break;
            }
            String key = tokens.get(i).value();
            i++;
            if (i >= tokens.size() || tokens.get(i).type() != NoSqlTokenType.COLON) {
                break;
            }
            i++;
            int valStart = i;
            i = skipValue(tokens, valStart);
            pairs.add(new KeyVal(key, valStart, i));
            if (i < tokens.size() && tokens.get(i).type() == NoSqlTokenType.COMMA) {
                i++;
                continue;
            }
            break;
        }
        return pairs;
    }

    static int skipValue(List<NoSqlToken> tokens, int start) {
        if (start >= tokens.size()) {
            return start;
        }
        NoSqlTokenType t = tokens.get(start).type();
        if (t == NoSqlTokenType.BRACE_OPEN) {
            return skipBalanced(tokens, start, NoSqlTokenType.BRACE_OPEN, NoSqlTokenType.BRACE_CLOSE);
        }
        if (t == NoSqlTokenType.BRACKET_OPEN) {
            return skipBalanced(tokens, start, NoSqlTokenType.BRACKET_OPEN, NoSqlTokenType.BRACKET_CLOSE);
        }
        return start + 1;
    }

    static int skipBalanced(List<NoSqlToken> tokens, int openIdx, NoSqlTokenType open, NoSqlTokenType close) {
        int depth = 0;
        for (int j = openIdx; j < tokens.size(); j++) {
            NoSqlTokenType ty = tokens.get(j).type();
            if (ty == open) {
                depth++;
            } else if (ty == close) {
                depth--;
                if (depth == 0) {
                    return j + 1;
                }
            }
        }
        return tokens.size();
    }

    static int indexOfMethod(List<NoSqlToken> tokens, NoSqlTokenType method) {
        for (int i = 0; i < tokens.size(); i++) {
            if (tokens.get(i).type() == method) {
                return i;
            }
        }
        return -1;
    }

    static int firstPipelineBracket(List<NoSqlToken> tokens, int methodIdx) {
        int i = methodIdx + 1;
        while (i < tokens.size() && tokens.get(i).type() == NoSqlTokenType.COMMA) {
            i++;
        }
        if (i < tokens.size() && tokens.get(i).type() == NoSqlTokenType.BRACKET_OPEN) {
            return i;
        }
        return -1;
    }

    static List<Integer> collectStageBraceOpens(List<NoSqlToken> tokens, int pipelineBracketOpen) {
        List<Integer> stages = new ArrayList<>();
        int i = pipelineBracketOpen + 1;
        while (i < tokens.size() && tokens.get(i).type() != NoSqlTokenType.BRACKET_CLOSE) {
            if (tokens.get(i).type() == NoSqlTokenType.COMMA) {
                i++;
                continue;
            }
            if (tokens.get(i).type() == NoSqlTokenType.BRACE_OPEN) {
                stages.add(i);
                i = skipValue(tokens, i);
                continue;
            }
            i++;
        }
        return stages;
    }
}
