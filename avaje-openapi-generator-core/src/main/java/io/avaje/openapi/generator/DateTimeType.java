package io.avaje.openapi.generator;

/**
 * Java {@code java.time} type used for OpenAPI {@code format: date-time} properties.
 *
 * <p>Defaults to {@link #OFFSET_DATE_TIME}, matching the prevailing OpenAPI generator
 * convention and RFC 3339, which defines {@code date-time} as carrying a timezone
 * offset. Choose {@link #INSTANT} to normalise to UTC (losing the original offset).
 *
 * <p>Individual properties can override this global default via an extended
 * {@code format} value ({@code instant}, {@code offset-date-time},
 * {@code local-date-time}, {@code zoned-date-time}) or the {@code x-java-type}
 * vendor extension.
 */
public enum DateTimeType {
  /** {@code java.time.Instant} (UTC, offset discarded). */
  INSTANT("Instant", "java.time.Instant"),

  /** {@code java.time.OffsetDateTime} (default, preserves the wire offset). */
  OFFSET_DATE_TIME("OffsetDateTime", "java.time.OffsetDateTime"),

  /** {@code java.time.LocalDateTime} (no offset). */
  LOCAL_DATE_TIME("LocalDateTime", "java.time.LocalDateTime"),

  /** {@code java.time.ZonedDateTime} (zone region or offset). */
  ZONED_DATE_TIME("ZonedDateTime", "java.time.ZonedDateTime");

  private final String simpleName;
  private final String className;

  DateTimeType(String simpleName, String className) {
    this.simpleName = simpleName;
    this.className = className;
  }

  /** Simple type name, e.g. {@code OffsetDateTime}. */
  public String simpleName() {
    return simpleName;
  }

  /** Fully qualified class name, e.g. {@code java.time.OffsetDateTime}. */
  public String className() {
    return className;
  }
}
