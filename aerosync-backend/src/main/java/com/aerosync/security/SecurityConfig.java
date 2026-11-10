package com.aerosync.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

/**
 * Security beans. Authentication itself is {@link JwtFilter}; there is no
 * Spring Security filter chain (only its crypto module is used, for BCrypt).
 */
@Configuration
public class SecurityConfig implements WebMvcConfigurer {

    @Bean
    public PasswordEncoder passwordEncoder(@Value("${aerosync.security.bcrypt-strength:12}") int strength) {
        return new BCryptPasswordEncoder(strength);
    }

    @Bean
    public LoginAttemptLimiter loginAttemptLimiter(
            @Value("${aerosync.security.login.max-failures:5}") int maxFailures,
            @Value("${aerosync.security.login.lockout-minutes:15}") long lockoutMinutes) {
        return new LoginAttemptLimiter(maxFailures, Duration.ofMinutes(lockoutMinutes), Clock.systemUTC());
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new CurrentUserArgumentResolver());
    }
}
