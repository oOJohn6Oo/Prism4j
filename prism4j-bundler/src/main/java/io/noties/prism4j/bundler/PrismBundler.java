package io.noties.prism4j.bundler;

import com.google.googlejavaformat.java.Formatter;
import com.google.googlejavaformat.java.FormatterException;
import com.google.googlejavaformat.java.JavaFormatterOptions;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;
import javax.tools.JavaFileObject;

import io.noties.prism4j.annotations.PrismBundle;

import static javax.tools.Diagnostic.Kind.*;

@SuppressWarnings("RegExpRedundantEscape")
public class PrismBundler extends AbstractProcessor {

    private static final String LANGUAGES_PACKAGE = "io.noties.prism4j.languages";
    private static final String LANGUAGES_FOLDER = "languages/";
    private static final String LANGUAGE_SOURCE_PATTERN = LANGUAGES_FOLDER + "Prism_%1$s.java";

    private static final String TEMPLATE_PACKAGE_NAME = "{{package-name}}";
    private static final String TEMPLATE_IMPORTS = "{{imports}}";
    private static final String TEMPLATE_CLASS_NAME = "{{class-name}}";
    private static final String TEMPLATE_REAL_LANGUAGE_NAME = "{{real-language-name}}";
    private static final String TEMPLATE_OBTAIN_GRAMMAR = "{{obtain-grammar}}";
    private static final String TEMPLATE_TRIGGER_MODIFY = "{{trigger-modify}}";
    private static final String TEMPLATE_LANGUAGES = "{{languages}}";

    private static final Pattern LANGUAGE_NAME = Pattern.compile("Prism\\_(\\w+)\\.java");

    private final AnnotationsInformation annotationsInformation = AnnotationsInformation.create();
    private final ListResources listResources = ListResources.create();

    private Messager messager;
    private Elements elements;
    private Filer filer;

    // this override might've killed me... without it processor cannot find ANY resources...
    @Override
    public Set<String> getSupportedOptions() {
        return Collections.emptySet();
    }

    @Override
    public Set<String> getSupportedAnnotationTypes() {
        return Collections.singleton(PrismBundle.class.getName());
    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.RELEASE_8;
    }

    @Override
    public synchronized void init(ProcessingEnvironment processingEnvironment) {
        super.init(processingEnvironment);
        messager = processingEnvironment.getMessager();
        elements = processingEnvironment.getElementUtils();
        filer = processingEnvironment.getFiler();
    }

    @Override
    public boolean process(Set<? extends TypeElement> set, RoundEnvironment roundEnvironment) {

        if (!roundEnvironment.processingOver()) {
            final long start = System.currentTimeMillis();
            final Set<? extends Element> elements = roundEnvironment.getElementsAnnotatedWith(PrismBundle.class);

            // generate locator for each annotated element
            // extract languages that are used by each locator
            // then write them
            if (elements != null) {

                final Set<LanguageInfo> languages = new HashSet<>();

                for (Element element : elements) {
                    if (element != null) {
                        languages.addAll(process(element));
                    }
                }

                if (!languages.isEmpty()) {
                    writeLanguages(languages);
                }
            }
            final long end = System.currentTimeMillis();
            messager.printMessage(NOTE, "[prism4j-bundler] Processing took: " + (end - start) + " ms");
        }

        return false;
    }

    // we must return a set of languageInfos here (so we do not copy language more than once
    // thus allowing multiple grammarLocators)

    @NotNull
    private Set<LanguageInfo> process(@NotNull Element element) {

        final PrismBundle bundle = element.getAnnotation(PrismBundle.class);
        if (bundle == null) {
            return Collections.emptySet();
        }

        final Map<String, LanguageInfo> languages = prepareLanguages(bundle);

        if (languages.isEmpty()) {
            messager.printMessage(ERROR, "No languages are specified to be included", element);
            throw new RuntimeException("No languages are specified to be included");
        }

        final String template = grammarLocatorTemplate();
        final ClassInfo classInfo = classInfo(element, bundle.grammarLocatorClassName());
        final String source = grammarLocatorSource(template, classInfo, languages);

        Writer writer = null;
        try {
            final JavaFileObject javaFileObject = filer.createSourceFile(classInfo.packageName + "." + classInfo.className);
            writer = javaFileObject.openWriter();
            writer.write(source);
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            if (writer != null) {
                try {
                    writer.close();
                } catch (IOException e) {
                    // no op
                }
            }
        }

        return new HashSet<>(languages.values());

        // first obtain all languages from annotation `include` parameter
        // then validate that such a language is present in `languages` folder
        // then parse @Language annotation of a language source file
        //  additionally check for extend clause and add parent language to compilation also (and then again recursively)
        // then just copy all requested language files
        // then generate a grammarLocator implementation
        //  include all languages and additionally generate aliases information

        // so, to start we generate a map of languages to include
    }

