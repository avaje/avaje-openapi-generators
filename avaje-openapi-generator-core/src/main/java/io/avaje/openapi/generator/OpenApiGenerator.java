package io.avaje.openapi.generator;

import static io.avaje.openapi.generator.internal.NameUtils.apiName;
import static io.avaje.openapi.generator.internal.NameUtils.className;
import static io.avaje.openapi.generator.internal.NameUtils.commonLiteralPrefix;
import static io.avaje.openapi.generator.internal.NameUtils.enumConstant;
import static io.avaje.openapi.generator.internal.NameUtils.methodName;
import static io.avaje.openapi.generator.internal.NameUtils.newEnumConstantSet;
import static io.avaje.openapi.generator.internal.NameUtils.operationName;
import static io.avaje.openapi.generator.internal.NameUtils.packageToPath;
import static io.avaje.openapi.generator.internal.NameUtils.simpleJavaIdentifier;
import static io.avaje.openapi.generator.internal.NameUtils.variableName;
import static java.util.Objects.requireNonNull;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.ComposedSchema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Generates Avaje HTTP API contracts and models from an OpenAPI specification. */
public final class OpenApiGenerator {

  private static final String APPLICATION_JSON = "application/json";

  /** Generate Java source for the given configuration. */
  public GenerationResult generate(GeneratorConfig config) {
    requireNonNull(config, "config");
    var diagnostics = new ArrayList<Diagnostic>();
    var openApi = parse(config, diagnostics);
    if (openApi == null) {
      return new GenerationResult(List.of(), diagnostics);
    }

    var context = new Context(config, diagnostics);
    var generated = new ArrayList<GeneratedFile>();
    var schemas = readSchemas(openApi, context);
    var apis = readApis(openApi, context);
    writeModels(schemas, context, generated);
    writeApis(apis, context, generated);
    writeFiles(generated, diagnostics);
    return new GenerationResult(generated, diagnostics);
  }

  private static OpenAPI parse(GeneratorConfig config, List<Diagnostic> diagnostics) {
    var options = new ParseOptions();
    options.setResolve(true);
    options.setFlatten(true);
    var result = new OpenAPIV3Parser().readLocation(config.inputSpec().toString(), null, options);
    if (result == null) {
      diagnostics.add(Diagnostic.error("Unable to parse OpenAPI spec " + config.inputSpec()));
      return null;
    }
    for (var message : Optional.ofNullable(result.getMessages()).orElse(List.of())) {
      diagnostics.add(Diagnostic.warn(message));
    }
    var openApi = result.getOpenAPI();
    if (openApi == null) {
      diagnostics.add(Diagnostic.error("OpenAPI parser returned no model for " + config.inputSpec()));
    }
    return openApi;
  }

  private static List<SchemaDef> readSchemas(OpenAPI openApi, Context context) {
    var schemas = Optional.ofNullable(openApi.getComponents())
      .map(components -> components.getSchemas())
      .orElse(Map.of());
    var result = new ArrayList<SchemaDef>();
    for (var entry : schemas.entrySet()) {
      var schema = entry.getValue();
      if (schema == null) {
        continue;
      }
      var name = className(entry.getKey());
      if (schema.getEnum() != null && !schema.getEnum().isEmpty()) {
        result.add(readEnum(name, schema));
      } else {
        result.add(readObject(name, schema, context));
      }
    }
    result.sort(Comparator.comparing(SchemaDef::name));
    return result;
  }

  private static EnumDef readEnum(String name, Schema<?> schema) {
    var values = new ArrayList<EnumValue>();
    var existing = newEnumConstantSet();
    for (var raw : schema.getEnum()) {
      var value = String.valueOf(raw);
      values.add(new EnumValue(enumConstant(value, existing), value));
    }
    return new EnumDef(name, values);
  }

  private static ObjectDef readObject(String name, Schema<?> schema, Context context) {
    if (schema instanceof ComposedSchema || schema.getOneOf() != null || schema.getAnyOf() != null) {
      context.unsupported("Composed schema " + name + " is not supported yet");
    }
    var properties = Optional.ofNullable(schema.getProperties()).orElse(Map.of());
    var required = new HashSet<>(Optional.ofNullable(schema.getRequired()).orElse(List.of()));
    var fields = new ArrayList<FieldDef>();
    for (var entry : properties.entrySet()) {
      var propName = entry.getKey();
      var javaName = variableName(propName);
      if (!simpleJavaIdentifier(propName)) {
        context.unsupported("Schema property '" + name + "." + propName + "' needs JSON property mapping");
      }
      var propSchema = (Schema<?>) entry.getValue();
      fields.add(new FieldDef(javaName, propName, context.javaType(propSchema), required.contains(propName), constraints(propSchema)));
    }
    return new ObjectDef(name, fields);
  }

