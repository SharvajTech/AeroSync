package com.aerosync.testsupport;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * TEST ONLY. An in-memory stand-in for a Spring Data repository interface, so
 * services can be tested as plain objects without a database or a Spring
 * context. It implements the CRUD methods the services use and whichever
 * query methods a test registers with {@link #query}; any other method fails
 * loudly instead of returning a silent default.
 *
 * <p>The real repositories, mappings and queries are exercised by the Spring
 * integration tests in {@code com.aerosync.network}.</p>
 *
 * @param <T>  entity type
 * @param <ID> id type
 */
public final class InMemoryRepository<T, ID> implements InvocationHandler {

    private final Map<ID, T> rows = new LinkedHashMap<>();
    private final Function<T, ID> idOf;
    private final Consumer<T> beforeInsert;
    private final Map<String, BiFunction<Collection<T>, Object[], Object>> queries = new HashMap<>();
    private int saves;
    private RuntimeException failNextSave;

    private InMemoryRepository(Function<T, ID> idOf, Consumer<T> beforeInsert) {
        this.idOf = idOf;
        this.beforeInsert = beforeInsert;
    }

    /**
     * @param idOf         reads the id of an entity
     * @param beforeInsert what {@code @PrePersist} and the database would do
     *                     for a new entity: assign the id and defaults
     */
    public static <T, ID> InMemoryRepository<T, ID> of(Function<T, ID> idOf, Consumer<T> beforeInsert) {
        return new InMemoryRepository<>(idOf, beforeInsert);
    }

    /** Registers a query method by name: it receives the stored rows and the call's arguments. */
    public InMemoryRepository<T, ID> query(String methodName, BiFunction<Collection<T>, Object[], Object> query) {
        queries.put(methodName, query);
        return this;
    }

    /** The repository interface backed by this store. */
    @SuppressWarnings("unchecked")
    public <R> R as(Class<R> repositoryInterface) {
        return (R) Proxy.newProxyInstance(repositoryInterface.getClassLoader(),
                new Class<?>[]{repositoryInterface}, this);
    }

    public List<T> rows() {
        return new ArrayList<>(rows.values());
    }

    /** Number of {@code save} calls (including each element of {@code saveAll}). */
    public int saves() {
        return saves;
    }

    /** The next {@code save} or {@code saveAll} throws this once (to simulate a storage failure). */
    public void failNextSave(RuntimeException exception) {
        this.failNextSave = exception;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Object invoke(Object proxy, Method method, Object[] args) {
        String name = method.getName();
        switch (name) {
            case "save":
                return store((T) args[0]);
            case "saveAll": {
                List<T> saved = new ArrayList<>();
                for (T entity : (Iterable<T>) args[0]) {
                    saved.add(store(entity));
                }
                return saved;
            }
            case "findById":
                return Optional.ofNullable(rows.get((ID) args[0]));
            case "getReferenceById": {
                T row = rows.get((ID) args[0]);
                if (row == null) {
                    throw new IllegalStateException("No row with id " + args[0]);
                }
                return row;
            }
            case "existsById":
                return rows.containsKey((ID) args[0]);
            case "findAllById": {
                List<T> found = new ArrayList<>();
                for (ID id : (Iterable<ID>) args[0]) {
                    if (rows.containsKey(id)) {
                        found.add(rows.get(id));
                    }
                }
                return found;
            }
            case "findAll":
                if (args == null || args.length == 0) {
                    return rows();
                }
                break;
            case "count":
                return (long) rows.size();
            case "delete":
                rows.remove(idOf.apply((T) args[0]));
                return null;
            case "deleteAll":
                if (args == null || args.length == 0) {
                    rows.clear();
                    return null;
                }
                break;
            case "flush":
                return null;
            case "hashCode":
                return System.identityHashCode(proxy);
            case "equals":
                return proxy == args[0];
            case "toString":
                return "InMemoryRepository(" + rows.size() + " rows)";
            default:
                break;
        }
        BiFunction<Collection<T>, Object[], Object> query = queries.get(name);
        if (query == null) {
            throw new UnsupportedOperationException("InMemoryRepository has no fake for " + name
                    + "; register one with query(\"" + name + "\", ...)");
        }
        return query.apply(new ArrayList<>(rows.values()), args == null ? new Object[0] : args);
    }

    private T store(T entity) {
        if (failNextSave != null) {
            RuntimeException failure = failNextSave;
            failNextSave = null;
            throw failure;
        }
        if (idOf.apply(entity) == null) {
            beforeInsert.accept(entity);
        }
        saves++;
        rows.put(idOf.apply(entity), entity);
        return entity;
    }

    /** Removes rows for which the predicate is true; returns how many (for delete queries). */
    public int removeIf(java.util.function.Predicate<T> predicate) {
        int before = rows.size();
        rows.values().removeIf(predicate);
        return before - rows.size();
    }
}
