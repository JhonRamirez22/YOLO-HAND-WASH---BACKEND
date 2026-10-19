package com.handwash.config;

import com.handwash.security.ClientAddressFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;

@Configuration
@Profile("cloud")
public class ClientAddressConfig {
    /** CloudFront adds the viewer and the ALB adds CloudFront's edge: two trusted hops. */
    @Bean
    FilterRegistrationBean<ClientAddressFilter> clientAddressFilter(
        @Value("${handwash.security.trusted-proxy-hops:2}") int trustedHops) {
        FilterRegistrationBean<ClientAddressFilter> registration = new FilterRegistrationBean<>(new ClientAddressFilter(trustedHops));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
