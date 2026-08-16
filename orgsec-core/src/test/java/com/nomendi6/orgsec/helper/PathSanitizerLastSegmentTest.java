package com.nomendi6.orgsec.helper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nomendi6.orgsec.exceptions.OrgsecSecurityException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@code validateHierarchyAnchor} and {@code lastSegment}.
 *
 * <p>The pairing matters: {@code validatePath} alone accepts {@code "|"} (the pattern allows zero
 * segments) and {@code isUsableHierarchyAnchor} alone accepts {@code "|A|B"} (it only looks at
 * emptiness). Either check on its own lets through a value that compares in a way no caller intends.
 */
class PathSanitizerLastSegmentTest {

    @Test
    void validateHierarchyAnchorReturnsAWellFormedUsablePathUnchanged() {
        assertThat(PathSanitizer.validateHierarchyAnchor("|1|10|22|")).isEqualTo("|1|10|22|");
        assertThat(PathSanitizer.validateHierarchyAnchor("|root|")).isEqualTo("|root|");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "",
        "   ",
        "|",          // accepted by validatePath, rejected here: every path starts with it
        "|A|B",       // accepted by isUsableHierarchyAnchor, rejected here: no closing separator
        "|A||",       // empty inner segment
        "|A$|",       // illegal character
        "A|B|"        // no leading separator
    })
    void validateHierarchyAnchorRejectsUnusableOrMalformedPaths(String path) {
        assertThatThrownBy(() -> PathSanitizer.validateHierarchyAnchor(path))
            .isInstanceOf(OrgsecSecurityException.class);
    }

    @Test
    void validateHierarchyAnchorRejectsNull() {
        assertThatThrownBy(() -> PathSanitizer.validateHierarchyAnchor(null))
            .isInstanceOf(OrgsecSecurityException.class);
    }

    @Test
    void lastSegmentReturnsTheLeafOfACanonicalPath() {
        assertThat(PathSanitizer.lastSegment("|1|10|22|")).isEqualTo("22");
        assertThat(PathSanitizer.lastSegment("|root|ow|")).isEqualTo("ow");
        assertThat(PathSanitizer.lastSegment("|root|")).isEqualTo("root");
    }

    @Test
    void lastSegmentIsTheInverseOfBuildPathForTheLeaf() {
        String built = PathSanitizer.buildPath("|1|10|", "22");

        assertThat(built).isEqualTo("|1|10|22|");
        assertThat(PathSanitizer.lastSegment(built)).isEqualTo("22");
    }

    @ParameterizedTest
    @ValueSource(strings = { "|", "|A|B", "|A||", "" })
    void lastSegmentRejectsWhatValidateHierarchyAnchorRejects(String path) {
        assertThatThrownBy(() -> PathSanitizer.lastSegment(path))
            .isInstanceOf(OrgsecSecurityException.class);
    }
}
