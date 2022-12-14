package com.acme.resume.refresh.monster;

import com.acme.resume.refresh.common.ConditionalOnPropertyNotEmpty;
import com.acme.resume.refresh.common.ResumeProperties;
import com.acme.resume.refresh.common.ResumeRefresher;
import com.acme.resume.refresh.monster.exchange.InitialCookieAndRedirectUrl;
import com.acme.resume.refresh.monster.exchange.LoginResponse;
import com.acme.resume.refresh.monster.exchange.UploadResponse;
import com.acme.resume.refresh.monster.exchange.UserProfileResponse;
import com.acme.resume.refresh.util.MiscUtil;
import lombok.extern.log4j.Log4j2;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.http.client.reactive.ClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import javax.validation.Valid;
import java.util.function.Function;
import java.util.regex.Pattern;

import static java.util.Objects.requireNonNull;
import static org.springframework.http.HttpHeaders.LOCATION;
import static org.springframework.http.HttpHeaders.REFERER;
import static org.springframework.http.HttpHeaders.SET_COOKIE;
import static org.springframework.http.HttpHeaders.USER_AGENT;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.http.MediaType.APPLICATION_PDF;
import static org.springframework.http.MediaType.MULTIPART_FORM_DATA;
import static org.springframework.web.reactive.function.BodyInserters.fromFormData;

@Log4j2
@Component
@EnableConfigurationProperties(MonsterProperties.class)
@ConditionalOnPropertyNotEmpty({"app.monster.username", "app.monster.password"})
@Order(300) // uploading of resume fails most of the time. Let do this at end
@SuppressWarnings("unused") // since we are dealing with a component
public class MonsterResumeRefresher implements ResumeRefresher {
  //@formatter:off
  private static final String INITIAL_COOKIE_NAME = "MRE";
  private static final String MAIN_COOKIE_NAME = "MSSOAT";

  private final MonsterProperties monsterProperties;
  private final ResumeProperties resumeProperties;
  private final WebClient webClient;

  @SuppressWarnings("unused") // since we are dealing with a component
  public MonsterResumeRefresher(@Valid MonsterProperties monsterProperties,
      @Valid ResumeProperties resumeProperties,
      ClientHttpConnector clientHttpConnector,
      Function<String, ClientHttpConnector> loggerNameToClientHttpConnectorMapper) {
    this.monsterProperties = monsterProperties;
    this.resumeProperties = resumeProperties;
    this.webClient =
        WebClient.builder().baseUrl("https://www.monsterindia.com")
            .clientConnector(loggerNameToClientHttpConnectorMapper.apply(getClass().getCanonicalName()))
            .defaultHeaders(httpHeaders -> {
              httpHeaders.add(USER_AGENT, "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/104.0.5112.81 Safari/537.36");
              httpHeaders.add(REFERER, "https://www.monsterindia.com/");
            })
            .build();
  }

  @Override
  public Mono<Void> refresh() {
    Mono<String> mainCookie$ = buildMainCookie$().cache();
    Mono<String> profileId$ = buildProfileId$(mainCookie$).cache();
    // monster upload API is quite unstable & fails quite often, even if we retry. If we delete resume & upload fails, teh profile wont have resume till this program run next time. So lets not delete resume.
//    Mono<Void> deleteResume$ = buildDeleteResume$(mainCookie$);
    Mono<Void> uploadResumeAndPublishUpdate$ = buildUploadResumeAndPublishUpdate$(mainCookie$, profileId$);

    return profileId$
//        .then(deleteResume$)
        .then(uploadResumeAndPublishUpdate$)
        .doOnSubscribe(__ -> log.info("Attempting to refresh resume on Monster"))
        .doFinally(signal -> log.info("Finished attempt to refresh resume on Monster. Final signal received is {}", signal));
  }

