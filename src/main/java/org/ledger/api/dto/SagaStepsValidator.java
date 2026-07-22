package org.ledger.api.dto;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.util.List;

/**
 * See {@link ValidSagaSteps}. Null-tolerant throughout: {@code @NotNull} on the individual fields
 * reports those violations separately; this validator only judges the pairing, once every field it
 * needs is present.
 */
public class SagaStepsValidator
    implements ConstraintValidator<ValidSagaSteps, CreateSagaTransferRequest> {

  @Override
  public boolean isValid(CreateSagaTransferRequest request, ConstraintValidatorContext context) {
    if (request == null || request.steps() == null) {
      return true;
    }
    List<SagaStepRequest> steps = request.steps();
    if (steps.size() < 2 || steps.size() % 2 != 0) {
      return false;
    }
    for (int i = 0; i < steps.size(); i += 2) {
      if (!isValidPair(steps.get(i), steps.get(i + 1))) {
        return false;
      }
    }
    return true;
  }

  private static boolean isValidPair(SagaStepRequest debit, SagaStepRequest credit) {
    if (debit == null || credit == null) {
      return false;
    }
    if (!"DEBIT".equals(debit.type()) || !"CREDIT".equals(credit.type())) {
      return false;
    }
    if (debit.amount() == null || !debit.amount().equals(credit.amount())) {
      return false;
    }
    return debit.accountId() != null
        && credit.accountId() != null
        && !debit.accountId().equals(credit.accountId());
  }
}
