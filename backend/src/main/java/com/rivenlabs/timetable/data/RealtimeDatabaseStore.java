package com.rivenlabs.timetable.data;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;
import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

@Repository
public class RealtimeDatabaseStore {
    private static final Pattern SAFE_KEY = Pattern.compile("[A-Za-z0-9_-]{1,128}");
    private final FirebaseDatabase database;

    public RealtimeDatabaseStore(FirebaseDatabase database) {
        this.database = database;
    }

    public String tenantPath(String institutionId, String... segments) {
        safe(institutionId);
        StringBuilder path = new StringBuilder("institutions/").append(institutionId);
        for (String segment : segments) {
            path.append('/').append(safe(segment));
        }
        return path.toString();
    }

    public Object read(String path) {
        try {
            CompletableFuture<DataSnapshot> result = new CompletableFuture<>();

            database.getReference(path).addListenerForSingleValueEvent(
                new ValueEventListener() {
                    @Override
                    public void onDataChange(DataSnapshot snapshot) {
                        result.complete(snapshot);
                    }

                    @Override
                    public void onCancelled(DatabaseError error) {
                        result.completeExceptionally(
                            new IllegalStateException(error.getMessage())
                        );
                    }
                }
            );

            return result.get(20, TimeUnit.SECONDS).getValue();
        } catch (Exception ex) {
            throw new IllegalStateException(
                "Could not read the Realtime Database path " + path, ex
            );
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> readMap(String path) {
        Object value = read(path);
        return value instanceof Map<?, ?> map
            ? (Map<String, Object>) map
            : Map.of();
    }

    public void write(String path, Object value) {
        try {
            database.getReference(path)
                .setValueAsync(value)
                .get(20, TimeUnit.SECONDS);
        } catch (Exception ex) {
            throw new IllegalStateException(
                "Could not write the Realtime Database path " + path, ex
            );
        }
    }

    public void update(String path, Map<String, Object> values) {
        try {
            database.getReference(path)
                .updateChildrenAsync(values)
                .get(20, TimeUnit.SECONDS);
        } catch (Exception ex) {
            throw new IllegalStateException(
                "Could not update the Realtime Database path " + path, ex
            );
        }
    }

    private String safe(String key) {
        if (key == null || !SAFE_KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("Invalid Realtime Database key");
        }
        return key;
    }
}