  private Mono<Void> buildUploadResumeAndPublishUpdate$(Mono<String> mainCookie$, Mono<String> profileId$) {
    /*
    curl 'https://www.monsterindia.com/middleware/upload-resume' \
      -H 'cookie: MSSOAT=<>' \
      -H 'referer: https://www.monsterindia.com/' \
      -H 'user-agent: Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/104.0.5112.81 Safari/537.36' \
      -F file='@<fullpath>'

      {"uploadResumeStatus":500,"uploadResumeStatusText":"Internal Server Error","uploadResumeResponse":{"appName":"falcon","appVersion":"30.67.2","errorCode":"SERVER_ERROR","errorMessage":"Something went wrong. Please try after sometime.","detailErrorMessage":"failed to update seeker profile resume"}}
     */
    Mono<Void> uploadResume$ = mainCookie$
        .flatMap(mainCookie -> {
          MultipartBodyBuilder multipartBodyBuilder = new MultipartBodyBuilder();
          Resource resumePdf = new FileSystemResource(resumeProperties.path());
          multipartBodyBuilder.part("file", resumePdf, APPLICATION_PDF).filename(resumeProperties.filename());

          return webClient
              .method(POST)
              .uri("/middleware/upload-resume")
              .cookie(MAIN_COOKIE_NAME, mainCookie)
              .contentType(MULTIPART_FORM_DATA)
              .bodyValue(multipartBodyBuilder.build())
              .exchangeToMono(response -> {
                final var uploadResponse$ = response.bodyToMono(UploadResponse.class);
                return uploadResponse$.flatMap(uploadResponse -> {
                  if (uploadResponse.uploadResumeStatus() != 200) {
                    return Mono.<Void>error(new RuntimeException(String.format("Upload failed. Status code: %d; Status text: %s; Detailed status text: %s", uploadResponse.uploadResumeStatus(), uploadResponse.uploadResumeStatusText(), uploadResponse.additionalDetails().errorMessage() + uploadResponse.additionalDetails().detailErrorMessage())));
                  } else {
                    return Mono.empty();
                  }
                });
              })
              .doOnSubscribe(__ -> log.info("Attempting to upload new resume"))
              .doFinally(signal -> log.info("Finished attempt to upload new resume. Terminal signal received is {}", signal));
        });

    /*
    curl 'https://www.monsterindia.com/middleware/publish/events/field-level-update' \
      -H 'content-type: application/json; charset=UTF-8' \
      -H 'cookie: MSSOAT=<>' \
      -H 'referer: https://www.monsterindia.com/' \
      -H 'user-agent: Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/104.0.5112.81 Safari/537.36' \
      --data-raw '{"fieldNames":["RESUME_UPLOAD"],"profileId":<>,"tenant":"web"}'
     */
    Mono<Void> publishUpdate$ = mainCookie$
        .zipWith(profileId$)
        .flatMap(mainCookieAndProfileId -> {
          return webClient
              .method(POST)
              .uri("/middleware/publish/events/field-level-update")
              .cookie(MAIN_COOKIE_NAME, mainCookieAndProfileId.getT1())
              .contentType(APPLICATION_JSON)
              .bodyValue("{\"fieldNames\":[\"RESUME_UPLOAD\"],\"profileId\":" + mainCookieAndProfileId.getT2() + ",\"tenant\":\"web\"}")
              .retrieve()
              .bodyToMono(Void.class)
              .doOnSubscribe(__ -> log.info("Attempting to publish resume uploaded event"))
              .doFinally(signal -> log.info("Finished attempt to publish resume uploaded event. Terminal signal received is {}", signal));
        });

    return uploadResume$
        .thenEmpty(publishUpdate$);
  }

  private Mono<Void> buildDeleteResume$(Mono<String> mainCookie$) {
    /*
    curl 'https://www.monsterindia.com/middleware/deleteResume' \
      -X 'POST' \
      -H 'content-length: 0' \
      -H 'content-type: application/json' \
      -H 'cookie: MSSOAT=<>' \
      -H 'referer: https://www.monsterindia.com/' \
      -H 'user-agent: Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/104.0.5112.81 Safari/537.36'
     */
    return mainCookie$
        .flatMap(mainCookie -> {
          //noinspection CodeBlock2Expr
          return webClient
              .method(POST)
              .uri("/middleware/deleteResume")
              .cookie(MAIN_COOKIE_NAME, mainCookie)
              .contentLength(0)
              .contentType(APPLICATION_JSON)
              .retrieve()
              .bodyToMono(Void.class)
              .doOnSubscribe(__ -> log.info("Attempting to delete resume"))
              .doFinally(signal -> log.info("Finished attempt to delete resume. Terminal signal received is {}", signal));
        });
  }

