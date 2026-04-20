package com.aerosync.dto;

import lombok.Data;

@Data
public class FlightPredictionRequest {
    private String  flightId;
    private Integer departureHour;
    private Integer dayOfWeek;
    private Integer month;
    private String  originAirport;
    private String  destinationAirport;
    private Double  routeDistanceKm;
    private Integer weatherScore;
    private Double  crewDutyHours;
    private Double  historicalDelayRate;
    private Integer aircraftAge;
    private Integer gateTurnaroundMins;
    private String  aircraftType;
}