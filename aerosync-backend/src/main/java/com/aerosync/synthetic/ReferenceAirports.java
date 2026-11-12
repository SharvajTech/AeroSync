package com.aerosync.synthetic;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Reference airports of the operational network.
 *
 * <p>These rows are real public reference data (IATA code, name, city, IANA
 * time zone, approximate aerodrome coordinates), not synthetic data. They are
 * inserted by Flyway migration {@code V2__reference_airports.sql}; the seeder
 * also inserts any that are missing (for example in the H2 test database).
 * {@code ReferenceAirportsMigrationTest} keeps this class and V2 in sync.</p>
 *
 * <p>The first nine airports are the ones the GNN cascade model was trained
 * on ({@code aerosync-ml/gnn/graph_dataset.py AIRPORT_ENCODE}); the other six
 * exist only in the operational graph and are not fed to the GNN.</p>
 */
public final class ReferenceAirports {

    public static final String INDIA_TIME_ZONE = "Asia/Kolkata";

    /** Order and codes match AIRPORT_ENCODE in aerosync-ml/gnn/graph_dataset.py. */
    public static final List<String> GNN_TRAINING_AIRPORTS =
            List.of("BOM", "DEL", "BLR", "HYD", "CCU", "MAA", "AMD", "GOI", "PNQ");

    public record ReferenceAirport(
            String iataCode,
            String airportName,
            String city,
            String timezone,
            double latitude,
            double longitude) {

        /** Deterministic id, identical in every environment and in migration V2. */
        public UUID airportId() {
            return airportUuid(iataCode);
        }

        public boolean gnnTrainingAirport() {
            return GNN_TRAINING_AIRPORTS.contains(iataCode);
        }
    }

    public static final List<ReferenceAirport> ALL = List.of(
            // Airports used by the existing GNN / ML models
            new ReferenceAirport("BOM", "Chhatrapati Shivaji Maharaj International Airport", "Mumbai",
                    INDIA_TIME_ZONE, 19.0896, 72.8656),
            new ReferenceAirport("DEL", "Indira Gandhi International Airport", "Delhi",
                    INDIA_TIME_ZONE, 28.5562, 77.1000),
            new ReferenceAirport("BLR", "Kempegowda International Airport", "Bengaluru",
                    INDIA_TIME_ZONE, 13.1986, 77.7066),
            new ReferenceAirport("HYD", "Rajiv Gandhi International Airport", "Hyderabad",
                    INDIA_TIME_ZONE, 17.2403, 78.4294),
            new ReferenceAirport("CCU", "Netaji Subhas Chandra Bose International Airport", "Kolkata",
                    INDIA_TIME_ZONE, 22.6547, 88.4467),
            new ReferenceAirport("MAA", "Chennai International Airport", "Chennai",
                    INDIA_TIME_ZONE, 12.9941, 80.1709),
            new ReferenceAirport("AMD", "Sardar Vallabhbhai Patel International Airport", "Ahmedabad",
                    INDIA_TIME_ZONE, 23.0772, 72.6347),
            new ReferenceAirport("GOI", "Goa International Airport (Dabolim)", "Goa",
                    INDIA_TIME_ZONE, 15.3808, 73.8314),
            new ReferenceAirport("PNQ", "Pune International Airport", "Pune",
                    INDIA_TIME_ZONE, 18.5821, 73.9197),
            // Additional airports of the operational network (not used by the GNN)
            new ReferenceAirport("COK", "Cochin International Airport", "Kochi",
                    INDIA_TIME_ZONE, 10.1520, 76.4019),
            new ReferenceAirport("JAI", "Jaipur International Airport", "Jaipur",
                    INDIA_TIME_ZONE, 26.8242, 75.8122),
            new ReferenceAirport("LKO", "Chaudhary Charan Singh International Airport", "Lucknow",
                    INDIA_TIME_ZONE, 26.7606, 80.8893),
            new ReferenceAirport("GAU", "Lokpriya Gopinath Bordoloi International Airport", "Guwahati",
                    INDIA_TIME_ZONE, 26.1061, 91.5859),
            new ReferenceAirport("NAG", "Dr. Babasaheb Ambedkar International Airport", "Nagpur",
                    INDIA_TIME_ZONE, 21.0922, 79.0472),
            new ReferenceAirport("BBI", "Biju Patnaik International Airport", "Bhubaneswar",
                    INDIA_TIME_ZONE, 20.2444, 85.8178));

    /**
     * ICAO location indicators of the reference airports (real public
     * reference data, same values as migration V4). OpenSky identifies
     * airports by these codes.
     */
    public static final Map<String, String> ICAO_CODES = Map.ofEntries(
            Map.entry("BOM", "VABB"), Map.entry("DEL", "VIDP"), Map.entry("BLR", "VOBL"),
            Map.entry("HYD", "VOHS"), Map.entry("CCU", "VECC"), Map.entry("MAA", "VOMM"),
            Map.entry("AMD", "VAAH"), Map.entry("GOI", "VOGO"), Map.entry("PNQ", "VAPO"),
            Map.entry("COK", "VOCI"), Map.entry("JAI", "VIJP"), Map.entry("LKO", "VILK"),
            Map.entry("GAU", "VEGT"), Map.entry("NAG", "VANP"), Map.entry("BBI", "VEBS"));

    private static final double EARTH_RADIUS_KM = 6371.0088;

    private ReferenceAirports() {
    }

    /** Name-based (type 3) UUID of {@code "aerosync:airport:" + IATA}. */
    public static UUID airportUuid(String iataCode) {
        return UUID.nameUUIDFromBytes(("aerosync:airport:" + iataCode).getBytes(StandardCharsets.UTF_8));
    }

    /** ICAO code of a reference airport, or null when the airport is not one. */
    public static String icaoCode(String iataCode) {
        return iataCode == null ? null : ICAO_CODES.get(iataCode);
    }

    public static Optional<ReferenceAirport> find(String iataCode) {
        return ALL.stream().filter(a -> a.iataCode().equals(iataCode)).findFirst();
    }

    public static ReferenceAirport require(String iataCode) {
        return find(iataCode).orElseThrow(() ->
                new IllegalArgumentException("Unknown reference airport " + iataCode));
    }

    /** Great-circle (haversine) distance in kilometres. */
    public static double greatCircleKm(ReferenceAirport a, ReferenceAirport b) {
        double lat1 = Math.toRadians(a.latitude());
        double lat2 = Math.toRadians(b.latitude());
        double dLat = lat2 - lat1;
        double dLon = Math.toRadians(b.longitude() - a.longitude());
        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * EARTH_RADIUS_KM * Math.asin(Math.min(1.0, Math.sqrt(h)));
    }
}
