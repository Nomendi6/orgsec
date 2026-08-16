package com.nomendi6.orgsec.helper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nomendi6.orgsec.exceptions.OrgsecSecurityException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PathSanitizerLastSegmentTest {

    @Test
    void returnsTheLeafOfAValidatedFullPath() {
        assertThat(PathSanitizer.lastSegment("|1|10|22|")).isEqualTo("22");
        assertThat(PathSanitizer.lastSegment("|root|ow|")).isEqualTo("ow");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "|", "|A|B", "|A||", "|A$|", "A|B|"})
    void rejectsMalformedOrUnusableHierarchyPaths(String path) {
        assertThatThrownBy(() -> PathSanitizer.lastSegment(path))
            .isInstanceOf(OrgsecSecurityException.class);
    }

    @Test
    void rejectsNullHierarchyPath() {
        assertThatThrownBy(() -> PathSanitizer.lastSegment(null))
            .isInstanceOf(OrgsecSecurityException.class);
    }
}
