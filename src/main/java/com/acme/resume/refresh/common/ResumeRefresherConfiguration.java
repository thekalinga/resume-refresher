package com.acme.resume.refresh.common;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ClientHttpConnector;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import reactor.netty.http.Http11SslContextSpec;
import reactor.netty.http.Http2SslContextSpec;
import reactor.netty.http.client.HttpClient;
import reactor.netty.tcp.TcpSslContextSpec;

import java.util.function.Function;

import static io.netty.handler.logging.LogLevel.DEBUG;
import static io.netty.handler.ssl.SslProtocols.TLS_v1_2;
import static io.netty.handler.ssl.SslProtocols.TLS_v1_3;
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
//          .protocol(H2, HTTP11) // even tho http2 is performant, we are disabling it here as reactor netty debugging is not that good for http2
//          .secure(sslContextSpec -> sslContextSpec.sslContext(Http2SslContextSpec.forClient().configure(builder -> builder.protocols(TLS_v1_3, TLS_v1_2))))
          .secure(sslContextSpec -> sslContextSpec.sslContext(Http11SslContextSpec.forClient().configure(builder -> builder.protocols(TLS_v1_3, TLS_v1_2))))
          .wiretap(loggerName, DEBUG, TEXTUAL, UTF_8); // capture messages over wire
      return new ReactorClientHttpConnector(reactorHttpClient);
    };
  }
}
