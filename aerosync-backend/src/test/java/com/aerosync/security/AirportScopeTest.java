package com.aerosync.security;

import com.aerosync.exception.AccessForbiddenException;
import com.aerosync.exception.InvalidRequestParameterException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AirportScopeTest {

    private final AirportScope bom = AirportScope.of("BOM");
    private final AirportScope bomDel = AirportScope.of(List.of("del", " BOM "));

    @Test
    void normalizesAndSortsCodes() {
        assertEquals(Set.of("BOM", "DEL"), bomDel.airports());
        assertEquals(List.of("BOM", "DEL"), List.copyOf(bomDel.airports()));
        assertTrue(bomDel.includes("bom"));
        assertFalse(bom.includes("DEL"));
        assertFalse(bom.includes(null));
        assertFalse(bom.includes(" "));
    }

    @Test
    void flightIsVisibleFromEitherEndButOperatedOnlyFromItsOrigin() {
        assertTrue(bom.canView("BOM", "DEL"));
        assertTrue(bom.canView("DEL", "BOM"));
        assertFalse(bom.canView("DEL", "BLR"));
        assertTrue(bom.canOperate("BOM"));
        assertFalse(bom.canOperate("DEL"));
    }

    @Test
    void requireMethodsThrowForbidden() {
        bom.requireAirport("BOM");
        bom.requireView("DEL", "BOM", "Flight X");
        bom.requireOperate("BOM", "Flight X");
        assertThrows(AccessForbiddenException.class, () -> bom.requireAirport("DEL"));
        assertThrows(AccessForbiddenException.class, () -> bom.requireView("DEL", "BLR", "Flight Y"));
        AccessForbiddenException ex = assertThrows(AccessForbiddenException.class,
                () -> bom.requireOperate("DEL", "Flight Z"));
        assertTrue(ex.getMessage().contains("DEL"));
    }

    @Test
    void errorMessagesOnlyEchoCodeLikeInput() {
        AccessForbiddenException ex = assertThrows(AccessForbiddenException.class,
                () -> bom.requireAirport("<script>"));
        assertFalse(ex.getMessage().contains("<script>"));
    }

    @Test
    void narrowingKeepsOrReducesScope() {
        assertSame(bomDel, bomDel.narrowTo(List.of()));
        assertSame(bomDel, bomDel.narrowTo(null));
        assertEquals(AirportScope.of("DEL"), bomDel.narrowTo(List.of("del")));
    }

    @Test
    void narrowingCanNeverWidenScope() {
        assertThrows(AccessForbiddenException.class, () -> bom.narrowTo(List.of("DEL")));
        assertThrows(AccessForbiddenException.class, () -> bom.narrowTo(List.of("BOM", "DEL")));
        assertThrows(InvalidRequestParameterException.class, () -> bom.narrowTo(List.of("BOMBAY")));
        assertThrows(InvalidRequestParameterException.class, () -> bom.narrowTo(List.of("B1")));
    }

    @Test
    void emptyScopeSeesNothing() {
        AirportScope none = AirportScope.of(List.of());
        assertTrue(none.airports().isEmpty());
        assertFalse(none.isUnrestricted());
        assertFalse(none.canView("BOM", "DEL"));
        assertThrows(AccessForbiddenException.class, () -> none.narrowTo(List.of("BOM")));
    }

    @Test
    void unrestrictedScopeIsOnlyForSystemUse() {
        AirportScope all = AirportScope.unrestricted();
        assertTrue(all.isUnrestricted());
        assertTrue(all.includes("GAU"));
        assertTrue(all.airports().isEmpty());
        assertFalse(all.includes(null));
        assertEquals(AirportScope.of("BOM"), all.narrowTo(List.of("bom")));
    }

    @Test
    void parsesCommaSeparatedParameters() {
        assertEquals(List.of(), AirportScope.parseCsv(null));
        assertEquals(List.of(), AirportScope.parseCsv("  "));
        assertEquals(List.of("BOM", "del"), AirportScope.parseCsv(" BOM, ,del"));
    }
}
