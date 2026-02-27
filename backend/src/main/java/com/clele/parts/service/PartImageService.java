package com.clele.parts.service;

import com.clele.parts.dto.PartImageDTO;
import com.clele.parts.model.Part;
import com.clele.parts.model.PartImage;
import com.clele.parts.repository.PartImageRepository;
import com.clele.parts.repository.PartRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PartImageService {

    private static final int MAX_IMAGES = 5;

    private final PartImageRepository partImageRepository;
    private final PartRepository partRepository;
    private final RestTemplate restTemplate;

    public List<PartImageDTO> listImages(Long partId) {
        return partImageRepository.findByPartIdOrderByDisplayOrder(partId)
                .stream().map(this::toDTO).toList();
    }

    public byte[] getImageData(Long partId, Long imageId) {
        return partImageRepository.findByIdAndPartId(imageId, partId)
                .orElseThrow(() -> new EntityNotFoundException("Image not found: " + imageId))
                .getImageData();
    }

    @Transactional
    public PartImageDTO upload(Long partId, MultipartFile file) {
        Part part = partRepository.findById(partId)
                .orElseThrow(() -> new EntityNotFoundException("Part not found: " + partId));

        int count = partImageRepository.countByPartId(partId);
        if (count >= MAX_IMAGES) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Maximum of " + MAX_IMAGES + " images per part");
        }

        byte[] pngData = convertToPng(file);

        PartImage image = PartImage.builder()
                .part(part)
                .displayOrder(count)
                .imageData(pngData)
                .build();

        return toDTO(partImageRepository.save(image));
    }

    @Transactional
    public void delete(Long partId, Long imageId) {
        PartImage image = partImageRepository.findByIdAndPartId(imageId, partId)
                .orElseThrow(() -> new EntityNotFoundException("Image not found: " + imageId));
        partImageRepository.delete(image);

        // Re-sequence display_order so it stays 0-based and contiguous
        List<PartImage> remaining = partImageRepository.findByPartIdOrderByDisplayOrder(partId);
        for (int i = 0; i < remaining.size(); i++) {
            remaining.get(i).setDisplayOrder(i);
        }
        partImageRepository.saveAll(remaining);
    }

    @Transactional
    public PartImageDTO uploadFromUrl(Long partId, String url) {
        Part part = partRepository.findById(partId)
                .orElseThrow(() -> new EntityNotFoundException("Part not found: " + partId));

        int count = partImageRepository.countByPartId(partId);
        if (count >= MAX_IMAGES) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Maximum of " + MAX_IMAGES + " images per part");
        }

        byte[] pngData = downloadAndConvertToPng(url);

        PartImage image = PartImage.builder()
                .part(part)
                .displayOrder(count)
                .imageData(pngData)
                .build();

        return toDTO(partImageRepository.save(image));
    }

    private byte[] downloadAndConvertToPng(String url) {
        log.info("Downloading image from URL: {}", url);
        byte[] imageBytes;
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("User-Agent", "Mozilla/5.0 (X11; Linux x86_64; rv:124.0) Gecko/20100101 Firefox/124.0");
            headers.set("Accept", "image/*, */*;q=0.8");
            headers.set("Accept-Language", "en-US,en;q=0.5");
            try {
                var uri = new java.net.URI(url);
                headers.set("Referer", uri.getScheme() + "://" + uri.getHost() + "/");
            } catch (Exception ignored) {}
            HttpEntity<Void> entity = new HttpEntity<>(headers);
            ResponseEntity<byte[]> response = restTemplate.exchange(url, HttpMethod.GET, entity, byte[].class);
            imageBytes = response.getBody();
            if (imageBytes == null || imageBytes.length == 0) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Empty response from image URL");
            }
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to download image from {}: {}", url, e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Failed to download image: " + e.getMessage());
        }

        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(imageBytes));
            if (image == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "URL did not return a valid image");
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(image, "PNG", baos);
            return baos.toByteArray();
        } catch (ResponseStatusException e) {
            throw e;
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Failed to process image: " + e.getMessage());
        }
    }

    private byte[] convertToPng(MultipartFile file) {
        BufferedImage image;
        try {
            image = ImageIO.read(file.getInputStream());
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Failed to read image: " + e.getMessage());
        }
        if (image == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Unsupported or invalid image file");
        }
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(image, "PNG", baos);
            return baos.toByteArray();
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to encode image as PNG: " + e.getMessage());
        }
    }

    private PartImageDTO toDTO(PartImage image) {
        return PartImageDTO.builder()
                .id(image.getId())
                .partId(image.getPart().getId())
                .displayOrder(image.getDisplayOrder())
                .createdAt(image.getCreatedAt())
                .build();
    }
}
