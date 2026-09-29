package com.dmg.notify.channel;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChannelConfigRepository extends JpaRepository<ChannelConfig, Long> {
    Optional<ChannelConfig> findByTenantIdAndChannel(Long tenantId, ChannelType channel);
    List<ChannelConfig> findByTenantId(Long tenantId);
}
