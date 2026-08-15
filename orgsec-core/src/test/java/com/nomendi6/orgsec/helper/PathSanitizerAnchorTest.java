package com.nomendi6.orgsec.helper;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * {@link PathSanitizer#isUsableHierarchyAnchor} answers a narrower question than
 * {@link PathSanitizer#validatePath}: not "is this well-formed" but "can a hierarchy comparison use it
 * without matching everything".
 *
 * <p>The two disagree on exactly one value, and that disagreement is the reason this method exists.
 */
class PathSanitizerAnchorTest {

    @Test
    void acceptsAnOrdinaryPath() {
        assertThat(PathSanitizer.isUsableHierarchyAnchor("|ow|o1|o1_1|")).isTrue();
        assertThat(PathSanitizer.isUsableHierarchyAnchor("|ow|")).as("a root node").isTrue();
    }

    @Test
    void refusesAMissingPath() {
        assertThat(PathSanitizer.isUsableHierarchyAnchor(null)).isFalse();
    }

    /** Every string starts with the empty string, so a comparison against it grants on every record. */
    @Test
    void refusesTheEmptyPath() {
        assertThat(PathSanitizer.isUsableHierarchyAnchor("")).isFalse();
        assertThat(PathSanitizer.isUsableHierarchyAnchor("   ")).as("whitespace decides emptiness the way sanitizePath does").isFalse();
    }

    /**
     * The one value the two methods disagree on. A bare separator is well-formed - the pattern allows
     * zero segments - yet every well-formed path starts with it, so it matches everything. No node
     * carries it: the root of a tree is {@code |ow|}.
     */
    @Test
    void refusesTheBareSeparatorEvenThoughItValidates() {
        assertThat(PathSanitizer.validatePath("|")).as("well-formed, so validatePath does not throw").isEqualTo("|");
        assertThat(PathSanitizer.isUsableHierarchyAnchor("|")).isFalse();
    }
}
