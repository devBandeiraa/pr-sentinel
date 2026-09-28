package dev.bandeira.prsentinel.common.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Regras de arquitetura em camadas aplicadas por cada serviço.
 *
 * <p>domain concentra regra de negócio pura: não pode conhecer infrastructure nem framework.
 * application orquestra casos de uso sobre o domain e também não conhece infrastructure — depende
 * só de portas declaradas no próprio serviço.
 */
public final class LayerRules {

  private static final String DOMAIN = "..domain..";
  private static final String APPLICATION = "..application..";
  private static final String INFRASTRUCTURE = "..infrastructure..";

  private LayerRules() {}

  /** Importa as classes de produção de um serviço, ignorando as de teste. */
  public static JavaClasses classesOf(String basePackage) {
    return new ClassFileImporter()
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        .importPackages(basePackage);
  }

  public static ArchRule domainDoesNotDependOnInfrastructure() {
    return noClasses()
        .that()
        .resideInAPackage(DOMAIN)
        .should()
        .dependOnClassesThat()
        .resideInAPackage(INFRASTRUCTURE)
        .because("domain guarda regra de negócio pura e não pode conhecer detalhes de I/O");
  }

  public static ArchRule domainDoesNotDependOnSpring() {
    return noClasses()
        .that()
        .resideInAPackage(DOMAIN)
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("org.springframework..", "jakarta.persistence..", "com.fasterxml..")
        .because("domain precisa ser testável sem subir contexto de framework");
  }

  public static ArchRule applicationDoesNotDependOnInfrastructure() {
    return noClasses()
        .that()
        .resideInAPackage(APPLICATION)
        .should()
        .dependOnClassesThat()
        .resideInAPackage(INFRASTRUCTURE)
        .because("casos de uso falam com portas, não com adaptadores concretos");
  }
}
