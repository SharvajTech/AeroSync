package com.aerosync.weather;

/**
 * AeroSync's 0-3 operational weather score (0 clear, 1 light, 2 moderate,
 * 3 severe) from OpenWeatherMap condition codes, wind and visibility. The
 * thresholds are the ones the delay model was trained with; this class only
 * moves them out of WeatherService so they can be tested on their own.
 */
public final class WeatherScoring {

    private WeatherScoring() {
    }

    /**
     * @param conditionCode   OpenWeatherMap weather id (800 = clear)
     * @param windSpeedMs     wind speed in m/s; null when not reported
     * @param visibilityM     visibility in metres; null when not reported
     * @param precipitationMm rain plus snow in millimetres; null when not reported
     */
    public static int score(int conditionCode, Double windSpeedMs, Integer visibilityM, Double precipitationMm) {
        double wind = windSpeedMs == null ? 0 : windSpeedMs;
        int visibility = visibilityM == null ? 10_000 : visibilityM;
        double precipitation = precipitationMm == null ? 0 : precipitationMm;

        int score = 0;
        if (conditionCode >= 200 && conditionCode <= 232) {
            score = 3;                                      // thunderstorm
        } else if (conditionCode == 502 || conditionCode == 503 || conditionCode == 504) {
            score = 3;                                      // heavy rain
        } else if (conditionCode == 501 || (conditionCode >= 311 && conditionCode <= 314)) {
            score = 2;                                      // moderate rain
        } else if ((conditionCode >= 300 && conditionCode <= 321) || conditionCode == 500) {
            score = 1;                                      // light rain or drizzle
        } else if (conditionCode >= 600 && conditionCode <= 622) {
            score = precipitation > 1 ? 2 : 1;              // snow
        } else if (conditionCode == 741 || conditionCode == 701) {
            score = 2;                                      // fog or mist
        }

        if (wind > 15) {
            score = Math.max(score, 2);
        } else if (wind > 10) {
            score = Math.max(score, 1);
        }

        if (visibility < 1000) {
            score = Math.max(score, 3);
        } else if (visibility < 3000) {
            score = Math.max(score, 2);
        } else if (visibility < 5000) {
            score = Math.max(score, 1);
        }
        return Math.min(score, 3);
    }

    public static String label(int score) {
        return switch (score) {
            case 3 -> "Severe: operations affected";
            case 2 -> "Moderate: delays likely";
            case 1 -> "Light: minor impact";
            default -> "Clear";
        };
    }
}
