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
      .contains("OffsetDateTime createdAt")
      .contains("Instant updatedAt")
      .contains("ZonedDateTime auditedAt")
      .contains("import java.time.OffsetDateTime;")
      .contains("import java.time.Instant;")
      .contains("import java.time.ZonedDateTime;")
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

  @Test
  void dateTimeTypeInstantGlobalOption() throws Exception {
    var input = resourcePath("openapi/pets.yaml");
    var config = GeneratorConfig.builder(input, tempDir, "org.example.api")
      .dateTimeType(DateTimeType.INSTANT)
      .build();

    var result = new OpenApiGenerator().generate(config);

    assertThat(result.diagnostics())
      .filteredOn(it -> it.severity() == DiagnosticSeverity.ERROR)
      .isEmpty();

    assertThat(tempDir.resolve("org/example/api/model/Pet.java"))
      .content()
      // format: date-time follows the global option
      .contains("Instant createdAt")
      // format: instant is always Instant
      .contains("Instant updatedAt")
      // x-java-type still overrides the global option
      .contains("ZonedDateTime auditedAt")
      .doesNotContain("OffsetDateTime");
  }

  @Test
  void generateOverloadsNullableOnlyWithExplicitOverride() throws Exception {
    var input = resourcePath("openapi/pets.yaml");
    var config = GeneratorConfig.builder(input, tempDir, "org.example.api")
      .generateOverloads(true)
      .build();

    var result = new OpenApiGenerator().generate(config);

    assertThat(result.diagnostics())
      .filteredOn(it -> it.severity() == DiagnosticSeverity.ERROR)
      .isEmpty();

    assertThat(tempDir.resolve("org/example/api/PetsApi.java"))
      .content()
      // NULLABLE_ONLY: both optional no-default params are a trailing droppable run
      .contains("default List<Pet> listPets(Integer limit) {")
      .contains("return listPets(limit, null);")
      .contains("default List<Pet> listPets() {")
      .contains("return listPets(null, null);")
      // streaming endpoint gets a no-arg overload
      .contains("default Stream<Pet> streamPets() {")
      .contains("return streamPets(null);")
      // x-overload: true makes the defaulted useMaster droppable (passes its default);
      // x-overload: false on the header stops the trailing run, so only useMaster drops
      .contains("default Pet getPet(Long id, String xRequestId) {")
      .contains("return getPet(id, xRequestId, false);")
      .doesNotContain("default Pet getPet(Long id) {");
  }

  @Test
  void noOverloadsByDefault() throws Exception {
    var input = resourcePath("openapi/pets.yaml");
    var config = GeneratorConfig.builder(input, tempDir, "org.example.api").build();

    var result = new OpenApiGenerator().generate(config);

    assertThat(result.diagnostics())
      .filteredOn(it -> it.severity() == DiagnosticSeverity.ERROR)
      .isEmpty();
    assertThat(tempDir.resolve("org/example/api/PetsApi.java"))
      .content()
      .doesNotContain("default ");
  }

  @Test
  void serverBasePathBecomesInterfacePath() throws Exception {
    var input = resourcePath("openapi/versioned.yaml");
    var config = GeneratorConfig.builder(input, tempDir, "org.example.api").build();

    var result = new OpenApiGenerator().generate(config);

    assertThat(result.diagnostics())
      .filteredOn(it -> it.severity() == DiagnosticSeverity.ERROR)
      .isEmpty();

    assertThat(tempDir.resolve("org/example/api/StoreApi.java"))
      .content()
      // path component of the servers URL drives @Path; the two resources share no
      // literal prefix so the @Path is exactly the server base
      .contains("@Path(\"/v1\")")
      .contains("@Get(\"/pets/{id}\")")
      .contains("@Get(\"/owners/{id}\")");
  }

  @Test
  void allOfMergeFlattensComposition() throws Exception {
    var input = resourcePath("openapi/composition.yaml");
    var config = GeneratorConfig.builder(input, tempDir, "org.example.api").build();

    var result = new OpenApiGenerator().generate(config);

    // allOf no longer produces an "unsupported" diagnostic
    assertThat(result.diagnostics())
      .filteredOn(it -> it.severity() == DiagnosticSeverity.ERROR)
      .isEmpty();

    // allOf members ($ref base + inline extension) flatten into one record;
    // inline object/array/map schemas are extracted into named records by the parser
    assertThat(result.generatedFiles())
      .extracting(file -> file.path().getFileName().toString())
      .contains(
        "Animal.java",
        "Dog.java",
        "Pet.java",
        "PetHomeAddress.java",
        "PetHomeAddressCountry.java",
        "PetTags.java",
        "PetMetadata.java");

    // allOf merge: Animal fields (incl required id) + Dog fields (incl required breed)
    assertThat(tempDir.resolve("org/example/api/model/Dog.java"))
      .content()
      .contains("public record Dog(")
      .contains("@NotNull Long id")
      .contains("String name")
      .contains("@NotNull String breed")
      .contains("Integer barkVolume");

    // inline object property, array-of-inline-object, map-of-inline-object
    assertThat(tempDir.resolve("org/example/api/model/Pet.java"))
      .content()
      .contains("@NotNull Long id")
      .contains("PetHomeAddress homeAddress")
      .contains("List<PetTags> tags")
      .contains("Map<String, PetMetadata> metadata");

    // deep nesting: an inline object inside an inline object
    assertThat(tempDir.resolve("org/example/api/model/PetHomeAddress.java"))
      .content()
      .contains("@NotNull String city")
      .contains("PetHomeAddressCountry country");

    assertThat(tempDir.resolve("org/example/api/model/PetMetadata.java"))
      .content()
      .contains("Integer score");
  }

  @Test
  void javadocAndDeprecatedFromSpec() throws Exception {
    var input = resourcePath("openapi/documented.yaml");
    var config = GeneratorConfig.builder(input, tempDir, "org.example.api").build();

    var result = new OpenApiGenerator().generate(config);

    assertThat(result.diagnostics())
      .filteredOn(it -> it.severity() == DiagnosticSeverity.ERROR)
      .isEmpty();

    // model: type description as Javadoc + @param per documented field
    assertThat(tempDir.resolve("org/example/api/model/Widget.java"))
      .content()
      .contains("/**")
      .contains(" * A widget that does widget things.")
      .contains(" * @param id The unique widget identifier")
      .contains(" * @param name A human readable widget name")
      .contains("public record Widget(");

    // model: schema-level deprecated -> @Deprecated annotation
    assertThat(tempDir.resolve("org/example/api/model/OldWidget.java"))
      .content()
      .contains(" * Superseded by Widget.")
      .contains("@Deprecated")
      .contains("public record OldWidget(");

    // enum: description as Javadoc
    assertThat(tempDir.resolve("org/example/api/model/WidgetStatus.java"))
      .content()
      .contains(" * The lifecycle status of a widget.")
      .contains("public enum WidgetStatus {");

    // operation: summary + description Javadoc, @param and @return tags
    assertThat(tempDir.resolve("org/example/api/WidgetsApi.java"))
      .content()
      .contains(" * Fetch a widget")
      .contains(" * Returns a single widget by its identifier.")
      .contains(" * @param id The widget identifier")
      .contains(" * @return The requested widget")
      // operation-level deprecated -> @Deprecated on the method
      .contains("  @Deprecated")
      .contains(" * List legacy widgets");
  }

  private static Path resourcePath(String name) throws URISyntaxException {
    return Path.of(OpenApiGeneratorTest.class.getClassLoader().getResource(name).toURI());
  }
}
