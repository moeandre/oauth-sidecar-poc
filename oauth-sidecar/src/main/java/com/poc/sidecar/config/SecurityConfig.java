package com.poc.sidecar.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProvider;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import jakarta.servlet.http.HttpServletRequest;

import java.util.Arrays;
import java.util.List;

@Configuration
public class SecurityConfig {

    @Value("${spring.security.oauth2.client.provider.keycloak.issuer-uri}")
    private String issuerUrl;

    // "*" por padrao (dev). Em producao, defina SIDECAR_CORS_ALLOWED_ORIGINS
    // com a(s) origem(ns) real(is) do(s) front-end(s) - nunca "*" numa API
    // autenticada exposta publicamente.
    @Value("${sidecar.cors.allowed-origins:*}")
    private String allowedOriginsCsv;

    // Porta separada de health/metrics/prometheus (application.yml,
    // management.server.port) - IMPORTANTE: nesta versao do Boot, uma porta
    // de management diferente NAO cria um contexto de seguranca separado
    // (verificado empiricamente: sem isto, /actuator/prometheus na 9090
    // caia no mesmo login OAuth2 do resto do app, o que quebraria qualquer
    // scraper). O match abaixo libera "/actuator/**" so quando a requisicao
    // chega por ESSA porta - continua exigindo login se, por engano, alguem
    // publicar/alcancar a 9090 pela porta errada... o inverso tambem vale:
    // /actuator/** continua protegido na 8082 (a porta publicada no host).
    @Value("${management.server.port:9090}")
    private int managementPort;

    /**
     * Qualquer chamada de negocio que chegue ao sidecar exige login OAuth2/OIDC
     * valido; health/metrics (porta separada, ver "isActuatorOnManagementPort")
     * ficam de fora. Se nao houver sessao autenticada, o
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
                .requestMatchers(this::isActuatorOnManagementPort).permitAll()
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

    private boolean isActuatorOnManagementPort(HttpServletRequest request) {
        return request.getLocalPort() == managementPort && request.getRequestURI().startsWith("/actuator");
    }

    @Bean
    public OAuth2AuthorizationRequestResolver stepUpResolver(ClientRegistrationRepository clientRegistrationRepository) {
        DefaultOAuth2AuthorizationRequestResolver defaultResolver =
                new DefaultOAuth2AuthorizationRequestResolver(clientRegistrationRepository, "/oauth2/authorization");
        return new StepUpAuthorizationRequestResolver(defaultResolver);
    }

    /**
     * Sem isso, o ProxyController usava {@link OAuth2AuthorizedClientRepository}
     * direto: ele so LE o authorized client da sessao, nunca renova. Um
     * access token expirado ficaria "preso" (ou o request cai sempre no
     * step-up mesmo com o usuario ja tendo aprovado o escopo antes). Com o
     * manager + o provider "refreshToken()", um token expirado e renovado
     * automaticamente via refresh_token (chamada ao Keycloak) na hora do
     * "authorize()" - so quando de fato ja expirou (checagem local, sem custo
     * de rede na maioria das chamadas); sem refresh_token valido, cai de
     * volta pro fluxo normal de reautorizacao.
     */
    @Bean
    public OAuth2AuthorizedClientManager authorizedClientManager(
            ClientRegistrationRepository clientRegistrationRepository,
            OAuth2AuthorizedClientRepository authorizedClientRepository) {

        OAuth2AuthorizedClientProvider authorizedClientProvider = OAuth2AuthorizedClientProviderBuilder.builder()
                .authorizationCode()
                .refreshToken()
                .build();

        DefaultOAuth2AuthorizedClientManager manager =
                new DefaultOAuth2AuthorizedClientManager(clientRegistrationRepository, authorizedClientRepository);
        manager.setAuthorizedClientProvider(authorizedClientProvider);
        return manager;
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

        List<String> allowedOrigins = Arrays.stream(allowedOriginsCsv.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .toList();
        config.setAllowedOrigins(allowedOrigins);

        // Permite qualquer método
        config.setAllowedMethods(List.of("*"));

        // Permite qualquer header
        config.setAllowedHeaders(List.of("*"));

        // IMPORTANTE: com origin "*", credentials tem que ser false (o browser
        // recusa a combinacao). Como a autenticacao aqui e via cookie de sessao,
        // origin "*" na pratica so libera respostas que nao dependem de sessao
        // pra quem le via fetch/XHR cross-origin - mesmo assim, em producao
        // prefira sempre restringir a origens conhecidas (ver
        // sidecar.cors.allowed-origins) em vez de depender so disso.
        config.setAllowCredentials(!allowedOrigins.contains("*"));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);

        return source;
    }
}
