package com.acme;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.HashMap;

/**
 * Changing logging properties so that we can enable debugging using commandline/system property/environment variable flag.
 * This will be processed as soon as bootstrap application context is initialised but before main application context is built & refreshed so we get an opportunity to modify environment that main application context can see environment changes
 * This is made available to spring via `META-INF/spring.factories`
 */
public class LoggingDebugEnvironmentPostProcessor implements EnvironmentPostProcessor {
  @Override
  public void postProcessEnvironment(ConfigurableEnvironment environment,
      SpringApplication application) {
    final var debugEnabled = environment.containsProperty("app.debug");
    if (debugEnabled) {
      var keyToValue = new HashMap<String, Object>();
      keyToValue.put("logging.level.com.acme", "DEBUG");
      // lets disable color so that we can redirect output to a log file & not have ansi characters in log
      // pulled from default.xml of logback files from spring logging
      keyToValue.put("logging.pattern.console", "%d{${LOG_DATEFORMAT_PATTERN:yyyy-MM-dd HH:mm:ss.SSS}} ${LOG_LEVEL_PATTERN:%5p} ${PID: } --- [%t] %-40.40logger{39} : %m%n${LOG_EXCEPTION_CONVERSION_WORD:%wEx}");
      final var propertySource = new MapPropertySource("debugPropertyCustomSource", keyToValue);
      environment.getPropertySources().addLast(propertySource);
    }
  }
}
