package com.nomendi6.orgsec.api.controller;

import com.nomendi6.orgsec.api.dto.PersonApiDTO;
import com.nomendi6.orgsec.api.service.PersonApiService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
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
    @GetMapping("/by-user/{userId}")
    public ResponseEntity<PersonApiDTO> getPersonByUserId(@PathVariable String userId) {
        long startTime = System.currentTimeMillis();
        log.debug("GET /api/orgsec/person/by-user/{}", userId);

        try {
            PersonApiDTO person = personApiService.getPersonByUserId(userId);

            if (person == null) {
                log.warn("Person not found for userId: {}", userId);
                return ResponseEntity.notFound().build();
            }

            long duration = System.currentTimeMillis() - startTime;
            log.info("GET /api/orgsec/person/by-user/{} - {} ms", userId, duration);

            return ResponseEntity.ok(person);

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
    public ResponseEntity<PersonApiDTO> getPersonById(@PathVariable Long personId) {
        long startTime = System.currentTimeMillis();
        log.debug("GET /api/orgsec/person/{}", personId);

        try {
            PersonApiDTO person = personApiService.getPersonById(personId);

            if (person == null) {
                log.warn("Person not found for personId: {}", personId);
                return ResponseEntity.notFound().build();
            }

            long duration = System.currentTimeMillis() - startTime;
            log.info("GET /api/orgsec/person/{} - {} ms", personId, duration);

            return ResponseEntity.ok(person);

        } catch (Exception e) {
            log.error("Error fetching person for personId: {}", personId, e);
            return ResponseEntity.internalServerError().build();
        }
    }
}
