package dev.agentbox.orchestrator;

import java.util.regex.Pattern;

public final class Names {

    public static final String PATTERN = "^[a-z][a-z0-9-]{0,31}$";
    private static final Pattern COMPILED = Pattern.compile(PATTERN);

    private Names() {}

    public static String check(String name) {
        if (name == null || !COMPILED.matcher(name).matches()) {
            throw new IllegalArgumentException(
                    "agent name must be 1-32 characters, start with a letter, and use only lowercase letters, digits, and hyphens");
        }
        return name;
    }

    public static String container(String name) {
        return "agentbox-" + name;
    }
}
