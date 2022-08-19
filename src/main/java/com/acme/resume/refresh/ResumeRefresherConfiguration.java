package com.acme.resume.refresh;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ClientHttpConnector;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import reactor.netty.http.client.HttpClient;

import java.util.function.Function;

import static io.netty.handler.logging.LogLevel.DEBUG;
import static java.nio.charset.StandardCharsets.UTF_8;
import static reactor.netty.transport.logging.AdvancedByteBufFormat.TEXTUAL;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ResumeProperties.class)
public final class ResumeRefresherConfiguration {
  @Bean
  public Function<String, ClientHttpConnector> loggerNameToClientHttpConnectorMapper() {
    // lets allow the callers to specify the logger name
    return loggerName -> {
      HttpClient reactorHttpClient = HttpClient.create()
          .wiretap(loggerName, DEBUG, TEXTUAL, UTF_8); // capture messages over wire
      return new ReactorClientHttpConnector(reactorHttpClient);
    };
  }
}
