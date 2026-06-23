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
import java.util.stream.Collectors;

/** Generates Avaje HTTP API contracts and models from an OpenAPI specification. */
public final class OpenApiGenerator {

  private static final String APPLICATION_JSON = "application/json";
  private static final String APPLICATION_STREAM_JSON = "application/stream+json";
  private static final String APPLICATION_NDJSON = "application/x-ndjson";

  /**
   * Streaming media types map to a {@code Stream<T>} return type rather than
   * {@code List<T>}. The Avaje HTTP processors (client and server generators)
   * detect streaming purely from the {@code java.util.stream.Stream} return type,
   * so no {@code @Produces} annotation is emitted for these.
   */
  private static boolean isStreamingMedia(String mediaType) {
    return APPLICATION_STREAM_JSON.equals(mediaType) || APPLICATION_NDJSON.equals(mediaType);
  }

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
    if (config.generateModels()) {
      writeModels(schemas, context, generated);
    }
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
      var prefix = commonLiteralPrefix(operations.stream().map(OperationDef::fullPath).collect(Collectors.toList()));
      var adjusted = operations.stream()
        .map(op -> op.withMethodPath(trimPrefix(op.fullPath(), prefix)))
        .collect(Collectors.toList());
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
      requestMediaType(operation.getRequestBody()),
      response.streaming());
  }

  private static ParamDef readParameter(Context context, Parameter parameter) {
    var in = Optional.ofNullable(parameter.getIn()).orElse("query").toLowerCase(Locale.ROOT);
    var name = requireNonNull(parameter.getName(), "parameter.name");
    var schema = parameter.getSchema();
    var type = context.javaType(schema);
    var javaName = variableName(name);
    var annotations = new ArrayList<String>();
    switch (in) {
      case "path":
        break;
      case "query":
        annotations.add("@QueryParam(\"" + escape(name) + "\")");
        break;
      case "header":
        annotations.add("@Header(\"" + escape(name) + "\")");
        break;
      case "cookie":
        annotations.add("@Cookie(\"" + escape(name) + "\")");
        break;
      default:
        context.unsupported("Unsupported parameter location '" + in + "' for parameter " + name);
        break;
    }
    var defaultValue = schema == null ? null : schema.getDefault();
    if (defaultValue != null) {
      annotations.add("@Default(\"" + escape(String.valueOf(defaultValue)) + "\")");
      // a default guarantees a value, so use the primitive form where applicable
      type = primitiveType(type);
    }
    return new ParamDef(javaName, List.copyOf(annotations), type);
  }

  /** Unbox a wrapper type to its primitive form (used for parameters with a default). */
  private static JavaType primitiveType(JavaType type) {
    switch (type.code()) {
      case "Boolean":
        return JavaType.simple("boolean");
      case "Integer":
        return JavaType.simple("int");
      case "Long":
        return JavaType.simple("long");
      case "Double":
        return JavaType.simple("double");
      case "Float":
        return JavaType.simple("float");
      default:
        return type;
    }
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
    return Optional.of(new ParamDef(bodyName(type), List.of(), type));
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
    return new ResponseDef(JavaType.simple("void"), 200, APPLICATION_JSON, false);
  }

  private static ResponseDef readResponse(Context context, int statusCode, ApiResponse response) {
    var media = selectMedia(response == null ? null : response.getContent());
    if (media == null || media.mediaType().getSchema() == null) {
      return new ResponseDef(JavaType.simple("void"), statusCode, APPLICATION_JSON, false);
    }
    var schema = media.mediaType().getSchema();
    if (isStreamingMedia(media.name())) {
      return new ResponseDef(context.streamType(schema), statusCode, media.name(), true);
    }
    return new ResponseDef(context.javaType(schema), statusCode, media.name(), false);
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
      if (schema instanceof ObjectDef) {
        writeObject((ObjectDef) schema, context, generated);
      } else if (schema instanceof EnumDef) {
        writeEnum((EnumDef) schema, context, generated);
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
    // Streaming responses are signalled by the Stream<T> return type; the Avaje
    // HTTP processors set application/stream+json automatically, so no @Produces
    // media value is emitted for them.
    var nonDefaultMedia = !operation.streaming() && !APPLICATION_JSON.equals(operation.responseMediaType());
    if (operation.statusCode() != expectedStatus || nonDefaultMedia) {
      source.addImport("io.avaje.http.api.Produces");
      source.body.append("  @Produces(");
      if (nonDefaultMedia) {
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
      for (var ann : param.annotations()) {
        source.addImport(annotationImport(ann));
      }
    }
    source.body.append("  ").append(operation.returnType().code()).append(' ').append(operation.methodName()).append('(');
    for (var i = 0; i < operation.parameters().size(); i++) {
      var param = operation.parameters().get(i);
      if (i > 0) {
        source.body.append(", ");
      }
      for (var ann : param.annotations()) {
        source.body.append(ann).append(' ');
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
    switch (httpMethod) {
      case "GET":
        return "Get";
      case "POST":
        return "Post";
      case "PUT":
        return "Put";
      case "PATCH":
        return "Patch";
      case "DELETE":
        return "Delete";
      default:
        throw new IllegalArgumentException("Unsupported method " + httpMethod);
    }
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

  private static final class Context {

    private final GeneratorConfig config;
    private final List<Diagnostic> diagnostics;

    private Context(GeneratorConfig config, List<Diagnostic> diagnostics) {
      this.config = config;
      this.diagnostics = diagnostics;
    }

    JavaType javaType(Schema<?> schema) {
      if (schema == null) {
        return JavaType.simple("Object");
      }
      var xJavaType = extensionString(schema, "x-java-type");
      if (xJavaType != null) {
        return javaTypeFromName(xJavaType);
      }
      if (schema.get$ref() != null) {
        var name = schema.get$ref().substring(schema.get$ref().lastIndexOf('/') + 1);
        return JavaType.of(className(name), config.modelPackage() + "." + className(name));
      }
      if (schema instanceof ArraySchema || "array".equals(schema.getType())) {
        var itemType = javaType(schema.getItems());
        return JavaType.generic("List", "java.util.List", itemType);
      }
      if (schema.getAdditionalProperties() instanceof Schema<?>) {
        var additional = (Schema<?>) schema.getAdditionalProperties();
        return JavaType.map(javaType(additional));
      }
      if (Boolean.TRUE.equals(schema.getAdditionalProperties())) {
        return JavaType.map(JavaType.simple("Object"));
      }
      if (schema.getOneOf() != null || schema.getAnyOf() != null || schema.getAllOf() != null) {
        unsupported("Composed inline schema is not supported yet");
        return JavaType.simple("Object");
      }
      switch (Optional.ofNullable(schema.getType()).orElse("object")) {
        case "string":
          return stringType(schema.getFormat());
        case "integer":
          return integerType(schema.getFormat());
        case "number":
          return numberType(schema.getFormat());
        case "boolean":
          return JavaType.simple("Boolean");
        case "object":
        default:
          return JavaType.simple("Object");
      }
    }

    /**
     * Build a {@code Stream<T>} return type for a streaming response. When the
     * schema is an array the element type is streamed; otherwise the schema
     * itself is treated as the streamed element type.
     */
    JavaType streamType(Schema<?> schema) {
      JavaType element;
      if (schema instanceof ArraySchema || (schema != null && "array".equals(schema.getType()))) {
        element = javaType(schema.getItems());
      } else {
        element = javaType(schema);
      }
      return JavaType.generic("Stream", "java.util.stream.Stream", element);
    }

    private JavaType stringType(String format) {
      switch (Optional.ofNullable(format).orElse("")) {
        case "date":
          return JavaType.of("LocalDate", LocalDate.class.getName());
        case "date-time":
          return dateTimeJavaType(config.dateTimeType());
        case "instant":
          return dateTimeJavaType(DateTimeType.INSTANT);
        case "offset-date-time":
          return dateTimeJavaType(DateTimeType.OFFSET_DATE_TIME);
        case "local-date-time":
          return dateTimeJavaType(DateTimeType.LOCAL_DATE_TIME);
        case "zoned-date-time":
          return dateTimeJavaType(DateTimeType.ZONED_DATE_TIME);
        case "uuid":
          return JavaType.of("UUID", UUID.class.getName());
        case "binary":
          return JavaType.simple("byte[]");
        default:
          return JavaType.simple("String");
      }
    }

    private static JavaType dateTimeJavaType(DateTimeType type) {
      return JavaType.of(type.simpleName(), type.className());
    }

    /** Read a string-valued {@code x-} vendor extension, or {@code null} when absent. */
    private static String extensionString(Schema<?> schema, String name) {
      var extensions = schema.getExtensions();
      if (extensions == null) {
        return null;
      }
      var value = extensions.get(name);
      return value == null ? null : value.toString();
    }

    /**
     * Resolve a Java type from a fully qualified class name supplied via
     * {@code x-java-type}. {@code java.lang} types and simple (unqualified) names
     * are emitted without an import.
     */
    private static JavaType javaTypeFromName(String typeName) {
      var name = typeName.trim();
      var lastDot = name.lastIndexOf('.');
      if (lastDot < 0) {
        return JavaType.simple(name);
      }
      var simple = name.substring(lastDot + 1);
      var packageName = name.substring(0, lastDot);
      if (packageName.equals("java.lang")) {
        return JavaType.simple(simple);
      }
      return JavaType.of(simple, name);
    }

    private JavaType integerType(String format) {
      return "int64".equals(format) ? JavaType.simple("Long") : JavaType.simple("Integer");
    }

    private JavaType numberType(String format) {
      switch (Optional.ofNullable(format).orElse("")) {
        case "float":
          return JavaType.simple("Float");
        case "double":
          return JavaType.simple("Double");
        default:
          return JavaType.of("BigDecimal", BigDecimal.class.getName());
      }
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

  private interface SchemaDef {
    String name();
  }

  private static final class ObjectDef implements SchemaDef {
    private final String name;
    private final List<FieldDef> fields;

    private ObjectDef(String name, List<FieldDef> fields) {
      this.name = name;
      this.fields = List.copyOf(fields);
    }

    @Override
    public String name() {
      return name;
    }

    List<FieldDef> fields() {
      return fields;
    }
  }

  private static final class FieldDef {
    private final String javaName;
    private final String jsonName;
    private final JavaType type;
    private final boolean required;
    private final List<String> constraints;

    private FieldDef(String javaName, String jsonName, JavaType type, boolean required, List<String> constraints) {
      this.javaName = javaName;
      this.jsonName = jsonName;
      this.type = type;
      this.required = required;
      this.constraints = List.copyOf(constraints);
    }

    String javaName() {
      return javaName;
    }

    String jsonName() {
      return jsonName;
    }

    JavaType type() {
      return type;
    }

    boolean required() {
      return required;
    }

    List<String> constraints() {
      return constraints;
    }
  }

  private static final class EnumDef implements SchemaDef {
    private final String name;
    private final List<EnumValue> values;

    private EnumDef(String name, List<EnumValue> values) {
      this.name = name;
      this.values = List.copyOf(values);
    }

    @Override
    public String name() {
      return name;
    }

    List<EnumValue> values() {
      return values;
    }
  }

  private static final class EnumValue {
    private final String constant;
    private final String value;

    private EnumValue(String constant, String value) {
      this.constant = constant;
      this.value = value;
    }

    String constant() {
      return constant;
    }

    String value() {
      return value;
    }
  }

  private static final class ApiDef {
    private final String name;
    private final String pathPrefix;
    private final List<OperationDef> operations;

    private ApiDef(String name, String pathPrefix, List<OperationDef> operations) {
      this.name = name;
      this.pathPrefix = pathPrefix;
      this.operations = List.copyOf(operations);
    }

    String name() {
      return name;
    }

    String pathPrefix() {
      return pathPrefix;
    }

    List<OperationDef> operations() {
      return operations;
    }
  }

  private static final class OperationDef {
    private final String httpMethod;
    private final String fullPath;
    private final String methodPath;
    private final String methodName;
    private final List<ParamDef> parameters;
    private final JavaType returnType;
    private final int statusCode;
    private final String responseMediaType;
    private final String requestMediaType;
    private final boolean streaming;

    private OperationDef(
      String httpMethod,
      String fullPath,
      String methodPath,
      String methodName,
      List<ParamDef> parameters,
      JavaType returnType,
      int statusCode,
      String responseMediaType,
      String requestMediaType,
      boolean streaming) {

      this.httpMethod = httpMethod;
      this.fullPath = fullPath;
      this.methodPath = methodPath;
      this.methodName = methodName;
      this.parameters = List.copyOf(parameters);
      this.returnType = returnType;
      this.statusCode = statusCode;
      this.responseMediaType = responseMediaType;
      this.requestMediaType = requestMediaType;
      this.streaming = streaming;
    }

    String httpMethod() {
      return httpMethod;
    }

    String fullPath() {
      return fullPath;
    }

    String methodPath() {
      return methodPath;
    }

    String methodName() {
      return methodName;
    }

    List<ParamDef> parameters() {
      return parameters;
    }

    JavaType returnType() {
      return returnType;
    }

    int statusCode() {
      return statusCode;
    }

    String responseMediaType() {
      return responseMediaType;
    }

    String requestMediaType() {
      return requestMediaType;
    }

    boolean streaming() {
      return streaming;
    }

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
        requestMediaType,
        streaming);
    }
  }

  private static final class ParamDef {
    private final String name;
    private final List<String> annotations;
    private final JavaType type;

    private ParamDef(String name, List<String> annotations, JavaType type) {
      this.name = name;
      this.annotations = annotations;
      this.type = type;
    }

    String name() {
      return name;
    }

    List<String> annotations() {
      return annotations;
    }

    JavaType type() {
      return type;
    }
  }

  private static final class ResponseDef {
    private final JavaType type;
    private final int statusCode;
    private final String mediaType;
    private final boolean streaming;

    private ResponseDef(JavaType type, int statusCode, String mediaType, boolean streaming) {
      this.type = type;
      this.statusCode = statusCode;
      this.mediaType = mediaType;
      this.streaming = streaming;
    }

    JavaType type() {
      return type;
    }

    int statusCode() {
      return statusCode;
    }

    String mediaType() {
      return mediaType;
    }

    boolean streaming() {
      return streaming;
    }
  }

  private static final class SelectedMedia {
    private final String name;
    private final MediaType mediaType;

    private SelectedMedia(String name, MediaType mediaType) {
      this.name = name;
      this.mediaType = mediaType;
    }

    String name() {
      return name;
    }

    MediaType mediaType() {
      return mediaType;
    }
  }

  private static final class JavaType {
    private final String code;
    private final Set<String> imports;

    private JavaType(String code, Set<String> imports) {
      this.code = code;
      this.imports = Set.copyOf(imports);
    }

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

    String code() {
      return code;
    }

    Set<String> imports() {
      return imports;
    }
  }
}
