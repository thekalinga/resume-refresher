package com.acme.resume.refresh.common;

import org.springframework.boot.context.properties.ConfigurationProperties;

import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;

@ConfigurationProperties("app.resume")
public record ResumeProperties(@NotEmpty String path, @NotEmpty String filename) {
}
