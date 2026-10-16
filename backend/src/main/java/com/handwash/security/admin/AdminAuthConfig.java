package com.handwash.security.admin;

import com.handwash.service.SessionAuditService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.Base64;

@Configuration
@Profile("onprem")
public class AdminAuthConfig {
    @Bean
    AdminAccountRepository adminAccountRepository(JdbcTemplate jdbc) { return new AdminAccountRepository(jdbc); }

    @Bean
    AdminAuthService adminAuthService(AdminAccountRepository accounts, SessionAuditService audit,
                                      @Value("${handwash.admin.totp-key:}") String totpKeyBase64,
                                      @Value("${handwash.admin.require-totp:false}") boolean requireTotp) {
        byte[] key;
        try {
            key = Base64.getDecoder().decode(totpKeyBase64);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalStateException("HANDWASH_ADMIN_TOTP_KEY debe ser Base64 de 32 bytes");
        }
        return new AdminAuthService(accounts, new BCryptPasswordEncoder(12), new TotpSecretVault(key), audit, System::currentTimeMillis, requireTotp);
    }

    /** Creates the first ADMIN from the installer's one-time variables when the table is empty; later starts do nothing. */
    @Bean
    ApplicationRunner bootstrapFirstAdmin(AdminAuthService auth,
                                          @Value("${handwash.admin.bootstrap-username:}") String username,
                                          @Value("${handwash.admin.bootstrap-password:}") String password) {
        return args -> { if (!username.isBlank() && !password.isBlank()) auth.bootstrap(username, password); };
    }
}
