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
    this.webClient = WebClient.builder().baseUrl("https://www.nma.mobi")
        .clientConnector(loggerNameToClientHttpConnectorMapper.apply(getClass().getCanonicalName()))
        .defaultHeaders(httpHeaders -> {
          //  TODO: Naukri moved its login page behind Akamai bot blocker recently
          // Akamai is blocking even get calls & considering us to be a bot. Is it because it is expecting certain headers to be there/expecting HTTP2 to be used when its a browser?
          //  For some reason akamai is allowing curl to go thru but not browser. Why?
          // do we need to take the header only login page & use non-curl header so that akamai doesnt block us?
          // Some reading: https://www.zenrows.com/blog/bypass-akamai#conclusion
          //              httpHeaders.add(USER_AGENT, "Dalvik/2.1.0 (Linux; U; Android 5.1.1; Android SDK built for x86_64 Build/LMY48X");
          // lets make calls as Android client to bypass Akamai bot
          httpHeaders.add(USER_AGENT, "Dalvik/2.1.0 (Linux; U; Android 5.1.1; Android SDK built for x86_64 Build/LMY48X");
        }).build();
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
    curl -v 'https://filevalidation.nma.mobi/file' \
      -H 'user-agent: Dalvik/2.1.0 (Linux; U; Android 5.1.1; Android SDK built for x86_64 Build/LMY48X' \
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
     *  curl 'https://www.nma.mobi/apigateway/servicegateway-mynaukri/resman-aggregator-services/v0/users/self/profiles/<>/advResume' \
     *   -H 'user-agent: Dalvik/2.1.0 (Linux; U; Android 5.1.1; Android SDK built for x86_64 Build/LMY48X' \
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
              .uri("/apigateway/servicegateway-mynaukri/resman-aggregator-services/v0/users/self/profiles/" + bearerTokenAndProfileIdAndFormKey.getT2() + "/advResume")
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
    curl 'https://www.nma.mobi/apigateway/servicegateway-mynaukri/resman-aggregator-services/v0/users/self/profiles/<>/deleteResume' \
      -X 'POST' \
      -H 'user-agent: Dalvik/2.1.0 (Linux; U; Android 5.1.1; Android SDK built for x86_64 Build/LMY48X' \
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
              .uri("/apigateway/servicegateway-mynaukri/resman-aggregator-services/v0/users/self/profiles/" + bearerTokenAndProfileId.getT2() + "/deleteResume")
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
    curl 'https://www.nma.mobi/apigateway/servicegateway-mynaukri/resman-aggregator-services/v0/users/self/dashboard' \
      -H 'user-agent: Dalvik/2.1.0 (Linux; U; Android 5.1.1; Android SDK built for x86_64 Build/LMY48X'
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
              .uri("/apigateway/servicegateway-mynaukri/resman-aggregator-services/v0/users/self/dashboard")
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
   * <a href="https://www.nma.mobi/nlogin/login">login page</a>
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
    // This value is hardcoded in apk & same value is also available if we can navigate to `naukri.com/nlogin/login`, but this url is kept behind Akamai bot blocker & its blocking us access. Since its already hardcoded in app, lets also hardcoded it.

    // Here is how you can get latest value from naukri APK
    // 1. Download the naukri apk
    // 2. Decompile apk using apktool
    // 3. Search for the following regex (case sensitive)
    // `F[0-9a-f]{12}`
    // It searches for values that start with capital `F` followed by 12 hex chars. Thats the secret number used for `formKey`. Also you can search for `formKey` aswell till you find something thats assigned to it. It will be of the format
    // You will see something of the format. Copy the value
    //`
    //    const-string v9, "formKey"
    //
    //    const-string v10, "F51f8e7e54e205"
    //
    //        .line 15
    //    invoke-virtual {v7, v9, v10}, Lc2/c0$a;->a(Ljava/lang/String;Ljava/lang/String;)Lc2/c0$a;
    //`

    return Mono.just("F51f8e7e54e205");
  }

  Mono<String> buildBearerToken$() {
    // login
    /*
    curl 'https://www.nma.mobi/central-login-services/v1/login' \
      -H 'user-agent: Dalvik/2.1.0 (Linux; U; Android 5.1.1; Android SDK built for x86_64 Build/LMY48X' \
      -H 'appid: 103' \
      -H 'systemid: jobseeker' \
      -H 'content-type: application/json' \
      --data-raw $'{"username":"<>", "password":"<>", "isLoginByEmail": true}' | jq
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
        .bodyValue(new LoginRequest(naukriProperties.username(), naukriProperties.password(), true))
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
