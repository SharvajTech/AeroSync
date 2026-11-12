package com.aerosync.weather;

/** The weather source could not be read. The message never contains the API key or the request URL. */
public class WeatherUnavailableException extends RuntimeException {

    public WeatherUnavailableException(String message) {
        super(message);
    }
}
