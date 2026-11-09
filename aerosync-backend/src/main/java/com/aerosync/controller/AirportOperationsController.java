package com.aerosync.controller;

import com.aerosync.dto.operations.AirportDto;
import com.aerosync.dto.operations.AirportOperationsResponse;
import com.aerosync.service.AirportOperationsService;
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

    // GET /api/airports
    @GetMapping
    public ResponseEntity<List<AirportDto>> airports() {
        return ResponseEntity.ok(operationsService.listAirports());
    }

    // GET /api/airports/BOM/operations?date=2026-11-09
    @GetMapping("/{iata}/operations")
    public ResponseEntity<AirportOperationsResponse> operations(
            @PathVariable("iata") String iata,
            @RequestParam(name = "date", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ResponseEntity.ok(operationsService.getOperations(iata, date));
    }
}