    @NotNull
    private Map<String, LanguageInfo> prepareLanguages(@NotNull PrismBundle bundle) {

        final Map<String, LanguageInfo> languages;

        final List<String> names;

        if (bundle.includeAll()) {
            // list files from our resources folder and create names
            names = allLanguages();
        } else {
            names = processLanguageNames(bundle.include());
        }

        final int size = names.size();
        if (size > 0) {
            languages = new LinkedHashMap<>(size);
            for (String name : names) {
                languageInfo(languages, name);
            }
        } else {
            languages = Collections.emptyMap();
        }

        return languages;
    }

    @NotNull
    private List<String> allLanguages() {

        final List<String> list = listResources.listResourceFiles(PrismBundler.class, LANGUAGES_FOLDER);
        if (list.isEmpty()) {
            throw new RuntimeException("Cannot obtain language files");
        }

        return list.stream()
                .map(LANGUAGE_NAME::matcher)
                .filter(Matcher::matches)
                .map(m -> m.group(1))
                .map(s -> s.replace('_', '-'))
                .collect(Collectors.toList());
    }

    private void writeLanguages(@NotNull Set<LanguageInfo> languages) {
        for (LanguageInfo info : languages) {
            Writer writer = null;
            try {
                final JavaFileObject javaFileObject = filer.createSourceFile(LANGUAGES_PACKAGE + ".Prism_" + javaValidName(info.name));
                writer = javaFileObject.openWriter();
                writer.write(info.source);
            } catch (IOException e) {
                throw new RuntimeException(e);
            } finally {
                if (writer != null) {
                    try {
                        writer.close();
                    } catch (IOException e) {
                        // no op
                    }
                }
            }
        }
    }

    @NotNull
    private List<String> processLanguageNames(@NotNull String[] names) {
        return Arrays.stream(names)
                .filter(s -> !s.isBlank())
                .collect(Collectors.toList());
    }

    private void languageInfo(@NotNull Map<String, LanguageInfo> map, @NotNull String name) {

        if (map.containsKey(name)) {
            return;
        }

        // read info
        final String source;
        try {
            source = resourceToString(languageSourceFileName(name), PrismBundler.class.getClassLoader());
        } catch (RuntimeException e) {
            throw new RuntimeException(String.format(Locale.US, "Unable to read language `%1$s` " +
                    "source file. Either it is not defined yet or it was referenced as an alias " +
                    "when specifying extend clause", name), e);
        }

        final List<String> aliases = annotationsInformation.findAliasesInformation(source);
        final String extend = annotationsInformation.findExtendInformation(source);
        final List<String> modify = annotationsInformation.findModifyInformation(source);

        map.put(name, new LanguageInfo(name, aliases, extend, modify, source));

        if (extend != null) {
            languageInfo(map, extend);
        }
    }

    @NotNull
    private static String languageSourceFileName(@NotNull String name) {
        return String.format(Locale.US, LANGUAGE_SOURCE_PATTERN, javaValidName(name));
    }

    @NotNull
    private static String grammarLocatorTemplate() {
        try {
            return resourceToString("/GrammarLocator.template.java");
        } catch (RuntimeException e) {
            throw new RuntimeException(e);
        }
    }

    @NotNull
    private ClassInfo classInfo(@NotNull Element element, @NotNull String name) {

        final String packageName;
        final String className;

        if ('.' == name.charAt(0)) {
            final PackageElement packageElement = elements.getPackageOf(element);
            packageName = packageElement.getQualifiedName().toString();
            className = name.substring(1);
        } else {
            final int index = name.lastIndexOf('.');
            if (index < 0) {
                // we won't allow _default_ package (aka no package)
                messager.printMessage(ERROR, "No package info specified for grammar " +
                        "locator. In can start with a `dot` to put in the same package or must be " +
                        "fully specified, for example: `com.mypackage`", element);
                throw new RuntimeException("No package info is specified. See error output " +
                        "for more details");
            }
            packageName = name.substring(0, index);
            className = name.substring(index + 1);
        }

        return new ClassInfo(packageName, className);
    }

    @NotNull
    private static String grammarLocatorSource(
            @NotNull String template,
            @NotNull ClassInfo classInfo,
            @NotNull Map<String, LanguageInfo> languages) {
        final StringBuilder builder = new StringBuilder(template);
        replaceTemplate(builder, TEMPLATE_PACKAGE_NAME, classInfo.packageName);
        replaceTemplate(builder, TEMPLATE_IMPORTS, createImports(languages));
        replaceTemplate(builder, TEMPLATE_CLASS_NAME, classInfo.className);
        replaceTemplate(builder, TEMPLATE_REAL_LANGUAGE_NAME, createRealLanguageName(languages));
        replaceTemplate(builder, TEMPLATE_OBTAIN_GRAMMAR, createObtainGrammar(languages));
        replaceTemplate(builder, TEMPLATE_TRIGGER_MODIFY, createTriggerModify(languages));
        replaceTemplate(builder, TEMPLATE_LANGUAGES, createLanguages(languages));
        final Formatter formatter = new Formatter(JavaFormatterOptions.defaultOptions());
        try {
            return formatter.formatSource(builder.toString());
        } catch (FormatterException e) {
            System.out.printf("source: %n%s%n", builder);
            throw new RuntimeException(e);
        }
    }

