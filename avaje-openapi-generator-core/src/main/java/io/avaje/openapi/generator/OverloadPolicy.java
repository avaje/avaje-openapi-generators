package io.avaje.openapi.generator;

/**
 * Policy deciding which trailing parameters are <em>omittable</em> when generating
 * convenience {@code default} method overloads (see
 * {@link GeneratorConfig#generateOverloads()}).
 *
 * <p>Only a contiguous run of omittable parameters at the <em>end</em> of a method
 * signature can be dropped, because Java positional overloads can only omit trailing
 * arguments. The policy classifies each parameter; a per-parameter {@code x-overload}
 * vendor extension ({@code true}/{@code false}) overrides the policy for that
 * parameter.
 *
 * <p>Path parameters and request bodies are never omittable.
 */
public enum OverloadPolicy {
  /**
   * Only parameters explicitly marked with {@code x-overload: true} are omittable.
   * No parameter is omittable by default.
   */
  EXPLICIT,

  /**
   * Optional parameters that have no {@code default} value are omittable (they pass
   * {@code null} when omitted). Parameters with a {@code default} are not omittable
   * unless marked {@code x-overload: true}. This is the default.
   */
  NULLABLE_ONLY,

  /**
   * Every optional parameter is omittable, including those with a {@code default}
   * value (a defaulted parameter passes its default literal when omitted).
   */
  ALL_OPTIONAL
}
