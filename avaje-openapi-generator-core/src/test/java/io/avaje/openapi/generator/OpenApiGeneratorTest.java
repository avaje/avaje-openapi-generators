package io.avaje.openapi.generator;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.Map;
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
      .contains("List<Pet> listPets(@Nullable @QueryParam(\"limit\") Integer limit, @Nullable @QueryParam(\"status\") PetStatus status)")
      .contains("Stream<Pet> streamPets(@Nullable @QueryParam(\"status\") PetStatus status)")
      .contains("import java.util.stream.Stream;")
      .contains("Pet getPet(Long id, @Nullable @Header(\"X-Request-Id\") String xRequestId, @QueryParam(\"useMaster\") @Default(\"false\") boolean useMaster)")
      .contains("import io.avaje.http.api.Default;")
      .contains("import org.jspecify.annotations.Nullable;");

    assertThat(tempDir.resolve("org/example/api/PetsApi.java"))
      .content()
      .doesNotContain("@Produces(value = \"application/stream+json\")");

    assertThat(tempDir.resolve("org/example/api/model/Pet.java"))
      .content()
      .doesNotContain("@RecordBuilder")
      .contains("@Json")
      .contains("@Min(1) long id")
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
  void requiredNonNullableScalarsUsePrimitives() throws Exception {
    var input = resourcePath("openapi/primitives.yaml");
    var config = GeneratorConfig.builder(input, tempDir, "org.example.api").build();

    var result = new OpenApiGenerator().generate(config);

    assertThat(result.diagnostics())
      .filteredOn(it -> it.severity() == DiagnosticSeverity.ERROR)
      .isEmpty();

    assertThat(tempDir.resolve("org/example/api/model/Sample.java"))
      .content()
      // required + non-nullable scalars -> primitive, with no redundant @NotNull
      .contains("int reqInt")
      .contains("long reqLong")
      .contains("boolean reqBool")
      .contains("double reqDouble")
      .doesNotContain("@NotNull int")
      .doesNotContain("@NotNull long")
      .doesNotContain("@NotNull boolean")
      .doesNotContain("@NotNull double")
      // required reference types keep @NotNull (no primitive form)
      .contains("@NotNull BigDecimal reqDecimal")
      .contains("@NotNull String reqString")
      // required + nullable stays boxed (must be able to hold null)
      .contains("@Nullable Integer reqNullableInt")
      .doesNotContain("int reqNullableInt")
      // optional (not required) stays boxed
      .contains("Integer optInt")
      .contains("Boolean optBool");
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
      .contains("long id")
      .doesNotContain("@NotNull Long id")
      .contains("String name")
      .contains("@NotNull String breed")
      .contains("Integer barkVolume");

    // inline object property, array-of-inline-object, map-of-inline-object
    assertThat(tempDir.resolve("org/example/api/model/Pet.java"))
      .content()
      .contains("long id")
      .doesNotContain("@NotNull Long id")
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

  @Test
  void nullableAnnotationFromSpec() throws Exception {
    var input = resourcePath("openapi/nullable.yaml");
    var config = GeneratorConfig.builder(input, tempDir, "org.example.api").build();

    var result = new OpenApiGenerator().generate(config);

    assertThat(result.diagnostics())
      .filteredOn(it -> it.severity() == DiagnosticSeverity.ERROR)
      .isEmpty();

    // optional (required: false) query param -> @Nullable; path and required params not
    assertThat(tempDir.resolve("org/example/api/ThingsApi.java"))
      .content()
      .contains("import org.jspecify.annotations.Nullable;")
      .contains("Thing getThing(Long id, @Nullable @QueryParam(\"filter\") String filter, @QueryParam(\"page\") Integer page)");

    // model: nullable:true field -> @Nullable; required+nullable suppresses @NotNull
    assertThat(tempDir.resolve("org/example/api/model/Thing.java"))
      .content()
      .contains("import org.jspecify.annotations.Nullable;")
      .contains("long id")
      .doesNotContain("@NotNull Long id")
      .contains("@Nullable String code")
      .contains("@Nullable String note")
      .doesNotContain("@NotNull String code");
  }

  @Test
  void nullableAnnotationDisabledWhenBlank() throws Exception {
    var input = resourcePath("openapi/nullable.yaml");
    var config = GeneratorConfig.builder(input, tempDir, "org.example.api")
      .nullableAnnotation("")
      .build();

    var result = new OpenApiGenerator().generate(config);

    assertThat(result.diagnostics())
      .filteredOn(it -> it.severity() == DiagnosticSeverity.ERROR)
      .isEmpty();

    assertThat(tempDir.resolve("org/example/api/ThingsApi.java"))
      .content()
      .doesNotContain("@Nullable")
      .doesNotContain("org.jspecify");

    // with @Nullable disabled, a required+nullable field falls back to @NotNull
    assertThat(tempDir.resolve("org/example/api/model/Thing.java"))
      .content()
      .doesNotContain("@Nullable")
      .contains("@NotNull String code");
  }

  @Test
  void nullableAnnotationDisabledWithNoneSentinel() throws Exception {
    var input = resourcePath("openapi/nullable.yaml");
    // NONE (case-insensitive) disables @Nullable just like blank; it exists because
    // Maven collapses an empty configuration element to the parameter default
    var config = GeneratorConfig.builder(input, tempDir, "org.example.api")
      .nullableAnnotation("NONE")
      .build();

    var result = new OpenApiGenerator().generate(config);

    assertThat(result.diagnostics())
      .filteredOn(it -> it.severity() == DiagnosticSeverity.ERROR)
      .isEmpty();

    assertThat(tempDir.resolve("org/example/api/ThingsApi.java"))
      .content()
      .doesNotContain("@Nullable")
      .doesNotContain("org.jspecify")
      .doesNotContain("NONE");

    // with @Nullable disabled, a required+nullable field falls back to @NotNull
    assertThat(tempDir.resolve("org/example/api/model/Thing.java"))
      .content()
      .doesNotContain("@Nullable")
      .contains("@NotNull String code");
  }

  @Test
  void nullableAnnotationCustomType() throws Exception {
    var input = resourcePath("openapi/nullable.yaml");
    var config = GeneratorConfig.builder(input, tempDir, "org.example.api")
      .nullableAnnotation("jakarta.annotation.Nullable")
      .build();

    var result = new OpenApiGenerator().generate(config);

    assertThat(tempDir.resolve("org/example/api/model/Thing.java"))
      .content()
      .contains("import jakarta.annotation.Nullable;")
      .contains("@Nullable String note")
      .doesNotContain("org.jspecify");
  }

  @Test
  void emitsExplicitParamAnnotationName() throws Exception {
    var input = resourcePath("openapi/pets.yaml");
    var config = GeneratorConfig.builder(input, tempDir, "org.example.api").build();

    new OpenApiGenerator().generate(config);

    assertThat(tempDir.resolve("org/example/api/PetsApi.java"))
      .content()
      // explicit wire name is always emitted so the interface is robust when imported
      // from a precompiled jar without -parameters
      .contains("@QueryParam(\"limit\") Integer limit")
      .contains("@QueryParam(\"status\") PetStatus status")
      .contains("@QueryParam(\"useMaster\") @Default(\"false\") boolean useMaster")
      .doesNotContain("@QueryParam Integer limit")
      .doesNotContain("@QueryParam PetStatus status")
      .doesNotContain("@QueryParam @Default")
      // wire name differing from the Java parameter name is likewise explicit
      .contains("@Header(\"X-Request-Id\") String xRequestId");
  }

  @Test
  void validationBreadthFromSpec() throws Exception {
    var input = resourcePath("openapi/validation.yaml");
    var config = GeneratorConfig.builder(input, tempDir, "org.example.api").build();

    var result = new OpenApiGenerator().generate(config);

    assertThat(result.diagnostics())
      .filteredOn(it -> it.severity() == DiagnosticSeverity.ERROR)
      .isEmpty();

    assertThat(tempDir.resolve("org/example/api/model/Widget.java"))
      .content()
      // pattern
      .contains("@Pattern(regexp = \"^[A-Z]{3}\\\\d+$\")")
      // email format
      .contains("@Email")
      // inclusive decimal bounds
      .contains("@DecimalMin(\"0.5\")")
      .contains("@DecimalMax(\"99.99\")")
      // exclusive bounds use DecimalMin/Max with inclusive = false
      .contains("@DecimalMin(value = \"0\", inclusive = false)")
      .contains("@DecimalMax(value = \"1\", inclusive = false)")
      // whole inclusive bounds keep @Min/@Max
      .contains("@Min(1)")
      .contains("@Max(100)")
      // array item bounds -> @Size
      .contains("@Size(min = 1, max = 5)")
      // imports (default Jakarta style)
      .contains("import jakarta.validation.constraints.Pattern;")
      .contains("import jakarta.validation.constraints.Email;")
      .contains("import jakarta.validation.constraints.DecimalMin;")
      .contains("import jakarta.validation.constraints.DecimalMax;")
      .contains("import jakarta.validation.constraints.Size;");
  }

  @Test
  void validationBreadthAvajeStyleImports() throws Exception {
    var input = resourcePath("openapi/validation.yaml");
    var config = GeneratorConfig.builder(input, tempDir, "org.example.api")
      .validationStyle(ValidationStyle.AVAJE)
      .build();

    new OpenApiGenerator().generate(config);

    assertThat(tempDir.resolve("org/example/api/model/Widget.java"))
      .content()
      .contains("import io.avaje.validation.constraints.Pattern;")
      .contains("import io.avaje.validation.constraints.Email;")
      .contains("import io.avaje.validation.constraints.DecimalMin;")
      .contains("import io.avaje.validation.constraints.DecimalMax;")
      .doesNotContain("jakarta.validation.constraints");
  }

  @Test
  void typeMappingsByFormatTypeAndPrecedence() throws Exception {
    var input = resourcePath("openapi/typemap.yaml");
    var config = GeneratorConfig.builder(input, tempDir, "org.example.api")
      .typeMappings(Map.of(
        "uuid", "com.example.Identifier",
        "date-time", "java.time.Instant",
        "string", "com.example.Text"))
      .build();

    var result = new OpenApiGenerator().generate(config);

    assertThat(result.diagnostics())
      .filteredOn(it -> it.severity() == DiagnosticSeverity.ERROR)
      .isEmpty();

    assertThat(tempDir.resolve("org/example/api/model/Mapped.java"))
      .content()
      // format key (uuid) beats type key (string)
      .contains("Identifier externalId")
      .contains("import com.example.Identifier;")
      .doesNotContain("UUID externalId")
      // format key date-time overrides the default OffsetDateTime
      .contains("Instant created")
      .contains("import java.time.Instant;")
      // type key applies to a plain string
      .contains("Text name")
      .contains("import com.example.Text;")
      // per-property x-java-type wins over the type mapping
      .contains("Code code")
      .contains("import com.example.Code;");
  }

  @Test
  void typeMappingsEmptyByDefault() throws Exception {
    var input = resourcePath("openapi/typemap.yaml");
    var config = GeneratorConfig.builder(input, tempDir, "org.example.api").build();

    new OpenApiGenerator().generate(config);

    assertThat(tempDir.resolve("org/example/api/model/Mapped.java"))
      .content()
      .contains("UUID externalId")
      .contains("OffsetDateTime created")
      .contains("String name");
  }

  @Test
  void validCascadeOnModelFields() throws Exception {
    var input = resourcePath("openapi/valid.yaml");
    var config = GeneratorConfig.builder(input, tempDir, "org.example.api").build();

    var result = new OpenApiGenerator().generate(config);

    assertThat(result.diagnostics())
      .filteredOn(it -> it.severity() == DiagnosticSeverity.ERROR)
      .isEmpty();

    assertThat(tempDir.resolve("org/example/api/model/Order.java"))
      .content()
      .contains("import jakarta.validation.Valid;")
      // object ref (also required, so @NotNull first)
      .contains("@NotNull @Valid Customer customer")
      // array of model refs
      .contains("@Valid List<Item> items")
      // map of model refs
      .contains("@Valid Map<String, Item> attachments")
      // array of scalars, enum ref and plain string do NOT cascade
      .contains("List<String> labels")
      .doesNotContain("@Valid List<String>")
      .doesNotContain("@Valid OrderStatus")
      .doesNotContain("@Valid String note");
  }

  @Test
  void validCascadeAvajeStyleImport() throws Exception {
    var input = resourcePath("openapi/valid.yaml");
    var config = GeneratorConfig.builder(input, tempDir, "org.example.api")
      .validationStyle(ValidationStyle.AVAJE)
      .build();

    new OpenApiGenerator().generate(config);

    assertThat(tempDir.resolve("org/example/api/model/Order.java"))
      .content()
      .contains("import io.avaje.validation.constraints.Valid;")
      .doesNotContain("jakarta.validation");
  }

  @Test
  void readOnlyWriteOnlyAvajeStyle() throws Exception {
    var input = resourcePath("openapi/readonly-writeonly.yaml");
    var config = GeneratorConfig.builder(input, tempDir, "org.example.api").build();

    new OpenApiGenerator().generate(config);

    assertThat(tempDir.resolve("org/example/api/model/UserProfile.java"))
      .content()
      .contains("@Json")
      .contains("import io.avaje.jsonb.Json;")
      .contains("@Json.Ignore(deserialize = true) Long id")
      .contains("@NotNull String username")
      .contains("@Json.Ignore(serialize = true) String password")
      .contains("@Nullable String email")
      .doesNotContain("JsonProperty");
  }

  @Test
  void readOnlyWriteOnlyJacksonStyle() throws Exception {
    var input = resourcePath("openapi/readonly-writeonly.yaml");
    var config = GeneratorConfig.builder(input, tempDir, "org.example.api")
      .jsonStyle(JsonStyle.JACKSON)
      .build();

    new OpenApiGenerator().generate(config);

    assertThat(tempDir.resolve("org/example/api/model/UserProfile.java"))
      .content()
      .contains("import com.fasterxml.jackson.annotation.JsonProperty;")
      .contains("@JsonProperty(access = JsonProperty.Access.READ_ONLY) Long id")
      .contains("@NotNull String username")
      .contains("@JsonProperty(access = JsonProperty.Access.WRITE_ONLY) String password")
      .contains("@Nullable String email")
      .doesNotContain("@Json.Ignore");
  }

  @Test
  void responseHeadersInJavadoc() throws Exception {
    var input = resourcePath("openapi/response-headers.yaml");
    var config = GeneratorConfig.builder(input, tempDir, "org.example.api").build();

    new OpenApiGenerator().generate(config);

    assertThat(tempDir.resolve("org/example/api/ItemsApi.java"))
      .content()
      // listItems: three response headers, two with descriptions, one without
      .contains("@apiNote Response headers: X-Rate-Limit (integer \u2014 Request limit per hour), X-Rate-Limit-Remaining (integer \u2014 Remaining requests in window), X-Rate-Limit-Reset (string)")
      // normal method structure preserved
      .contains("List<Item> listItems()")
      .contains("Item getItem(Long id)")
      // getItem has no response headers — @apiNote appears exactly once (for listItems)
      .containsOnlyOnce("@apiNote Response headers");
  }

  @Test
  void responseHeadersAbsentWhenNoDefined() throws Exception {
    var input = resourcePath("openapi/pets.yaml");
    var config = GeneratorConfig.builder(input, tempDir, "org.example.api").build();

    new OpenApiGenerator().generate(config);

    assertThat(tempDir.resolve("org/example/api/PetsApi.java"))
      .content()
      .doesNotContain("@apiNote Response headers");
  }

  private static Path resourcePath(String name) throws URISyntaxException {
    return Path.of(OpenApiGeneratorTest.class.getClassLoader().getResource(name).toURI());
  }
}
