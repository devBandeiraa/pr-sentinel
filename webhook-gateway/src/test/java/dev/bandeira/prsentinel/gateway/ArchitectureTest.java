package dev.bandeira.prsentinel.gateway;

import com.tngtech.archunit.core.domain.JavaClasses;
import dev.bandeira.prsentinel.common.architecture.LayerRules;
import org.junit.jupiter.api.Test;

class ArchitectureTest {

  private static final JavaClasses CLASSES =
      LayerRules.classesOf("dev.bandeira.prsentinel.gateway");

  @Test
  void dominioNaoDependeDeInfraestrutura() {
    LayerRules.domainDoesNotDependOnInfrastructure().check(CLASSES);
  }

  @Test
  void dominioNaoDependeDeFramework() {
    LayerRules.domainDoesNotDependOnSpring().check(CLASSES);
  }

  @Test
  void aplicacaoNaoDependeDeInfraestrutura() {
    LayerRules.applicationDoesNotDependOnInfrastructure().check(CLASSES);
  }
}
