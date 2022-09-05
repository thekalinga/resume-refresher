package com.acme.resume.refresh;

import com.acme.resume.refresh.common.ResumeRefresher;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Bean;
import reactor.core.publisher.Flux;

import java.util.List;

@SpringBootApplication(proxyBeanMethods = false)
public class ResumeRefresherApplication {

  public static void main(String[] args) {
    new SpringApplicationBuilder(ResumeRefresherApplication.class)
        .properties("spring.output.ansi.enabled=always")
        .bannerMode(Banner.Mode.OFF)
        .web(WebApplicationType.NONE) // We only care about webclient & we don't webflux features. Let's run the app in non-server mode
        .run(args);
  }

  @Bean
  ApplicationRunner doOnInit(List<ResumeRefresher> refreshers) {
    return args -> {
      if (refreshers.isEmpty()) {
        throw new RuntimeException(
            "No built in resume refreshers are enabled. Make sure you run the application by enabling atleast one resume refresher by specifying corresponding properties. For eg. by specifying app_naukri_username & app_naukri_password, etc");
      }
      Flux.fromIterable(refreshers)
        .concatMapDelayError(ResumeRefresher::refresh) //lets allow refresh of resume to proceed even if we fail to refresh in one of the resume service provider
        .blockLast();
    };
  }

}
