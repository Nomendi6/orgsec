package com.nomendi6.orgsec.storage.inmemory.store;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import com.nomendi6.orgsec.model.PersonDef;

/**
 * Store class that holds all person data in memory.
 * Separated from loading logic for better separation of concerns.
 */
@Component
public class AllPersonsStore {

    private volatile Map<Long, PersonDef> personsMap;

    public AllPersonsStore() {
        this.personsMap = new ConcurrentHashMap<>();
    }

    /**
     * Get the persons map
     * @return Map with person ID as key and PersonDef as value
     */
    public Map<Long, PersonDef> getPersonsMap() {
        return personsMap;
    }

    /**
     * Replace the entire persons map
     * @param personsMap new persons map
     */
    public void setPersonsMap(Map<Long, PersonDef> personsMap) {
        this.personsMap = new ConcurrentHashMap<>(personsMap);
    }

    /**
     * Get person by ID
     * @param personId person ID
     * @return PersonDef or null if not found
     */
    public PersonDef getPerson(Long personId) {
        return personsMap.get(personId);
    }

    /**
     * Add or update person
     * @param personId person ID
     * @param person person definition
     */
    public void putPerson(Long personId, PersonDef person) {
        personsMap.put(personId, person);
    }

    /**
     * Remove person
     * @param personId person ID
     */
    public void removePerson(Long personId) {
        personsMap.remove(personId);
    }

    /**
     * Get number of persons
     * @return size of persons map
     */
    public int size() {
        return personsMap.size();
    }

    /**
     * Clear all persons
     */
    public void clear() {
        personsMap.clear();
    }
}
