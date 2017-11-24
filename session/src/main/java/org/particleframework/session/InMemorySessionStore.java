/*
 * Copyright 2017 original authors
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License. 
 */
package org.particleframework.session;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.github.benmanes.caffeine.cache.RemovalListener;
import org.particleframework.context.annotation.Primary;
import org.particleframework.context.event.ApplicationEventPublisher;
import org.particleframework.core.annotation.Internal;
import org.particleframework.session.event.SessionCreatedEvent;
import org.particleframework.session.event.SessionDeletedEvent;
import org.particleframework.session.event.SessionDestroyedEvent;
import org.particleframework.session.event.SessionExpiredEvent;

import javax.inject.Singleton;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Default implementation that stores sessions in-memory
 *
 * @author Graeme Rocher
 * @since 1.0
 */
@Singleton
@Primary
public class InMemorySessionStore implements SessionStore<InMemorySession> {

    private final SessionConfiguration sessionConfiguration;
    private final ApplicationEventPublisher eventPublisher;
    private final Cache<String, InMemorySession> sessions;
    private final SessionIdGenerator sessionIdGenerator;

    public InMemorySessionStore(
            SessionIdGenerator sessionIdGenerator,
            SessionConfiguration sessionConfiguration,
            ApplicationEventPublisher eventPublisher) {
        this.sessionIdGenerator = sessionIdGenerator;
        this.eventPublisher = eventPublisher;
        this.sessionConfiguration = sessionConfiguration;
        this.sessions = newSessionCache(sessionConfiguration);
    }

    @Override
    public InMemorySession newSession() {
        return new InMemorySession(sessionIdGenerator.generateId(), sessionConfiguration.getMaxInactiveInternal());
    }

    @Override
    public CompletableFuture<Optional<InMemorySession>> findSession(String id) {
        return CompletableFuture.completedFuture(
                Optional.ofNullable(sessions.getIfPresent(id))
        );
    }

    @Override
    public CompletableFuture<Boolean> deleteSession(String id) {
        sessions.invalidate(id);
        return CompletableFuture.completedFuture(true);
    }

    @Override
    public CompletableFuture<InMemorySession> save(InMemorySession session) {
        if(session == null) throw new IllegalArgumentException("Session cannot be null");
        String id = session.getId();
        InMemorySession existing = sessions.getIfPresent(id);
        if(session != existing) {
            sessions.put(id, session);
            if(existing == null) {
                eventPublisher.publishEvent(new SessionCreatedEvent(session));
            }
        }
        return CompletableFuture.completedFuture(session);
    }

    @Internal
    void cleanUp() {
        sessions.cleanUp();
    }

    protected Cache<String, InMemorySession> newSessionCache(SessionConfiguration configuration) {
        Caffeine<String, InMemorySession> builder = Caffeine.newBuilder()
                .removalListener(newRemovalListener())
                .expireAfter(newExpiry());
        configuration.getMaxActiveSessions().ifPresent(builder::maximumSize);
        return builder.build();
    }

    private Expiry<String, InMemorySession> newExpiry() {
        return new Expiry<String, InMemorySession>() {
            @Override
            public long expireAfterCreate(String key, InMemorySession value, long currentTime) {
                return interval(value, currentTime);
            }

            @Override
            public long expireAfterUpdate(String key, InMemorySession value, long currentTime, long currentDuration) {
                return interval(value, currentTime);
            }

            @Override
            public long expireAfterRead(String key, InMemorySession value, long currentTime, long currentDuration) {
                return interval(value, currentTime);
            }

            private long interval(InMemorySession value, long currentTime) {
                Instant current = Instant.ofEpochMilli(TimeUnit.NANOSECONDS.toMillis(currentTime));
                value.setLastAccessTime(current);
                return TimeUnit.MILLISECONDS.toNanos( current.plus(value.getMaxInactiveInterval()).toEpochMilli() );
            }
        };
    }

    private RemovalListener<String, Session> newRemovalListener() {
        return (key, value, cause) -> {
            switch (cause) {
                case REPLACED:
                    eventPublisher.publishEvent(new SessionDestroyedEvent(value));
                    break;
                case SIZE:
                case EXPIRED:
                    eventPublisher.publishEvent(new SessionExpiredEvent(value));
                    eventPublisher.publishEvent(new SessionDestroyedEvent(value));
                    break;
                case EXPLICIT:
                    eventPublisher.publishEvent(new SessionDeletedEvent(value));
                    eventPublisher.publishEvent(new SessionDestroyedEvent(value));
                    break;
                case COLLECTED:
                    throw new IllegalStateException("Session should not be garbage collectable");
            }
        };
    }
}