package com.acme.resume.refresh.naukri;

import com.acme.resume.refresh.ResumeProperties;
import com.acme.resume.refresh.ResumeRefresher;
import com.acme.resume.refresh.naukri.exchange.AdvertiseResumeRequest;
import com.acme.resume.refresh.naukri.exchange.Cookie;
import com.acme.resume.refresh.naukri.exchange.DashboardResponse;
import com.acme.resume.refresh.naukri.exchange.LoginRequest;
import com.acme.resume.refresh.naukri.exchange.LoginResponse;
import com.acme.resume.refresh.naukri.exchange.TextCv;
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
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.function.Function;
import java.util.regex.Pattern;

import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.HttpHeaders.USER_AGENT;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.http.MediaType.APPLICATION_PDF;
import static org.springframework.http.MediaType.MULTIPART_FORM_DATA;

@Log4j2
@Component
@EnableConfigurationProperties(NaukriProperties.class)
@Order(99) // lets do this before monster one
@SuppressWarnings("unused") // since we are dealing with a component
public class NaukriResumeRefresher implements ResumeRefresher {
  private static final String BEARER_JWT_COOKIE_NAME = "nauk_at";

  private final NaukriProperties naukriProperties;
  private final ResumeProperties resumeProperties;
  private final WebClient webClient;

  @SuppressWarnings("unused") // since we are dealing with a component
  public NaukriResumeRefresher(NaukriProperties naukriProperties, ResumeProperties resumeProperties, Function<String, ClientHttpConnector> loggerNameToClientHttpConnectorMapper) {
    this.naukriProperties = naukriProperties;
    this.resumeProperties = resumeProperties;
    this.webClient =
        WebClient.builder().baseUrl("https://www.naukri.com")
            .clientConnector(loggerNameToClientHttpConnectorMapper.apply(getClass().getCanonicalName()))
            .defaultHeaders(httpHeaders -> {
              httpHeaders.add(USER_AGENT, "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/104.0.5112.81 Safari/537.36");
            })
            .build();
  }

