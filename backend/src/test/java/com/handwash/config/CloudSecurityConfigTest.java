package com.handwash.config;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CloudSecurityConfigTest {
    private final CloudSecurityConfig config = new CloudSecurityConfig();

    @Test
    void acceptsOnlyCognitoAccessTokenForConfiguredClient() {
        Jwt valid = jwt(Map.of("token_use", "access", "client_id", "client-1", "sub", "admin-1"));
        Jwt idToken = jwt(Map.of("token_use", "id", "client_id", "client-1", "sub", "admin-1"));
        Jwt wrongClient = jwt(Map.of("token_use", "access", "client_id", "other", "sub", "admin-1"));

        assertFalse(CloudSecurityConfig.validateCognitoClaims(valid, "client-1").hasErrors());
        assertTrue(CloudSecurityConfig.validateCognitoClaims(idToken, "client-1").hasErrors());
        assertTrue(CloudSecurityConfig.validateCognitoClaims(wrongClient, "client-1").hasErrors());
    }

    @Test
    void mapsOnlyExactAdminAndOperatorGroupsToRoles() {
        var converter = config.cognitoAuthorities("");
        var admin = converter.convert(jwt(Map.of("cognito:groups", List.of("ADMIN", "operator"))));
        var operator = converter.convert(jwt(Map.of("cognito:groups", List.of("OPERATOR"))));
        var lowercase = converter.convert(jwt(Map.of("cognito:groups", List.of("operator"))));

        assertTrue(admin.getAuthorities().stream().anyMatch(authority ->
            "ROLE_ADMIN".equals(authority.getAuthority())));
        assertFalse(operator.getAuthorities().stream().anyMatch(authority ->
            "ROLE_ADMIN".equals(authority.getAuthority())));
        assertTrue(operator.getAuthorities().stream().anyMatch(authority ->
            "ROLE_OPERATOR".equals(authority.getAuthority())));
        assertFalse(lowercase.getAuthorities().stream().anyMatch(authority ->
            authority.getAuthority().startsWith("ROLE_")));
    }

    @Test
    void allowsOnlyConfiguredHumanOrProducerAccessTokenClients() {
        Jwt human = jwt(Map.of("token_use", "access", "client_id", "spa", "sub", "operator-1"));
        Jwt producer = jwt(Map.of("token_use", "access", "client_id", "producer", "sub", "producer"));
        Jwt producerWithUserSubject = jwt(Map.of(
            "token_use", "access", "client_id", "producer", "sub", "operator-1"));
        Jwt unknown = jwt(Map.of("token_use", "access", "client_id", "other", "sub", "other-1"));

        assertFalse(CloudSecurityConfig.validateCognitoClaims(human, "spa", "producer").hasErrors());
        assertFalse(CloudSecurityConfig.validateCognitoClaims(producer, "spa", "producer").hasErrors());
        assertTrue(CloudSecurityConfig.validateCognitoClaims(
            producerWithUserSubject, "spa", "producer").hasErrors());
        assertTrue(CloudSecurityConfig.validateCognitoClaims(unknown, "spa", "producer").hasErrors());
    }

    @Test
    void operatorPoolTokensNeverGetAdminAndNeedTheOperatorClient() throws Exception {
        String operatorIssuer = "https://cognito-idp.sa-east-1.amazonaws.com/operators";
        var converter = config.cognitoAuthorities(operatorIssuer);
        Jwt spoofed = Jwt.withTokenValue("token").header("alg", "none")
            .issuer(operatorIssuer).claim("cognito:groups", List.of("ADMIN")).subject("op-1").build();

        var authorities = converter.convert(spoofed).getAuthorities();
        assertFalse(authorities.stream().anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority())));
        assertTrue(authorities.stream().anyMatch(a -> "ROLE_OPERATOR".equals(a.getAuthority())));

        Jwt valid = jwt(Map.of("token_use", "access", "client_id", "op-client", "sub", "op-1"));
        Jwt otherClient = jwt(Map.of("token_use", "access", "client_id", "spa", "sub", "op-1"));
        Jwt idToken = jwt(Map.of("token_use", "id", "client_id", "op-client", "sub", "op-1"));
        assertFalse(CloudSecurityConfig.validateOperatorClaims(valid, "op-client").hasErrors());
        assertTrue(CloudSecurityConfig.validateOperatorClaims(otherClient, "op-client").hasErrors());
        assertTrue(CloudSecurityConfig.validateOperatorClaims(idToken, "op-client").hasErrors());
    }

    private static Jwt jwt(Map<String, Object> claims) {
        var builder = Jwt.withTokenValue("test-token")
            .header("alg", "none")
            .issuedAt(Instant.now().minusSeconds(5))
            .expiresAt(Instant.now().plusSeconds(60))
            .subject("admin-1")
            .issuer("https://cognito-idp.sa-east-1.amazonaws.com/sa-east-1_AbCdEf123");
        claims.forEach(builder::claim);
        return builder.build();
    }
}
