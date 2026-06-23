package io.avaje.openapi.generator;

/** JSON serialisation library whose annotations are generated on DTO records. */
public enum JsonStyle {
  /**
   * Generate <a href="https://avaje.io/jsonb/">Avaje Jsonb</a> annotations.
   * {@code readOnly} fields get {@code @Json.Ignore(deserialize = true)};
   * {@code writeOnly} fields get {@code @Json.Ignore(serialize = true)}.
   */
  AVAJE,

  /**
   * Generate <a href="https://github.com/FasterXML/jackson-annotations">Jackson</a> annotations.
   * {@code readOnly} fields get {@code @JsonProperty(access = JsonProperty.Access.READ_ONLY)};
   * {@code writeOnly} fields get {@code @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)}.
   */
  JACKSON;
}
