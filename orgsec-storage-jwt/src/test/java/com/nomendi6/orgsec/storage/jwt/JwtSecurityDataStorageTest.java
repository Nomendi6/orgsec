package com.nomendi6.orgsec.storage.jwt;

import com.nomendi6.orgsec.model.PersonDef;
import com.nomendi6.orgsec.storage.SecurityDataStorage;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JwtSecurityDataStorageTest {

    @Test
    void shouldNotReusePersonForDifferentTokensWithSameJavaHashCode() {
        String firstToken = "Aa";
        String secondToken = "BB";
        assertThat(firstToken).isNotEqualTo(secondToken);
        assertThat(firstToken.hashCode()).isEqualTo(secondToken.hashCode());

        JwtClaimsParser claimsParser = mock(JwtClaimsParser.class);
        JwtTokenContextHolder tokenContextHolder = new JwtTokenContextHolder();
        SecurityDataStorage delegateStorage = mock(SecurityDataStorage.class);

        PersonDef firstPerson = new PersonDef(1L, "First");
        PersonDef secondPerson = new PersonDef(2L, "Second");
        when(claimsParser.parsePersonFromToken(firstToken)).thenReturn(firstPerson);
        when(claimsParser.parsePersonFromToken(secondToken)).thenReturn(secondPerson);

        JwtSecurityDataStorage storage = new JwtSecurityDataStorage(claimsParser, tokenContextHolder, delegateStorage);

        tokenContextHolder.setToken(firstToken);
        assertThat(storage.getPerson(1L)).isSameAs(firstPerson);

        tokenContextHolder.setToken(secondToken);
        assertThat(storage.getPerson(2L)).isSameAs(secondPerson);
    }
}
