package com.nomendi6.orgsec.storage.redis.serialization;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Jackson MixIn for PersonDef to enable deserialization
 * without modifying the original class.
 */
public abstract class PersonDefMixin {

    @JsonCreator
    public PersonDefMixin(
        @JsonProperty("personId") Long personId,
        @JsonProperty("personName") String personName
    ) {
    }
}
