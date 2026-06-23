package io.avaje.openapi.generator;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URISyntaxException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OpenApiGeneratorTest {

  @TempDir
  Path tempDir;

  @Test
  void generateContractSources() throws Exception {
    var input = resourcePath("openapi/pets.yaml");
    var config = GeneratorConfig.builder(input, tempDir, "org.example.api").build();

    var result = new OpenApiGenerator().generate(config);

    assertThat(result.diagnostics())
      .filteredOn(it -> it.severity() == DiagnosticSeverity.ERROR)
      .isEmpty();
    assertThat(result.generatedFiles())
      .extracting(file -> file.path().getFileName().toString())
      .containsExactlyInAnyOrder("CreatePetRequest.java", "Pet.java", "PetStatus.java", "PetsApi.java");

    assertThat(tempDir.resolve("org/example/api/PetsApi.java"))
      .content()
      .contains("@Client")
      .contains("@Path(\"/pets\")")
      .contains("@Get")
      .contains("@Post")
      .contains("List<Pet> listPets(@QueryParam(\"limit\") Integer limit, @QueryParam(\"status\") PetStatus status)")
      .contains("Stream<Pet> streamPets(@QueryParam(\"status\") PetStatus status)")
      .contains("import java.util.stream.Stream;")
      .contains("Pet getPet(Long id, @Header(\"X-Request-Id\") String xRequestId, @QueryParam(\"useMaster\") @Default(\"false\") boolean useMaster)")
      .contains("import io.avaje.http.api.Default;");

    assertThat(tempDir.resolve("org/example/api/PetsApi.java"))
      .content()
      .doesNotContain("@Produces(value = \"application/stream+json\")");

    assertThat(tempDir.resolve("org/example/api/model/Pet.java"))
      .content()
      .doesNotContain("@RecordBuilder")
      .contains("@Json")
      .contains("@NotNull @Min(1) Long id")
      .contains("@NotNull @Size(min = 1, max = 100) String name")
      .contains("Instant createdAt")
      .contains("LocalDate birthDate")
      .contains("UUID externalId")
      .contains("Map<String, String> attributes");
  }

  @Test
  void generateContractSourcesWithRecordBuilder() throws Exception {
    var input = resourcePath("openapi/pets.yaml");
    var config = GeneratorConfig.builder(input, tempDir, "org.example.api")
      .validationStyle(ValidationStyle.AVAJE)
      .recordBuilder(true)
      .build();

    var result = new OpenApiGenerator().generate(config);

    assertThat(result.diagnostics())
      .filteredOn(it -> it.severity() == DiagnosticSeverity.ERROR)
      .isEmpty();
    assertThat(tempDir.resolve("org/example/api/model/Pet.java"))
      .content()
      .contains("import io.avaje.validation.constraints.NotNull;")
      .contains("import io.avaje.validation.constraints.Size;")
      .doesNotContain("jakarta.validation.constraints")
      .contains("import io.avaje.recordbuilder.RecordBuilder;")
      .contains("@RecordBuilder")
      .contains("@Json")
      .contains("public record Pet(")
      .contains("public static PetBuilder builder()")
      .contains("return PetBuilder.builder();")
      .contains("public static PetBuilder builder(Pet from)")
      .contains("return PetBuilder.builder(from);");
    assertThat(tempDir.resolve("org/example/api/model/PetStatus.java"))
      .content()
      .doesNotContain("@RecordBuilder")
      .doesNotContain("builder()");
  }

  @Test
  void generateApiOnlyWhenModelsDisabled() throws Exception {
    var input = resourcePath("openapi/pets.yaml");
    var config = GeneratorConfig.builder(input, tempDir, "org.example.api")
      .generateModels(false)
      .build();

    var result = new OpenApiGenerator().generate(config);

    assertThat(result.diagnostics())
      .filteredOn(it -> it.severity() == DiagnosticSeverity.ERROR)
      .isEmpty();
    assertThat(result.generatedFiles())
      .extracting(file -> file.path().getFileName().toString())
      .containsExactly("PetsApi.java");

    assertThat(tempDir.resolve("org/example/api/PetsApi.java"))
      .content()
      .contains("import org.example.api.model.Pet;")
      .contains("import org.example.api.model.PetStatus;")
      .contains("List<Pet> listPets(");
  }

  private static Path resourcePath(String name) throws URISyntaxException {
    return Path.of(OpenApiGeneratorTest.class.getClassLoader().getResource(name).toURI());
  }
}