  private static List<String> constraints(Schema<?> schema) {
    if (schema == null) {
      return List.of();
    }
    var constraints = new ArrayList<String>();
    if (schema.getMinLength() != null && schema.getMaxLength() != null) {
      constraints.add("@Size(min = " + schema.getMinLength() + ", max = " + schema.getMaxLength() + ")");
    } else if (schema.getMinLength() != null) {
      constraints.add("@Size(min = " + schema.getMinLength() + ")");
    } else if (schema.getMaxLength() != null) {
      constraints.add("@Size(max = " + schema.getMaxLength() + ")");
    }
    if (schema.getMinimum() != null && isWholeNumber(schema.getMinimum())) {
      constraints.add("@Min(" + schema.getMinimum().longValue() + ")");
    }
    if (schema.getMaximum() != null && isWholeNumber(schema.getMaximum())) {
      constraints.add("@Max(" + schema.getMaximum().longValue() + ")");
    }
    return constraints;
  }

  private static boolean isWholeNumber(BigDecimal value) {
    return value.stripTrailingZeros().scale() <= 0;
  }

  private static List<ApiDef> readApis(OpenAPI openApi, Context context) {
    var grouped = new LinkedHashMap<String, List<OperationDef>>();
    var paths = Optional.ofNullable(openApi.getPaths()).orElse(new io.swagger.v3.oas.models.Paths());
    for (var pathEntry : paths.entrySet()) {
      var path = pathEntry.getKey();
      var item = pathEntry.getValue();
      if (item == null) {
        continue;
      }
      var pathParams = Optional.ofNullable(item.getParameters()).orElse(List.of());
      addOperation(grouped, context, "GET", path, item.getGet(), pathParams);
      addOperation(grouped, context, "POST", path, item.getPost(), pathParams);
      addOperation(grouped, context, "PUT", path, item.getPut(), pathParams);
      addOperation(grouped, context, "PATCH", path, item.getPatch(), pathParams);
      addOperation(grouped, context, "DELETE", path, item.getDelete(), pathParams);
    }
    var apis = new ArrayList<ApiDef>();
    for (var entry : grouped.entrySet()) {
      var operations = entry.getValue();
      var prefix = commonLiteralPrefix(operations.stream().map(OperationDef::fullPath).toList());
      var adjusted = operations.stream().map(op -> op.withMethodPath(trimPrefix(op.fullPath(), prefix))).toList();
      apis.add(new ApiDef(apiName(entry.getKey()), prefix, adjusted));
    }
    apis.sort(Comparator.comparing(ApiDef::name));
    return apis;
  }

  private static void addOperation(
    Map<String, List<OperationDef>> grouped,
    Context context,
    String httpMethod,
    String path,
    Operation operation,
    List<Parameter> pathItemParams) {

    if (operation == null) {
      return;
    }
    var tag = Optional.ofNullable(operation.getTags()).orElse(List.of()).stream().findFirst().orElse("Default");
    grouped.computeIfAbsent(tag, unused -> new ArrayList<>())
      .add(readOperation(context, httpMethod, path, operation, pathItemParams));
  }

  private static OperationDef readOperation(
    Context context,
    String httpMethod,
    String path,
    Operation operation,
    List<Parameter> pathItemParams) {

    var operationId = Optional.ofNullable(operation.getOperationId())
      .filter(id -> !id.isBlank())
      .orElseGet(() -> operationName(httpMethod, path));
    var parameters = new ArrayList<ParamDef>();
    var allParameters = new ArrayList<Parameter>();
    allParameters.addAll(pathItemParams);
    allParameters.addAll(Optional.ofNullable(operation.getParameters()).orElse(List.of()));
    var seen = new HashSet<String>();
    for (var parameter : allParameters) {
      if (parameter == null) {
        continue;
      }
      var key = parameter.getIn() + ":" + parameter.getName();
      if (seen.add(key)) {
        parameters.add(readParameter(context, parameter));
      }
    }
    readBody(context, operation.getRequestBody()).ifPresent(parameters::add);
    var response = readResponse(context, operation);
    return new OperationDef(
      httpMethod,
      path,
      "",
      methodName(operationId),
      parameters,
      response.type(),
      response.statusCode(),
      response.mediaType(),
      requestMediaType(operation.getRequestBody()));
  }

