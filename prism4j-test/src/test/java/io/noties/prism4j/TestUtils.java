package io.noties.prism4j;

import com.google.gson.Gson;
import com.google.gson.JsonArray;

import org.jetbrains.annotations.NotNull;
import org.junit.Assert;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Scanner;
import java.util.stream.Collectors;

import static net.javacrumbs.jsonunit.JsonAssert.assertJsonEquals;

public abstract class TestUtils {

    private static final String DELIMITER = "-{52}";

    private static final Gson GSON = new Gson();

    @NotNull
    public static Collection<Object> testFiles(@NotNull String lang) {

        final String folder = "languages/" + lang + "/";
        InputStream inputStream = TestUtils.class.getClassLoader().getResourceAsStream(folder);
        Assert.assertNotNull(inputStream);
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(inputStream, StandardCharsets.UTF_8)
        )) {
            return Collections.singleton(br.lines()
                    .filter(s -> s.endsWith(".test"))
                    .map(s -> folder + s)
                    .collect(Collectors.toList()));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static class Case {

        public final String input;
        public final JsonArray simplifiedOutput;
        public final String description;

        Case(@NotNull String input, @NotNull JsonArray simplifiedOutput, @NotNull String description) {
            this.input = input;
            this.simplifiedOutput = simplifiedOutput;
            this.description = description;
        }
    }

    @NotNull
    public static Case readCase(@NotNull String file) {

        final String raw;
        try {
            raw = resourceToString(file);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }

        if (raw == null || raw.isEmpty()) {
            throw new RuntimeException("Test file has no contents, file: " + file);
        }

        final String[] split = raw.split(DELIMITER);
        if (split.length < 2) {
            throw new RuntimeException("Test file seems to have wrong delimiter, file: " + file);
        }

        final String input = split[0].trim();
        final JsonArray simplifiedOutput = GSON.fromJson(split[1].trim(), JsonArray.class);
        final String description = split[2].trim();

        return new Case(input, simplifiedOutput, description);
    }

    public static void assertCase(@NotNull Case c, @NotNull List<? extends Prism4j.Node> nodes) {

        final String expected = c.simplifiedOutput.toString();
        final String actual = simplify(nodes).toString();

        try {
            assertJsonEquals(expected, actual);
        } catch (AssertionError e) {
            final String newMessage = c.description + "\n" +
                    e.getMessage() + "\n" +
                    "expected: " + expected + "\n" +
                    "actual  : " + actual + "\n\n";
            throw new AssertionError(newMessage, e);
        }
    }

    @NotNull
    private static JsonArray simplify(@NotNull List<? extends Prism4j.Node> nodes) {
        // root array
        final JsonArray array = new JsonArray();
        for (Prism4j.Node node : nodes) {
            if (node instanceof Prism4j.Text) {
                final String literal = ((Prism4j.Text) node).literal();
                if (!literal.isBlank()) {
                    array.add(literal);
                }
            } else {
                final Prism4j.Syntax syntax = (Prism4j.Syntax) node;
                final JsonArray inner = new JsonArray();
                inner.add(syntax.type());
                if (syntax.tokenized()) {
                    inner.add(simplify(syntax.children()));
                } else {
                    inner.addAll(simplify(syntax.children()));
                }
                array.add(inner);
            }
        }
        return array;
    }

    private static String resourceToString(String file) {
        ClassLoader classLoader = TestUtils.class.getClassLoader();
        try (InputStream is = classLoader.getResourceAsStream(file)) {
            assert is != null;
            try (Scanner scanner = new Scanner(is, StandardCharsets.UTF_8)) {
                scanner.useDelimiter("\\A");
                return scanner.hasNext() ? scanner.next() : "";
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to load resource: " + file, e);
        }
    }

    private TestUtils() {
    }
}
