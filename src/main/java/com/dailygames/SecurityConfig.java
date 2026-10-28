package com.dailygames;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;
import java.util.Arrays;

@Configuration
class SecurityConfig {
  @Bean
  SecurityFilterChain securityFilterChain(HttpSecurity http, SessionAuthenticationFilter sessionFilter,
                                          OriginValidationFilter originFilter) throws Exception {
    return http
        .csrf(csrf -> csrf.disable())
        .cors(cors -> cors.disable())
        .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .exceptionHandling(errors -> errors.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
        .authorizeHttpRequests(auth -> auth
            .requestMatchers("/api/health", "/actuator/health").permitAll()
            .requestMatchers(HttpMethod.POST, "/api/v1/auth/magic-link").permitAll()
            .requestMatchers(HttpMethod.GET, "/api/v1/auth/magic-link/consume").permitAll()
            .anyRequest().authenticated())
        .addFilterBefore(originFilter, UsernamePasswordAuthenticationFilter.class)
        .addFilterBefore(sessionFilter, UsernamePasswordAuthenticationFilter.class)
        .build();
  }
}

@Component
class SessionAuthenticationFilter extends OncePerRequestFilter {
  private final AuthService authService;
  SessionAuthenticationFilter(AuthService authService) { this.authService = authService; }

  @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String token = request.getCookies() == null ? null : Arrays.stream(request.getCookies())
        .filter(cookie -> AuthService.COOKIE_NAME.equals(cookie.getName())).map(Cookie::getValue).findFirst().orElse(null);
    authService.authenticate(token).ifPresent(user -> SecurityContextHolder.getContext().setAuthentication(
        new UsernamePasswordAuthenticationToken(user, token, java.util.List.of())));
    chain.doFilter(request, response);
  }
}

@Component
class OriginValidationFilter extends OncePerRequestFilter {
  private final String expectedOrigin;
  OriginValidationFilter(@Value("${app.public-base-url}") String publicBaseUrl) {
    URI uri = URI.create(publicBaseUrl);
    expectedOrigin = uri.getScheme() + "://" + uri.getAuthority();
  }

  @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String method = request.getMethod();
    String origin = request.getHeader("Origin");
    if (!(method.equals("GET") || method.equals("HEAD") || method.equals("OPTIONS")) && origin != null && !origin.equals(expectedOrigin)) {
      response.sendError(403, "Invalid request origin");
      return;
    }
    chain.doFilter(request, response);
  }
}
