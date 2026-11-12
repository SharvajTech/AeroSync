package com.aerosync.weather;

import com.aerosync.datasource.DataSources;
import com.aerosync.dto.live.AirportWeatherDto;
import com.aerosync.ingestion.StubHttpServer;
import com.aerosync.ingestion.StubHttpServer.Reply;
import com.aerosync.model.Airport;
import com.aerosync.model.AirportStatus;
import com.aerosync.model.WeatherReport;
import com.aerosync.repository.AirportRepository;
import com.aerosync.repository.WeatherReportRepository;
import com.aerosync.service.WeatherService;
import com.aerosync.testsupport.InMemoryRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Weather integration against a local stand-in for OpenWeatherMap: lookup by
 * coordinates, stored observation timestamps, caching, observation versus
 * forecast, and honest behaviour when the source is unavailable.
 */
class WeatherServiceTest {

    private static final String KEY = "test-key-never-logged";
    private static final Instant NOW = Instant.parse("2026-11-10T10:00:00Z");

    private StubHttpServer owm;
    private Instant now;
    private InMemoryRepository<Airport, UUID> airports;
    private InMemoryRepository<WeatherReport, UUID> reports;
    private Airport bom;
    private Airport del;

    @BeforeEach
    void setUp() {
        owm = new StubHttpServer();
        now = NOW;
        airports = InMemoryRepository.of(Airport::getAirportId, a -> a.setAirportId(UUID.randomUUID()));
        airports.query("findAllByOrderByIataCodeAsc", (rows, args) -> rows.stream()
                .sorted(Comparator.comparing(Airport::getIataCode)).toList());
        airports.query("findByIataCode", (rows, args) -> rows.stream()
                .filter(a -> a.getIataCode().equals(args[0])).findFirst());
        reports = InMemoryRepository.of(WeatherReport::getReportId, r -> r.setReportId(UUID.randomUUID()));
        reports.query("findFirstByAirportIdAndKindOrderByValidAtDesc", (rows, args) -> rows.stream()
                .filter(r -> r.getAirportId().equals(args[0]) && r.getKind() == args[1])
                .max(Comparator.comparing(WeatherReport::getValidAt)));
        reports.query("findByAirportIdAndKindAndValidAtAfterOrderByValidAtAsc", (rows, args) -> rows.stream()
                .filter(r -> r.getAirportId().equals(args[0]) && r.getKind() == args[1]
                        && r.getValidAt().isAfter((Instant) args[2]))
                .sorted(Comparator.comparing(WeatherReport::getValidAt)).toList());
        reports.query("findByAirportIdAndKindAndValidAtAndDataSource", (rows, args) -> rows.stream()
                .filter(r -> r.getAirportId().equals(args[0]) && r.getKind() == args[1]
                        && r.getValidAt().equals(args[2]) && r.getDataSource().equals(args[3]))
                .findFirst());
        reports.query("findFirstByKindOrderByFetchedAtDesc", (rows, args) -> rows.stream()
                .filter(r -> r.getKind() == args[0]).max(Comparator.comparing(WeatherReport::getFetchedAt)));
        reports.query("deleteOlderThan", (rows, args) ->
                reports.removeIf(r -> r.getValidAt().isBefore((Instant) args[0])));
        bom = airport("BOM", 19.0896, 72.8656);
        del = airport("DEL", 28.5562, 77.1000);
    }

    @AfterEach
    void tearDown() {
        owm.close();
    }

    private Airport airport(String iata, double latitude, double longitude) {
        return airports.as(AirportRepository.class).save(Airport.builder()
                .airportId(UUID.nameUUIDFromBytes(iata.getBytes())).iataCode(iata).airportName(iata)
                .city(iata).timezone("Asia/Kolkata").latitude(latitude).longitude(longitude)
                .status(AirportStatus.OPERATIONAL).build());
    }

