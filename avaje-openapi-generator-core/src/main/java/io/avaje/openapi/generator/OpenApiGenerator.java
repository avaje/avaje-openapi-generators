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
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
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
    var reader = new ModelReader(schemas, context);
    for (var entry : schemas.entrySet()) {
      var schema = entry.getValue();
      if (schema == null) {
        continue;
      }
      var name = className(entry.getKey());
      if (schema.getEnum() != null && !schema.getEnum().isEmpty()) {
        reader.add(readEnum(name, schema));
      } else {
        reader.readObject(name, schema);
      }
    }
    return reader.result();
  }

  private static EnumDef readEnum(String name, Schema<?> schema) {
    var values = new ArrayList<EnumValue>();
    var existing = newEnumConstantSet();
    for (var raw : schema.getEnum()) {
      var value = String.valueOf(raw);
      values.add(new EnumValue(enumConstant(value, existing), value));
    }
    return new EnumDef(name, values, schema.getDescription(), Boolean.TRUE.equals(schema.getDeprecated()));
  }

  /**
   * Reads component object schemas into record models, flattening {@code allOf}
   * composition into a single record. Inline object schemas (object properties,
   * array items and map values) are already extracted into named component
   * schemas by the parser ({@code flatten}), so they arrive here as ordinary
   * {@code $ref} properties.
   */
  private static final class ModelReader {

    private final Map<String, Schema> components;
    private final Context context;
    private final Map<String, SchemaDef> models = new LinkedHashMap<>();

    private ModelReader(Map<String, Schema> components, Context context) {
      this.components = components;
      this.context = context;
    }

    void add(SchemaDef def) {
      models.putIfAbsent(def.name(), def);
    }

    List<SchemaDef> result() {
      var list = new ArrayList<>(models.values());
      list.sort(Comparator.comparing(SchemaDef::name));
      return list;
    }

    /** Read (and register) an object schema as a record model. */
    ObjectDef readObject(String name, Schema<?> schema) {
      if (schema.getOneOf() != null || schema.getAnyOf() != null) {
        context.unsupported("Composed schema " + name + " (oneOf/anyOf) is not supported yet");
      }
      var properties = new LinkedHashMap<String, Schema<?>>();
      var required = new LinkedHashSet<String>();
      collectProperties(schema, properties, required);

      var fields = new ArrayList<FieldDef>();
      for (var entry : properties.entrySet()) {
        var propName = entry.getKey();
        if (!simpleJavaIdentifier(propName)) {
          context.unsupported("Schema property '" + name + "." + propName + "' needs JSON property mapping");
        }
        var propSchema = entry.getValue();
        var fieldNullable = propSchema != null && Boolean.TRUE.equals(propSchema.getNullable());
        var fieldReadOnly = propSchema != null && Boolean.TRUE.equals(propSchema.getReadOnly());
        var fieldWriteOnly = propSchema != null && Boolean.TRUE.equals(propSchema.getWriteOnly());
        var fieldRequired = required.contains(propName);
        var fieldType = context.javaType(propSchema);
        // a required, non-nullable scalar is guaranteed present and non-null, so use the primitive form
        if (fieldRequired && !fieldNullable) {
          fieldType = primitiveType(fieldType);
        }
        fields.add(new FieldDef(variableName(propName), propName, fieldType, fieldRequired, constraints(propSchema), propSchema == null ? null : propSchema.getDescription(), fieldNullable, needsValid(propSchema), fieldReadOnly, fieldWriteOnly));
      }
      var def = new ObjectDef(name, fields, schema.getDescription(), Boolean.TRUE.equals(schema.getDeprecated()));
      add(def);
      return def;
    }

    /**
     * Merge {@code properties}/{@code required} from a schema, following
     * {@code allOf} members (resolving {@code $ref} members against the component
     * schemas). Members are merged in declaration order so a member's property
     * overrides an earlier member's property of the same name.
     */
    private void collectProperties(Schema<?> schema, Map<String, Schema<?>> properties, Set<String> required) {
      if (schema == null) {
        return;
      }
      var allOf = schema.getAllOf();
      if (allOf != null) {
        for (var member : allOf) {
          collectProperties(resolveRef(member), properties, required);
        }
      }
      var schemaProperties = schema.getProperties();
      if (schemaProperties != null) {
        schemaProperties.forEach((key, value) -> properties.put(key, (Schema<?>) value));
      }
      var schemaRequired = schema.getRequired();
      if (schemaRequired != null) {
        required.addAll(schemaRequired);
      }
    }

    /** Resolve a {@code $ref} schema against the component schemas, otherwise return the schema as-is. */
    private Schema<?> resolveRef(Schema<?> schema) {
      if (schema == null || schema.get$ref() == null) {
        return schema;
      }
      var ref = schema.get$ref();
      var target = components.get(ref.substring(ref.lastIndexOf('/') + 1));
      if (target == null) {
        context.unsupported("Could not resolve $ref '" + ref + "'");
      }
      return target;
    }

    /**
     * Whether a property type cascades validation with {@code @Valid}: a reference to
     * a generated object model, an inline object, or an array/map whose element is one.
     * Enum and scalar references do not.
     */
    private boolean needsValid(Schema<?> schema) {
      if (schema == null) {
        return false;
      }
      if (schema.get$ref() != null) {
        return isObjectModel(resolveRef(schema));
      }
      if (schema instanceof ArraySchema || "array".equals(schema.getType())) {
        return needsValid(schema.getItems());
      }
      if (schema.getAdditionalProperties() instanceof Schema<?>) {
        return needsValid((Schema<?>) schema.getAdditionalProperties());
      }
      return isObjectModel(schema);
    }

    /** Whether a (resolved) schema generates a record model rather than an enum or scalar. */
    private boolean isObjectModel(Schema<?> schema) {
      if (schema == null || schema.getEnum() != null) {
        return false;
      }
      if (schema.get$ref() != null) {
        return isObjectModel(resolveRef(schema));
      }
      return schema.getProperties() != null
        || "object".equals(schema.getType())
        || schema.getAllOf() != null
        || schema.getOneOf() != null
        || schema.getAnyOf() != null;
    }
  }

  private static List<String> constraints(Schema<?> schema) {
    if (schema == null) {
      return List.of();
    }
    var constraints = new ArrayList<String>();
    // @Size from string length or array item bounds (mutually exclusive by type)
    var sizeMin = schema.getMinLength() != null ? schema.getMinLength() : schema.getMinItems();
    var sizeMax = schema.getMaxLength() != null ? schema.getMaxLength() : schema.getMaxItems();
    if (sizeMin != null && sizeMax != null) {
      constraints.add("@Size(min = " + sizeMin + ", max = " + sizeMax + ")");
    } else if (sizeMin != null) {
      constraints.add("@Size(min = " + sizeMin + ")");
    } else if (sizeMax != null) {
      constraints.add("@Size(max = " + sizeMax + ")");
    }
    // numeric lower bound (OAS 3.0 boolean exclusiveMinimum or OAS 3.1 exclusiveMinimumValue)
    var min = schema.getMinimum();
    var minExclusive = Boolean.TRUE.equals(schema.getExclusiveMinimum());
    if (schema.getExclusiveMinimumValue() != null) {
      min = schema.getExclusiveMinimumValue();
      minExclusive = true;
    }
    if (min != null) {
      constraints.add(boundConstraint("Min", min, minExclusive));
    }
    // numeric upper bound
    var max = schema.getMaximum();
    var maxExclusive = Boolean.TRUE.equals(schema.getExclusiveMaximum());
    if (schema.getExclusiveMaximumValue() != null) {
      max = schema.getExclusiveMaximumValue();
      maxExclusive = true;
    }
    if (max != null) {
      constraints.add(boundConstraint("Max", max, maxExclusive));
    }
    if (schema.getPattern() != null) {
      constraints.add("@Pattern(regexp = \"" + escape(schema.getPattern()) + "\")");
    }
    if ("email".equals(schema.getFormat())) {
      constraints.add("@Email");
    }
    return constraints;
  }

  /**
   * Render a numeric bound. Whole-number inclusive bounds use the integral
   * {@code @Min}/{@code @Max}; decimal or exclusive bounds use
   * {@code @DecimalMin}/{@code @DecimalMax} (which carry an {@code inclusive} flag).
   */
  private static String boundConstraint(String integralName, BigDecimal value, boolean exclusive) {
    if (!exclusive && isWholeNumber(value)) {
      return "@" + integralName + "(" + value.longValue() + ")";
    }
    var decimalName = "Decimal" + integralName;
    return exclusive
      ? "@" + decimalName + "(value = \"" + value.toPlainString() + "\", inclusive = false)"
      : "@" + decimalName + "(\"" + value.toPlainString() + "\")";
  }

  /** Extract the annotation simple name from a constraint string, e.g. {@code @Min(1)} -> {@code Min}. */
  private static String constraintSimpleName(String constraint) {
    var paren = constraint.indexOf('(');
    return paren < 0 ? constraint.substring(1) : constraint.substring(1, paren);
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
    var serverBase = serverBasePath(openApi, context);
    for (var entry : grouped.entrySet()) {
      var operations = entry.getValue();
      var prefix = commonLiteralPrefix(operations.stream().map(OperationDef::fullPath).collect(Collectors.toList()));
      var adjusted = operations.stream()
        .map(op -> op.withMethodPath(trimPrefix(op.fullPath(), prefix)))
        .collect(Collectors.toList());
      apis.add(new ApiDef(apiName(entry.getKey()), serverBase + prefix, adjusted));
    }
    apis.sort(Comparator.comparing(ApiDef::name));
    return apis;
  }

  /**
   * The static path component of the first {@code servers} URL, used as the base of
   * the interface {@code @Path}. Supports an absolute URL ({@code https://host/v1})
   * or a relative path ({@code /v1}); a trailing slash is removed and a bare
   * {@code /} yields no base path. Server URLs containing template variables (e.g.
   * {@code https://{host}/v1}) cannot form a static prefix and are ignored with a
   * diagnostic.
   */
  private static String serverBasePath(OpenAPI openApi, Context context) {
    var servers = openApi.getServers();
    if (servers == null || servers.isEmpty()) {
      return "";
    }
    var url = servers.get(0).getUrl();
    if (url == null || url.isBlank()) {
      return "";
    }
    String path;
    try {
      path = URI.create(url).getPath();
    } catch (IllegalArgumentException e) {
      context.diagnostics.add(Diagnostic.warn(
        "Ignoring servers url '" + url + "' for @Path: not a valid URI (server variables are not supported)"));
      return "";
    }
    if (path == null || path.isBlank() || "/".equals(path)) {
      return "";
    }
    return path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
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
      response.streaming(),
      operationDoc(operation),
      Boolean.TRUE.equals(operation.getDeprecated()),
      response.description(),
      responseHeaderDocs(operation));
  }

  /** Combine an operation {@code summary} and {@code description} into a Javadoc body. */
  private static String operationDoc(Operation operation) {
    var summary = operation.getSummary();
    var description = operation.getDescription();
    var hasSummary = summary != null && !summary.isBlank();
    var hasDescription = description != null && !description.isBlank();
    if (hasSummary && hasDescription) {
      return summary.strip() + "\n\n" + description.strip();
    }
    if (hasSummary) {
      return summary;
    }
    return description;
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
        annotations.add(paramAnnotation("QueryParam", name));
        break;
      case "header":
        annotations.add(paramAnnotation("Header", name));
        break;
      case "cookie":
        annotations.add(paramAnnotation("Cookie", name));
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
    var overloadDrop = overloadDrop(context, parameter, in, defaultValue);
    var dropValue = dropValue(type, defaultValue);
    var nullable = !"path".equals(in) && defaultValue == null
      && (!Boolean.TRUE.equals(parameter.getRequired())
        || (schema != null && Boolean.TRUE.equals(schema.getNullable())));
    return new ParamDef(javaName, List.copyOf(annotations), type, overloadDrop, dropValue, parameter.getDescription(), nullable);
  }

  /**
   * Build an avaje-http parameter annotation with an explicit wire-name value, for example
   * {@code @QueryParam("status")}. The explicit name keeps the generated interface robust as
   * a contract-first artifact consumed via {@code @Client.Import} from a precompiled jar,
   * where Java parameter names are not available unless the consumer compiles with
   * {@code -parameters}.
   */
  private static String paramAnnotation(String simpleName, String wireName) {
    return "@" + simpleName + "(\"" + escape(wireName) + "\")";
  }

  /**
   * Decide whether a parameter may be omitted from a generated convenience overload.
   * Path parameters are never omittable; an explicit {@code x-overload} extension wins,
   * otherwise the configured {@link OverloadPolicy} applies.
   */
  private static boolean overloadDrop(Context context, Parameter parameter, String in, Object defaultValue) {
    if ("path".equals(in)) {
      return false;
    }
    var explicit = parameterExtensionBoolean(parameter, "x-overload");
    if (explicit != null) {
      return explicit;
    }
    var optional = !Boolean.TRUE.equals(parameter.getRequired());
    switch (context.config.overloadPolicy()) {
      case ALL_OPTIONAL:
        return optional;
      case NULLABLE_ONLY:
        return optional && defaultValue == null;
      case EXPLICIT:
      default:
        return false;
    }
  }

  /** The literal passed for an omitted parameter: its default value, or {@code null}. */
  private static String dropValue(JavaType type, Object defaultValue) {
    if (defaultValue == null) {
      return "null";
    }
    if ("String".equals(type.code())) {
      return "\"" + escape(String.valueOf(defaultValue)) + "\"";
    }
    return String.valueOf(defaultValue);
  }

  /** Read a boolean {@code x-} vendor extension on a parameter, or {@code null} when absent. */
  private static Boolean parameterExtensionBoolean(Parameter parameter, String name) {
    var extensions = parameter.getExtensions();
    if (extensions == null) {
      return null;
    }
    var value = extensions.get(name);
    if (value instanceof Boolean) {
      return (Boolean) value;
    }
    if (value != null) {
      return Boolean.valueOf(value.toString());
    }
    return null;
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

  /** Whether the Java type is a primitive (and therefore intrinsically non-null). */
  private static boolean isPrimitive(JavaType type) {
    switch (type.code()) {
      case "boolean":
      case "byte":
      case "short":
      case "char":
      case "int":
      case "long":
      case "float":
      case "double":
        return true;
      default:
        return false;
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
    var nullable = Boolean.FALSE.equals(requestBody.getRequired());
    return Optional.of(new ParamDef(bodyName(type), List.of(), type, false, "null", requestBody.getDescription(), nullable));
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
    return new ResponseDef(JavaType.simple("void"), 200, APPLICATION_JSON, false, null);
  }

  /** Collect response header documentation strings from the 2xx response of an operation. */
  private static List<String> responseHeaderDocs(Operation operation) {
    var responses = operation.getResponses();
    if (responses == null) {
      return List.of();
    }
    for (var entry : responses.entrySet()) {
      var code = parseStatus(entry.getKey());
      if (code >= 200 && code < 300) {
        return headerDocs(entry.getValue());
      }
    }
    return List.of();
  }

  /** Format each response header as {@code "Name (type)"} or {@code "Name (type — description)"}. */
  private static List<String> headerDocs(ApiResponse response) {
    if (response == null || response.getHeaders() == null || response.getHeaders().isEmpty()) {
      return List.of();
    }
    var result = new ArrayList<String>();
    for (var entry : response.getHeaders().entrySet()) {
      var name = entry.getKey();
      var header = entry.getValue();
      var type = "string";
      if (header.getSchema() != null && header.getSchema().getType() != null) {
        type = header.getSchema().getType();
      }
      var sb = new StringBuilder().append(name).append(" (").append(type);
      var desc = header.getDescription();
      if (desc != null && !desc.isBlank()) {
        sb.append(" \u2014 ").append(firstLine(desc));
      }
      sb.append(')');
      result.add(sb.toString());
    }
    return result;
  }

  private static ResponseDef readResponse(Context context, int statusCode, ApiResponse response) {
    var media = selectMedia(response == null ? null : response.getContent());
    var description = response == null ? null : response.getDescription();
    if (media == null || media.mediaType().getSchema() == null) {
      return new ResponseDef(JavaType.simple("void"), statusCode, APPLICATION_JSON, false, description);
    }
    var schema = media.mediaType().getSchema();
    if (isStreamingMedia(media.name())) {
      return new ResponseDef(context.streamType(schema), statusCode, media.name(), true, description);
    }
    return new ResponseDef(context.javaType(schema), statusCode, media.name(), false, description);
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
    var docTags = new ArrayList<String>();
    for (var field : object.fields()) {
      if (field.description() != null && !field.description().isBlank()) {
        docTags.add("@param " + field.javaName() + ' ' + firstLine(field.description()));
      }
    }
    source.body.append(javadoc("", object.description(), docTags));
    if (object.deprecated()) {
      source.body.append("@Deprecated\n");
    }
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
      if (context.nullableEnabled() && field.nullable()) {
        source.addImport(context.nullableImport());
      }
      if (context.config.validationAnnotations()) {
        if (field.required() && !field.nullable() && !isPrimitive(field.type())) {
          source.addImport(context.constraintImport("NotNull"));
        }
        if (field.validate()) {
          source.addImport(context.validImport());
        }
        for (var constraint : field.constraints()) {
          source.addImport(context.constraintImport(constraintSimpleName(constraint)));
        }
      }
      if (context.config.jsonAnnotations() && (field.readOnly() || field.writeOnly())) {
        if (context.config.jsonStyle() == JsonStyle.JACKSON) {
          source.addImport("com.fasterxml.jackson.annotation.JsonProperty");
        }
        // AVAJE: io.avaje.jsonb.Json already imported above for @Json on the class
      }
    }
    source.body.append("public record ").append(object.name()).append("(\n");
    for (var i = 0; i < object.fields().size(); i++) {
      var field = object.fields().get(i);
      source.body.append("  ");
      if (context.nullableEnabled() && field.nullable()) {
        source.body.append('@').append(context.nullableSimpleName()).append(' ');
      } else if (context.config.validationAnnotations() && field.required() && !isPrimitive(field.type())) {
        source.body.append("@NotNull ");
      }
      if (context.config.validationAnnotations() && field.validate()) {
        source.body.append("@Valid ");
      }
      if (context.config.validationAnnotations()) {
        for (var constraint : field.constraints()) {
          source.body.append(constraint).append(' ');
        }
      }
      if (context.config.jsonAnnotations() && field.readOnly()) {
        if (context.config.jsonStyle() == JsonStyle.JACKSON) {
          source.body.append("@JsonProperty(access = JsonProperty.Access.READ_ONLY) ");
        } else {
          source.body.append("@Json.Ignore(deserialize = true) ");
        }
      } else if (context.config.jsonAnnotations() && field.writeOnly()) {
        if (context.config.jsonStyle() == JsonStyle.JACKSON) {
          source.body.append("@JsonProperty(access = JsonProperty.Access.WRITE_ONLY) ");
        } else {
          source.body.append("@Json.Ignore(serialize = true) ");
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
    source.body.append(javadoc("", enumDef.description(), List.of()));
    if (enumDef.deprecated()) {
      source.body.append("@Deprecated\n");
    }
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
    var docTags = new ArrayList<String>();
    for (var param : operation.parameters()) {
      if (param.description() != null && !param.description().isBlank()) {
        docTags.add("@param " + param.name() + ' ' + firstLine(param.description()));
      }
    }
    if (!"void".equals(operation.returnType().code())
      && operation.returnDescription() != null && !operation.returnDescription().isBlank()) {
      docTags.add("@return " + firstLine(operation.returnDescription()));
    }
    if (!operation.responseHeaders().isEmpty()) {
      docTags.add("@apiNote Response headers: " + String.join(", ", operation.responseHeaders()));
    }
    source.body.append(javadoc("  ", operation.description(), docTags));
    if (operation.deprecated()) {
      source.body.append("  @Deprecated\n");
    }
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
      if (context.nullableEnabled() && param.nullable()) {
        source.addImport(context.nullableImport());
      }
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
      if (context.nullableEnabled() && param.nullable()) {
        source.body.append('@').append(context.nullableSimpleName()).append(' ');
      }
      for (var ann : param.annotations()) {
        source.body.append(ann).append(' ');
      }
      source.body.append(param.type().code()).append(' ').append(param.name());
    }
    source.body.append(");\n\n");
    writeOverloads(source, operation, context);
  }

  /**
   * Emit convenience {@code default} method overloads that omit a trailing run of
   * omittable parameters and delegate to the full abstract method. These are inert to
   * the Avaje HTTP server/client generators (they carry no HTTP annotation) and exist
   * purely for caller ergonomics.
   */
  private static void writeOverloads(JavaSource source, OperationDef operation, Context context) {
    if (!context.config.generateOverloads()) {
      return;
    }
    var params = operation.parameters();
    var trailing = 0;
    for (var i = params.size() - 1; i >= 0; i--) {
      if (params.get(i).overloadDrop()) {
        trailing++;
      } else {
        break;
      }
    }
    if (trailing == 0) {
      return;
    }
    var returnCode = operation.returnType().code();
    var isVoid = "void".equals(returnCode);
    for (var drop = 1; drop <= trailing; drop++) {
      var keep = params.size() - drop;
      source.body.append("  default ").append(returnCode).append(' ').append(operation.methodName()).append('(');
      for (var i = 0; i < keep; i++) {
        if (i > 0) {
          source.body.append(", ");
        }
        var param = params.get(i);
        source.body.append(param.type().code()).append(' ').append(param.name());
      }
      source.body.append(") {\n    ");
      if (!isVoid) {
        source.body.append("return ");
      }
      source.body.append(operation.methodName()).append('(');
      for (var i = 0; i < params.size(); i++) {
        if (i > 0) {
          source.body.append(", ");
        }
        source.body.append(i < keep ? params.get(i).name() : params.get(i).dropValue());
      }
      source.body.append(");\n  }\n\n");
    }
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

  /**
   * Render a Javadoc block indented by {@code indent} from an optional multi-line
   * {@code description} and a list of fully-formed tag lines (e.g.
   * {@code "@param id the id"}). Returns an empty string when there is nothing to
   * document.
   */
  private static String javadoc(String indent, String description, List<String> tags) {
    var hasDescription = description != null && !description.isBlank();
    if (!hasDescription && tags.isEmpty()) {
      return "";
    }
    var out = new StringBuilder();
    out.append(indent).append("/**\n");
    if (hasDescription) {
      for (var line : description.strip().split("\n", -1)) {
        if (line.isBlank()) {
          out.append(indent).append(" *\n");
        } else {
          out.append(indent).append(" * ").append(sanitizeJavadoc(line)).append('\n');
        }
      }
    }
    if (!tags.isEmpty()) {
      if (hasDescription) {
        out.append(indent).append(" *\n");
      }
      for (var tag : tags) {
        out.append(indent).append(" * ").append(tag).append('\n');
      }
    }
    out.append(indent).append(" */\n");
    return out.toString();
  }

  /** Escape sequences that would prematurely close a Javadoc comment. */
  private static String sanitizeJavadoc(String text) {
    return text.replace("*/", "*&#47;");
  }

  /** The first (sanitized, trimmed) line of a description, for single-line tag text. */
  private static String firstLine(String text) {
    var newline = text.indexOf('\n');
    return sanitizeJavadoc((newline < 0 ? text : text.substring(0, newline)).strip());
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
          return mappedType("string", schema.getFormat(), () -> stringType(schema.getFormat()));
        case "integer":
          return mappedType("integer", schema.getFormat(), () -> integerType(schema.getFormat()));
        case "number":
          return mappedType("number", schema.getFormat(), () -> numberType(schema.getFormat()));
        case "boolean":
          return mappedType("boolean", schema.getFormat(), () -> JavaType.simple("Boolean"));
        case "object":
        default:
          return JavaType.simple("Object");
      }
    }

    /**
     * Apply a configured {@code typeMappings} override for the given scalar type and
     * format, falling back to the built-in resolution. A {@code format} key (e.g.
     * {@code uuid}) takes precedence over a {@code type} key (e.g. {@code string}).
     */
    private JavaType mappedType(String type, String format, Supplier<JavaType> fallback) {
      var mappings = config.typeMappings();
      if (!mappings.isEmpty()) {
        if (format != null && !format.isBlank() && mappings.containsKey(format)) {
          return javaTypeFromName(mappings.get(format));
        }
        if (mappings.containsKey(type)) {
          return javaTypeFromName(mappings.get(type));
        }
      }
      return fallback.get();
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

    /** Fully-qualified {@code @Valid} import for the configured validation style. */
    String validImport() {
      return config.validationStyle().validClass();
    }

    /** Whether {@code @Nullable} generation is enabled. */
    boolean nullableEnabled() {
      return !config.nullableAnnotation().isBlank();
    }

    /** The fully-qualified {@code @Nullable} annotation to import. */
    String nullableImport() {
      return config.nullableAnnotation();
    }

    /** The simple name of the configured {@code @Nullable} annotation. */
    String nullableSimpleName() {
      var fqn = config.nullableAnnotation();
      var dot = fqn.lastIndexOf('.');
      return dot < 0 ? fqn : fqn.substring(dot + 1);
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
    private final String description;
    private final boolean deprecated;

    private ObjectDef(String name, List<FieldDef> fields, String description, boolean deprecated) {
      this.name = name;
      this.fields = List.copyOf(fields);
      this.description = description;
      this.deprecated = deprecated;
    }

    @Override
    public String name() {
      return name;
    }

    List<FieldDef> fields() {
      return fields;
    }

    String description() {
      return description;
    }

    boolean deprecated() {
      return deprecated;
    }
  }

  private static final class FieldDef {
    private final String javaName;
    private final String jsonName;
    private final JavaType type;
    private final boolean required;
    private final List<String> constraints;
    private final String description;
    private final boolean nullable;
    private final boolean validate;
    private final boolean readOnly;
    private final boolean writeOnly;

    private FieldDef(String javaName, String jsonName, JavaType type, boolean required, List<String> constraints, String description, boolean nullable, boolean validate, boolean readOnly, boolean writeOnly) {
      this.javaName = javaName;
      this.jsonName = jsonName;
      this.type = type;
      this.required = required;
      this.constraints = List.copyOf(constraints);
      this.description = description;
      this.nullable = nullable;
      this.validate = validate;
      this.readOnly = readOnly;
      this.writeOnly = writeOnly;
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

    String description() {
      return description;
    }

    boolean nullable() {
      return nullable;
    }

    /** Whether the field type is a generated model and should cascade with {@code @Valid}. */
    boolean validate() {
      return validate;
    }

    /** Whether this field appears only in responses (serialized out, not deserialized in). */
    boolean readOnly() {
      return readOnly;
    }

    /** Whether this field appears only in requests (deserialized in, not serialized out). */
    boolean writeOnly() {
      return writeOnly;
    }
  }

  private static final class EnumDef implements SchemaDef {
    private final String name;
    private final List<EnumValue> values;
    private final String description;
    private final boolean deprecated;

    private EnumDef(String name, List<EnumValue> values, String description, boolean deprecated) {
      this.name = name;
      this.values = List.copyOf(values);
      this.description = description;
      this.deprecated = deprecated;
    }

    @Override
    public String name() {
      return name;
    }

    List<EnumValue> values() {
      return values;
    }

    String description() {
      return description;
    }

    boolean deprecated() {
      return deprecated;
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
    private final String description;
    private final boolean deprecated;
    private final String returnDescription;
    private final List<String> responseHeaders;

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
      boolean streaming,
      String description,
      boolean deprecated,
      String returnDescription,
      List<String> responseHeaders) {

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
      this.description = description;
      this.deprecated = deprecated;
      this.returnDescription = returnDescription;
      this.responseHeaders = responseHeaders == null ? List.of() : List.copyOf(responseHeaders);
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

    String description() {
      return description;
    }

    boolean deprecated() {
      return deprecated;
    }

    String returnDescription() {
      return returnDescription;
    }

    /** Response header names and types documented from the 2xx response definition. */
    List<String> responseHeaders() {
      return responseHeaders;
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
        streaming,
        description,
        deprecated,
        returnDescription,
        responseHeaders);
    }
  }

  private static final class ParamDef {
    private final String name;
    private final List<String> annotations;
    private final JavaType type;
    private final boolean overloadDrop;
    private final String dropValue;
    private final String description;
    private final boolean nullable;

    private ParamDef(String name, List<String> annotations, JavaType type, boolean overloadDrop, String dropValue, String description, boolean nullable) {
      this.name = name;
      this.annotations = annotations;
      this.type = type;
      this.overloadDrop = overloadDrop;
      this.dropValue = dropValue;
      this.description = description;
      this.nullable = nullable;
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

    /** Whether this parameter may be omitted from a generated convenience overload. */
    boolean overloadDrop() {
      return overloadDrop;
    }

    /** The literal value passed for this parameter when it is omitted from an overload. */
    String dropValue() {
      return dropValue;
    }

    String description() {
      return description;
    }

    boolean nullable() {
      return nullable;
    }
  }

  private static final class ResponseDef {
    private final JavaType type;
    private final int statusCode;
    private final String mediaType;
    private final boolean streaming;
    private final String description;

    private ResponseDef(JavaType type, int statusCode, String mediaType, boolean streaming, String description) {
      this.type = type;
      this.statusCode = statusCode;
      this.mediaType = mediaType;
      this.streaming = streaming;
      this.description = description;
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

    String description() {
      return description;
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
