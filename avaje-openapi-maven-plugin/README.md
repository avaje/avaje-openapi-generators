# Avaje OpenAPI Maven Plugin

The Maven plugin exposes the `avaje-openapi:generate` goal. It runs in the `generate-sources` phase by default, reads an OpenAPI YAML/JSON contract, writes Avaje Java source, and adds the output directory to Maven compile source roots.

For server generation, the plugin emits `io.avaje.http.api` interfaces rather than Nima-specific source. The same generated contract can be consumed by any compatible Avaje HTTP server target, including Avaje Nima/Helidon, Avaje Jex, and Javalin via `avaje-http-javalin-generator`.

## Basic usage

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
      </configuration>
    </execution>
  </executions>
</plugin>
```

## Parameters

| Parameter | Default | Description |
|---|---:|---|
| `inputSpec` | required | OpenAPI YAML or JSON file |
| `outputDirectory` | `${project.build.directory}/generated-sources/avaje-openapi` | Generated source directory |
| `apiPackage` | required | Package for generated API interfaces |
| `modelPackage` | `${apiPackage}.model` | Package for generated DTO records and enums |
| `mode` | `CONTRACT` | Only `CONTRACT` is currently implemented |
| `generateValidationAnnotations` | `true` | Generate validation annotations where safe |
| `validationStyle` | `JAKARTA` | `JAKARTA` or `AVAJE` validation constraint package |
| `generateJsonAnnotations` | `true` | Generate Avaje Jsonb `@Json` annotations on DTO records/enums |
| `generateRecordBuilders` | `false` | Generate Avaje Record Builder support for DTO records |
| `generateClientAnnotations` | `true` | Generate `@Client` on API interfaces |
| `failOnUnsupported` | `true` | Fail when unsupported OpenAPI features are encountered |
| `cleanOutput` | `true` | Clean generated output before writing files |

## Server target selection

Generated API interfaces use `avaje-http-api` annotations such as `@Path`, `@Get`, `@Post`, `@QueryParam`, and `@Header`. Add an implementation class annotated for the selected server target, then configure the corresponding server annotation processor/runtime in the consuming project.

For example, the sample module uses `avaje-http-helidon-generator` to generate a Nima/Helidon route. A Jex or Javalin project can use the same generated API interface and DTOs by swapping in the appropriate Avaje HTTP server generator/runtime, such as `avaje-http-javalin-generator` for Javalin.

## Record builder DTOs

```xml
<generateRecordBuilders>true</generateRecordBuilders>
```

When enabled, object DTO records get `@RecordBuilder` and two static builder factory methods. The consuming project must include `io.avaje:avaje-record-builder` as a dependency and annotation processor.

## Validation style

By default, validation annotations use Jakarta:

```xml
<validationStyle>JAKARTA</validationStyle>
```

To generate Avaje Validator annotations instead:

```xml
<validationStyle>AVAJE</validationStyle>
```

When using `AVAJE`, add:

```xml
<dependency>
  <groupId>io.avaje</groupId>
  <artifactId>avaje-validator-constraints</artifactId>
  <version>${avaje.validator.version}</version>
</dependency>
```
