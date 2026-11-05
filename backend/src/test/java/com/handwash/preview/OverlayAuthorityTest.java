package com.handwash.preview;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

class OverlayAuthorityTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void javaProgressReplacesTheGuessOfThePc() throws Exception {
        String merged = OverlayAuthority.withProgress("{\"seq\":3,\"hands\":[],\"cur\":0,\"exp\":1,\"err\":true}", new SessionProgress(2, 3));
        JsonNode node = JSON.readTree(merged);
        assertThat(node.get("cur").asInt()).isEqualTo(2);
        assertThat(node.get("exp").asInt()).isEqualTo(3);
        assertThat(node.get("err").asBoolean()).isTrue();
        assertThat(node.get("seq").asInt()).isEqualTo(3);
    }

    @Test
    void anOverlayThatIsNotAnObjectPassesThroughUntouched() {
        assertThat(OverlayAuthority.withProgress("{broken", new SessionProgress(1, 2))).isEqualTo("{broken");
    }

    @Test
    void theHintForThePcCarriesOnlyTheProgress() throws Exception {
        JsonNode hint = JSON.readTree(OverlayAuthority.hint(new SessionProgress(4, 5))).get("hint");
        assertThat(hint.get("cur").asInt()).isEqualTo(4);
        assertThat(hint.get("exp").asInt()).isEqualTo(5);
    }

    @Test
    void noSessionMeansNothingDoneYetAndTheFirstStepNext() {
        assertThat(SessionProgress.NONE).isEqualTo(new SessionProgress(0, 1));
    }
}
