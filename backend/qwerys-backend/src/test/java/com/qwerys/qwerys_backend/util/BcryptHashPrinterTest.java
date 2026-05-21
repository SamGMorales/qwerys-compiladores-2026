package com.qwerys.qwerys_backend.util;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * Run locally to generate a bcrypt hash for manual DB reset:
 * mvn -q -Dtest=BcryptHashPrinterTest test
 */
class BcryptHashPrinterTest {

    @Test
    void printHashForManualReset() {
        String plain = System.getProperty("reset.password", "QwerysTemp2026");
        String hash = new BCryptPasswordEncoder().encode(plain);
        System.out.println("PLAIN (use this to log in after SQL update): " + plain);
        System.out.println("SQL:");
        System.out.println("UPDATE users SET password = '" + hash + "' WHERE email = 'ms.morales2901@gmail.com';");
    }
}
