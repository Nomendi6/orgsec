package com.nomendi6.orgsec.api.controller;

import com.nomendi6.orgsec.api.dto.PersonApiDTO;
import com.nomendi6.orgsec.api.dto.PersonApiErrorCodes;
import com.nomendi6.orgsec.api.dto.PersonApiErrorDTO;
import com.nomendi6.orgsec.api.service.PersonApiService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for OrgSec Person API.
 * This endpoint is called by Keycloak custom protocol mapper to fetch Person data.
 */
@RestController
@RequestMapping("/api/orgsec/person")
@ConditionalOnProperty(prefix = "orgsec.api.person", name = "enabled", havingValue = "true")
public class PersonApiController {

    private static final Logger log = LoggerFactory.getLogger(PersonApiController.class);

    private final PersonApiService personApiService;

    public PersonApiController(PersonApiService personApiService) {
        this.personApiService = personApiService;
    }

    /**
     * Get person by Keycloak user ID.
     *
     * @param userId Keycloak user UUID
     * @return PersonApiDTO or 404 if not found
     */
    // Path variables are named explicitly: this class ships compiled inside the starter jar, so
    // it must not depend on the consuming application's compiler settings to resolve them.
    @GetMapping("/by-user/{userId}")
    public ResponseEntity<PersonApiDTO> getPersonByUserId(@PathVariable("userId") String userId) {
        long startTime = System.currentTimeMillis();
        log.debug("GET /api/orgsec/person/by-user/{}", userId);

        try {
            PersonApiDTO person = personApiService.getPersonByUserId(userId);

            if (person == null) {
                log.warn("Person not found for userId: {}", userId);
                throw new PersonApiNotFoundException();
            }

            long duration = System.currentTimeMillis() - startTime;
            log.info("GET /api/orgsec/person/by-user/{} - {} ms", userId, duration);

            return ResponseEntity.ok(person);

        } catch (PersonApiNotFoundException notFound) {
            throw notFound;
        } catch (Exception e) {
            log.error("Error fetching person for userId: {}", userId, e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Get person by person ID.
     *
     * @param personId the person ID
     * @return PersonApiDTO or 404 if not found
     */
    @GetMapping("/{personId}")
    public ResponseEntity<PersonApiDTO> getPersonById(@PathVariable("personId") Long personId) {
        long startTime = System.currentTimeMillis();
        log.debug("GET /api/orgsec/person/{}", personId);

        try {
            PersonApiDTO person = personApiService.getPersonById(personId);

            if (person == null) {
                log.warn("Person not found for personId: {}", personId);
                throw new PersonApiNotFoundException();
            }

            long duration = System.currentTimeMillis() - startTime;
            log.info("GET /api/orgsec/person/{} - {} ms", personId, duration);

            return ResponseEntity.ok(person);

        } catch (PersonApiNotFoundException notFound) {
            throw notFound;
        } catch (Exception e) {
            log.error("Error fetching person for personId: {}", personId, e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @ExceptionHandler(PersonApiNotFoundException.class)
    public ResponseEntity<PersonApiErrorDTO> handlePersonNotFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .contentType(MediaType.APPLICATION_JSON)
            .body(new PersonApiErrorDTO(PersonApiErrorCodes.PERSON_NOT_FOUND));
    }

    static final class PersonApiNotFoundException extends RuntimeException {

        private static final long serialVersionUID = 1L;
    }
}
