package io.avaje.openapi.generator;

/** Generation mode. */
public enum GenerationMode {
  /** Generate shared Avaje HTTP API contracts and models. */
  CONTRACT,
  /** Reserved for future client-specific generation. */
  CLIENT,
  /** Reserved for future server-specific generation. */
  SERVER
}
