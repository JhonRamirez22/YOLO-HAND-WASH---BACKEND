package com.handwash;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.util.Arrays;
import java.util.stream.Stream;

@SpringBootApplication
public class HandWashApplication {
    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(HandWashApplication.class);
        application.setHeadless(!desktopProfileActive(
            args,
            System.getenv("SPRING_PROFILES_ACTIVE"),
            System.getProperty("spring.profiles.active")));
        application.run(args);
    }

    static boolean desktopProfileActive(String[] args, String environmentProfiles,
                                        String systemProfiles) {
        return hasDesktopProfile(environmentProfiles)
            || hasDesktopProfile(systemProfiles)
            || Arrays.stream(args)
                .filter(argument -> argument.startsWith("--spring.profiles.active="))
                .map(argument -> argument.substring(argument.indexOf('=') + 1))
                .anyMatch(HandWashApplication::hasDesktopProfile);
    }

    private static boolean hasDesktopProfile(String profiles) {
        if (profiles == null || profiles.isBlank()) return false;
        return Stream.of(profiles.split(","))
            .map(String::trim)
            .anyMatch(profile -> profile.equals("station") || profile.equals("station-demo")
                || profile.equals("diagnostics"));
    }
}
