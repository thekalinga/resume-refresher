package com.acme.resume.refresh.naukri;

import com.acme.resume.refresh.common.ResumeProperties;
import com.acme.resume.refresh.common.ResumeRefresher;
import com.acme.resume.refresh.common.ConditionalOnPropertyNotEmpty;
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

import javax.validation.Valid;
import java.util.function.Function;
import java.util.regex.Pattern;

import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.HttpHeaders.USER_AGENT;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.http.MediaType.APPLICATION_PDF;
import static org.springframework.http.MediaType.MULTIPART_FORM_DATA;
import static org.springframework.util.Assert.isTrue;

@Log4j2
@Component
@EnableConfigurationProperties(NaukriProperties.class)
@ConditionalOnPropertyNotEmpty({"app.naukri.username", "app.naukri.password"})
@Order(200) // lets do this before monster one
@SuppressWarnings("unused") // since we are dealing with a component
public class NaukriResumeRefresher implements ResumeRefresher {
  //@formatter:off
  private static final String BEARER_JWT_COOKIE_NAME = "nauk_at";

  private final NaukriProperties naukriProperties;
  private final ResumeProperties resumeProperties;
  private final WebClient webClient;

  @SuppressWarnings("unused") // since we are dealing with a component
  public NaukriResumeRefresher(@Valid NaukriProperties naukriProperties,
      @Valid ResumeProperties resumeProperties,
      Function<String, ClientHttpConnector> loggerNameToClientHttpConnectorMapper) {
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
    Mono<String> bearerToken$ = buildBearerToken$().cache();
    Mono<String> profileId$ = buildProfileId$(bearerToken$).cache();
    Mono<Void> deleteResume$ = buildDeleteResume$(bearerToken$, profileId$);
    Mono<String> formKey$ = buildFormKey$().cache();
    Mono<Void> uploadAndAdvertiseResume$ = buildUploadAndAdvertiseResume$(formKey$, bearerToken$, profileId$);

    // formKey$ often gives errors. Lets first fetch that before deleting resume to be on the safe side
    return formKey$
        .then(deleteResume$)
        .then(uploadAndAdvertiseResume$)
        .doOnSubscribe(__ -> log.info("Attempting to refresh resume on Naukri"))
        .doFinally(signal -> log.info("Finished attempt to refresh resume on Naukri. Final signal received is {}", signal));
  }

  private Mono<Void> buildUploadAndAdvertiseResume$(Mono<String> formKey$,
      Mono<String> bearerToken$, Mono<String> profileId$) {
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

    return uploadResume$
        .then(advertiseResume$);
  }

  private Mono<Void> buildDeleteResume$(Mono<String> bearerToken$, Mono<String> profileId$) {
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

    return deleteResume$;
  }

