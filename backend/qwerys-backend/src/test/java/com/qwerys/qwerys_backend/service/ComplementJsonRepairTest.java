package com.qwerys.qwerys_backend.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ComplementJsonRepairTest {

    @Test
    void repairsPseudoConcatInsideOriginalFragment() {
        String broken = """
                "originalFragment": "WHERE Id = '" + userId + "'"
                """;
        String fixed = ComplementJsonRepair.repair(broken);
        assertNotEquals(broken, fixed);
        assertTrue(fixed.contains("\\\" + userId + \\\""),
                () -> "expected escaped concat, got: " + fixed);
    }

    @Test
    void leavesValidJsonUnchanged() {
        String valid = """
                {
                  "pedagogy": "Use prepared statements.",
                  "additionalOptimizations": [{
                    "originalFragment": "WHERE id = @p1",
                    "optimizedFragment": "WHERE id = @p1"
                  }]
                }
                """;
        assertEquals(valid, ComplementJsonRepair.repair(valid));
    }
}