  private static ParamDef readParameter(Context context, Parameter parameter) {
    var in = Optional.ofNullable(parameter.getIn()).orElse("query").toLowerCase(Locale.ROOT);
    var name = requireNonNull(parameter.getName(), "parameter.name");
    var type = context.javaType(parameter.getSchema());
    var javaName = variableName(name);
    var annotation = switch (in) {
      case "path" -> null;
      case "query" -> "@QueryParam(\"" + escape(name) + "\")";
      case "header" -> "@Header(\"" + escape(name) + "\")";
      case "cookie" -> "@Cookie(\"" + escape(name) + "\")";
      default -> {
        context.unsupported("Unsupported parameter location '" + in + "' for parameter " + name);
        yield null;
      }
    };
    return new ParamDef(javaName, annotation, type);
  }

  private static Optional<ParamDef> readBody(Context context, RequestBody requestBody) {
    if (requestBody == null) {
      return Optional.empty();
    }
    var media = selectMedia(requestBody.getContent());
    if (media == null || media.mediaType().getSchema() == null) {
      return Optional.empty();
    }
    var type = context.javaType(media.mediaType().getSchema());
    return Optional.of(new ParamDef(bodyName(type), null, type));
  }

  private static String bodyName(JavaType type) {
    var raw = type.code();
    var generic = raw.indexOf('<');
    if (generic > -1) {
      raw = raw.substring(0, generic);
    }
    var dot = raw.lastIndexOf('.');
    raw = dot > -1 ? raw.substring(dot + 1) : raw;
    if ("String".equals(raw) || raw.endsWith("[]")) {
      return "body";
    }
    return variableName(raw);
  }

  private static ResponseDef readResponse(Context context, Operation operation) {
    var responses = Optional.ofNullable(operation.getResponses()).orElse(new io.swagger.v3.oas.models.responses.ApiResponses());
    for (var entry : responses.entrySet()) {
      var code = parseStatus(entry.getKey());
      if (code >= 200 && code < 300) {
        return readResponse(context, code, entry.getValue());
      }
    }
    return new ResponseDef(JavaType.simple("void"), 200, APPLICATION_JSON);
  }

  private static ResponseDef readResponse(Context context, int statusCode, ApiResponse response) {
    var media = selectMedia(response == null ? null : response.getContent());
    if (media == null || media.mediaType().getSchema() == null) {
      return new ResponseDef(JavaType.simple("void"), statusCode, APPLICATION_JSON);
    }
    return new ResponseDef(context.javaType(media.mediaType().getSchema()), statusCode, media.name());
  }

  private static int parseStatus(String status) {
    if (status == null || "default".equalsIgnoreCase(status)) {
      return 200;
    }
    try {
      return Integer.parseInt(status);
    } catch (NumberFormatException e) {
      return 200;
    }
  }

  private static String requestMediaType(RequestBody requestBody) {
    var media = requestBody == null ? null : selectMedia(requestBody.getContent());
    return media == null ? APPLICATION_JSON : media.name();
  }

  private static SelectedMedia selectMedia(Content content) {
    if (content == null || content.isEmpty()) {
      return null;
    }
    var json = content.get(APPLICATION_JSON);
    if (json != null) {
      return new SelectedMedia(APPLICATION_JSON, json);
    }
    return content.entrySet().stream().findFirst().map(entry -> new SelectedMedia(entry.getKey(), entry.getValue())).orElse(null);
  }

  private static String trimPrefix(String path, String prefix) {
    if (prefix == null || prefix.isBlank() || !path.startsWith(prefix)) {
      return path;
    }
    var trimmed = path.substring(prefix.length());
    return trimmed.isBlank() ? "" : trimmed;
  }

  private static void writeModels(List<SchemaDef> schemas, Context context, List<GeneratedFile> generated) {
    for (var schema : schemas) {
      switch (schema) {
        case ObjectDef objectDef -> writeObject(objectDef, context, generated);
        case EnumDef enumDef -> writeEnum(enumDef, context, generated);
      }
    }
  }

