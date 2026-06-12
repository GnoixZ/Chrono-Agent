package ai.chrono.backend.audio;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

@Component
public class LocalAudioStorage implements AudioStorage {
    private final Path root;

    public LocalAudioStorage(@Value("${chrono.audio.storage-root:./audio-data}") String root) {
        this.root = Path.of(root);
    }

    @Override
    public StoredAudio save(AudioInput input) {
        try {
            LocalDate today = LocalDate.now();
            Path directory = root.resolve(input.userId()).resolve(today.toString());
            Files.createDirectories(directory);
            String safeName = UUID.randomUUID() + "-" + input.fileName().replaceAll("[^a-zA-Z0-9._-]", "_");
            Path file = directory.resolve(safeName);
            Files.write(file, input.bytes());
            return new StoredAudio("local://" + root.relativize(file).toString().replace("\\", "/"), input.bytes().length);
        } catch (IOException error) {
            throw new IllegalStateException("failed to save audio", error);
        }
    }

    @Override
    public Optional<StoredAudio> find(String audioUri) {
        if (audioUri == null || !audioUri.startsWith("local://")) {
            return Optional.empty();
        }
        Path file = root.resolve(audioUri.substring("local://".length()));
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(new StoredAudio(audioUri, Files.size(file)));
        } catch (IOException error) {
            throw new IllegalStateException("failed to read audio metadata", error);
        }
    }

    @Override
    public byte[] read(String audioUri) {
        if (audioUri == null || !audioUri.startsWith("local://")) {
            throw new IllegalArgumentException("unsupported audio uri");
        }
        try {
            return Files.readAllBytes(root.resolve(audioUri.substring("local://".length())));
        } catch (IOException error) {
            throw new IllegalStateException("failed to read audio", error);
        }
    }

    @Override
    public void delete(String audioUri) {
        if (audioUri != null && audioUri.startsWith("local://")) {
            try {
                Files.deleteIfExists(root.resolve(audioUri.substring("local://".length())));
            } catch (IOException error) {
                throw new IllegalStateException("failed to delete audio", error);
            }
        }
    }
}
