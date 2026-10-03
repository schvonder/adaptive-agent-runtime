package io.adaptiveagents.workload.tools;

/**
 * Deterministic slug function used to map queries and URLs to fixture file names:
 * every alphanumeric character is lower-cased, every other character becomes {@code '-'}.
 */
final class Slug {

    private Slug() {
    }

    static String of(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                sb.append(Character.toLowerCase(c));
            } else {
                sb.append('-');
            }
        }
        return sb.toString();
    }
}