  private Mono<String> buildProfileId$(Mono<String> bearerToken$) {
    /*
    curl 'https://www.naukri.com/servicegateway-mynaukri/resman-aggregator-services/v0/users/self/dashboard' \
      -H 'user-agent: Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/104.0.5112.81 Safari/537.36'
      -H 'appid: 105' \
      -H 'systemid: Naukri' \
      -H 'accept: application/json' \
      -H 'authorization: Bearer <>' \
     */
    return bearerToken$
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
        });
  }

  /**
   * This form key is hidden behind many layers of minified js
   * <p>
   * Here are main things we are looking for
   * <p>
   * 1. In console, we are looking for a js file named mnj_v\d+.min.js
   * 2. Within that we are looking for string `c="attachCV",d="<>`
   * <p>
   * But the mnj_v\d+.min.js itself is hidden behind another min file which we can get by going to the
   * <a href="https://www.naukri.com/nlogin/login">login page</a>
   * <p>
   * The above script is available both on home page post login & also on login page, but not on unauthenticated naukri main page
   * <p>
   * Next step is from to find a pattern of this format `src="(?<appMinJsPath>\/\/.+?\/app_v\d+.min.js)"` so we can find the path of app script
   * <p>
   * From app script, search for `flowName:"mnj",jsVersion:"(?<jsVersion>_v\d+)"` to know the name of the minified script that contains formKey.
   * <p>
   * Its quite convoluted, but since we are dealing with minified script rather than APIs, we are doing all of this to avoid the application breaking anytime they deploy new build.
   *
   * @return publisher that contains formKey
   */
  private Mono<String> buildFormKey$() {
    // goto login page, find src="(?<appMinJsPath>\/\/.+?\/app_v\d+.min.js)"

    /*
    This page https://www.naukri.com/nlogin/login has link to app min js
    We are looking for string of following format `src="(?<appMinJsPath>\/\/.+?\/app_v\d+.min.js)"`
    */
    final Pattern appJsMinPathPattern = Pattern.compile("src=\"//(?<appMinJsPath>.+?/app_v\\d+.min.js)\"");
    Mono<String> appJsMinUrl$ = webClient
        .method(GET)
        .uri("/nlogin/login")
        .exchangeToMono(response -> {
          //noinspection CodeBlock2Expr
          return response.bodyToFlux(DataBuffer.class).as(MiscUtil::readAllBuffersAsUtf8String);
        })
        .map(bodyAsStr -> {
          final var matcher = appJsMinPathPattern.matcher(bodyAsStr);
          isTrue(matcher.find(), bodyAsStr + "\n\ndid not contain regex pattern " + appJsMinPathPattern.pattern());
          return "https://" + matcher.group("appMinJsPath");
        })
        .doOnSubscribe(__ -> log.info("Attempting to fetch formKey parameter"))
        .doFinally(signal -> log.info("Finished attempt to fetch formKey parameter. Final signal received is {}", signal));

    // Access appJsMinUrl & get js version `flowName:"mnj",jsVersion:"(?<jsVersion>_v\d+)"`
    final Pattern mnjJsVersionPattern = Pattern.compile("flowName:\"mnj\",jsVersion:\"(?<jsVersion>_v\\d+)\"");
    Mono<String> mnjJsMinUrl$ = appJsMinUrl$
        .flatMap(appJsMinUrl -> {
          return webClient
              .method(GET)
              .uri(appJsMinUrl)
              .exchangeToMono(response -> {
                //noinspection CodeBlock2Expr
                return response.bodyToFlux(DataBuffer.class).as(MiscUtil::readAllBuffersAsUtf8String);
              })
              .map(bodyAsStr -> {
                final var matcher = mnjJsVersionPattern.matcher(bodyAsStr);
                isTrue(matcher.find(), bodyAsStr + "\n\ndid not contain regex pattern " + mnjJsVersionPattern.pattern());
                final var jsVersionSufixPartialString = matcher.group("jsVersion");
                //noinspection UnnecessaryLocalVariable
                final var mnjJsMinUrl =
                    appJsMinUrl.replaceAll("/app_v\\d+", "/mnj" + jsVersionSufixPartialString);
                return mnjJsMinUrl;
              })
              .doOnSubscribe(__ -> log.info("Attempting to fetch formKey parameter"))
              .doFinally(signal -> log.info("Finished attempt to fetch formKey parameter. Final signal received is {}", signal));
        });

    /*
    This script has `formKey` parameter thats required to upload the file from https://static.naukimg.com/s/5/105/j/mnj_v<>.min.js
    We are looking for string of following format `c="attachCV",d="F<>"`
    */
    final Pattern formKeyPattern = Pattern.compile("=\"attachCV\",d=\"(?<formKey>F[^\"]+?)\"");
    //noinspection UnnecessaryLocalVariable
    Mono<String> formKey$ = mnjJsMinUrl$
        .flatMap(mnjJsMinUrl -> {
          return webClient
              .method(GET)
              .uri(mnjJsMinUrl)
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
              .doFinally(signal -> log.info("Finished attempt to fetch formKey parameter. Final signal received is {}", signal));
        });

    return formKey$;
  }

  Mono<String> buildBearerToken$() {
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
    return webClient
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
        .flatMap(response -> {
          return Flux.fromIterable(response.cookies())
              .filter(cookie -> cookie.name().equals(BEARER_JWT_COOKIE_NAME))
              .map(Cookie::value).single();
        })
        .doOnSubscribe(__ -> log.info("Attempting to fetch bearer token"))
        .doFinally(signal -> log.info("Finished attempt to fetch bearer token. Final signal received is {}", signal));
  }

  /**
   * This script <a href="https://static.naukimg.com/s/5/105/j/mnj_v<number>.min.js">java script</a> has a generator which generates random <code>fileKey</code> to upload file
   * <p>
   * Here is the anchor <code>f = "U" + e(13)</code> to the original source
   * <p>
   * Function that generates 13 characters is
   * <pre>
   * <code>
   * function(a) {
   * for (var b = "", c = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ", d = a; d > 0; --d)
   * b += c[Math.round(Math.random() * (c.length - 1))];
   * return b
   * }
   * </code>
   * </pre>
   * <p>
   * Context
   * <pre>
   * <code>
   * {
   * key: "initUploader",
   * value: function(a) {
   * var b = this
   * , c = "attachCV"
   * , d = "<>"
   * , e = ($("#" + c),
   * $("#uploadBtnCont"),
   * function(a) {
   * for (var b = "", c = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ", d = a; d > 0; --d)
   * b += c[Math.round(Math.random() * (c.length - 1))];
   * return b
   * }
   * )
   * , f = "U" + e(13)
   * , g = "//my.naukri.com"
   * , h = {
   * uploadTarget: {
   * saveCloudUrl: yb,
   * saveFileUrl: Ec,
   * deleteUrl: "//files.naukri.com/0/deleteFile.php"
   * },
   * parseResume: g + "/CVParser/parseResume"
   * };
   * window.resumeParser = {
   * util: {
   * _trackEvent: {}
   * }
   * },
   * ...
   * </code>
   * </pre>
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

  //@formatter:on
}
