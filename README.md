# Avaje OpenAPI Generators

Contract-first OpenAPI generators for Avaje HTTP API.

This repository currently focuses on an Avaje-owned Maven plugin that reads an OpenAPI YAML/JSON file and generates Java source using Avaje annotations:

- API interfaces using `io.avaje.http.api` annotations
- DTO records and enums
- optional Avaje Jsonb annotations
- optional Jakarta or Avaje validation annotations
- optional Avaje Record Builder support for DTO records

The generated API interfaces are then consumed by the existing Avaje annotation processors:

- `avaje-http-client-generator` generates typed HTTP clients
- server generators consume the same `avaje-http-api` contract for Nima/Helidon, Avaje Jex, Javalin, and other Avaje HTTP targets
- `avaje-jsonb-generator` generates JSON adapters for generated DTO records
- `avaje-record-builder` generates builders for generated DTO records when enabled

## Modules

| Module | Purpose |
|---|---|
| `avaje-openapi-generator-core` | Reusable OpenAPI parser and Java source generator |
| `avaje-openapi-maven-plugin` | Maven plugin with the `avaje-openapi:generate` goal |
| `avaje-openapi-plugin-tests` | Compile-level tests for generated source |
| `avaje-openapi-sample` | Example project showing generated contracts, HTTP client, Nima/Helidon route generation, JSON adapters, and record builders |

## Build

```bash
mvn clean verify
```

After a successful build, inspect generated sample files under:

```text
avaje-openapi-sample/target/generated-sources/
```

Useful files:

```text
avaje-openapi-sample/target/generated-sources/avaje-openapi/org/example/api/PetsApi.java
avaje-openapi-sample/target/generated-sources/avaje-openapi/org/example/api/model/Pet.java
avaje-openapi-sample/target/generated-sources/annotations/org/example/api/httpclient/PetsApiHttpClient.java
avaje-openapi-sample/target/generated-sources/annotations/org/example/server/PetsController$Route.java
avaje-openapi-sample/target/generated-sources/annotations/org/example/api/model/PetBuilder.java
```

## Server targets

For server generation, this plugin generates an `avaje-http-api` contract. The generated interface uses annotations such as `@Path`, `@Get`, `@Post`, `@QueryParam`, and `@Header` from `io.avaje.http.api`.

That same generated interface can be implemented by a controller and then processed by any compatible Avaje HTTP server target:

| Server target | Consuming processor/runtime    |
|---|--------------------------------|
| Avaje Nima / Helidon | `avaje-http-helidon-generator` |
| Avaje Jex | `avaje-http-jex-generator`     |
| Javalin | `avaje-http-javalin-generator` |

Switching server targets is a consuming-project dependency and annotation-processor choice; the OpenAPI-generated API interface and DTOs remain the same.

## Maven plugin usage

```xml
<plugin>
  <groupId>io.avaje</groupId>
  <artifactId>avaje-openapi-maven-plugin</artifactId>
  <version>${avaje.openapi.version}</version>
  <executions>
    <execution>
      <goals>
        <goal>generate</goal>
      </goals>
      <configuration>
        <inputSpec>${project.basedir}/src/main/openapi/openapi.yaml</inputSpec>
        <apiPackage>org.example.api</apiPackage>
        <modelPackage>org.example.api.model</modelPackage>
        <validationStyle>AVAJE</validationStyle>
        <generateRecordBuilders>true</generateRecordBuilders>
      </configuration>
    </execution>
  </executions>
</plugin>
```

Generated OpenAPI contract source defaults to:

```text
target/generated-sources/avaje-openapi
```

The plugin adds that directory to Maven compile source roots.

## Record builder DTOs

Enable DTO builder generation with:

```xml
<generateRecordBuilders>true</generateRecordBuilders>
```

Then add Avaje Record Builder to the consuming project:

```xml
<dependency>
  <groupId>io.avaje</groupId>
  <artifactId>avaje-record-builder</artifactId>
  <version>${avaje.record.builder.version}</version>
  <scope>provided</scope>
</dependency>
```

And add it to annotation processor paths:

```xml
<path>
  <groupId>io.avaje</groupId>
  <artifactId>avaje-record-builder</artifactId>
  <version>${avaje.record.builder.version}</version>
</path>
```

Example generated DTO:

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

## Validation annotations

Validation annotations are enabled by default:

```xml
<generateValidationAnnotations>true</generateValidationAnnotations>
```

The default validation style is Jakarta:

```xml
<validationStyle>JAKARTA</validationStyle>
```

This emits imports such as:

```java
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
```

To use Avaje Validator constraint annotations instead:

```xml
<validationStyle>AVAJE</validationStyle>
```

This emits imports such as:

```java
import io.avaje.validation.constraints.NotNull;
import io.avaje.validation.constraints.Size;
```

Add Avaje Validator constraints to the consuming project:

```xml
<dependency>
  <groupId>io.avaje</groupId>
  <artifactId>avaje-validator-constraints</artifactId>
  <version>${avaje.validator.version}</version>
</dependency>
```

## Current scope

Supported:

- OpenAPI 3 YAML/JSON
- REST paths and common HTTP methods
- JSON request/response bodies
- path/query/header/cookie parameters
- component object schemas, enums, arrays, maps, date/time/UUID formats

Unsupported features currently produce diagnostics:

- complex `oneOf` / `anyOf` / discriminator models
- multipart upload
- callbacks, links, webhooks
- multiple request body content types per operation
