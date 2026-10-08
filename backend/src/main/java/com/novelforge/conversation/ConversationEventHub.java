package com.novelforge.conversation;

import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/** In-memory delivery only. Authoritative turn state remains in the novel document. */
@Service
public class ConversationEventHub {
    public record Event(long sequence,String type,String turnId,String stage,String delta,String message) {}
    private static final int HISTORY_LIMIT=300;
    private static final long TIMEOUT_MILLIS=30L*60L*1000L;
    private final AtomicLong sequence=new AtomicLong();
    private final Map<String, CopyOnWriteArrayList<SseEmitter>> subscribers=new ConcurrentHashMap<>();
    private final Map<String, ArrayDeque<Event>> history=new ConcurrentHashMap<>();

    public SseEmitter subscribe(String novelId,String sessionId,String lastEventId) {
        String key=key(novelId,sessionId);
        SseEmitter emitter=new SseEmitter(TIMEOUT_MILLIS);
        subscribers.computeIfAbsent(key,ignored->new CopyOnWriteArrayList<>()).add(emitter);
        Runnable remove=()->remove(key,emitter);
        emitter.onCompletion(remove); emitter.onTimeout(remove); emitter.onError(error->remove.run());
        try {
            emitter.send(SseEmitter.event().name("ready").data(Map.of("status","connected")));
            long after=parseSequence(lastEventId);
            for (Event event:snapshot(key)) if (event.sequence()>after) send(emitter,event);
        } catch (IOException error) {
            remove.run(); emitter.completeWithError(error);
        }
        return emitter;
    }

    public void publish(String novelId,String sessionId,String type,String turnId,String stage,String delta,String message) {
        String key=key(novelId,sessionId);
        Event event=new Event(sequence.incrementAndGet(),type,turnId,stage,delta,message);
        ArrayDeque<Event> queue=history.computeIfAbsent(key,ignored->new ArrayDeque<>());
        synchronized (queue) {
            queue.addLast(event);
            while (queue.size()>HISTORY_LIMIT) queue.removeFirst();
        }
        for (SseEmitter emitter:subscribers.getOrDefault(key,new CopyOnWriteArrayList<>())) {
            try { send(emitter,event); }
            catch (IOException|IllegalStateException error) { remove(key,emitter); }
        }
    }

    private void send(SseEmitter emitter,Event event) throws IOException {
        synchronized (emitter) {
            emitter.send(SseEmitter.event().id(Long.toString(event.sequence())).name("conversation").data(event));
        }
    }
    private List<Event> snapshot(String key) {
        ArrayDeque<Event> queue=history.get(key);
        if (queue==null) return List.of();
        synchronized (queue) { return new ArrayList<>(queue); }
    }
    private void remove(String key,SseEmitter emitter) {
        var values=subscribers.get(key);
        if (values==null) return;
        values.remove(emitter);
        if (values.isEmpty()) subscribers.remove(key,values);
    }
    private long parseSequence(String value) {
        if (value==null||value.isBlank()) return Long.MAX_VALUE;
        try { return Long.parseLong(value); } catch (NumberFormatException ignored) { return Long.MAX_VALUE; }
    }
    private String key(String novelId,String sessionId) { return novelId+":"+sessionId; }
}
