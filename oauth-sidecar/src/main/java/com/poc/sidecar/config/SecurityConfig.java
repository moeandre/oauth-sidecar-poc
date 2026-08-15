package com.poc.sidecar.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
public class SecurityConfig {

    @Value("${spring.security.oauth2.client.provider.keycloak.issuer-uri}")
    private String issuerUrl;

    /**
     * Qualquer chamada que chegue ao sidecar (exceto health) exige um login
     * OAuth2/OIDC valido. Se nao houver sessao autenticada, o
     * RouteAwareAuthenticationEntryPoint decide, pelo path pedido, para qual
     * client-id (por microservico) redirecionar - isso e o "iniciar o
     * processo de oauth" para quem nunca autenticou. A checagem fina de
     * escopo/authorized-client por rota fica no ProxyController (que tambem
     * cobre o caso de trocar de rota/client dentro de uma sessao ja logada).
     */
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                            ClientRegistrationRepository clientRegistrationRepository,
                                            SidecarProperties sidecarProperties) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health").permitAll()
                .anyRequest().authenticated())
            .oauth2Login(oauth -> oauth
                .authorizationEndpoint(endpoint ->
                        endpoint.authorizationRequestResolver(stepUpResolver(clientRegistrationRepository))))
            .exceptionHandling(exceptions -> exceptions
                    .authenticationEntryPoint(new RouteAwareAuthenticationEntryPoint(sidecarProperties)))
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .logout(logout -> logout
                .logoutSuccessHandler(oidcLogoutSuccessHandler())
            )
            // CSRF desabilitado apenas para simplificar os testes via curl/Postman nesta PoC.
            // Em producao, mantenha o CSRF habilitado ou use um token de servico dedicado.
            .csrf(csrf -> csrf.disable());

        return http.build();
    }

    @Bean
    public OAuth2AuthorizationRequestResolver stepUpResolver(ClientRegistrationRepository clientRegistrationRepository) {
        DefaultOAuth2AuthorizationRequestResolver defaultResolver =
                new DefaultOAuth2AuthorizationRequestResolver(clientRegistrationRepository, "/oauth2/authorization");
        return new StepUpAuthorizationRequestResolver(defaultResolver);
    }

    @Bean
    public LogoutSuccessHandler oidcLogoutSuccessHandler() {
        return (request, response, authentication) -> {

            if (authentication != null && authentication.getPrincipal() instanceof OidcUser oidcUser) {

                String idToken = oidcUser.getIdToken().getTokenValue();

                String logoutUrl = issuerUrl + "/protocol/openid-connect/logout" +
                                "?id_token_hint=" + idToken +
                                "&post_logout_redirect_uri=http://localhost:8082";

                response.sendRedirect(logoutUrl);
            } else {
                response.sendRedirect("/");
            }
        };
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();

        // Permite qualquer origem
        config.setAllowedOrigins(List.of("*"));

        // Permite qualquer método
        config.setAllowedMethods(List.of("*"));

        // Permite qualquer header
        config.setAllowedHeaders(List.of("*"));

        // IMPORTANTE: quando origins = "*", credentials deve ser false
        config.setAllowCredentials(false);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);

        return source;
    }
}
