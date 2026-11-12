package com.aerosync.repository;

import com.aerosync.model.WeatherReport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface WeatherReportRepository extends JpaRepository<WeatherReport, UUID> {

    /** The latest report of a kind for an airport (latest observation, or furthest forecast). */
    Optional<WeatherReport> findFirstByAirportIdAndKindOrderByValidAtDesc(UUID airportId, WeatherReport.Kind kind);

    /** Reports of a kind valid after {@code after}, earliest first (upcoming forecasts). */
    List<WeatherReport> findByAirportIdAndKindAndValidAtAfterOrderByValidAtAsc(
            UUID airportId, WeatherReport.Kind kind, Instant after);

    Optional<WeatherReport> findByAirportIdAndKindAndValidAtAndDataSource(
            UUID airportId, WeatherReport.Kind kind, Instant validAt, String dataSource);

    /** When any report was last fetched (freshness of the weather feed). */
    Optional<WeatherReport> findFirstByKindOrderByFetchedAtDesc(WeatherReport.Kind kind);

    @Transactional
    @Modifying
    @Query("delete from WeatherReport w where w.validAt < :before")
    int deleteOlderThan(@Param("before") Instant before);
}
