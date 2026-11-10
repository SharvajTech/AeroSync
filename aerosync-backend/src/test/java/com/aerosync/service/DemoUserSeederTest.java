package com.aerosync.service;

import com.aerosync.model.UserRole;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Parsing of the development-only demo account definitions. */
class DemoUserSeederTest {

    @Test
    void parsesAccountsWithOneOrMoreAirports() {
        List<DemoUserSeeder.DemoAccount> accounts = DemoUserSeeder.parse(
                " BOM.Ops=bom:OPERATIONS_CONTROLLER ; network.ops=BOM, DEL:operations_controller;"
                        + "bom.viewer=BOM:OPERATIONS_VIEWER;");
        assertEquals(3, accounts.size());
        assertEquals(new DemoUserSeeder.DemoAccount("bom.ops", List.of("BOM"), UserRole.OPERATIONS_CONTROLLER),
                accounts.get(0));
        assertEquals(List.of("BOM", "DEL"), accounts.get(1).airports());
        assertEquals(UserRole.OPERATIONS_VIEWER, accounts.get(2).role());
    }

    @Test
    void emptySpecificationCreatesNothing() {
        assertTrue(DemoUserSeeder.parse(null).isEmpty());
        assertTrue(DemoUserSeeder.parse("  ").isEmpty());
    }

    @Test
    void rejectsMalformedDefinitions() {
        for (String bad : new String[]{"bom.ops", "bom.ops=BOM", "=BOM:OPERATIONS_VIEWER", "bom.ops=BOMBAY:OPERATIONS_VIEWER",
                "bom.ops=BOM:ADMIN", "b=BOM:OPERATIONS_VIEWER", "bad user=BOM:OPERATIONS_VIEWER"}) {
            assertThrows(IllegalArgumentException.class, () -> DemoUserSeeder.parse(bad), bad);
        }
    }
}
