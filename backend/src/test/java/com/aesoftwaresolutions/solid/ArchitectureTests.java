package com.aesoftwaresolutions.solid;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ArchitectureTests {

    static final JavaClasses PRODUCTION = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.aesoftwaresolutions.solid");

    /** Spec 001, AC 4: modules only depend on each other's public APIs. */
    @Test
    void modulesRespectBoundaries() {
        ApplicationModules.of(SolidApplication.class).verify();
    }

    /** Spec 001, AC 5: money must never be floating point. */
    @Test
    void noFloatingPointFields() {
        fields().should().notHaveRawType(double.class)
                .andShould().notHaveRawType(Double.class)
                .andShould().notHaveRawType(float.class)
                .andShould().notHaveRawType(Float.class)
                .allowEmptyShould(true)
                .because("money and quantities must use exact types (see CLAUDE.md)")
                .check(PRODUCTION);
    }

    @Test
    void noFloatingPointMethodParametersOrReturns() {
        noMethods().should().haveRawReturnType(double.class)
                .orShould().haveRawReturnType(float.class)
                .orShould().haveRawReturnType(Double.class)
                .orShould().haveRawReturnType(Float.class)
                .allowEmptyShould(true)
                .check(PRODUCTION);
        noClasses().should().callConstructor(java.math.BigDecimal.class, double.class)
                .because("new BigDecimal(double) is inexact; use BigDecimal.valueOf or String")
                .allowEmptyShould(true)
                .check(PRODUCTION);
    }
}