  private static void writeObject(ObjectDef object, Context context, List<GeneratedFile> generated) {
    var source = new JavaSource(context.config.modelPackage());
    if (context.config.recordBuilder()) {
      source.addImport("io.avaje.recordbuilder.RecordBuilder");
      source.body.append("@RecordBuilder\n");
    }
    if (context.config.jsonAnnotations()) {
      source.addImport("io.avaje.jsonb.Json");
      source.body.append("@Json\n");
    }
    for (var field : object.fields()) {
      field.type().imports().forEach(source::addImport);
      if (context.config.validationAnnotations()) {
        if (field.required()) {
          source.addImport(context.constraintImport("NotNull"));
        }
        for (var constraint : field.constraints()) {
          if (constraint.startsWith("@Size")) {
            source.addImport(context.constraintImport("Size"));
          } else if (constraint.startsWith("@Min")) {
            source.addImport(context.constraintImport("Min"));
          } else if (constraint.startsWith("@Max")) {
            source.addImport(context.constraintImport("Max"));
          }
        }
      }
    }
    source.body.append("public record ").append(object.name()).append("(\n");
    for (var i = 0; i < object.fields().size(); i++) {
      var field = object.fields().get(i);
      source.body.append("  ");
      if (context.config.validationAnnotations() && field.required()) {
        source.body.append("@NotNull ");
      }
      if (context.config.validationAnnotations()) {
        for (var constraint : field.constraints()) {
          source.body.append(constraint).append(' ');
        }
      }
      source.body.append(field.type().code()).append(' ').append(field.javaName());
      source.body.append(i == object.fields().size() - 1 ? "\n" : ",\n");
    }
    source.body.append(")");
    if (context.config.recordBuilder()) {
      writeRecordBuilderMethods(source.body, object.name());
    } else {
      source.body.append(" {}\n");
    }
    generated.add(context.file(context.config.modelPackage(), object.name(), source.render()));
  }

  private static void writeRecordBuilderMethods(StringBuilder body, String recordName) {
    var builderName = recordName + "Builder";
    body.append(" {\n\n")
      .append("  /**\n")
      .append("   * Create a new builder.\n")
      .append("   *\n")
      .append("   * @return A new ").append(recordName).append(" builder\n")
      .append("   */\n")
      .append("  public static ").append(builderName).append(" builder() {\n")
      .append("    return ").append(builderName).append(".builder();\n")
      .append("  }\n\n")
      .append("  /**\n")
      .append("   * Create a builder initialized with values from the given instance.\n")
      .append("   *\n")
      .append("   * @param from The instance to copy values from.\n")
      .append("   * @return A builder initialized with values from the given instance.\n")
      .append("   */\n")
      .append("  public static ").append(builderName).append(" builder(").append(recordName).append(" from) {\n")
      .append("    return ").append(builderName).append(".builder(from);\n")
      .append("  }\n")
      .append("}\n");
  }

  private static void writeEnum(EnumDef enumDef, Context context, List<GeneratedFile> generated) {
    var source = new JavaSource(context.config.modelPackage());
    if (context.config.jsonAnnotations()) {
      source.addImport("io.avaje.jsonb.Json");
      source.body.append("@Json\n");
    }
    source.body.append("public enum ").append(enumDef.name()).append(" {\n");
    for (var i = 0; i < enumDef.values().size(); i++) {
      source.body.append("  ").append(enumDef.values().get(i).constant());
      source.body.append(i == enumDef.values().size() - 1 ? ";\n" : ",\n");
    }
    source.body.append("}\n");
    generated.add(context.file(context.config.modelPackage(), enumDef.name(), source.render()));
  }

  private static void writeApis(List<ApiDef> apis, Context context, List<GeneratedFile> generated) {
    for (var api : apis) {
      writeApi(api, context, generated);
    }
  }

  private static void writeApi(ApiDef api, Context context, List<GeneratedFile> generated) {
    var source = new JavaSource(context.config.apiPackage());
    if (context.config.clientAnnotations()) {
      source.addImport("io.avaje.http.api.Client");
      source.body.append("@Client\n");
    }
    if (!api.pathPrefix().isBlank()) {
      source.addImport("io.avaje.http.api.Path");
      source.body.append("@Path(\"").append(escape(api.pathPrefix())).append("\")\n");
    }
    source.body.append("public interface ").append(api.name()).append(" {\n\n");
    for (var operation : api.operations()) {
      writeOperation(source, operation, context);
    }
    source.body.append("}\n");
    generated.add(context.file(context.config.apiPackage(), api.name(), source.render()));
  }

