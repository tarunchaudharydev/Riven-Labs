package com.rivenlabs.timetable.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.database.FirebaseDatabase;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.util.List;

@Configuration
public class FirebaseConfig {
    @Bean
    FirebaseApp firebaseApp(@Value("${app.firebase.project-id}") String projectId,
                            @Value("${app.firebase.database-url}") String databaseUrl) throws IOException {
        if (databaseUrl == null || databaseUrl.isBlank()) {
            throw new IllegalStateException("FIREBASE_DATABASE_URL must point to the Realtime Database instance in project " + projectId);
        }
        List<FirebaseApp> existing = FirebaseApp.getApps();
        if (!existing.isEmpty()) return existing.get(0);
        FirebaseOptions options = FirebaseOptions.builder()
                .setCredentials(GoogleCredentials.getApplicationDefault())
                .setProjectId(projectId)
                .setDatabaseUrl(databaseUrl)
                .build();
        return FirebaseApp.initializeApp(options);
    }

    @Bean
    FirebaseDatabase firebaseDatabase(FirebaseApp app) {
        return FirebaseDatabase.getInstance(app);
    }
}
