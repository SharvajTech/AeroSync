package com.aerosync.graph;

import com.aerosync.model.DependencyType;

/** Relationship (edge) types of the operational graph. */
public enum RelationshipType {
    /** airport:XXX -> flight:id. The flight departs from the airport. */
    DEPARTS_FROM(EdgeCategory.ROUTE),
    /** flight:id -> airport:XXX. The flight arrives at the airport. */
    ARRIVES_AT(EdgeCategory.ROUTE),
    AIRCRAFT_ROTATION(EdgeCategory.DEPENDENCY),
    PASSENGER_CONNECTION(EdgeCategory.DEPENDENCY),
    CREW_ROTATION(EdgeCategory.DEPENDENCY),
    OPERATIONAL_CONSTRAINT(EdgeCategory.DEPENDENCY);

    private final EdgeCategory category;

    RelationshipType(EdgeCategory category) {
        this.category = category;
    }

    public EdgeCategory category() {
        return category;
    }

    public static RelationshipType of(DependencyType dependencyType) {
        return switch (dependencyType) {
            case AIRCRAFT_ROTATION -> AIRCRAFT_ROTATION;
            case PASSENGER_CONNECTION -> PASSENGER_CONNECTION;
            case CREW_ROTATION -> CREW_ROTATION;
            case OPERATIONAL_CONSTRAINT -> OPERATIONAL_CONSTRAINT;
        };
    }
}
