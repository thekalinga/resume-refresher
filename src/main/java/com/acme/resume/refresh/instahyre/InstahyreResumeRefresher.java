package com.acme.resume.refresh.instahyre;

import com.acme.resume.refresh.ResumeProperties;
import com.acme.resume.refresh.ResumeRefresher;
import com.acme.resume.refresh.instahyre.exchange.CandidateResponse;
import com.acme.resume.refresh.instahyre.exchange.LoginRequest;
import com.acme.resume.refresh.instahyre.exchange.SessionIdAndCsrfToken;
import com.acme.resume.refresh.instahyre.exchange.UploadResumeRequest;
import com.acme.resume.refresh.util.MiscUtil;
import lombok.extern.log4j.Log4j2;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.client.reactive.ClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.nio.file.Paths;
import java.util.regex.Pattern;

import static io.netty.handler.codec.http.cookie.ClientCookieEncoder.STRICT;
import static java.nio.file.StandardOpenOption.READ;
import static java.util.Objects.requireNonNull;
import static org.springframework.core.io.buffer.DataBufferUtils.join;
import static org.springframework.core.io.buffer.DataBufferUtils.read;
import static org.springframework.core.io.buffer.DataBufferUtils.release;
import static org.springframework.http.HttpHeaders.COOKIE;
import static org.springframework.http.HttpHeaders.REFERER;
import static org.springframework.http.HttpHeaders.USER_AGENT;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.http.HttpMethod.PUT;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.util.Base64Utils.encodeToString;

@Log4j2
@Component
@EnableConfigurationProperties(InstahyreProperties.class)
@Order(1) // lets start updating resume here 1st
@SuppressWarnings("unused") // since we are dealing with a component
public class InstahyreResumeRefresher implements ResumeRefresher {

  private static final String CSRF_COOKIE_NAME = "csrftoken";
  private static final String CSRF_HEADER_NAME = "x-csrftoken";
  private static final String SESSION_ID_COOKIE_NAME = "sessionid";

  private final InstahyreProperties instahyreProperties;
  private final ResumeProperties resumeProperties;
  private final WebClient webClient;

  @SuppressWarnings("unused") // since we are dealing with a component
  public InstahyreResumeRefresher(InstahyreProperties instahyreProperties, ResumeProperties resumeProperties, ClientHttpConnector clientHttpConnector) {
    this.instahyreProperties = instahyreProperties;
    this.resumeProperties = resumeProperties;
    this.webClient =
        WebClient.builder().baseUrl("https://www.instahyre.com/")
            .clientConnector(clientHttpConnector)
            .defaultHeaders(httpHeaders -> {
              httpHeaders.add(USER_AGENT, "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/104.0.5112.81 Safari/537.36");
              httpHeaders.add(REFERER, "https://www.instahyre.com/");
            })
            .build();
  }

