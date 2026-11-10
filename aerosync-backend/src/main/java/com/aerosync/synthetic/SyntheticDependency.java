package com.aerosync.synthetic;

import com.aerosync.model.DependencyType;

import java.util.Map;
import java.util.UUID;

public record SyntheticDependency(
        UUID dependencyId,
        UUID sourceFlightId,
        UUID targetFlightId,
        DependencyType type,
        double impactWeight,
        Integer minimumConnectionMinutes,
        Map<String, Object> metadata) {
}
