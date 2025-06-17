package io.noties.prism4j;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import io.noties.prism4j.annotations.PrismBundle;

@PrismBundle(includeAll = true, grammarLocatorClassName = ".GrammarLocatorGrammarUtils")
public class GrammarUtilsTest {

    private GrammarLocator grammarLocator;
    private Prism4j prism4j;

    @Before
    public void before() {
        grammarLocator = new GrammarLocatorGrammarUtils();
        prism4j = new Prism4j(grammarLocator);
    }

    @Test
    public void clone_grammar() {
        Assert.assertNotNull(grammarLocator.languages());
        grammarLocator.languages()
                        .stream()
                .sorted()
                .forEach(s -> {
                    final Prism4j.Grammar grammar = prism4j.grammar(s);
                    if (grammar != null) {
                        System.err.printf("cloning language: %s%n", s);
                        GrammarUtils.clone(grammar);
                    }
                });

    }
}
