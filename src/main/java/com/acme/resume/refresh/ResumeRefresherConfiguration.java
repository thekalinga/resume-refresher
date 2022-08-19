package com.acme.resume.refresh;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ClientHttpConnector;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import reactor.netty.http.client.HttpClient;

import static io.netty.handler.logging.LogLevel.DEBUG;
import static java.nio.charset.StandardCharsets.UTF_8;
import static reactor.netty.transport.logging.AdvancedByteBufFormat.TEXTUAL;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ResumeProperties.class)
public final class ResumeRefresherConfiguration {
  @Bean
  public ClientHttpConnector clientHttpConnector() {
    HttpClient reactorHttpClient = HttpClient.create()
        .wiretap(getClass().getCanonicalName(), DEBUG, TEXTUAL, UTF_8); // capture messages over wire

    return new ReactorClientHttpConnector(reactorHttpClient);
  }
}