  @Override
  public Mono<Void> refresh() {
    // login
    /*
    curl 'https://www.naukri.com/central-login-services/v1/login' \
      -H 'user-agent: Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/104.0.5112.81 Safari/537.36' \
      -H 'appid: 103' \
      -H 'systemid: jobseeker' \
      -H 'content-type: application/json' \
      --data-raw $'{"username":"<>","password":"<>"}' | jq
    */
    // fetching cookie value with name `nauk_at`
    Mono<String> bearerToken$ = webClient
        .method(POST)
        .uri("/central-login-services/v1/login")
        .headers(httpHeaders -> {
          httpHeaders.add("appid", "103");
          httpHeaders.add("systemid", "jobseeker");
        })
        .contentType(APPLICATION_JSON)
        .bodyValue(new LoginRequest(naukriProperties.username(), naukriProperties.password()))
        .retrieve()
        .bodyToMono(LoginResponse.class)
        .flatMap(response -> Flux.fromIterable(response.cookies()).filter(cookie -> cookie.name().equals(BEARER_JWT_COOKIE_NAME)).map(
            Cookie::value).single())
        .doOnSubscribe(__ -> log.info("Attempting to fetch bearer token"))
        .doFinally(signal -> log.info("Finished attempt to fetch bearer token. Final signal received is {}", signal))
        .cache();

    /*
    curl 'https://www.naukri.com/servicegateway-mynaukri/resman-aggregator-services/v0/users/self/dashboard' \
      -H 'user-agent: Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/104.0.5112.81 Safari/537.36'
      -H 'appid: 105' \
      -H 'systemid: Naukri' \
      -H 'accept: application/json' \
      -H 'authorization: Bearer <>' \
     */
    Mono<String> profileId$ = bearerToken$
        .flatMap(bearerToken -> {
          //noinspection CodeBlock2Expr
          return webClient
              .method(GET)
              .uri("/servicegateway-mynaukri/resman-aggregator-services/v0/users/self/dashboard")
              .headers(httpHeaders -> {
                httpHeaders.add("appid", "105");
                httpHeaders.add("systemid", "Naukri");
                httpHeaders.add(AUTHORIZATION, "Bearer " + bearerToken);
              })
              .accept(APPLICATION_JSON)
              .retrieve()
              .bodyToMono(DashboardResponse.class)
              .map(response -> response.dashboard().profileId())
              .doOnSubscribe(__ -> log.info("Attempting to fetch profile id"))
              .doFinally(signal -> log.info("Finished attempt to fetch profile id. Final signal received is {}", signal));
        })
        .cache();

    /*
    curl 'https://www.naukri.com/servicegateway-mynaukri/resman-aggregator-services/v0/users/self/profiles/<>/deleteResume' \
      -X 'POST' \
      -H 'user-agent: Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/104.0.5112.81 Safari/537.36' \
      -H 'appid: 105' \
      -H 'systemid: 105' \
      -H 'authorization: Bearer <>' \
      -H 'content-length: 0' \
      -H 'x-http-method-override: DELETE'
     */
    Mono<Void> deleteResume$ = bearerToken$.zipWith(profileId$)
        .flatMap(bearerTokenAndProfileId -> {
          //noinspection CodeBlock2Expr
          return webClient
              .method(POST)
              .uri("/servicegateway-mynaukri/resman-aggregator-services/v0/users/self/profiles/" + bearerTokenAndProfileId.getT2() + "/deleteResume")
              .headers(httpHeaders -> {
                httpHeaders.add("appid", "105");
                httpHeaders.add("systemid", "105");
                httpHeaders.add(AUTHORIZATION, "Bearer " + bearerTokenAndProfileId.getT1());
                httpHeaders.add("x-http-method-override", "DELETE");
              })
              .contentLength(0)
              .retrieve()
              .bodyToMono(Void.class)
              .doOnSubscribe(__ -> log.info("Attempting to delete resume"))
              .doFinally(signal -> log.info("Finished attempt to delete resume. Final signal received is {}", signal));
        });

    /*
    This script has `formKey` parameter thats required to upload the file from https://static.naukimg.com/s/5/105/j/mnj_v152.min.js
    We are looking for string of following format `c="attachCV",d="<>    */
    final Pattern formKeyPattern = Pattern.compile("=\"attachCV\",d=\"(?<formKey>F[^\"]+?)\"");
    Mono<String> formKey$ = webClient
        .method(GET)
        .uri("https://static.naukimg.com/s/5/105/j/mnj_v152.min.js")
        .exchangeToMono(response -> {
          //noinspection CodeBlock2Expr
          return response.bodyToFlux(DataBuffer.class).as(MiscUtil::readAllBuffersAsUtf8String);
        })
        .map(bodyAsStr -> {
          final var matcher = formKeyPattern.matcher(bodyAsStr);
          // noinspection ResultOfMethodCallIgnored
          matcher.find();
          return matcher.group("formKey");
        })
        .doOnSubscribe(__ -> log.info("Attempting to fetch formKey parameter"))
        .doFinally(signal -> log.info("Finished attempt to fetch formKey parameter. Final signal received is {}", signal))
        .cache();

    /*
    curl -v 'https://filevalidation.naukri.com/file' \
      -H 'user-agent: Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/104.0.5112.81 Safari/537.36' \
      -H 'appid: 105' \
      -H 'systemid: fileupload' \
      -F formKey=<> \
      -F fileKey=<> \
      -F fileName='<>' \
      -F uploadCallback=true \
      -F file='@<full path>'
     */
    final var fileKey = generateRandomFileKey();
    Mono<Void> uploadResume$ = formKey$
        .flatMap(formKey -> {
          MultipartBodyBuilder multipartBodyBuilder = new MultipartBodyBuilder();
          multipartBodyBuilder.part("formKey", formKey);
          multipartBodyBuilder.part("fileKey", fileKey);
          multipartBodyBuilder.part("fileName", resumeProperties.filename());
          multipartBodyBuilder.part("uploadCallback", "true");
          Resource resumePdf = new FileSystemResource(resumeProperties.path());
          multipartBodyBuilder.part("file", resumePdf, APPLICATION_PDF).filename(resumeProperties.filename());

          return webClient
              .method(POST)
              .uri("https://filevalidation.naukri.com/file")
              .headers(httpHeaders -> {
                httpHeaders.add("appid", "105");
                httpHeaders.add("systemid", "fileupload");
              })
              .contentType(MULTIPART_FORM_DATA)
              .bodyValue(multipartBodyBuilder.build())
              .retrieve()
              .toBodilessEntity()
              .then()
              .doOnSubscribe(__ -> log.info("Attempting to upload resume"))
              .doFinally(signal -> log.info("Finished attempt to upload resume. Final signal received is {}", signal));
        });

    /*
     *  curl 'https://www.naukri.com/servicegateway-mynaukri/resman-aggregator-services/v0/users/self/profiles/<>/advResume' \
     *   -H 'user-agent: Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/104.0.5112.81 Safari/537.36' \
     *   -H 'appid: 105' \
     *   -H 'systemid: 105' \
     *   -H 'authorization: Bearer <>' \
     *   -H 'content-type: application/json' \
     *   -H 'x-http-method-override: PUT' \
     *   --data-raw '{"textCV":{"formKey":"<>","fileKey":"<>","textCvContent":null}}'
     */
    Mono<Void> advertiseResume$ = Mono.zip(bearerToken$, profileId$, formKey$)
        .flatMap(bearerTokenAndProfileIdAndFormKey -> {
          //noinspection CodeBlock2Expr
          return webClient
              .method(POST)
              .uri("/servicegateway-mynaukri/resman-aggregator-services/v0/users/self/profiles/" + bearerTokenAndProfileIdAndFormKey.getT2() + "/advResume")
              .headers(httpHeaders -> {
                httpHeaders.add("appid", "105");
                httpHeaders.add("systemid", "105");
                httpHeaders.add(AUTHORIZATION, "Bearer " + bearerTokenAndProfileIdAndFormKey.getT1());
                httpHeaders.add("x-http-method-override", "PUT");
              })
              .contentType(APPLICATION_JSON)
              .bodyValue(new AdvertiseResumeRequest(new TextCv(bearerTokenAndProfileIdAndFormKey.getT3(), fileKey, null)))
              .retrieve()
              .bodyToMono(Void.class)
              .doOnSubscribe(__ -> log.info("Attempting to advertise uploaded resume"))
              .doFinally(signal -> log.info("Finished attempt to advertise uploaded resume. Final signal received is {}", signal));
        });

    // formKey$ is giving errors. So, lets not delete the resume unless formKey$, bearerToken$ & profileId$ are successful
    return formKey$
        .then(deleteResume$)
        .then(uploadResume$)
        .then(advertiseResume$)
        .doOnSubscribe(__ -> log.info("Attempting to refresh resume on Naukri"))
        .doFinally(signal -> log.info("Finished attempt to refresh resume on Naukri. Final signal received is {}", signal));
  }

