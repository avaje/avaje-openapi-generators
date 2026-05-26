package io.avaje.openapi.generator.internal;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Java naming helpers. */
public final class NameUtils {

  private static final Set<String> RESERVED = Set.of(
    "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class",
    "const", "continue", "default", "do", "double", "else", "enum", "extends", "final",
    "finally", "float", "for", "goto", "if", "implements", "import", "instanceof", "int",
    "interface", "long", "native", "new", "package", "private", "protected", "public",
    "return", "short", "static", "strictfp", "super", "switch", "synchronized", "this",
    "throw", "throws", "transient", "try", "void", "volatile", "while", "var", "record",
    "yield", "sealed", "permits", "non-sealed");

  private NameUtils() {
  }

  public static String className(String raw) {
    var name = upperCamel(raw);
    return name.isEmpty() ? "GeneratedType" : name;
  }

  public static String apiName(String raw) {
    var name = className(raw);
    return name.endsWith("Api") ? name : name + "Api";
  }

  public static String methodName(String raw) {
    var name = lowerCamel(raw);
    return name.isEmpty() ? "operation" : escapeReserved(name);
  }

  public static String variableName(String raw) {
    var name = lowerCamel(raw);
    return name.isEmpty() ? "value" : escapeReserved(name);
  }

  public static String enumConstant(String raw, Set<String> existing) {
    var upper = raw == null || raw.isBlank() ? "VALUE" : raw;
    upper = upper.replaceAll("([a-z])([A-Z])", "$1_$2");
    upper = upper.replaceAll("[^A-Za-z0-9]+", "_").toUpperCase(Locale.ROOT);
    upper = upper.replaceAll("^_+|_+$", "");
    if (upper.isEmpty() || !Character.isJavaIdentifierStart(upper.charAt(0))) {
      upper = "VALUE_" + upper;
    }
    var result = upper;
    var count = 2;
    while (!existing.add(result)) {
      result = upper + "_" + count++;
    }
    return result;
  }

  public static String operationName(String method, String path) {
    var cleaned = path.replace("{", " ").replace("}", " ");
    return method.toLowerCase(Locale.ROOT) + " " + cleaned;
  }

  public static boolean simpleJavaIdentifier(String name) {
    if (name == null || name.isBlank() || RESERVED.contains(name)) {
      return false;
    }
    if (!Character.isJavaIdentifierStart(name.charAt(0))) {
      return false;
    }
    for (int i = 1; i < name.length(); i++) {
      if (!Character.isJavaIdentifierPart(name.charAt(i))) {
        return false;
      }
    }
    return true;
  }

  public static String packageToPath(String packageName) {
    return packageName.replace('.', '/');
  }

  public static String commonLiteralPrefix(Iterable<String> paths) {
    String[] common = null;
    for (var path : paths) {
      var parts = literalPrefixSegments(path);
      if (common == null) {
        common = parts;
      } else {
        var len = Math.min(common.length, parts.length);
        var keep = 0;
        while (keep < len && common[keep].equals(parts[keep])) {
          keep++;
        }
        var next = new String[keep];
        System.arraycopy(common, 0, next, 0, keep);
        common = next;
      }
    }
    if (common == null || common.length == 0) {
      return "";
    }
    return "/" + String.join("/", common);
  }

  private static String[] literalPrefixSegments(String path) {
    if (path == null || path.isBlank() || "/".equals(path)) {
      return new String[0];
    }
    var parts = path.split("/");
    var literals = new java.util.ArrayList<String>();
    for (var part : parts) {
      if (part.isBlank()) {
        continue;
      }
      if (part.contains("{") || part.contains("}")) {
        break;
      }
      literals.add(part);
    }
    return literals.toArray(String[]::new);
  }

  private static String upperCamel(String raw) {
    var lower = lowerCamel(raw);
    return lower.isEmpty() ? lower : Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
  }

  private static String lowerCamel(String raw) {
    var words = words(raw);
    if (words.isEmpty()) {
      return "";
    }
    var out = new StringBuilder(words.getFirst().toLowerCase(Locale.ROOT));
    for (var i = 1; i < words.size(); i++) {
      var word = words.get(i).toLowerCase(Locale.ROOT);
      out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
    }
    if (!out.isEmpty() && !Character.isJavaIdentifierStart(out.charAt(0))) {
      out.insert(0, "value");
    }
    return out.toString();
  }

  private static java.util.List<String> words(String raw) {
    if (raw == null) {
      return java.util.List.of();
    }
    var spaced = raw.replaceAll("([a-z])([A-Z])", "$1 $2").replaceAll("[^A-Za-z0-9]+", " ");
    var result = new java.util.ArrayList<String>();
    for (var part : spaced.trim().split("\\s+")) {
      if (!part.isBlank()) {
        result.add(part);
      }
    }
    return result;
  }

  private static String escapeReserved(String name) {
    return RESERVED.contains(name) ? name + "Value" : name;
  }

  public static Set<String> newEnumConstantSet() {
    return new HashSet<>();
  }
}
