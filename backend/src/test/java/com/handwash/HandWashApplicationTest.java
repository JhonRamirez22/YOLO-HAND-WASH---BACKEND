package com.handwash;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HandWashApplicationTest {

    @Test
    void enablesAwtForLocalStationProfilesFromLauncherEnvironment() {
        assertTrue(HandWashApplication.desktopProfileActive(new String[0], "diagnostics", null));
        assertTrue(HandWashApplication.desktopProfileActive(
            new String[0], "station-demo", null));
        assertTrue(HandWashApplication.desktopProfileActive(
            new String[0], "local, station", null));
    }

    @Test
    void recognizesStationProfilePassedAsSpringArgumentOrSystemProperty() {
        assertTrue(HandWashApplication.desktopProfileActive(
            new String[] {"--spring.profiles.active=station-demo"}, null, null));
        assertTrue(HandWashApplication.desktopProfileActive(
            new String[0], null, "station"));
    }

    @Test
    void keepsOrdinaryBackendProfilesHeadlessAndMatchesProfileNamesExactly() {
        assertFalse(HandWashApplication.desktopProfileActive(
            new String[0], "local", null));
        assertFalse(HandWashApplication.desktopProfileActive(
            new String[0], "not-station-demo", null));
        assertFalse(HandWashApplication.desktopProfileActive(
            new String[] {"--spring.profiles.active=local"}, null, null));
    }
}
