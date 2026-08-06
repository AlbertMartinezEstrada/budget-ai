package com.budgetai.backend.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            // El CORS el configura CorsConfig; aquí només s'activa perquè la
            // cadena de seguretat el respecti.
            .cors(cors -> {})

            // Sense estat: la sessió és el token de la cookie, no una sessió
            // del servidor. La protecció CSRF de Spring no aplica; el que
            // protegeix aquí és SameSite=Strict a la cookie.
            .csrf(csrf -> csrf.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

            .authorizeHttpRequests(auth -> auth
                    // Les preflight de CORS no porten cookie i han de passar.
                    .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                    // Iniciar i tancar sessió són els únics punts oberts.
                    .requestMatchers("/auth/login", "/auth/logout").permitAll()
                    // Tota la resta de l'API queda tancada. Abans qualsevol
                    // que arribés al port 8000 tenia accés complet a les
                    // dades financeres.
                    .anyRequest().authenticated())

            // Sense aquesta línia, Spring respondria amb una redirecció al
            // formulari de login, que per a una API vol dir que el frontend
            // rebria un 200 amb HTML en comptes d'un 401.
            .exceptionHandling(e -> e
                    .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))

            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
