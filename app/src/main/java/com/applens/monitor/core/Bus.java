package com.applens.monitor.core;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Minimal main-thread event bus. AppLens deliberately avoids AndroidX LiveData so
 * that the project has no third party dependencies at all.
 */
public final class Bus<T> {

    private final CopyOnWriteArrayList<Consumer<T>> subscribers = new CopyOnWriteArrayList<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile T last;

    public void subscribe(Consumer<T> consumer) {
        if (consumer != null && !subscribers.contains(consumer)) {
            subscribers.add(consumer);
        }
    }

    public void unsubscribe(Consumer<T> consumer) {
        subscribers.remove(consumer);
    }

    public T last() {
        return last;
    }

    public boolean hasSubscribers() {
        return !subscribers.isEmpty();
    }

    /** Delivers the value on the main thread. */
    public void post(final T value) {
        last = value;
        if (subscribers.isEmpty()) {
            return;
        }
        main.post(new Runnable() {
            @Override
            public void run() {
                for (Consumer<T> c : subscribers) {
                    try {
                        c.accept(value);
                    } catch (Throwable ignored) {
                        // a broken observer must never take the app down
                    }
                }
            }
        });
    }

    /** Delivers the value immediately on the calling thread. */
    public void postNow(T value) {
        last = value;
        for (Consumer<T> c : subscribers) {
            try {
                c.accept(value);
            } catch (Throwable ignored) {
                // ignore
            }
        }
    }
}
