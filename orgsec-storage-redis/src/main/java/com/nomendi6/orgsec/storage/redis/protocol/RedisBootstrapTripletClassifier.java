package com.nomendi6.orgsec.storage.redis.protocol;

import com.nomendi6.orgsec.fence.SecurityDatasetIdentity;

import java.util.Objects;

/** Pure structural classifier that grants no bootstrap or rebind authority. */
final class RedisBootstrapTripletClassifier {

    private RedisBootstrapTripletClassifier() {
    }

    static RedisBootstrapTripletAssessment assess(
        RedisBootstrapTripletObservation observation
    ) {
        Objects.requireNonNull(observation, "observation must not be null");

        boolean controlPresent = observation.control().isPresent();
        boolean leasePresent = observation.lease().isPresent();
        boolean counterPresent = observation.leaseCounter().isPresent();
        int presentCount = (controlPresent ? 1 : 0)
            + (leasePresent ? 1 : 0)
            + (counterPresent ? 1 : 0);
        if (presentCount == 0) {
            return RedisBootstrapTripletAssessment.tripletAbsent();
        }
        if (presentCount != 3) {
            return RedisBootstrapTripletAssessment.partial();
        }

        RedisControlEnvelope control = observation.control().orElseThrow();
        RedisCoordinatorLease.Unverified lease = observation.lease().orElseThrow();
        long counter = observation.leaseCounter().orElseThrow();
        RedisDatasetKeyspace requestedKeyspace = observation.requestedKeyspace();
        SecurityDatasetIdentity controlIdentity = control.getIdentity();
        SecurityDatasetIdentity leaseIdentity = lease.identity();

        if (!requestedKeyspace.matchesSecurityDatasetId(
            controlIdentity.getSecurityDatasetId()
        )) {
            return incoherent(
                RedisBootstrapTripletAssessment.Reason.CONTROL_DATASET_MISMATCH
            );
        }
        if (!requestedKeyspace.matchesSecurityDatasetId(leaseIdentity.getSecurityDatasetId())) {
            return incoherent(
                RedisBootstrapTripletAssessment.Reason.LEASE_DATASET_MISMATCH
            );
        }
        if (!requestedKeyspace.datasetHash().equals(lease.datasetHash())) {
            return incoherent(
                RedisBootstrapTripletAssessment.Reason.LEASE_DATASET_HASH_MISMATCH
            );
        }
        if (!control.getIncarnation().equals(lease.incarnation())) {
            return incoherent(RedisBootstrapTripletAssessment.Reason.INCARNATION_MISMATCH);
        }
        if (control.getCounter() != lease.boundControlCounter()) {
            return incoherent(
                RedisBootstrapTripletAssessment.Reason.BOUND_CONTROL_COUNTER_MISMATCH
            );
        }
        if (counter < lease.fencingSequence()) {
            return incoherent(
                RedisBootstrapTripletAssessment.Reason.COUNTER_BEHIND_FENCING_SEQUENCE
            );
        }
        if (observation.primary().runId().equals(
            control.getIncarnation().getPrimaryRunId()
        )) {
            return RedisBootstrapTripletAssessment.coherentSameRunId();
        }
        return RedisBootstrapTripletAssessment.coherentDifferentRunId();
    }

    private static RedisBootstrapTripletAssessment incoherent(
        RedisBootstrapTripletAssessment.Reason reason
    ) {
        return RedisBootstrapTripletAssessment.incoherent(reason);
    }
}
