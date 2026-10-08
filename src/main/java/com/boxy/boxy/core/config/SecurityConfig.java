package com.boxy.boxy.core.config;

import com.boxy.boxy.core.security.AuthRateLimitFilter;
import com.boxy.boxy.core.security.CustomUserDetailsService;
import com.boxy.boxy.core.security.JwtAuthFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;
    private final AuthRateLimitFilter authRateLimitFilter;
    private final CustomUserDetailsService userDetailsService;
    private final CorsConfig corsConfig;

    @Value("${springdoc.api-docs.enabled:false}")
    private boolean swaggerEnabled;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsConfig.corsConfigurationSource()))
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(headers -> headers
                        // This is a JSON API with no server-rendered HTML views, so nothing needs to
                        // load scripts/styles/frames from anywhere — deny everything by default.
                        .contentSecurityPolicy(csp -> csp.policyDirectives(
                                "default-src 'none'; frame-ancestors 'none'; base-uri 'none'"))
                        .frameOptions(frame -> frame.deny())
                        .contentTypeOptions(contentType -> {})
                        .referrerPolicy(referrer -> referrer.policy(
                                org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                        .httpStrictTransportSecurity(hsts -> hsts
                                .includeSubDomains(true)
                                .maxAgeInSeconds(31_536_000))
                )
                .authorizeHttpRequests(auth -> {
                    auth.requestMatchers("/api/v1/auth/login", "/api/v1/auth/refresh", "/api/v1/auth/logout").permitAll();
                    // The WebSocket handshake can't carry an Authorization header from a browser, so it is
                    // anonymous here; StompAuthChannelInterceptor authenticates the STOMP CONNECT frame
                    // (and RealtimeSessionRegistry drops sockets that never do).
                    auth.requestMatchers("/ws/**").permitAll();
                    // Anonymous storefront (PublicCatalogController): read-only, GET only. Anything new
                    // under /api/v1/public must be safe for an unauthenticated caller.
                    auth.requestMatchers(HttpMethod.GET, "/api/v1/public/**").permitAll();
                    if (swaggerEnabled) {
                        auth.requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll();
                    }
                    auth.anyRequest().authenticated();
                })
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, authException) -> {
                            response.setContentType("application/json");
                            response.setStatus(jakarta.servlet.http.HttpServletResponse.SC_UNAUTHORIZED);
                            response.getWriter().write("{\"code\":\"UNAUTHORIZED\",\"message\":\"Full authentication is required to access this resource.\",\"status\":401}");
                        })
                        .accessDeniedHandler((request, response, accessDeniedException) -> {
                            response.setContentType("application/json");
                            response.setStatus(jakarta.servlet.http.HttpServletResponse.SC_FORBIDDEN);
                            response.getWriter().write("{\"code\":\"FORBIDDEN\",\"message\":\"You do not have the required permissions to perform this action.\",\"status\":403}");
                        })
                )
                .authenticationProvider(authenticationProvider())
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(authRateLimitFilter, JwtAuthFilter.class);

        return http.build();
    }

    @Bean
    public AuthenticationProvider authenticationProvider() {
        DaoAuthenticationProvider authProvider = new DaoAuthenticationProvider();
        authProvider.setUserDetailsService(userDetailsService);
        authProvider.setPasswordEncoder(passwordEncoder());
        return authProvider;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        // Cost factor 12 (Spring's own default is 10) — BCrypt reads the cost back out of the
        // stored hash itself, so this only affects new encodes; existing hashes still verify.
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }
}
