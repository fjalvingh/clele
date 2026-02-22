package com.clele.parts.controller;

import com.clele.parts.dto.ImageFromUrlRequest;
import com.clele.parts.dto.PartImageDTO;
import com.clele.parts.service.PartImageService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/parts/{partId}/images")
@RequiredArgsConstructor
public class PartImageController {

    private final PartImageService partImageService;

    @GetMapping
    public List<PartImageDTO> list(@PathVariable Long partId) {
        return partImageService.listImages(partId);
    }

    @GetMapping("/{imageId}")
    public ResponseEntity<byte[]> getImage(@PathVariable Long partId, @PathVariable Long imageId) {
        byte[] data = partImageService.getImageData(partId, imageId);
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .cacheControl(CacheControl.maxAge(7, TimeUnit.DAYS))
                .body(data);
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public PartImageDTO upload(@PathVariable Long partId,
                               @RequestParam("file") MultipartFile file) {
        return partImageService.upload(partId, file);
    }

    @PostMapping("/from-url")
    @ResponseStatus(HttpStatus.CREATED)
    public PartImageDTO uploadFromUrl(@PathVariable Long partId,
                                     @jakarta.validation.Valid @org.springframework.web.bind.annotation.RequestBody ImageFromUrlRequest request) {
        return partImageService.uploadFromUrl(partId, request.getUrl());
    }

    @DeleteMapping("/{imageId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long partId, @PathVariable Long imageId) {
        partImageService.delete(partId, imageId);
    }
}
