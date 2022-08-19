package com.acme.resume.refresh;

import reactor.core.publisher.Mono;

public interface ResumeRefresher {
  Mono<Void> refresh();
}
