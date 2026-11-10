package com.aerosync.repository;

import com.aerosync.model.AppUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/** User accounts; both lookups fetch the assigned airports in the same statement. */
@Repository
public interface AppUserRepository extends JpaRepository<AppUser, UUID> {

    @Query("select u from AppUser u left join fetch u.airports where u.username = :username")
    Optional<AppUser> findWithAirportsByUsername(@Param("username") String username);

    @Query("select u from AppUser u left join fetch u.airports where u.userId = :userId")
    Optional<AppUser> findWithAirportsByUserId(@Param("userId") UUID userId);

    boolean existsByUsername(String username);
}