    private static void replaceTemplate(@NotNull StringBuilder template, @NotNull String name, @NotNull String content) {
        final int index = template.indexOf(name);
        template.replace(index, index + name.length(), content);
    }

    @NotNull
    private static String createImports(@NotNull Map<String, LanguageInfo> languages) {
        final StringBuilder builder = new StringBuilder();
        languages.values()
                .stream()
                .map(languageInfo -> languageInfo.name)
                .sorted(String::compareTo)
                .forEach(s -> builder
                        .append("import ")
                        .append(LANGUAGES_PACKAGE)
                        .append(".Prism_")
                        .append(javaValidName(s))
                        .append(';')
                        .append('\n'));
        return builder.toString();
    }

    @NotNull
    private static String createRealLanguageName(@NotNull Map<String, LanguageInfo> languages) {
        final StringBuilder builder = new StringBuilder();
        for (Map.Entry<String, LanguageInfo> entry : languages.entrySet()) {
            final List<String> aliases = entry.getValue().aliases;
            if (aliases != null
                    && !aliases.isEmpty()) {
                for (String alias : aliases) {
                    builder.append("case \"")
                            .append(alias)
                            .append('\"')
                            .append(':')
                            .append('\n');
                }
                builder.append("out = \"")
                        .append(entry.getKey())
                        .append('\"')
                        .append(';')
                        .append('\n')
                        .append("break;\n");
            }
        }

        if (builder.length() > 0) {
            builder.append("default:\n")
                    .append("out = name;\n")
                    .append("}\nreturn out;");
            builder.insert(0, "final String out;\nswitch (name) {");
            return builder.toString();
        } else {
            return "return name;";
        }
    }

    @NotNull
    private static String createObtainGrammar(@NotNull Map<String, LanguageInfo> languages) {
        final StringBuilder builder = new StringBuilder();
        builder.append("final Prism4j.Grammar grammar;\n").append("switch(name) {\n");
        languages.keySet().stream().sorted(String::compareTo).forEach(s -> builder.append("case \"")
                .append(s)
                .append("\":\n")
                .append("grammar = Prism_")
                .append(javaValidName(s))
                .append(".create(prism4j);\nbreak;\n"));
        builder.append("default:\ngrammar = null;\n}").append("return grammar;");
        return builder.toString();
    }

    @NotNull
    private static String createTriggerModify(@NotNull Map<String, LanguageInfo> languages) {

        // so, create a map collection where each entry in `modify` is the key and languageInfo.name is value
        final Map<String, List<String>> map = new HashMap<>(3);

        List<String> modify;

        for (LanguageInfo info : languages.values()) {

            modify = info.modify;

            if (modify != null
                    && !modify.isEmpty()) {

                for (String name : modify) {
                    map.computeIfAbsent(name, k -> new ArrayList<>(3)).add(info.name);
                }
            }
        }

        if (map.isEmpty()) {
            return "";
        }

        final StringBuilder builder = new StringBuilder();
        builder.append("switch(name){\n");
        for (Map.Entry<String, List<String>> entry : map.entrySet()) {
            builder.append("case \"")
                    .append(entry.getKey())
                    .append("\":\n");
            for (String lang : entry.getValue()) {
                builder.append("prism4j.grammar(\"")
                        .append(lang)
                        .append("\");\n");
            }
            builder.append("break;\n");
        }
        builder.append("}");
        return builder.toString();
    }

    @NotNull
    private static String javaValidName(@NotNull String name) {
        return name.replaceAll("-", "_");
    }

    @NotNull
    private static String createLanguages(@NotNull Map<String, LanguageInfo> languages) {

        final StringBuilder builder = new StringBuilder();
        final List<String> list = new ArrayList<>(languages.keySet());
        list.sort(String::compareTo);

        builder.append("final Set<String> set = new HashSet<String>(")
                .append(list.size())
                .append(");\n");

        for (String language : list) {
            builder.append("set.add(\"").append(language).append("\");\n");
        }

        builder.append("return set;");

        return builder.toString();
    }

    private static String resourceToString(String file) throws RuntimeException {
        try (InputStream is = PrismBundler.class.getResourceAsStream(file)) {
            assert is != null;
            try (Scanner scanner = new Scanner(is, StandardCharsets.UTF_8)) {
                scanner.useDelimiter("\\A");
                return scanner.hasNext() ? scanner.next() : "";
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to load resource: " + file, e);
        }
    }

    private static String resourceToString(String file, ClassLoader classLoader) throws RuntimeException {
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
}
