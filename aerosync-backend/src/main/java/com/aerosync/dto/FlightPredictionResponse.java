package com.aerosync.dto;

import lombok.Data;

@Data
public class FlightPredictionResponse {
    private String  flightId;
    private Double  delayProbability;
    private String  riskLevel;
    private Boolean predictedDelayed;
    private Double  confidence;
    private String  modelUsed;
    private Integer featuresUsed;
}