package com.poc.sidecar.proxy;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.annotation.RegisteredOAuth2AuthorizedClient;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.util.Set;

/**
 * Unico ponto de entrada exposto para o mundo externo.
 *
 * Regra de negocio da PoC:
 *  - GET/HEAD                     -> exige escopo "read"
 *  - POST/PUT/PATCH/DELETE        -> exige escopo "write"
 *
 * Se o usuario nao esta autenticado, o SecurityConfig ja cuida disso
 * (redireciona para o Keycloak antes de chegar aqui).
 *
 * Se o usuario esta autenticado mas o access token nao contem o escopo
 * necessario, este controller REINICIA o processo de OAuth pedindo
 * consentimento adicional (step-up), em vez de simplesmente barrar com 403.
 */
@RestController
@CrossOrigin(origins = "*")
public class ProxyController {

    private final RestClient backendClient;

    public ProxyController(@Value("${sidecar.backend.base-url}") String backendBaseUrl) {
        this.backendClient = RestClient.create(backendBaseUrl);
    }

    @RequestMapping("/api/**")
    public ResponseEntity<byte[]> proxy(HttpServletRequest request,
                                         HttpServletResponse response,
                                         @RequestBody(required = false) byte[] body,
                                         @RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient authorizedClient,
                                         @AuthenticationPrincipal OidcUser oidcUser) throws IOException {

        String httpMethod = request.getMethod();
        String requiredScope = requiredScopeFor(httpMethod);

        Set<String> grantedScopes = authorizedClient.getAccessToken().getScopes();

        if (!grantedScopes.contains(requiredScope)) {
            // Escopo insuficiente: (re)inicia o fluxo OAuth solicitando o escopo que falta.
            // O StepUpAuthorizationRequestResolver adiciona prompt=consent para o Keycloak
            // reabrir a tela de consentimento com a permissao adicional.
            response.sendRedirect("/oauth2/authorization/keycloak?reauth=true");
            return null;
        }

        String downstreamPath = request.getRequestURI().replaceFirst("^/api", "");
        String queryString = request.getQueryString();
        String fullPath = queryString != null ? downstreamPath + "?" + queryString : downstreamPath;

        return backendClient.method(HttpMethod.valueOf(httpMethod))
                .uri(fullPath)
                .headers(headers -> {
                    // Propaga identidade para o backend, mas nunca o token bruto:
                    // o crud-service confia no sidecar, nao precisa validar JWT.
                    headers.set("X-Auth-User", oidcUser.getPreferredUsername());
                    headers.set("X-Auth-Scopes", String.join(",", grantedScopes));
                    if (body != null && body.length > 0) {
                        headers.setContentType(MediaType.APPLICATION_JSON);
                    }
                })
                .body(body != null ? body : new byte[0])
                .retrieve()
                .toEntity(byte[].class);
    }

    private String requiredScopeFor(String httpMethod) {
        return switch (httpMethod) {
            case "GET", "HEAD" -> "read";
            default -> "write"; // POST, PUT, PATCH, DELETE
        };
    }
}
