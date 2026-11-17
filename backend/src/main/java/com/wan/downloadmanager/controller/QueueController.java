package com.wan.downloadmanager.controller;

import com.wan.downloadmanager.entity.Schedule;
import com.wan.downloadmanager.queue.QueueService;
import com.wan.downloadmanager.scheduler.SchedulerService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class QueueController {
    private final QueueService queues;
    private final SchedulerService scheduler;

    public QueueController(QueueService queues, SchedulerService scheduler) {
        this.queues = queues;
        this.scheduler = scheduler;
    }

    @GetMapping("/queues")
    public List<QueueService.QueueView> list() {
        return queues.list();
    }

    @PostMapping("/queues")
    public ResponseEntity<QueueService.QueueView> create(@Valid @RequestBody QueueService.QueueRequest req) {
        return ResponseEntity.status(201).body(queues.create(req));
    }

    @PutMapping("/queues/{id}")
    public QueueService.QueueView update(@PathVariable String id, @Valid @RequestBody QueueService.QueueRequest req) {
        return queues.update(id, req);
    }

    @PostMapping("/queues/{id}/start")
    public QueueService.QueueView start(@PathVariable String id) {
        return queues.start(id);
    }

    @PostMapping("/queues/{id}/pause")
    public QueueService.QueueView pause(@PathVariable String id) {
        return queues.stop(id);
    }

    @DeleteMapping("/queues/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        queues.delete(id);
        return ResponseEntity.noContent().build();
    }

    public record Assign(String downloadId, Integer move) {}

    @PostMapping("/queues/{id}/items")
    public Map<String, Object> assign(@PathVariable String id, @RequestBody Assign body) {
        if (body.move() != null) queues.move(body.downloadId(), body.move());
        else queues.assign(body.downloadId(), id);
        return Map.of("ok", true);
    }

    @GetMapping("/schedules")
    public List<Schedule> schedules() {
        return scheduler.list();
    }

    @PostMapping("/schedules")
    public Schedule createSchedule(@Valid @RequestBody SchedulerService.ScheduleRequest req) {
        return scheduler.save(null, req);
    }

    @PutMapping("/schedules/{id}")
    public Schedule updateSchedule(@PathVariable String id, @Valid @RequestBody SchedulerService.ScheduleRequest req) {
        return scheduler.save(id, req);
    }

    @DeleteMapping("/schedules/{id}")
    public ResponseEntity<Void> deleteSchedule(@PathVariable String id) {
        scheduler.delete(id);
        return ResponseEntity.noContent().build();
    }
}