  private static void writeOperation(JavaSource source, OperationDef operation, Context context) {
    var annotation = methodAnnotation(operation.httpMethod());
    source.addImport("io.avaje.http.api." + annotation);
    source.body.append("  @").append(annotation);
    if (!operation.methodPath().isBlank()) {
      source.body.append("(\"").append(escape(operation.methodPath())).append("\")");
    }
    source.body.append('\n');

    if (!APPLICATION_JSON.equals(operation.requestMediaType())) {
      source.addImport("io.avaje.http.api.Consumes");
      source.body.append("  @Consumes(\"").append(escape(operation.requestMediaType())).append("\")\n");
    }
    var expectedStatus = defaultStatus(operation.httpMethod(), operation.returnType().code());
    if (operation.statusCode() != expectedStatus || !APPLICATION_JSON.equals(operation.responseMediaType())) {
      source.addImport("io.avaje.http.api.Produces");
      source.body.append("  @Produces(");
      if (!APPLICATION_JSON.equals(operation.responseMediaType())) {
        source.body.append("value = \"").append(escape(operation.responseMediaType())).append("\"");
        if (operation.statusCode() != expectedStatus) {
          source.body.append(", ");
        }
      }
      if (operation.statusCode() != expectedStatus) {
        source.body.append("statusCode = ").append(operation.statusCode());
      }
      source.body.append(")\n");
    }
    operation.returnType().imports().forEach(source::addImport);
    for (var param : operation.parameters()) {
      param.type().imports().forEach(source::addImport);
      if (param.annotation() != null) {
        source.addImport(annotationImport(param.annotation()));
      }
    }
    source.body.append("  ").append(operation.returnType().code()).append(' ').append(operation.methodName()).append('(');
    for (var i = 0; i < operation.parameters().size(); i++) {
      var param = operation.parameters().get(i);
      if (i > 0) {
        source.body.append(", ");
      }
      if (param.annotation() != null) {
        source.body.append(param.annotation()).append(' ');
      }
      source.body.append(param.type().code()).append(' ').append(param.name());
    }
    source.body.append(");\n\n");
  }

  private static String annotationImport(String annotation) {
    var end = annotation.indexOf('(');
    var name = end > -1 ? annotation.substring(1, end) : annotation.substring(1);
    return "io.avaje.http.api." + name;
  }

  private static String methodAnnotation(String httpMethod) {
    return switch (httpMethod) {
      case "GET" -> "Get";
      case "POST" -> "Post";
      case "PUT" -> "Put";
      case "PATCH" -> "Patch";
      case "DELETE" -> "Delete";
      default -> throw new IllegalArgumentException("Unsupported method " + httpMethod);
    };
  }

  private static int defaultStatus(String httpMethod, String returnType) {
    if ("POST".equals(httpMethod)) {
      return 201;
    }
    if (("PUT".equals(httpMethod) || "PATCH".equals(httpMethod) || "DELETE".equals(httpMethod)) && "void".equals(returnType)) {
      return 204;
    }
    return 200;
  }

  private static void writeFiles(List<GeneratedFile> generated, List<Diagnostic> diagnostics) {
    for (var file : generated) {
      try {
        Files.createDirectories(file.path().getParent());
        Files.writeString(file.path(), file.content());
      } catch (IOException e) {
        diagnostics.add(Diagnostic.error("Failed to write " + file.path() + ": " + e.getMessage()));
      }
    }
  }

  private static String escape(String value) {
    return value.replace("\\", "\\\\").replace("\"", "\\\"");
  }

  private record Context(GeneratorConfig config, List<Diagnostic> diagnostics) {

    JavaType javaType(Schema<?> schema) {
      if (schema == null) {
        return JavaType.simple("Object");
      }
      if (schema.get$ref() != null) {
        var name = schema.get$ref().substring(schema.get$ref().lastIndexOf('/') + 1);
        return JavaType.of(className(name), config.modelPackage() + "." + className(name));
      }
      if (schema instanceof ArraySchema || "array".equals(schema.getType())) {
        var itemType = javaType(schema.getItems());
        return JavaType.generic("List", "java.util.List", itemType);
      }
      if (schema.getAdditionalProperties() instanceof Schema<?> additional) {
        return JavaType.map(javaType(additional));
      }
      if (Boolean.TRUE.equals(schema.getAdditionalProperties())) {
        return JavaType.map(JavaType.simple("Object"));
      }
      if (schema.getOneOf() != null || schema.getAnyOf() != null || schema.getAllOf() != null) {
        unsupported("Composed inline schema is not supported yet");
        return JavaType.simple("Object");
      }
      return switch (Optional.ofNullable(schema.getType()).orElse("object")) {
        case "string" -> stringType(schema.getFormat());
        case "integer" -> integerType(schema.getFormat());
        case "number" -> numberType(schema.getFormat());
        case "boolean" -> JavaType.simple("Boolean");
        case "object" -> JavaType.simple("Object");
        default -> JavaType.simple("Object");
      };
    }

