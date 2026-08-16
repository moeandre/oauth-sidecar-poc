package com.poc.sidecar.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Sem este bean explicito, {@code RestClient.create(url)} (usado antes no
 * ProxyController) monta um client com timeouts default do JDK - na pratica
 * sem timeout nenhum. Aqui aplicamos manualmente
 * {@code spring.http.client.connect-timeout}/{@code read-timeout} na factory
 * usada por todo RestClient criado a partir deste builder - critico com um
 * backend lento/instavel: sem timeout, uma chamada presa consome uma thread
 * (virtual ou nao) indefinidamente e, sob carga, esgota a capacidade do
 * sidecar de atender requisicoes novas.
 * (Nota: as classes de auto-configuracao "ClientHttpRequestFactorySettings"/
 * "ClientHttpRequestFactoryBuilder" que o Spring Boot 3.4+ normalmente
 * exporia pra isso nao existem nos modulos do Boot 4.1.0 usado aqui -
 * provavel reorganizacao de pacotes desta versao. Construir a factory na
 * mao com JdkClientHttpRequestFactory, que e da propria spring-web e nao
 * do Boot, evita depender de uma API que mudou de lugar.)
 */
@Configuration
public class RestClientConfig {

    @Bean
    public RestClient.Builder restClientBuilder(
            @Value("${spring.http.client.connect-timeout:2s}") Duration connectTimeout,
            @Value("${spring.http.client.read-timeout:3s}") Duration readTimeout) {

        HttpClient jdkHttpClient = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .build();

        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(jdkHttpClient);
        requestFactory.setReadTimeout(readTimeout);

        return RestClient.builder().requestFactory(requestFactory);
    }
}
