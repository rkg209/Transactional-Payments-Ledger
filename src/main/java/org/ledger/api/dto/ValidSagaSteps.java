package org.ledger.api.dto;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Class-level constraint on {@link CreateSagaTransferRequest}: the flat {@code steps} list must be
 * an even-length, alternating {@code (DEBIT, CREDIT)} sequence of equal-amount, different-account
 * pairs (ADR 0008's chain-of-legs model). Deliberately a bean-validation constraint, not ad hoc
 * controller logic, so a violation flows through the existing {@code @Valid} / {@code
 * MethodArgumentNotValidException} -> {@code 400 VALIDATION_ERROR} path already wired in {@code
 * GlobalExceptionHandler}, instead of adding a new error code.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = SagaStepsValidator.class)
public @interface ValidSagaSteps {

  String message() default
      "steps must be an even-length, alternating DEBIT/CREDIT sequence of equal-amount pairs on"
          + " different accounts";

  Class<?>[] groups() default {};

  Class<? extends Payload>[] payload() default {};
}
