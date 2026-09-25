package com.handwash.service;

import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminCreateUserRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminDisableUserRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminUserGlobalSignOutRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AttributeType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.MessageActionType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UserNotFoundException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UsernameExistsException;

import java.security.SecureRandom;

/** Operator pool accounts: no MFA, created by an ADMIN with a temporary password shown once. */
public final class CognitoOperatorAccountDirectory implements OperatorAccountDirectory {
    private static final String UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final String LOWER = "abcdefghijkmnopqrstuvwxyz";
    private static final String DIGIT = "23456789";
    private static final String SYMBOL = "!#$%*+-=?";
    private final CognitoIdentityProviderClient client;
    private final String userPoolId;
    private final SecureRandom random = new SecureRandom();

    public CognitoOperatorAccountDirectory(String region, String userPoolId) {
        this(CognitoIdentityProviderClient.builder().region(Region.of(region))
            .credentialsProvider(DefaultCredentialsProvider.builder().build())
            .httpClientBuilder(UrlConnectionHttpClient.builder()).build(), userPoolId);
    }

    CognitoOperatorAccountDirectory(CognitoIdentityProviderClient client, String userPoolId) {
        this.client = client;
        this.userPoolId = userPoolId;
    }

    @Override
    public CreatedAccount create(String email) {
        String password = temporaryPassword();
        try {
            var response = client.adminCreateUser(AdminCreateUserRequest.builder()
                .userPoolId(userPoolId).username(email).temporaryPassword(password)
                .messageAction(MessageActionType.SUPPRESS)
                .userAttributes(AttributeType.builder().name("email").value(email).build(),
                    AttributeType.builder().name("email_verified").value("true").build())
                .build());
            String subject = response.user().attributes().stream()
                .filter(attribute -> "sub".equals(attribute.name())).map(AttributeType::value)
                .findFirst().orElseThrow(() -> new UnavailableException("Cognito no devolvió el identificador."));
            return new CreatedAccount(subject, password);
        } catch (UsernameExistsException exists) {
            throw new AlreadyExistsException("Ya existe una cuenta de operador con ese correo.");
        } catch (SdkException failure) {
            throw new UnavailableException("No se pudo crear la cuenta de operador.", failure);
        }
    }

    @Override
    public void disable(String subject) {
        try {
            client.adminDisableUser(AdminDisableUserRequest.builder()
                .userPoolId(userPoolId).username(subject).build());
            client.adminUserGlobalSignOut(AdminUserGlobalSignOutRequest.builder()
                .userPoolId(userPoolId).username(subject).build());
        } catch (UserNotFoundException unknown) {
            // Not an operator-pool account (for example a legacy grant): nothing to disable.
        } catch (SdkException failure) {
            throw new UnavailableException("No se pudo deshabilitar la cuenta de operador.", failure);
        }
    }

    @Override public boolean available() { return true; }

    private String temporaryPassword() {
        StringBuilder password = new StringBuilder();
        for (String alphabet : new String[] {UPPER, LOWER, DIGIT, SYMBOL}) password.append(pick(alphabet));
        String all = UPPER + LOWER + DIGIT + SYMBOL;
        for (int i = 0; i < 12; i++) password.append(pick(all));
        char[] chars = password.toString().toCharArray();
        for (int i = chars.length - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            char swap = chars[i]; chars[i] = chars[j]; chars[j] = swap;
        }
        return new String(chars);
    }

    private char pick(String alphabet) { return alphabet.charAt(random.nextInt(alphabet.length())); }
}
