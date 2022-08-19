package com.acme.resume.refresh;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.resume")
public record ResumeProperties(String path, String filename) {
}
