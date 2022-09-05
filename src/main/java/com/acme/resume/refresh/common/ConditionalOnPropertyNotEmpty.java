package com.acme.resume.refresh.common;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.env.Environment;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Enables component/bean only if all specified properties are not empty/null. All specified properties will be evaluated against {@link Environment}.
 *
 * This exists because {@link ConditionalOnProperty} is not {@link Repeatable} & also it evaluates to true if the property is empty
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Conditional(PropertyNotEmptyCondition.class)
public @interface ConditionalOnPropertyNotEmpty {

  /**
   * Properties to check to not be empty/null. They will be evaluated against {@link Environment}
   */
  String[] value();

}