  private Mono<String> buildProfileId$(Mono<String> mainCookie$) {
    /*
    curl 'https://www.monsterindia.com/middleware/profileSettings?fields=personal_details' \
      -H 'cookie: MSSOAT=<>' \
      -H 'referer: https://www.monsterindia.com/' \
      -H 'user-agent: Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/104.0.5112.81 Safari/537.36'

    userProfile.personalDetailSection.personalDetails.profileId
     */
    return mainCookie$
        .flatMap(mainCookie -> {
          //noinspection CodeBlock2Expr
          return webClient
              .method(GET)
              .uri("/middleware/profileSettings?fields=personal_details")
              .cookie(MAIN_COOKIE_NAME, mainCookie)
              .retrieve()
              .bodyToMono(UserProfileResponse.class)
              .map(userProfileResponse -> userProfileResponse.userProfile().personalDetailSection().personalDetails().profileId())
              .doOnSubscribe(__ -> log.info("Attempting to retrieve profile id"))
              .doFinally(signal -> log.info("Finished attempt to retrieve profile id. Terminal signal received is {}", signal));
        });
  }

  private Mono<String> buildMainCookie$() {
    Mono<String> clientId$ = buildClientId$();
    Mono<InitialCookieAndRedirectUrl> initialCookieAndRedirectUrl$ = buildInitialCookieAndRedirectUrl$(clientId$).cache();
    Mono<String> oauthCallbackUrl$ = buildOauthCallbackUrl$(initialCookieAndRedirectUrl$);

    /*
    curl -v 'http://www.monsterindia.com/rio/login/oauth/callback?code=<>' \
      -H 'cookie: MRE=<>' \
      -H 'user-agent: Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/104.0.5112.81 Safari/537.36'

    Set-Cookie: MSSOAT=<>;
     */
    return oauthCallbackUrl$
        .zipWith(initialCookieAndRedirectUrl$)
        .flatMap(oauthCallbackUrlPlusInitialCookieAndRedirectUrl -> {
          //noinspection CodeBlock2Expr
          return webClient
              .method(GET)
              .uri(oauthCallbackUrlPlusInitialCookieAndRedirectUrl.getT1())
              .cookie(INITIAL_COOKIE_NAME, oauthCallbackUrlPlusInitialCookieAndRedirectUrl.getT2().initialCookie())
              .exchangeToMono(response -> {
                // This is not working as server is sending multiple `set-cookie`s with same name & library is picking one of the set cookie as value
                // Mono.just(requireNonNull(response.cookies().getFirst(MAIN_COOKIE_NAME)).getValue())
                var mainCookiePattern = Pattern.compile(MAIN_COOKIE_NAME + "=(?<mainCookie>[^;]+);");
                String mainCookie = null;
                for (String setCookieHeader : response.headers().header(SET_COOKIE)) {
                  final var matcher = mainCookiePattern.matcher(setCookieHeader);
                  if (matcher.find()) {
                    mainCookie = matcher.group("mainCookie");
                    break;
                  }
                }
                return Mono.just(requireNonNull(mainCookie));
              })
              .doOnSubscribe(__ -> log.info("Attempting to retrieve main cookie"))
              .doFinally(signal -> log.info("Finished attempt to retrieve main cookie. Terminal signal received is {}", signal));
        });
  }

