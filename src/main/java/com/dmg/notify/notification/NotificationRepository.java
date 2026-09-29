package com.dmg.notify.notification;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationRepository extends JpaRepository<Notification, String> {

    Optional<Notification> findByIdAndTenantId(String id, Long tenantId);

    Optional<Notification> findByTenantIdAndIdempotencyKey(Long tenantId, String key);

    Page<Notification> findByTenantId(Long tenantId, Pageable pageable);

    Page<Notification> findByTenantIdAndStatus(Long tenantId, NotificationStatus status, Pageable pageable);

    @Query("select distinct n.tenantId from Notification n where n.status = 'PENDING' and n.nextAttemptAt <= :now")
    List<Long> tenantsWithDue(@Param("now") Instant now);

    @Query("select n from Notification n where n.tenantId = :tenantId and n.status = 'PENDING' "
            + "and n.nextAttemptAt <= :now order by n.nextAttemptAt")
    List<Notification> findDue(@Param("tenantId") Long tenantId, @Param("now") Instant now, Pageable pageable);

    @Query("select n from Notification n where n.status = 'PROCESSING' and n.leaseUntil < :now")
    List<Notification> findExpiredLeases(@Param("now") Instant now);

    /**
     * Atomic compare-and-set claim: only one dispatcher wins the PENDING -> PROCESSING flip,
     * so a row is never handed to two workers. attemptCount doubles as the fencing token.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Notification n set n.status = 'PROCESSING', n.leaseUntil = :lease, "
            + "n.attemptCount = n.attemptCount + 1, n.updatedAt = :now, n.version = n.version + 1 "
            + "where n.id = :id and n.status = 'PENDING' and n.nextAttemptAt <= :now")
    int claim(@Param("id") String id, @Param("now") Instant now, @Param("lease") Instant lease);

    @Query("select n.channel, n.status, count(n) from Notification n where n.tenantId = :tenantId "
            + "and n.createdAt >= :from and n.createdAt < :to group by n.channel, n.status")
    List<Object[]> countByChannelAndStatus(@Param("tenantId") Long tenantId, @Param("from") Instant from,
                                           @Param("to") Instant to);
}
