package dev.oreslang.realtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Validates the JamaicaVM Builder options required by the Oreslang strict guest
 * AOT profile. This validates configuration, not the resulting machine image;
 * deployment must additionally bind the compiled image to the audited
 * classfile digest and VM qualification.
 */
public final class JamaicaBuilderContract {
    private JamaicaBuilderContract() { }

    public record Result(List<String> failures) {
        public Result {
            failures = List.copyOf(Objects.requireNonNull(failures, "failures"));
        }
        public boolean admitted() { return failures.isEmpty(); }
    }

    public static Result evaluate(List<String> arguments) {
        Objects.requireNonNull(arguments, "arguments");
        ArrayList<String> failures = new ArrayList<>();
        boolean compile = false;
        boolean closed = false;

        for (String raw : arguments) {
            String argument = Objects.requireNonNull(raw, "builder argument").trim();
            if ("-compile".equals(argument)) compile = true;
            if ("-closed".equals(argument)) closed = true;
            if ("-interpret".equals(argument) || "-Xint".equals(argument)) {
                failures.add("interpreter-forced build is forbidden");
            }
            if (argument.equals("-profile") || argument.startsWith("-profile=")
                    || argument.startsWith("-useProfile")
                    || argument.startsWith("-percentageCompiled")) {
                failures.add("profile/partial compilation is forbidden in strict guest AOT");
            }
            if (argument.startsWith("-excludeFromCompile")) {
                failures.add("excluding application methods from AOT is forbidden");
            }
        }

        if (!compile) failures.add("JamaicaVM Builder -compile is required");
        if (!closed) failures.add("JamaicaVM Builder -closed is required");
        return new Result(failures);
    }

    public static void requireStrictGuestAot(List<String> arguments) {
        Result result = evaluate(arguments);
        if (!result.admitted()) {
            throw new IllegalArgumentException(
                    "JamaicaVM strict guest AOT denied: "
                            + String.join("; ", result.failures()));
        }
    }
}
