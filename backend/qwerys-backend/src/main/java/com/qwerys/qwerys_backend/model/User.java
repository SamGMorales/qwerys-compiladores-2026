package com.qwerys.qwerys_backend.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "users")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(nullable = false)
    private String password;

    @Column(length = 2)
    @Builder.Default
    private String language = "es";

    @Column(nullable = false)
    @Builder.Default
    private Boolean darkTheme = false;

    @Column(nullable = false)
    @Builder.Default
    private Boolean blindMode = false;

    @Column(nullable = false)
    @Builder.Default
    private Boolean lowVisionMode = false;

    @Column(nullable = false)
    @Builder.Default
    private Boolean dyslexiaMode = false;

    @Column(nullable = false)
    @Builder.Default
    private Boolean deafMode = false;

    @Column(nullable = false)
    @Builder.Default
    private Boolean adhdMode = false;

    @Column(nullable = false)
    @Builder.Default
    private Boolean studentMode = false;

    @Column(nullable = false)
    @Builder.Default
    private Boolean expertMode = false;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** Entradas {@code NombreMotor::motorBase} (ej. {@code MiMongo::mongodb}). */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_custom_databases", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "database_entry")
    @Builder.Default
    private List<String> customDatabases = new ArrayList<>();

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}
