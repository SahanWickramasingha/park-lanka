package lk.sahan.parking.config;

import lk.sahan.parking.repo.CashierRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {
    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }

    @Bean UserDetailsService users(@Value("${parking.admin-username}") String adminUsername,
                                  @Value("${parking.admin-password}") String adminPassword,
                                  PasswordEncoder encoder, CashierRepository cashiers) {
        if (adminUsername == null || !adminUsername.matches("[a-z][a-z0-9_.-]{2,31}")
                || adminPassword == null || adminPassword.startsWith("${")
                || adminPassword.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 10
                || adminPassword.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72)
            throw new IllegalArgumentException("Set valid PARKING_ADMIN_USER and PARKING_ADMIN_PASSWORD (10-72 UTF-8 bytes)");
        if (cashiers.findAll().stream().anyMatch(c -> c.getUsername().equalsIgnoreCase(adminUsername)))
            throw new IllegalArgumentException("Configured admin username conflicts with a database cashier (including archived accounts). Choose a different admin username.");
        String adminHash = encoder.encode(adminPassword);
        return name -> {
            if (name.equals(adminUsername))
                return User.withUsername(adminUsername).password(adminHash).roles("ADMIN").build();
            return cashiers.findByUsername(name)
                    .map(c -> User.withUsername(c.getUsername()).password(c.getPasswordHash())
                            .roles("CASHIER").disabled(!c.canAccess()).build())
                    .orElseThrow(() -> new UsernameNotFoundException("Unknown cashier"));
        };
    }

    @Bean org.springframework.boot.web.servlet.FilterRegistrationBean<lk.sahan.parking.service.MaintenanceGuard> maintenanceRegistration(lk.sahan.parking.service.MaintenanceGuard guard){var registration=new org.springframework.boot.web.servlet.FilterRegistrationBean<>(guard);registration.setEnabled(false);return registration;}
    @Bean SecurityFilterChain security(HttpSecurity http,lk.sahan.parking.service.MaintenanceGuard guard) throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable())
                .authorizeHttpRequests(auth -> auth.requestMatchers(HttpMethod.OPTIONS, "/**").denyAll()
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/rates", "/api/rates/**").hasRole("ADMIN")
                        .requestMatchers("/api/checkouts/**").hasRole("CASHIER")
                        .requestMatchers("/api/shifts", "/api/shifts/**").hasRole("CASHIER")
                        .requestMatchers("/api/parking-history/**").hasRole("CASHIER")
                        .requestMatchers("/api/slots", "/api/sessions", "/api/sessions/**", "/api/receipts", "/api/receipts/**", "/api/reports/**", "/api/messages", "/api/messages/**").hasRole("CASHIER")
                        .requestMatchers("/api/me").authenticated()
                        .requestMatchers("/api/notifications", "/api/notifications/**", "/api/conversations", "/api/conversations/**", "/api/conversation-messages").hasAnyRole("ADMIN", "CASHIER")
                        .anyRequest().denyAll())
                .httpBasic(Customizer.withDefaults())
                .addFilterAfter(guard,org.springframework.security.web.access.intercept.AuthorizationFilter.class)
                .build();
    }
}
