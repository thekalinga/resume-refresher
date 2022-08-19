package com.acme;

import com.acme.resume.refresh.ResumeProperties;
import com.acme.resume.refresh.ResumeRefresher;
import com.acme.resume.refresh.instahyre.exchange.CandidateResponse;
import com.acme.resume.refresh.instahyre.exchange.ResumeResponse;
import com.acme.resume.refresh.instahyre.exchange.SessionIdAndCsrfToken;
import com.acme.resume.refresh.instahyre.exchange.UploadResumeRequest;
import com.acme.resume.refresh.monster.exchange.InitialCookieAndRedirectUrl;
import com.acme.resume.refresh.monster.exchange.PersonalDetailSection;
import com.acme.resume.refresh.monster.exchange.PersonalDetails;
import com.acme.resume.refresh.monster.exchange.UploadResponse;
import com.acme.resume.refresh.monster.exchange.UploadResumeUploadDetailedStatus;
import com.acme.resume.refresh.monster.exchange.UserProfile;
import com.acme.resume.refresh.monster.exchange.UserProfileResponse;
import com.acme.resume.refresh.naukri.exchange.AdvertiseResumeRequest;
import com.acme.resume.refresh.naukri.exchange.Cookie;
import com.acme.resume.refresh.naukri.exchange.Dashboard;
import com.acme.resume.refresh.naukri.exchange.DashboardResponse;
import com.acme.resume.refresh.naukri.exchange.LoginRequest;
import com.acme.resume.refresh.naukri.exchange.LoginResponse;
import com.acme.resume.refresh.naukri.exchange.TextCv;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.nativex.hint.InitializationHint;
import org.springframework.nativex.hint.InitializationTime;
import org.springframework.nativex.hint.NativeHint;
import org.springframework.nativex.hint.TypeAccess;
import org.springframework.nativex.hint.TypeHint;
import reactor.core.publisher.Flux;

import java.util.List;

@EnableConfigurationProperties(ResumeProperties.class)
@SpringBootApplication(proxyBeanMethods = false)
public class DemoApplication {

  public static void main(String[] args) {
    new SpringApplicationBuilder(DemoApplication.class).properties(
            "spring.output.ansi.enabled=always")
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
