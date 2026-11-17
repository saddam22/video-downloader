package com.wan.downloadmanager.queue;

import com.wan.downloadmanager.download.DownloadService;
import com.wan.downloadmanager.entity.DownloadQueue;
import com.wan.downloadmanager.entity.QueueItem;
import com.wan.downloadmanager.entity.QueueState;
import com.wan.downloadmanager.exception.WdmException;
import com.wan.downloadmanager.repository.DownloadQueueRepository;
import com.wan.downloadmanager.repository.QueueItemRepository;
import com.wan.downloadmanager.notification.EventLog;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/** Named queues with priority and their own concurrency; state survives restarts (queue recovery). */
@Service
public class QueueService {
    public record QueueRequest(@NotBlank @Size(max = 80) String name, @Min(-100) @Max(100) Integer priority,
                               @Min(1) @Max(16) Integer maxConcurrent) {}

    public record QueueView(String id, String name, int priority, int maxConcurrent, String state, boolean builtIn, int items) {}

    private final DownloadQueueRepository queues;
    private final QueueItemRepository items;
    private final DownloadService downloads;
    private final EventLog events;

    public QueueService(DownloadQueueRepository queues, QueueItemRepository items, DownloadService downloads, EventLog events) {
        this.queues = queues;
        this.items = items;
        this.downloads = downloads;
        this.events = events;
    }

    public List<QueueView> list() {
        return queues.findAllByOrderByPriorityDescNameAsc().stream().map(this::view).toList();
    }

    public QueueView create(QueueRequest req) {
        if (queues.findAll().stream().anyMatch(q -> q.getName().equalsIgnoreCase(req.name().trim()))) {
            throw WdmException.conflict("queue_exists", "A queue with this name exists");
        }
        DownloadQueue q = new DownloadQueue();
        q.setId(UUID.randomUUID().toString());
        q.setName(req.name().trim());
        q.setPriority(req.priority() == null ? 0 : req.priority());
        q.setMaxConcurrent(req.maxConcurrent() == null ? 3 : req.maxConcurrent());
        q.setState(QueueState.STOPPED);
        q.setCreatedAt(System.currentTimeMillis());
        q.setUpdatedAt(System.currentTimeMillis());
        queues.save(q);
        return view(q);
    }

    public QueueView update(String id, QueueRequest req) {
        DownloadQueue q = get(id);
        q.setName(req.name().trim());
        if (req.priority() != null) q.setPriority(req.priority());
        if (req.maxConcurrent() != null) q.setMaxConcurrent(req.maxConcurrent());
        q.setUpdatedAt(System.currentTimeMillis());
        queues.save(q);
        downloads.pump();
        return view(q);
    }

    public QueueView start(String id) {
        DownloadQueue q = get(id);
        q.setState(QueueState.RUNNING);
        q.setUpdatedAt(System.currentTimeMillis());
        queues.save(q);
        events.info("queue", "Started queue " + q.getName(), id);
        downloads.pump();
        return view(q);
    }

    /** Stops the queue: running downloads go back to waiting and continue when the queue starts again. */
    public QueueView stop(String id) {
        DownloadQueue q = get(id);
        q.setState(QueueState.STOPPED);
        q.setUpdatedAt(System.currentTimeMillis());
        queues.save(q);
        for (QueueItem i : items.findByQueueIdOrderByPosition(id)) downloads.requeue(i.getDownloadId());
        events.info("queue", "Stopped queue " + q.getName(), id);
        return view(q);
    }

    /** Removes a queue; its downloads move to the main queue. The built-in queue cannot be removed. */
    public void delete(String id) {
        DownloadQueue q = get(id);
        if (q.isBuiltIn()) throw WdmException.conflict("builtin_queue", "The main queue cannot be removed");
        int pos = items.maxPosition(DownloadService.MAIN_QUEUE);
        for (QueueItem i : items.findByQueueIdOrderByPosition(id)) {
            i.setQueueId(DownloadService.MAIN_QUEUE);
            i.setPosition(++pos);
            items.save(i);
        }
        queues.delete(q);
        downloads.pump();
    }

    /** Moves a download into a queue (at its end). */
    public void assign(String downloadId, String queueId) {
        get(queueId);
        downloads.get(downloadId);
        QueueItem i = items.findByDownloadId(downloadId).orElseGet(() -> {
            QueueItem n = new QueueItem();
            n.setId(UUID.randomUUID().toString());
            n.setDownloadId(downloadId);
            return n;
        });
        i.setQueueId(queueId);
        i.setPosition(items.maxPosition(queueId) + 1);
        items.save(i);
        downloads.pump();
    }

    /** Moves a download one place up (-1) or down (+1) within its queue. */
    public void move(String downloadId, int delta) {
        QueueItem item = items.findByDownloadId(downloadId).orElseThrow(() -> WdmException.notFound("Queue item"));
        List<QueueItem> list = items.findByQueueIdOrderByPosition(item.getQueueId());
        int idx = -1;
        for (int i = 0; i < list.size(); i++) if (list.get(i).getId().equals(item.getId())) idx = i;
        int target = idx + Integer.signum(delta);
        if (idx < 0 || target < 0 || target >= list.size()) return;
        QueueItem other = list.get(target);
        int p = item.getPosition();
        item.setPosition(other.getPosition());
        other.setPosition(p);
        items.save(item);
        items.save(other);
    }

    private DownloadQueue get(String id) {
        return queues.findById(id).orElseThrow(() -> WdmException.notFound("Queue"));
    }

    private QueueView view(DownloadQueue q) {
        return new QueueView(q.getId(), q.getName(), q.getPriority(), q.getMaxConcurrent(), q.getState().name(), q.isBuiltIn(),
                items.findByQueueIdOrderByPosition(q.getId()).size());
    }
}
