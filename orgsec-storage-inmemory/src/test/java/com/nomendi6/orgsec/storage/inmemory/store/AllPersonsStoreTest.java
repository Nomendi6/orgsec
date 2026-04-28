package com.nomendi6.orgsec.storage.inmemory.store;

import com.nomendi6.orgsec.model.PersonDef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AllPersonsStoreTest {

    private AllPersonsStore store;

    @BeforeEach
    void setUp() {
        store = new AllPersonsStore();
    }

    @Test
    void shouldStartEmpty() {
        assertThat(store.size()).isZero();
        assertThat(store.getPersonsMap()).isEmpty();
    }

    @Test
    void shouldPutAndGetPerson() {
        // Given
        Long personId = 1L;
        PersonDef person = createPerson(personId, "John Doe");

        // When
        store.putPerson(personId, person);

        // Then
        assertThat(store.getPerson(personId)).isEqualTo(person);
        assertThat(store.size()).isEqualTo(1);
    }

    @Test
    void shouldReturnNullForNonExistentPerson() {
        assertThat(store.getPerson(999L)).isNull();
    }

    @Test
    void shouldReturnNullForNullPersonId() {
        store.putPerson(1L, createPerson(1L, "John Doe"));

        assertThat(store.getPerson(null)).isNull();
    }

    @Test
    void shouldUpdateExistingPerson() {
        // Given
        Long personId = 1L;
        PersonDef originalPerson = createPerson(personId, "John Doe");
        PersonDef updatedPerson = createPerson(personId, "John Updated");
        store.putPerson(personId, originalPerson);

        // When
        store.putPerson(personId, updatedPerson);

        // Then
        assertThat(store.getPerson(personId).personName).isEqualTo("John Updated");
        assertThat(store.size()).isEqualTo(1);
    }

    @Test
    void shouldRemovePerson() {
        // Given
        Long personId = 1L;
        store.putPerson(personId, createPerson(personId, "John Doe"));

        // When
        store.removePerson(personId);

        // Then
        assertThat(store.getPerson(personId)).isNull();
        assertThat(store.size()).isZero();
    }

    @Test
    void shouldClearAllPersons() {
        // Given
        store.putPerson(1L, createPerson(1L, "Person 1"));
        store.putPerson(2L, createPerson(2L, "Person 2"));
        store.putPerson(3L, createPerson(3L, "Person 3"));

        // When
        store.clear();

        // Then
        assertThat(store.size()).isZero();
        assertThat(store.getPersonsMap()).isEmpty();
    }

    @Test
    void shouldSetPersonsMap() {
        // Given
        Map<Long, PersonDef> newMap = new HashMap<>();
        newMap.put(1L, createPerson(1L, "Person 1"));
        newMap.put(2L, createPerson(2L, "Person 2"));

        // When
        store.setPersonsMap(newMap);

        // Then
        assertThat(store.size()).isEqualTo(2);
        assertThat(store.getPerson(1L).personName).isEqualTo("Person 1");
        assertThat(store.getPerson(2L).personName).isEqualTo("Person 2");
    }

    @Test
    void shouldHandleMultiplePersons() {
        // Given & When
        for (int i = 1; i <= 100; i++) {
            store.putPerson((long) i, createPerson((long) i, "Person " + i));
        }

        // Then
        assertThat(store.size()).isEqualTo(100);
        assertThat(store.getPerson(50L).personName).isEqualTo("Person 50");
    }

    private PersonDef createPerson(Long id, String name) {
        return new PersonDef(id, name);
    }
}
