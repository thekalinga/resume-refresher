package com.acme;

import com.acme.resume.refresh.ResumeRefresher;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Bean;
import reactor.core.publisher.Flux;

import java.util.List;

@SpringBootApplication(proxyBeanMethods = false)
public class DemoApplication {

  public static void main(String[] args) {
    new SpringApplicationBuilder(DemoApplication.class)
        .properties("spring.output.ansi.enabled=always")
        .bannerMode(Banner.Mode.OFF)
        .web(WebApplicationType.NONE).run(args);
  }

  @Bean
  ApplicationRunner onInit(List<ResumeRefresher> refreshers) {
    return args -> {
      Flux.fromIterable(refreshers)
        .concatMap(ResumeRefresher::refresh)
        .blockLast();
    };
  }

}