  private Mono<String> buildOauthCallbackUrl$(Mono<InitialCookieAndRedirectUrl> initialCookieAndRedirectUrl$) {
    /*
    curl -v 'https://www.monsterindia.com/rio/oauth/authorize?client_id=&scope=all&response_type=code&redirect_uri=http://www.monsterindia.com/rio/login/oauth/callback' \
      -H 'cookie: MRE=<>' \
      -H 'user-agent: Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/104.0.5112.81 Safari/537.36'

    302

    location: http://www.monsterindia.com/rio/login/oauth/callback?code=<>
     */
    return initialCookieAndRedirectUrl$
        .flatMap(initialCookieAndRedirectUrl -> {
          //noinspection CodeBlock2Expr
          return webClient
              .method(GET)
              .uri(initialCookieAndRedirectUrl.redirectUrl())
              .cookie(INITIAL_COOKIE_NAME, initialCookieAndRedirectUrl.initialCookie())
              .exchangeToMono(response -> Mono.just(response.headers().header(LOCATION).get(0)))
              .doOnSubscribe(__ -> log.info("Attempting to retrieve initial cookie & redirectUrl"))
              .doFinally(signal -> log.info("Finished attempt to retrieve initial cookie & redirectUrl call. Terminal signal received is {}", signal));
        });
  }

  private Mono<InitialCookieAndRedirectUrl> buildInitialCookieAndRedirectUrl$(Mono<String> clientId$) {
    // login
    /*
    curl -v 'https://www.monsterindia.com/rio/login' \
      -H 'content-type: application/x-www-form-urlencoded' \
      -H 'referer: https://www.monsterindia.com/' \
      -H 'user-agent: Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/104.0.5112.81 Safari/537.36' \
      --data-raw $'username=<>&password=<>&client_id=<>'

    set-cookie: MRE=<>;

    {"redirectUrl":"http://www.monsterindia.com/rio/oauth/authorize?client_id=&scope=all&response_type=code&redirect_uri=http://www.monsterindia.com/rio/login/oauth/callback"}
     */
    return clientId$
        .flatMap(clientId -> {
          //noinspection CodeBlock2Expr
          return webClient
              .method(POST)
              .uri("/rio/login")
              .contentType(APPLICATION_FORM_URLENCODED)
              .body(
                  fromFormData("username", monsterProperties.username())
                    .with("password", monsterProperties.password())
                    .with("client_id", clientId)
              )
              .exchangeToMono(response -> {
                //noinspection CodeBlock2Expr
                return response.bodyToMono(LoginResponse.class)
                    .map(loginResponse -> {
                      final var targetCookie = requireNonNull(response.cookies().getFirst(INITIAL_COOKIE_NAME)).getValue();
                      return new InitialCookieAndRedirectUrl(targetCookie, loginResponse.redirectUrl());
                    });
              })
              .doOnSubscribe(__ -> log.info("Attempting to retrieve initial cookie & redirectUrl"))
              .doFinally(signal -> log.info("Finished attempt to retrieve initial cookie & redirectUrl call. Terminal signal received is {}", signal));
        });
  }

  private Mono<String> buildClientId$() {
    /*
    This script has `client_id` parameter thats required to upload the file from https://media.monsterindia.com/rio/public/js/login-app-service.js
    We are looking for string of following format `client_id="<>"`
    */
    final Pattern clientIdPattern = Pattern.compile("client_id=\"(?<clientId>[^\"]+?)\"");
    return webClient
        .method(GET)
        .uri("https://media.monsterindia.com/rio/public/js/login-app-service.js")
        .exchangeToMono(response -> {
          //noinspection CodeBlock2Expr
          return response.bodyToFlux(DataBuffer.class).as(MiscUtil::readAllBuffersAsUtf8String);
        })
        .map(bodyAsStr -> {
          final var matcher = clientIdPattern.matcher(bodyAsStr);
          // noinspection ResultOfMethodCallIgnored
          matcher.find();
          return matcher.group("clientId");
        })
        .doOnSubscribe(__ -> log.info("Attempting to retrieve clientId"))
        .doFinally(signal -> log.info("Finished attempt to retrieve clientId. Terminal signal received is {}", signal));
  }
  //@formatter:on
}
