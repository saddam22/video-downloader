package com.wan.downloadmanager.repository;

import com.wan.downloadmanager.entity.Schedule;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ScheduleRepository extends JpaRepository<Schedule, String> {
    java.util.List<Schedule> findByEnabledTrue();
}
