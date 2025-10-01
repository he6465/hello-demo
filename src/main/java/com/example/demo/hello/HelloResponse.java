package com.example.demo.hello;

import java.time.ZonedDateTime;

public class HelloResponse {
    private final ZonedDateTime kTime;
    private final long timestamp;
    private final String message;

    public HelloResponse(ZonedDateTime koreaTime, long timestamp, String message) {
        this.kTime = koreaTime;
        this.timestamp = timestamp;
        this.message = message;
    }

    public ZonedDateTime getKoreaTime() {
        return kTime;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public String getMessage() {
        return message;
    }

}
