package com.example.demo.hello;

import java.time.ZoneId;
import java.time.ZonedDateTime;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HelloCtrl {

    @GetMapping("/hello")
    public HelloResponse getHello() {
        ZonedDateTime seoulTime = ZonedDateTime.now(ZoneId.of("Asia/Seoul"));

        return new HelloResponse(
                seoulTime,
                System.currentTimeMillis(), // 현재 시간을 Unix 타임스탬프(밀리초)로 가져옵니다.
                "Hello, World!");
    }
}
