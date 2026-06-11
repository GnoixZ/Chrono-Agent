package ai.chrono.backend.audio;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@RestController
@RequestMapping("/api/audio")
public class AudioController {
    private final AudioService audioService;
    private final AudioStreamService audioStreamService;

    public AudioController(AudioService audioService, AudioStreamService audioStreamService) {
        this.audioService = audioService;
        this.audioStreamService = audioStreamService;
    }

    @PostMapping
    ResponseEntity<AudioUploadResponse> upload(@RequestParam String userId, @RequestParam MultipartFile file) throws IOException {
        return ResponseEntity.ok(audioService.upload(userId, file.getOriginalFilename(), file.getBytes()));
    }

    @PostMapping("/stream")
    ResponseEntity<AudioStreamSessionResponse> openStream(@RequestParam String userId) {
        return ResponseEntity.ok(audioStreamService.open(userId));
    }

    @PostMapping("/stream/{streamSessionId}/close")
    ResponseEntity<AudioStreamSessionResponse> closeStream(@RequestParam String userId, @PathVariable String streamSessionId) {
        return ResponseEntity.ok(audioStreamService.close(userId, streamSessionId));
    }
}
