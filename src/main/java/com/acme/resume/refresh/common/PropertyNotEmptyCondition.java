package com.acme.resume.refresh.common;

import org.springframework.boot.autoconfigure.condition.ConditionMessage;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.core.type.ClassMetadata;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;

import java.util.ArrayList;

/**
 * Enables component/bean only if all specified properties are not empty/null. All specified properties will be evaluated against {@link Environment}
 */
class PropertyNotEmptyCondition extends SpringBootCondition {

  @Override
  public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
    MultiValueMap<String, Object> attrs = metadata.getAllAnnotationAttributes(
        ConditionalOnPropertyNotEmpty.class.getName());

    String sourceClass = "";
    if (metadata instanceof ClassMetadata) {
      sourceClass = ((ClassMetadata) metadata).getClassName();
    }
    final var conditionMessageBuilder =
        ConditionMessage.forCondition(ConditionalOnPropertyNotEmpty.class, sourceClass);

    if (attrs != null) {
      for (Object value : attrs.get("value")) {
        final var propertyKeys = (String[]) value;
        final var missingProperties = new ArrayList<>();
        final var emptyProperties = new ArrayList<>();

        for (String propertyKey : propertyKeys) {
          final var exists = context.getEnvironment().containsProperty(propertyKey);
          if (!exists) {
            missingProperties.add(propertyKey);
            continue;
          }
          final String property = context.getEnvironment().getProperty(propertyKey);
          if (!StringUtils.hasText(property)) {
            emptyProperties.add(propertyKey);
          }
        }
        final var reasonBuilder = new StringBuilder();
        for (String propertyKey : propertyKeys) {
          if (missingProperties.contains(propertyKey)) {
            reasonBuilder.append("Property ").append(propertyKey).append(" could not be found. ");
          } else if (emptyProperties.contains(propertyKey)) {
            reasonBuilder.append("Property ").append(propertyKey).append(" is empty/null. ");
          } else {
            reasonBuilder.append("Property ").append(propertyKey).append(" is present & is not empty/null. ");
          }
        }

        final var matchSuccess = missingProperties.isEmpty() && emptyProperties.isEmpty();
        if (matchSuccess) {
          return ConditionOutcome.match(conditionMessageBuilder.because(reasonBuilder.toString()));
        } else {
          return ConditionOutcome.noMatch(conditionMessageBuilder.because(reasonBuilder.toString()));
        }
      }
    }
    return ConditionOutcome.match(conditionMessageBuilder.because("No properties specified. So, condition evaluation defaulted to match success"));
  }

}
