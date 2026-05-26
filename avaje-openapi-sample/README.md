# Avaje OpenAPI Sample

This module demonstrates the contract-first flow:

1. `src/main/openapi/pets.yaml` defines the OpenAPI contract.
2. `avaje-openapi-maven-plugin` generates Avaje HTTP API interfaces and DTO records.
3. `avaje-http-client-generator` generates a typed HTTP client implementation.
4. `avaje-http-helidon-generator` generates a Nima/Helidon route for `PetsController`.
5. `avaje-jsonb-generator` generates JSON adapters.
6. `avaje-record-builder` generates DTO builders.

This sample chooses the Nima/Helidon server target. The generated contract source is not Nima-specific: `PetsApi` uses `io.avaje.http.api` annotations, so the same generated API interface and DTOs can be consumed by other Avaje HTTP server targets such as Avaje Jex or Javalin via `avaje-http-javalin-generator`.

The sample sets `<validationStyle>AVAJE</validationStyle>`, so generated DTOs use `io.avaje.validation.constraints.*` rather than Jakarta validation constraints.

The sample compiles with Java 21 because it demonstrates the Nima/Helidon server target. The generator artifacts themselves target Java 11, but generated DTOs currently use Java records and therefore need a newer Java release in consuming projects.

## Build

From the repository root:

```bash
mvn clean verify
```

## Generated contract source

```text
target/generated-sources/avaje-openapi/org/example/api/PetsApi.java
target/generated-sources/avaje-openapi/org/example/api/model/Pet.java
target/generated-sources/avaje-openapi/org/example/api/model/CreatePetRequest.java
```

## Generated Avaje processor source

```text
target/generated-sources/annotations/org/example/api/httpclient/PetsApiHttpClient.java
target/generated-sources/annotations/org/example/server/PetsController$Route.java
target/generated-sources/annotations/org/example/api/model/PetJsonAdapter.java
target/generated-sources/annotations/org/example/api/model/CreatePetRequestJsonAdapter.java
target/generated-sources/annotations/org/example/api/model/PetBuilder.java
target/generated-sources/annotations/org/example/api/model/CreatePetRequestBuilder.java
```

`PetsController$Route.java` is generated because this sample has the Nima/Helidon processor on the annotation processor path. Other server targets would produce their own route/adapter source from the same generated `PetsApi` contract.

## Example generated DTO shape

```java
@RecordBuilder
@Json
public record Pet(
  @NotNull @Min(1) Long id,
  @NotNull @Size(min = 1, max = 100) String name,
  Instant createdAt
) {

  public static PetBuilder builder() {
    return PetBuilder.builder();
  }

  public static PetBuilder builder(Pet from) {
    return PetBuilder.builder(from);
  }
}
```
