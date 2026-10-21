package com.clele.parts.service;

import com.clele.parts.model.Footprint;
import com.clele.parts.model.FootprintAlias;
import com.clele.parts.repository.FootprintRepository;
import com.clele.parts.repository.PartRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * How a spelling finds its footprint. The match key must agree with V64's SQL, and an alias must
 * reach its footprint, or the same package ends up on the list twice.
 */
class FootprintServiceTest {

    private final FootprintRepository footprintRepository = mock(FootprintRepository.class);
    private final CurrentOrganisationService currentOrganisationService = mock(CurrentOrganisationService.class);
    private final FootprintService service = new FootprintService(
            footprintRepository, mock(PartRepository.class), currentOrganisationService);

    @BeforeEach
    void list() {
        when(currentOrganisationService.currentId()).thenReturn(1L);
        when(footprintRepository.findByOrganisationIdOrderByNameAsc(1L)).thenReturn(List.of(
                footprint(1L, "SOT-23", "TO-236"),
                footprint(2L, "SOT-23-5", "SOT-753"),
                footprint(3L, "0805", "0805 (2012 Metric)")));
    }

    @Test
    void theMatchKeyIgnoresCaseAndPunctuation() {
        assertThat(FootprintService.matchKey("SOT-23")).isEqualTo("sot23");
        assertThat(FootprintService.matchKey(" sot 23 ")).isEqualTo("sot23");
        assertThat(FootprintService.matchKey("0805 (2012 Metric)")).isEqualTo("08052012metric");
        assertThat(FootprintService.matchKey("-")).isEqualTo("-");
    }

    @Test
    void aSpellingOrAnAliasFindsTheCanonicalName() {
        assertThat(service.match("sot23").name()).isEqualTo("SOT-23");
        assertThat(service.match("TO236").name()).isEqualTo("SOT-23");
        assertThat(service.match("0805 (2012 metric)").name()).isEqualTo("0805");
    }

    @Test
    void anUnknownSpellingComesBackWithTheMostSpecificNearMissFirst() {
        FootprintService.Match match = service.match("SOT23-5L");

        assertThat(match.found()).isFalse();
        assertThat(match.similar()).containsExactly("SOT-23-5", "SOT-23");
    }

    @Test
    void canonicalizeAddsAnUnknownSpellingToTheList() {
        assertThat(service.canonicalize("HTSSOP-14")).isEqualTo("HTSSOP-14");
        verify(footprintRepository).save(any(Footprint.class));
    }

    @Test
    void canonicalizeLeavesAKnownSpellingAndBlankAlone() {
        assertThat(service.canonicalize("sot-753")).isEqualTo("SOT-23-5");
        assertThat(service.canonicalize("  ")).isNull();
        verify(footprintRepository, never()).save(any(Footprint.class));
    }

    @Test
    void whatTheComponentCacheWritesInsteadOfAPackageIsNoPackage() {
        assertThat(FootprintService.isPackageName("插件")).isFalse();
        assertThat(FootprintService.isPackageName("插件-3P,9.5x4.8mm")).isFalse();
        assertThat(FootprintService.isPackageName("SMD,25.5x18mm")).isFalse();
        assertThat(FootprintService.isPackageName("Through hole")).isFalse();
        assertThat(FootprintService.isPackageName("smd")).isFalse();
        assertThat(FootprintService.isPackageName("-")).isFalse();
        assertThat(FootprintService.isPackageName("SMD-3225-4P")).isTrue();
        assertThat(FootprintService.isPackageName("0805")).isTrue();
    }

    @Test
    void aSecondOutlineInParenthesesIsLookedUpWithoutIt() {
        assertThat(service.match("SOT-23(TO-236AB)").name()).isEqualTo("SOT-23");
        assertThat(service.match("0805(2012)").name()).isEqualTo("0805");
    }

    @Test
    void canonicalizeStoresNoFootprintForAMountingStyle() {
        assertThat(service.canonicalize("插件,P=5mm")).isNull();
        assertThat(service.canonicalize("SMD")).isNull();
        verify(footprintRepository, never()).save(any(Footprint.class));
    }

    private Footprint footprint(Long id, String name, String... aliases) {
        Footprint footprint = Footprint.builder().id(id).name(name).build();
        for (String alias : aliases) {
            footprint.getAliases().add(FootprintAlias.builder().footprint(footprint).alias(alias).build());
        }
        return footprint;
    }
}
