package it.chewbecca.youtubeanalytics.config;

import it.chewbecca.youtubeanalytics.security.ApiKeyAuthenticationFilter;
import it.chewbecca.youtubeanalytics.chatgpt.ChatGptBridgeAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;

@Configuration
public class SecurityConfig {

    /** Evita una seconda esecuzione del filtro come servlet filter fuori da Spring Security. */
    @Bean
    FilterRegistrationBean<ChatGptBridgeAuthenticationFilter> chatGptBridgeFilterRegistration(
            ChatGptBridgeAuthenticationFilter filter) {
        FilterRegistrationBean<ChatGptBridgeAuthenticationFilter> bean = new FilterRegistrationBean<>(filter);
        bean.setEnabled(false);
        return bean;
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    UserDetailsService userDetailsService(AppSecurityProperties properties, PasswordEncoder passwordEncoder) {
        return new InMemoryUserDetailsManager(
                User.withUsername(properties.adminUsername())
                        .password(passwordEncoder.encode(properties.adminPassword()))
                        .roles("ADMIN")
                        .build()
        );
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
                                            ApiKeyAuthenticationFilter apiKeyAuthenticationFilter,
                                            ChatGptBridgeAuthenticationFilter chatGptBridgeAuthenticationFilter) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/oauth2/google/callback",
                                "/oauth2/meta/callback",
                                "/api/integrations/telegram/webhook",
                                "/actuator/health/**",
                                "/privacy-policy.html",
                                "/openapi-gpt.yaml",
                                "/swagger-ui.html",
                                "/swagger-ui/**",
                                "/v3/api-docs/**"
                        ).permitAll()
                        .requestMatchers("/internal/chatgpt/**").hasRole("CHATGPT_PLUGIN")
                        .requestMatchers("/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/v1/**").hasRole("GPT")
                        .anyRequest().permitAll()
                )
                .httpBasic(Customizer.withDefaults())
                .addFilterBefore(apiKeyAuthenticationFilter, BasicAuthenticationFilter.class)
                .addFilterBefore(chatGptBridgeAuthenticationFilter, BasicAuthenticationFilter.class)
                .build();
    }
}
