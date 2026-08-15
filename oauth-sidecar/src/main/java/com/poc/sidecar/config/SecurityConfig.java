package com.poc.sidecar.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {

    /**
     * Qualquer chamada que chegue ao sidecar (exceto health) exige um login
     * OAuth2/OIDC valido. Se nao houver sessao autenticada, o Spring Security
     * ja redireciona automaticamente para o Keycloak (fluxo Authorization Code) -
     * isso e o "iniciar o processo de oauth" para quem nunca autenticou.
     * A checagem fina de escopo por rota/metodo fica no ProxyController.
     */
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, ClientRegistrationRepository clientRegistrationRepository) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health").permitAll()
                .anyRequest().authenticated())
            .oauth2Login(oauth -> oauth
                .authorizationEndpoint(endpoint ->
                        endpoint.authorizationRequestResolver(stepUpResolver(clientRegistrationRepository))))
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
}
