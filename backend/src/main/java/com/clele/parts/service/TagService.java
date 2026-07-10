package com.clele.parts.service;

import com.clele.parts.dto.TagDTO;
import com.clele.parts.model.Tag;
import com.clele.parts.repository.TagRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TagService {

    private final TagRepository tagRepository;

    public List<TagDTO> search(String q) {
        String term = q != null ? q.trim() : "";
        if (term.isEmpty()) {
            return List.of();
        }
        return tagRepository.findByNameContainingIgnoreCaseOrderByNameAsc(term).stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    /**
     * Resolve each name to its existing tag (matched case-insensitively) or create a new one.
     * Whitespace is trimmed/collapsed so "  SMD " and "smd" resolve to the same tag.
     */
    @Transactional
    public Set<Tag> resolveOrCreate(List<String> names) {
        Set<Tag> result = new LinkedHashSet<>();
        if (names == null) {
            return result;
        }
        for (String raw : names) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String name = raw.trim().replaceAll("\\s+", " ");
            Tag tag = tagRepository.findByNameIgnoreCase(name)
                    .orElseGet(() -> tagRepository.save(Tag.builder().name(name).build()));
            result.add(tag);
        }
        return result;
    }

    private TagDTO toDTO(Tag tag) {
        return new TagDTO(tag.getId(), tag.getName());
    }
}
