package com.poc.sidecar.config;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

import java.util.HashMap;
import java.util.Map;

/**
 * Envolve o resolver padrao do Spring Security. Quando a requisicao de
 * autorizacao chega com "?reauth=true" (usado pelo ProxyController quando
 * o token atual nao tem o escopo necessario), adicionamos "prompt=consent"
 * para o Keycloak reexibir a tela de consentimento e o usuario poder
 * conceder o escopo adicional (ex.: "write") sem precisar deslogar.
 */
public class StepUpAuthorizationRequestResolver implements OAuth2AuthorizationRequestResolver {

    private final OAuth2AuthorizationRequestResolver delegate;

    public StepUpAuthorizationRequestResolver(OAuth2AuthorizationRequestResolver delegate) {
        this.delegate = delegate;
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
        return customize(delegate.resolve(request), request);
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request, String clientRegistrationId) {
        return customize(delegate.resolve(request, clientRegistrationId), request);
    }

    private OAuth2AuthorizationRequest customize(OAuth2AuthorizationRequest authRequest, HttpServletRequest request) {
        if (authRequest == null) {
            return null;
        }
        if ("true".equals(request.getParameter("reauth"))) {
            Map<String, Object> extra = new HashMap<>(authRequest.getAdditionalParameters());
            extra.put("prompt", "consent");
            return OAuth2AuthorizationRequest.from(authRequest)
                    .additionalParameters(extra)
                    .build();
        }
        return authRequest;
    }
}
