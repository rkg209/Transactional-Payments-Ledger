package org.ledger.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Enforces planning/03-system-design.md §1.2's module dependency matrix, plus the ledger-entry
 * write rule (invariant #2).
 *
 * <p>Packages this SPEC does not build yet (api.dto, idempotency, saga, reconciliation,
 * concurrency) simply have no classes to analyze, so their rules pass vacuously until those specs
 * add classes -- at which point these same rules start enforcing the boundary immediately.
 *
 * <p>Honest limitation: ArchUnit checks <b>type references</b>, not SQL verbs. "No UPDATE/DELETE
 * against ledger_entries" is enforced by three layers together, none sufficient alone: (1) {@code
 * LedgerEntryRepository} exposes only inserts and reads -- no update/delete method exists there or
 * anywhere; (2) the rule below restricts who may even reference the generated {@code LedgerEntries}
 * types; (3) the runtime {@code ledger_entries_immutable_tg} trigger, proven by {@code
 * LedgerImmutabilityIT}.
 */
@AnalyzeClasses(packages = "org.ledger", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureFitnessTest {

  @ArchTest
  static final ArchRule api_must_not_depend_on_db_generated_or_concurrency =
      noClasses()
          .that()
          .resideInAPackage("org.ledger.api..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("org.ledger.db.generated..", "org.ledger.concurrency..");

  @ArchTest
  static final ArchRule idempotency_must_not_depend_on_domain_modules =
      noClasses()
          .that()
          .resideInAPackage("org.ledger.idempotency..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "org.ledger.transfer..",
              "org.ledger.saga..",
              "org.ledger.account..",
              "org.ledger.reconciliation..");

  @ArchTest
  static final ArchRule transfer_must_not_depend_on_forbidden_modules =
      noClasses()
          .that()
          .resideInAPackage("org.ledger.transfer..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("org.ledger.api..", "org.ledger.idempotency..", "org.ledger.saga..");

  @ArchTest
  static final ArchRule account_must_not_depend_on_forbidden_modules =
      noClasses()
          .that()
          .resideInAPackage("org.ledger.account..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "org.ledger.api..",
              "org.ledger.idempotency..",
              "org.ledger.transfer..",
              "org.ledger.saga..",
              "org.ledger.concurrency..");

  @ArchTest
  static final ArchRule saga_must_not_depend_on_forbidden_modules =
      noClasses()
          .that()
          .resideInAPackage("org.ledger.saga..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "org.ledger.api..", "org.ledger.idempotency..", "org.ledger.concurrency..");

  @ArchTest
  static final ArchRule reconciliation_must_not_depend_on_forbidden_modules =
      noClasses()
          .that()
          .resideInAPackage("org.ledger.reconciliation..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "org.ledger.api..",
              "org.ledger.idempotency..",
              "org.ledger.transfer..",
              "org.ledger.saga..",
              "org.ledger.concurrency..");

  @ArchTest
  static final ArchRule concurrency_must_not_depend_on_forbidden_modules =
      noClasses()
          .that()
          .resideInAPackage("org.ledger.concurrency..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "org.ledger.transfer..",
              "org.ledger.saga..",
              "org.ledger.idempotency..",
              "org.ledger.api..");

  @ArchTest
  static final ArchRule db_must_not_depend_on_domain_logic =
      noClasses()
          .that()
          .resideInAPackage("org.ledger.db")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "org.ledger.api..",
              "org.ledger.idempotency..",
              "org.ledger.transfer..",
              "org.ledger.account..",
              "org.ledger.saga..",
              "org.ledger.reconciliation..",
              "org.ledger.concurrency..");

  /**
   * The headline rule: only LedgerEntryRepository may reference the LedgerEntries generated types.
   *
   * <p>Excludes {@code org.ledger.db.generated} itself from the {@code that()} side: jOOQ's own
   * generated {@code Tables}, {@code Keys}, {@code Public}, and {@code DefaultCatalog} classes
   * cross-reference every generated table (including LedgerEntries) as part of the schema's own
   * internal wiring. That is codegen plumbing, not application code touching ledger entries, so it
   * is out of scope for this rule.
   */
  @ArchTest
  static final ArchRule only_ledger_entry_repository_touches_ledger_entries_generated_types =
      noClasses()
          .that(
              DescribedPredicate.not(JavaClass.Predicates.simpleName("LedgerEntryRepository"))
                  .and(
                      DescribedPredicate.not(
                          JavaClass.Predicates.resideInAPackage("org.ledger.db.generated.."))))
          .should()
          .dependOnClassesThat(
              JavaClass.Predicates.resideInAPackage("org.ledger.db.generated..")
                  .and(JavaClass.Predicates.simpleNameStartingWith("LedgerEntries")));
}
