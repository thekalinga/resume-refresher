package com.acme.resume.refresh.common;

import reactor.core.publisher.Mono;

public interface ResumeRefresher {
  Mono<Void> refresh();
}
