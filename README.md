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

The published generator modules target Java 11. The sample module overrides the compiler release to Java 21 because it compiles generated DTO records together with the Nima/Helidon sample server.

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

## Java version

The generator itself is intended to run on Java 11+:

- `avaje-openapi-generator-core`
- `avaje-openapi-maven-plugin`
- `avaje-openapi-plugin-tests`

Generated DTO models currently use Java records, so projects that compile the generated model source need a Java version with record support. Use Java 17+ as the practical baseline for generated DTO projects. The `avaje-openapi-sample` module uses Java 21 because it demonstrates the Nima/Helidon server target.

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

## API-only generation (reuse existing models)

By default the plugin generates both the API interfaces and the DTO model
records. Set `generateModels` to `false` to generate **only** the API interfaces.
The generated interfaces still reference `modelPackage` types, which are expected
to be provided by an existing (hand-written) module on the classpath.

```xml
<configuration>
  <inputSpec>${project.basedir}/src/main/openapi/openapi.yaml</inputSpec>
  <apiPackage>org.example.api</apiPackage>
  <modelPackage>org.example.model</modelPackage>
  <generateModels>false</generateModels>
</configuration>
```

This is useful for adopting contract-first on an existing API where the model
records are already hand-maintained (with their own Javadoc, field types and
conventions) and should remain the single source of truth — the OpenAPI spec
then defines only the operations, and the DTO schemas exist purely so the
generated interface signatures resolve to those existing types.

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

## Parameter defaults

When a parameter schema declares a `default`, the generator emits an
`@Default` annotation alongside the location annotation, and uses the
**primitive** form of the type (since a default guarantees a value):

```yaml
parameters:
  - name: useMaster
    in: query
    schema:
      type: boolean
      default: false
```

generates:

```java
Pet getPet(Long id, @QueryParam("useMaster") @Default("false") boolean useMaster);
```

Wrapper types `Boolean`, `Integer`, `Long`, `Double` and `Float` are unboxed to
their primitive form when a default is present. Other types keep their declared
type and simply gain the `@Default("...")` annotation.

## Overloads

Set `generateOverloads` to `true` to emit convenience `default` method overloads
that omit a trailing run of **omittable** parameters and delegate to the full
method. The overloads carry no HTTP annotation, so the Avaje HTTP server and
client generators ignore them — they exist purely for caller ergonomics and are
inherited by both the controller and the generated HTTP client.

```xml
<generateOverloads>true</generateOverloads>
<overloadPolicy>NULLABLE_ONLY</overloadPolicy>
<!-- EXPLICIT | NULLABLE_ONLY (default) | ALL_OPTIONAL -->
```

Only a contiguous run of omittable parameters at the **end** of the signature can
be dropped (Java overloads can only omit trailing arguments). Path parameters and
request bodies are never omittable. The `overloadPolicy` decides which parameters
are omittable by default:

| Policy          | Omittable parameters                                  | Value passed when omitted |
| --------------- | ----------------------------------------------------- | ------------------------- |
| `EXPLICIT`      | only those marked `x-overload: true`                  | default, or `null`        |
| `NULLABLE_ONLY` | optional parameters **without** a `default` (default) | `null`                    |
| `ALL_OPTIONAL`  | every optional parameter (including defaulted ones)   | its `default` literal     |

A per-parameter `x-overload` vendor extension overrides the policy for that
parameter (`true` forces omittable, `false` forces required):

```yaml
parameters:
  - name: modifiedSince      # optional, no default -> dropped under NULLABLE_ONLY
    in: query
    schema:
      type: string
      format: date-time
  - name: withMachines       # defaulted, but opted in -> dropped, passing its default
    in: query
    x-overload: true
    schema:
      type: boolean
      default: false
```

For an endpoint `findFleet(fleetGid, useMaster, withMachines, withDrivers)` where
`withMachines`/`withDrivers` are marked `x-overload: true` and `useMaster` keeps
its default, the generator emits one overload per trailing suffix length:

```java
FleetDetail findFleet(UUID fleetGid, @QueryParam("useMaster") @Default("false") boolean useMaster,
    @QueryParam("withMachines") @Default("false") boolean withMachines,
    @QueryParam("withDrivers") @Default("false") boolean withDrivers);

default FleetDetail findFleet(UUID fleetGid, boolean useMaster, boolean withMachines) {
  return findFleet(fleetGid, useMaster, withMachines, false);
}

default FleetDetail findFleet(UUID fleetGid, boolean useMaster) {
  return findFleet(fleetGid, useMaster, false, false);
}
```

## Date-time types

OpenAPI's `format: date-time` (RFC 3339) carries a timezone offset, so it maps to
`java.time.OffsetDateTime` by default. Set the global `dateTimeType` to change the
type used for all `format: date-time` properties:

```xml
<dateTimeType>INSTANT</dateTimeType>
<!-- INSTANT | OFFSET_DATE_TIME (default) | LOCAL_DATE_TIME | ZONED_DATE_TIME -->
```

`format: date` always maps to `java.time.LocalDate`.

### Per-property overrides

Two mechanisms override the global default for an individual property. Precedence,
highest first:

1. **`x-java-type`** vendor extension — keeps the spec standard and takes any fully
   qualified class name:

   ```yaml
   externalLastModified:
     type: string
     format: date-time
     x-java-type: java.time.OffsetDateTime
   ```

2. **Extended `format` values** — concise shorthand for the common `java.time` types:

   | `format:` value    | Java type                  |
   | ------------------ | -------------------------- |
   | `instant`          | `java.time.Instant`        |
   | `offset-date-time` | `java.time.OffsetDateTime` |
   | `local-date-time`  | `java.time.LocalDateTime`  |
   | `zoned-date-time`  | `java.time.ZonedDateTime`  |

3. **Global `dateTimeType`** — applied to plain `format: date-time`.

## Current scope

Supported:

- OpenAPI 3 YAML/JSON
- REST paths and common HTTP methods
- JSON request/response bodies
- path/query/header/cookie parameters (with `@Default` for parameter defaults)
- component object schemas, enums, arrays, maps, date/time/UUID formats
- configurable `date-time` Java type (global `dateTimeType`, extended formats, `x-java-type`)
- convenience `default` method overloads (`generateOverloads`, `overloadPolicy`, `x-overload`)

Unsupported features currently produce diagnostics:

- complex `oneOf` / `anyOf` / discriminator models
- multipart upload
- callbacks, links, webhooks
- multiple request body content types per operation
