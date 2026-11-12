package com.aerosync.ingestion.opensky;

import java.util.Locale;

/** WGS-84 bounding box of a {@code /states/all} query. */
public record BoundingBox(double minLatitude, double minLongitude, double maxLatitude, double maxLongitude) {

    public BoundingBox {
        if (!(minLatitude >= -90 && maxLatitude <= 90 && minLatitude < maxLatitude)) {
            throw new IllegalArgumentException("Latitude range must be within -90..90 and min < max");
        }
        if (!(minLongitude >= -180 && maxLongitude <= 180 && minLongitude < maxLongitude)) {
            throw new IllegalArgumentException("Longitude range must be within -180..180 and min < max");
        }
    }

    /** Parses {@code "minLat,minLon,maxLat,maxLon"}. */
    public static BoundingBox parse(String csv) {
        String[] parts = csv.split(",");
        if (parts.length != 4) {
            throw new IllegalArgumentException(
                    "A bounding box is minLat,minLon,maxLat,maxLon, was '" + csv + "'");
        }
        try {
            return new BoundingBox(Double.parseDouble(parts[0].trim()), Double.parseDouble(parts[1].trim()),
                    Double.parseDouble(parts[2].trim()), Double.parseDouble(parts[3].trim()));
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(
                    "A bounding box is minLat,minLon,maxLat,maxLon, was '" + csv + "'");
        }
    }

    /** Area in square degrees, which is how OpenSky prices a states request. */
    public double areaSquareDegrees() {
        return (maxLatitude - minLatitude) * (maxLongitude - minLongitude);
    }

    /**
     * API credits one {@code /states/all} request for this box costs, per the
     * OpenSky documentation: up to 25 square degrees 1, up to 100 2, up to
     * 400 3, larger 4.
     */
    public int creditCost() {
        double area = areaSquareDegrees();
        if (area <= 25) {
            return 1;
        }
        if (area <= 100) {
            return 2;
        }
        return area <= 400 ? 3 : 4;
    }

    String toQuery() {
        return String.format(Locale.ROOT, "lamin=%.4f&lomin=%.4f&lamax=%.4f&lomax=%.4f",
                minLatitude, minLongitude, maxLatitude, maxLongitude);
    }
}
