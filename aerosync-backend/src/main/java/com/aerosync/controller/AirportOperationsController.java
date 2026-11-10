package com.aerosync.controller;

import com.aerosync.dto.operations.AirportDto;
import com.aerosync.dto.operations.AirportOperationsResponse;
import com.aerosync.service.AirportOperationsService;
import com.aerosync.security.AuthenticatedUser;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/airports")
@RequiredArgsConstructor
@CrossOrigin(origins = {"http://localhost:3000"})
public class AirportOperationsController {

    private final AirportOperationsService operationsService;

    // GET /api/airports — the signed-in user's airports
    @GetMapping
    public ResponseEntity<List<AirportDto>> airports(AuthenticatedUser user) {
        return ResponseEntity.ok(operationsService.listAirports(user.scope()));
    }

    // GET /api/airports/BOM/operations?date=2026-11-09
    @GetMapping("/{iata}/operations")
    public ResponseEntity<AirportOperationsResponse> operations(
            AuthenticatedUser user,
            @PathVariable("iata") String iata,
            @RequestParam(name = "date", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ResponseEntity.ok(operationsService.getOperations(iata, date, user.scope()));
    }
}
