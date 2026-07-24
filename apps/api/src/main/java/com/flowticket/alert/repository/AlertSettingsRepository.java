package com.flowticket.alert.repository;

import com.flowticket.alert.domain.AlertSettings;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AlertSettingsRepository extends JpaRepository<AlertSettings, Long> {
}
