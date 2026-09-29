package com.dmg.notify.template;

import com.dmg.notify.channel.ChannelType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TemplateRepository extends JpaRepository<Template, Long> {
    Optional<Template> findFirstByTenantIdAndNameAndChannelOrderByVersionDesc(Long tenantId, String name, ChannelType channel);
    Optional<Template> findByIdAndTenantId(Long id, Long tenantId);
    List<Template> findByTenantIdOrderByNameAscVersionDesc(Long tenantId);
}