  @Override
  public Mono<Void> refresh() {
    /*
    curl 'https://www.instahyre.com/api/v1/user_login' \
      -H 'content-type: application/json' \
      -H 'user-agent: Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/104.0.5112.81 Safari/537.36' \
      --data-raw $'{"email":"<>","password":"<>"}'

    get `csrftoken` & `sessionid` from `set-cookie` response headers
     */
    Mono<SessionIdAndCsrfToken> sessionIdAndCsrfToken$ = webClient
        .method(POST)
        .uri("/api/v1/user_login")
        .contentType(APPLICATION_JSON)
        .bodyValue(new LoginRequest(instahyreProperties.username(), instahyreProperties.password()))
        .exchangeToMono(response -> {
          final var sessionId = requireNonNull(response.cookies().getFirst(SESSION_ID_COOKIE_NAME)).getValue();
          final var csrfToken = requireNonNull(response.cookies().getFirst(CSRF_COOKIE_NAME)).getValue();
          return Mono.just(new SessionIdAndCsrfToken(sessionId, csrfToken));
        })
        .doOnSubscribe(__ -> log.info("Attempting to fetch session id & csrf token"))
        .doFinally(signal -> log.info("Finished attempt to fetch session id & csrf token. Final signal received is {}", signal))
        .cache();

    /*
    curl 'https://www.instahyre.com/candidate/profile/' \
      -H 'cookie: sessionid=<>' \
      -H 'user-agent: Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/104.0.5112.81 Safari/537.36'

    get `candidateId = '<\d+>',` from response html
     */
    final Pattern candidateIdPattern = Pattern.compile("candidateId\s*=\s*'(?<candidateId>\\d+)'");
    Mono<String> candidateId$ = sessionIdAndCsrfToken$
        .flatMap(sessionIdAndCsrfToken -> {
          //noinspection CodeBlock2Expr
          return webClient
            .method(GET)
            .uri("/candidate/profile/")
            .cookie(SESSION_ID_COOKIE_NAME, sessionIdAndCsrfToken.sessionId())
            .exchangeToMono(response -> {
              //noinspection CodeBlock2Expr
              return response.bodyToFlux(DataBuffer.class).as(MiscUtil::readAllBuffersAsUtf8String);
            })
            .map(bodyAsStr -> {
              final var matcher = candidateIdPattern.matcher(bodyAsStr);
              // noinspection ResultOfMethodCallIgnored
              matcher.find();
              return matcher.group("candidateId");
            })
            .doOnSubscribe(__ -> log.info("Attempting to retrieve candidateId"))
            .doFinally(signal -> log.info("Finished attempt to retrieve candidateId. Terminal signal received is {}", signal));
        })
        .cache();

    /*
    curl -v 'https://www.instahyre.com/api/v1/candidate/<candidateId>' \
      -H 'cookie: sessionid=<>' \
      -H 'user-agent: Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/104.0.5112.81 Safari/537.36'

    get resume.id from json response. its `id` property of response json
     */
    Mono<Long> resumeId$ = candidateId$.zipWith(sessionIdAndCsrfToken$)
        .flatMap(candidateIdPlusSessionIdAndCsrfToken -> {
          //noinspection CodeBlock2Expr
          return webClient
              .method(GET)
              .uri("/api/v1/candidate/" + candidateIdPlusSessionIdAndCsrfToken.getT1())
              .cookie(SESSION_ID_COOKIE_NAME, candidateIdPlusSessionIdAndCsrfToken.getT2().sessionId())
              .retrieve()
              .bodyToMono(CandidateResponse.class)
              .map(candidateResponse -> candidateResponse.resume().id())
              .doOnSubscribe(__ -> log.info("Attempting to retrieve resumeId"))
              .doFinally(signal -> log.info("Finished attempt to retrieve resumeId. Terminal signal received is {}", signal));
        })
        .cache();

    /*
    curl 'https://www.instahyre.com/api/v1/resume/<resumeId>' \
      -X 'PUT' \
      -H 'content-type: application/json' \
      -H 'cookie: csrftoken=<csrf>; sessionid=<sessionid>' \
      -H 'referer: https://www.instahyre.com/' \
      -H 'user-agent: Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/104.0.5112.81 Safari/537.36' \
      -H 'x-csrftoken: <csrf>' \
      --data-raw '{"calculate_opps":true,"candidate":"/api/v1/limited_candidate/<candidateId>","resource_uri":"/api/v1/resume/<resumeId>","file_b64":"data:application/pdf;base64,<base64 content>","title":"<>"}'
     */
    Mono<Void> uploadResume$ = Mono.zip(candidateId$, resumeId$, sessionIdAndCsrfToken$)
        .flatMap(candidateIdPlusResumeIdPlusSessionIdAndCsrfToken -> {
          final var candidateId = candidateIdPlusResumeIdPlusSessionIdAndCsrfToken.getT1();
          final var resumeId = candidateIdPlusResumeIdPlusSessionIdAndCsrfToken.getT2();
          final var sessionIdAndCsrfToken = candidateIdPlusResumeIdPlusSessionIdAndCsrfToken.getT3();

          var resumePath = Paths.get(resumeProperties.path());
          var uploadResumeRequest$ = join(read(resumePath, new DefaultDataBufferFactory(), 32 * 1024, READ))
              .map(aggregateBuffer -> {
                final var allBytes = new byte[aggregateBuffer.readableByteCount()];
                aggregateBuffer.read(allBytes);
                release(aggregateBuffer);
                return encodeToString(allBytes);
              })
              .map(base64FileContent -> {
                //noinspection CodeBlock2Expr
                return new UploadResumeRequest(
                    "/api/v1/limited_candidate/" + candidateId,
                    "/api/v1/resume/" + resumeId,
                    resumeProperties.filename(),
                    base64FileContent,
                    true
                );
              });

          return webClient
              .method(PUT)
              .uri("/api/v1/resume/" + resumeId)
              .headers(headers -> {
                // we cant use cookie(name, value).cookie(name, value) as the server is not spec complaint i.e it expects all cookies to be sent in a single cookie rather than multiple cookies
                headers.add(COOKIE, STRICT.encode(SESSION_ID_COOKIE_NAME, sessionIdAndCsrfToken.sessionId()) + "; " + STRICT.encode(CSRF_COOKIE_NAME, sessionIdAndCsrfToken.csrfToken()));
              })
//              .cookie(SESSION_ID_COOKIE_NAME, sessionIdAndCsrfToken.sessionId())
//              .cookie(CSRF_COOKIE_NAME, sessionIdAndCsrfToken.csrfToken())
              .header(CSRF_HEADER_NAME, sessionIdAndCsrfToken.csrfToken())
              .body(uploadResumeRequest$, UploadResumeRequest.class)
              .retrieve().bodyToMono(Void.class)
              .doOnSubscribe(__ -> log.info("Attempting to upload resume"))
              .doFinally(signal -> log.info("Finished attempt to upload resume. Terminal signal received is {}", signal));
        });

    return uploadResume$
        .doOnSubscribe(__ -> log.info("Attempting to refresh resume on Instahyre"))
        .doFinally(signal -> log.info("Finished attempt to refresh resume on Instahyre. Final signal received is {}", signal));
  }
}