    private WeatherService service(String apiKey, boolean forecasts) {
        Clock clock = new Clock() {
            @Override
            public ZoneId getZone() {
                return ZoneId.of("UTC");
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now;
            }
        };
        OpenWeatherClient client = new OpenWeatherClient(owm.url() + "/data/2.5", apiKey, Duration.ofSeconds(2),
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
        return new WeatherService(airports.as(AirportRepository.class), reports.as(WeatherReportRepository.class),
                client, clock, Duration.ofMinutes(30), Duration.ofMinutes(120), forecasts,
                Duration.ofMinutes(180), 4);
    }

    /** An OpenWeatherMap current-weather body observed {@code minutesAgo} before now. */
    private String observation(int conditionId, String description, double wind, int visibility, long minutesAgo) {
        return "{\"weather\":[{\"id\":" + conditionId + ",\"description\":\"" + description + "\"}],"
                + "\"main\":{\"temp\":27.4},\"visibility\":" + visibility + ",\"wind\":{\"speed\":" + wind + "},"
                + "\"rain\":{\"1h\":3.2},\"dt\":" + now.minus(Duration.ofMinutes(minutesAgo)).getEpochSecond()
                + ",\"cod\":200}";
    }

    @Test
    void theObservationIsFetchedByCoordinatesAndStoredWithItsOwnTimestamp() {
        owm.on("/data/2.5/weather", Reply.of(200, observation(502, "heavy intensity rain", 8.0, 4000, 12)));
        WeatherService service = service(KEY, false);

        assertEquals(3, service.getWeatherScore("bom"));

        String query = owm.received("/data/2.5/weather").get(0).query();
        assertTrue(query.contains("lat=19.0896") && query.contains("lon=72.8656"), query);
        WeatherReport stored = reports.rows().get(0);
        assertEquals(WeatherReport.Kind.OBSERVATION, stored.getKind());
        assertEquals(now.minus(Duration.ofMinutes(12)), stored.getValidAt());    // the source's time, not ours
        assertEquals(now, stored.getFetchedAt());
        assertEquals(DataSources.OPENWEATHERMAP, stored.getDataSource());
        assertEquals(502, stored.getConditionCode());
        assertEquals("heavy intensity rain", stored.getDescription());
        assertEquals(3.2, stored.getPrecipitationMm());
        assertEquals(bom.getAirportId(), stored.getAirportId());
    }

    @Test
    void anObservationIsReusedUntilTheRefreshIntervalHasPassed() {
        owm.on("/data/2.5/weather", Reply.of(200, observation(800, "clear sky", 2.0, 10000, 5)));
        WeatherService service = service(KEY, false);

        service.getWeatherScore("BOM");
        service.getWeatherScore("BOM");
        service.currentObservation("BOM");
        assertEquals(1, owm.received("/data/2.5/weather").size());

        now = now.plus(Duration.ofMinutes(31));
        owm.on("/data/2.5/weather", Reply.of(200, observation(501, "moderate rain", 2.0, 10000, 1)));
        assertEquals(2, service.getWeatherScore("BOM"));
        assertEquals(2, owm.received("/data/2.5/weather").size());
        assertEquals(2, reports.rows().size(), "each observation time is kept");
    }

    @Test
    void anUnavailableSourceIsNotReportedAsAMeasuredValue() {
        owm.on("/data/2.5/weather", Reply.of(503, ""));
        WeatherService service = service(KEY, false);

        // The delay model still gets its previous default...
        assertEquals(WeatherService.DEFAULT_SCORE, service.getWeatherScore("BOM"));
        // ...but nothing claims that it was observed.
        assertTrue(service.currentObservation("BOM").isEmpty());
        assertTrue(service.getWeatherForAllAirports().isEmpty());
        assertTrue(service.storedCurrentObservations().isEmpty());
        assertEquals(0, reports.rows().size());

        AirportWeatherDto weather = service.airportWeather(bom);
        assertEquals(AirportWeatherDto.UNAVAILABLE, weather.status());
        assertNull(weather.observation());
        assertTrue(weather.note().contains("HTTP 503"), weather.note());
        assertNotNull(service.status().lastError());
    }

    @Test
    void afterAFailureTheSourceIsLeftAloneForAWhile() {
        owm.on("/data/2.5/weather", Reply.of(500, ""));
        WeatherService service = service(KEY, false);

        service.getWeatherForAllAirports();     // two airports, but only one failed call
        service.getWeatherScore("DEL");
        assertEquals(1, owm.received("/data/2.5/weather").size());

        now = now.plus(Duration.ofMinutes(6));
        owm.on("/data/2.5/weather", Reply.of(200, observation(800, "clear sky", 1.0, 10000, 2)));
        assertEquals(Map.of("BOM", 0, "DEL", 0), service.getWeatherForAllAirports());
        assertNull(service.status().lastError());
    }

    @Test
    void anOldObservationIsShownAsStaleAndNotUsed() {
        owm.on("/data/2.5/weather", Reply.of(200, observation(211, "thunderstorm", 9.0, 6000, 3)));
        WeatherService service = service(KEY, false);
        assertEquals(3, service.getWeatherScore("BOM"));

        // The source goes away; three hours later the stored observation is too old to act on.
        owm.on("/data/2.5/weather", Reply.of(503, ""));
        now = now.plus(Duration.ofHours(3));

        assertEquals(WeatherService.DEFAULT_SCORE, service.getWeatherScore("BOM"));
        assertTrue(service.storedCurrentObservations().isEmpty());
        AirportWeatherDto weather = service.airportWeather(bom);
        assertEquals(AirportWeatherDto.STALE, weather.status());
        assertEquals(3, weather.observation().severityScore());
        assertEquals(183L, weather.observationAgeMinutes());
    }

    @Test
    void withoutAnApiKeyNothingIsFetched() {
        WeatherService service = service("", true);

        assertEquals(WeatherService.DEFAULT_SCORE, service.getWeatherScore("BOM"));
        assertEquals(0, service.refreshDue());
        assertFalse(service.configured());
        assertEquals(0, owm.received().size());
        AirportWeatherDto weather = service.airportWeather(bom);
        assertEquals(AirportWeatherDto.UNAVAILABLE, weather.status());
        assertFalse(weather.sourceConfigured());
        assertTrue(weather.note().contains("WEATHER_API_KEY"));
    }

    @Test
    void forecastsAreStoredSeparatelyAndNeverUsedAsObservations() {
        owm.on("/data/2.5/weather", Reply.of(200, observation(800, "clear sky", 2.0, 10000, 4)));
        long step = now.plus(Duration.ofHours(2)).getEpochSecond();
        owm.on("/data/2.5/forecast", Reply.of(200, "{\"list\":["
                + "{\"dt\":" + step + ",\"main\":{\"temp\":24.0},\"weather\":[{\"id\":212,\"description\":"
                + "\"heavy thunderstorm\"}],\"wind\":{\"speed\":14.0},\"visibility\":3000,\"pop\":0.9,"
                + "\"rain\":{\"3h\":18.5}},"
                + "{\"dt\":" + (step + 10800) + ",\"main\":{\"temp\":23.0},\"weather\":[{\"id\":500,"
                + "\"description\":\"light rain\"}],\"wind\":{\"speed\":4.0},\"visibility\":10000,\"pop\":0.4}"
                + "]}"));
        WeatherService service = service(KEY, true);

        assertEquals(2, service.refreshDue());

        // A severe forecast does not change the observed score.
        assertEquals(0, service.getWeatherScore("BOM"));
        assertEquals(0, service.storedCurrentObservations().get("BOM").getSeverityScore());

        AirportWeatherDto weather = service.airportWeather(bom);
        assertEquals(AirportWeatherDto.LIVE, weather.status());
        assertEquals(WeatherReport.Kind.OBSERVATION, weather.observation().kind());
        assertEquals(2, weather.forecast().size());
        assertEquals(WeatherReport.Kind.FORECAST, weather.forecast().get(0).kind());
        assertEquals(3, weather.forecast().get(0).severityScore());
        assertEquals(0.9, weather.forecast().get(0).precipitationProbability());
        assertEquals(Instant.ofEpochSecond(step), weather.forecast().get(0).validAt());

        // Refreshing again within the intervals makes no further calls and no duplicate rows.
        int rows = reports.rows().size();
        assertEquals(0, service.refreshDue());
        assertEquals(2, owm.received("/data/2.5/weather").size());
        assertEquals(2, owm.received("/data/2.5/forecast").size());
        assertEquals(rows, reports.rows().size());
    }

    @Test
    void theApiKeyNeverAppearsInErrorsOrStatus() {
        owm.on("/data/2.5/weather", Reply.of(401, "{\"cod\":401,\"message\":\"Invalid API key " + KEY + "\"}"));
        WeatherService service = service(KEY, false);

        service.getWeatherScore("BOM");

        assertFalse(service.status().lastError().contains(KEY));
        assertFalse(service.airportWeather(bom).note().contains(KEY));
        OpenWeatherClient client = new OpenWeatherClient("http://127.0.0.1:1/data", KEY, Duration.ofMillis(300));
        WeatherUnavailableException ex = assertThrows(WeatherUnavailableException.class,
                () -> client.fetchCurrent(19.0, 72.8));
        assertFalse(ex.getMessage().contains(KEY));
        assertFalse(ex.getMessage().contains("appid"));
    }

    @Test
    void scoringKeepsTheThresholdsTheDelayModelWasTrainedWith() {
        assertEquals(0, WeatherScoring.score(800, 3.0, 10000, null));
        assertEquals(3, WeatherScoring.score(211, 3.0, 10000, null));      // thunderstorm
        assertEquals(3, WeatherScoring.score(502, null, null, null));      // heavy rain
        assertEquals(2, WeatherScoring.score(501, 3.0, 10000, 2.0));       // moderate rain
        assertEquals(1, WeatherScoring.score(500, 3.0, 10000, 0.4));       // light rain
        assertEquals(2, WeatherScoring.score(741, 1.0, 10000, null));      // fog
        assertEquals(2, WeatherScoring.score(600, 1.0, 10000, 1.5));       // snow with accumulation
        assertEquals(1, WeatherScoring.score(600, 1.0, 10000, 0.2));
        assertEquals(2, WeatherScoring.score(800, 16.0, 10000, null));     // strong wind alone
        assertEquals(1, WeatherScoring.score(800, 11.0, 10000, null));
        assertEquals(3, WeatherScoring.score(800, 1.0, 800, null));        // very low visibility alone
        assertEquals(2, WeatherScoring.score(800, 1.0, 2500, null));
        assertEquals(1, WeatherScoring.score(800, 1.0, 4500, null));
        assertEquals(List.of("Clear", "Severe: operations affected"),
                List.of(WeatherScoring.label(0), WeatherScoring.label(3)));
    }
}
