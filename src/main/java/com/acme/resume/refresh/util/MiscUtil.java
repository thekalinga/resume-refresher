package com.acme.resume.refresh.util;

import lombok.experimental.UtilityClass;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.springframework.core.io.buffer.DataBufferUtils.release;

@UtilityClass
public class MiscUtil {
  public static Mono<String> readAllBuffersAsUtf8String(Flux<DataBuffer> source) {
    return source.as(DataBufferUtils::join)
        .map(accumulator -> {
          final var responseBodyAsStr = accumulator.toString(UTF_8);
          release(accumulator);
          return responseBodyAsStr;
        });
  }
}
