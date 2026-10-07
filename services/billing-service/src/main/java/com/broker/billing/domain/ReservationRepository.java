package com.broker.billing.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {
    Optional<Reservation> findByJobId(UUID jobId);
    List<Reservation> findByStatusAndExpiresAtBefore(Reservation.Status status, Instant before);
}