    private JavaType stringType(String format) {
      return switch (Optional.ofNullable(format).orElse("")) {
        case "date" -> JavaType.of("LocalDate", LocalDate.class.getName());
        case "date-time" -> JavaType.of("Instant", Instant.class.getName());
        case "uuid" -> JavaType.of("UUID", UUID.class.getName());
        case "binary" -> JavaType.simple("byte[]");
        default -> JavaType.simple("String");
      };
    }

    private JavaType integerType(String format) {
      return "int64".equals(format) ? JavaType.simple("Long") : JavaType.simple("Integer");
    }

    private JavaType numberType(String format) {
      return switch (Optional.ofNullable(format).orElse("")) {
        case "float" -> JavaType.simple("Float");
        case "double" -> JavaType.simple("Double");
        default -> JavaType.of("BigDecimal", BigDecimal.class.getName());
      };
    }

    void unsupported(String message) {
      diagnostics.add(config.failOnUnsupported() ? Diagnostic.error(message) : Diagnostic.warn(message));
    }

    GeneratedFile file(String packageName, String typeName, String content) {
      return new GeneratedFile(config.outputDirectory().resolve(packageToPath(packageName)).resolve(typeName + ".java"), content);
    }

    String constraintImport(String simpleName) {
      return config.validationStyle().constraintsPackage() + "." + simpleName;
    }
  }

  private static final class JavaSource {
    private final String packageName;
    private final Set<String> imports = new java.util.TreeSet<>();
    private final StringBuilder body = new StringBuilder();

    private JavaSource(String packageName) {
      this.packageName = packageName;
    }

    void addImport(String type) {
      if (type == null || type.isBlank() || !type.contains(".")) {
        return;
      }
      var packageEnd = type.lastIndexOf('.');
      var typePackage = type.substring(0, packageEnd);
      if (!typePackage.equals(packageName) && !typePackage.equals("java.lang")) {
        imports.add(type);
      }
    }

    String render() {
      var out = new StringBuilder();
      out.append("package ").append(packageName).append(";\n\n");
      for (var anImport : imports) {
        out.append("import ").append(anImport).append(";\n");
      }
      if (!imports.isEmpty()) {
        out.append('\n');
      }
      out.append(body);
      return out.toString();
    }
  }

  private sealed interface SchemaDef permits ObjectDef, EnumDef {
    String name();
  }

  private record ObjectDef(String name, List<FieldDef> fields) implements SchemaDef {
  }

  private record FieldDef(String javaName, String jsonName, JavaType type, boolean required, List<String> constraints) {
  }

  private record EnumDef(String name, List<EnumValue> values) implements SchemaDef {
  }

  private record EnumValue(String constant, String value) {
  }

  private record ApiDef(String name, String pathPrefix, List<OperationDef> operations) {
  }

  private record OperationDef(
    String httpMethod,
    String fullPath,
    String methodPath,
    String methodName,
    List<ParamDef> parameters,
    JavaType returnType,
    int statusCode,
    String responseMediaType,
    String requestMediaType) {

    OperationDef withMethodPath(String methodPath) {
      return new OperationDef(
        httpMethod,
        fullPath,
        methodPath,
        methodName,
        parameters,
        returnType,
        statusCode,
        responseMediaType,
        requestMediaType);
    }
  }

  private record ParamDef(String name, String annotation, JavaType type) {
  }

  private record ResponseDef(JavaType type, int statusCode, String mediaType) {
  }

  private record SelectedMedia(String name, MediaType mediaType) {
  }

  private record JavaType(String code, Set<String> imports) {

    static JavaType simple(String code) {
      return new JavaType(code, Set.of());
    }

    static JavaType of(String code, String importType) {
      return new JavaType(code, Set.of(importType));
    }

    static JavaType generic(String raw, String rawImport, JavaType nested) {
      var imports = new HashSet<String>();
      imports.add(rawImport);
      imports.addAll(nested.imports());
      return new JavaType(raw + "<" + nested.code() + ">", imports);
    }

    static JavaType map(JavaType valueType) {
      var imports = new HashSet<String>();
      imports.add(Map.class.getName());
      imports.addAll(valueType.imports());
      return new JavaType("Map<String, " + valueType.code() + ">", imports);
    }
  }
}
