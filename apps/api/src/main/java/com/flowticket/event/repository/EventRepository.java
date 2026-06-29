package com.flowticket.event.repository;

import com.flowticket.event.domain.Event;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EventRepository extends JpaRepository<Event, Long>, EventRepositoryCustom {

    Optional<Event> findByKopisId(String kopisId);
}