  /**
   This script <a href="https://static.naukimg.com/s/5/105/j/mnj_v152.min.js">java script</a> has a stupid generator which generates random <code>fileKey</code> to upload file

   Here is the anchor <code>f = "U" + e(13)</code> to the original source

   Function that generates 13 characters is
   <pre>
   <code>
   function(a) {
   for (var b = "", c = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ", d = a; d > 0; --d)
   b += c[Math.round(Math.random() * (c.length - 1))];
   return b
   }
   </code>
   </pre>

   Context
   <pre>
   <code>
   {
   key: "initUploader",
   value: function(a) {
   var b = this
   , c = "attachCV"
   , d = "<>"
   , e = ($("#" + c),
   $("#uploadBtnCont"),
   function(a) {
   for (var b = "", c = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ", d = a; d > 0; --d)
   b += c[Math.round(Math.random() * (c.length - 1))];
   return b
   }
   )
   , f = "U" + e(13)
   , g = "//my.naukri.com"
   , h = {
   uploadTarget: {
   saveCloudUrl: yb,
   saveFileUrl: Ec,
   deleteUrl: "//files.naukri.com/0/deleteFile.php"
   },
   parseResume: g + "/CVParser/parseResume"
   };
   window.resumeParser = {
   util: {
   _trackEvent: {}
   }
   },
   ...
   </code>
   </pre>
   */
  private String generateRandomFileKey() {
    /*
    function(a) {
        for (var b = "", c = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ", d = a; d > 0; --d)
            b += c[Math.round(Math.random() * (c.length - 1))];
        return b
    }
     */
    final var builder = new StringBuilder("U");
    var count = 13;
    var numberLowerAndUpperString = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
    for (; count > 0; --count) {
      builder.append(numberLowerAndUpperString.charAt((int) Math.round(Math.random() * (numberLowerAndUpperString.length() - 1))));
    }
    return builder.toString();
  }

}
