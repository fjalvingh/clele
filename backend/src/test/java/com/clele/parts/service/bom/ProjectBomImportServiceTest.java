package com.clele.parts.service.bom;

import com.clele.parts.dto.BomImportLinePreviewDTO;
import com.clele.parts.dto.BomImportPreviewDTO;
import com.clele.parts.model.*;
import com.clele.parts.repository.PartRepository;
import com.clele.parts.repository.ProjectBomLineRepository;
import com.clele.parts.repository.ProjectBomRepository;
import com.clele.parts.service.CurrentOrganisationService;
import com.clele.parts.service.CurrentUserService;
import com.clele.parts.service.ProjectService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

/**
 * Pins the merge. Everything here exists because getting it wrong loses work the user cannot
 * reconstruct: a BOM's matches are hours of judgement spread over days, and a re-import that
 * discards them looks exactly like a re-import that worked.
 */
class ProjectBomImportServiceTest {

    private static final Long ORG_ID = 7L;
    private static final Long PROJECT_ID = 42L;

    private ProjectBomRepository bomRepository;
    private ProjectBomLineRepository lineRepository;
    private PartRepository partRepository;
    private EntityManager entityManager;
    private ProjectBomImportService service;

    private Project project;
    private ProjectBom bom;
    private List<ProjectBomLine> storedLines;

    @BeforeEach
    void setUp() {
        bomRepository = mock(ProjectBomRepository.class);
        lineRepository = mock(ProjectBomLineRepository.class);
        partRepository = mock(PartRepository.class);
        ProjectService projectService = mock(ProjectService.class);
        CurrentUserService currentUserService = mock(CurrentUserService.class);
        CurrentOrganisationService currentOrganisationService = mock(CurrentOrganisationService.class);

        project = new Project();
        project.setId(PROJECT_ID);
        project.setName("Test board");
        project.setInstanceCount(1);
        project.setStatus(ProjectStatus.PLANNING);

        storedLines = new ArrayList<>();
        bom = null;

        when(projectService.requireOwnProject(PROJECT_ID)).thenReturn(project);
        when(currentOrganisationService.currentId()).thenReturn(ORG_ID);
        when(currentUserService.current()).thenReturn(new AppUser());
        when(bomRepository.findByProjectId(PROJECT_ID)).thenAnswer(i -> java.util.Optional.ofNullable(bom));
        when(lineRepository.findByBomIdWithPart(anyLong())).thenAnswer(i -> storedLines);
        when(bomRepository.save(any(ProjectBom.class))).thenAnswer(i -> {
            ProjectBom saved = i.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(1L);
            }
            return saved;
        });
        when(lineRepository.saveAll(any())).thenAnswer(i -> i.getArgument(0));

        // No part matches anything unless a test says otherwise.
        when(partRepository.findByOrganisationIdAndPartNumberIgnoreCase(anyLong(), anyString()))
                .thenReturn(List.of());
        when(partRepository.findByOrganisationIdAndMpnIgnoreCase(anyLong(), anyString()))
                .thenReturn(List.of());

        entityManager = mock(EntityManager.class);

        service = new ProjectBomImportService(bomRepository, lineRepository, partRepository,
                new BomFileParser(), new BomColumnMapper(), projectService,
                currentUserService, currentOrganisationService, entityManager);
    }

    // ------------------------------------------------------------------
    // First import
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a first import adds every line and detects the KiCad columns unaided")
    void firstImportAddsEverything() {
        BomImportPreviewDTO preview = service.preview(PROJECT_ID, file("""
                Reference,Value,Footprint,Qty,DNP
                "C1,C2",100nF,C_0805,2,
                R1,10k,R_0805,1,
                U1,LM317,TO-220,1,
                """), null);

        assertEquals(3, preview.getTotalLines());
        assertEquals(3, preview.getAdded());
        assertEquals(0, preview.getRemoved());
        assertFalse(preview.isCommitted());
        assertEquals("Reference", preview.getMapping().get("REFERENCES"));
        assertEquals("Qty", preview.getMapping().get("QUANTITY"));
    }

    @Test
    @DisplayName("quantity falls back to the designator count when the file has no Qty column")
    void derivesQuantityFromDesignators() {
        BomImportPreviewDTO preview = service.preview(PROJECT_ID, file("""
                Reference,Value
                "C1,C2,C3",100nF
                R1,10k
                """), null);

        assertEquals(3, preview.getLines().get(0).getQuantity());
        assertEquals(1, preview.getLines().get(1).getQuantity());
        assertTrue(preview.getWarnings().stream().anyMatch(w -> w.contains("quantity")));
    }

    @Test
    @DisplayName("a DNP line arrives excluded rather than as work to do")
    void dnpLinesLandExcluded() {
        service.commit(PROJECT_ID, file("""
                Reference,Value,DNP
                R1,10k,
                R2,0R,DNP
                """), null);

        assertEquals(BomLineStatus.UNMATCHED, savedLine("R1").getStatus());
        assertEquals(BomLineStatus.EXCLUDED, savedLine("R2").getStatus());
    }

    // ------------------------------------------------------------------
    // Auto-match
    // ------------------------------------------------------------------

    @Test
    @DisplayName("an unambiguous exact hit on the part number matches itself")
    void autoMatchesExactPartNumber() {
        Part lm317 = part(100L, "LM317");
        when(partRepository.findByOrganisationIdAndPartNumberIgnoreCase(ORG_ID, "LM317"))
                .thenReturn(List.of(lm317));

        BomImportPreviewDTO preview = service.preview(PROJECT_ID, file("""
                Reference,Value
                U1,LM317
                """), null);

        assertEquals(1, preview.getAutoMatched());
        assertEquals("LM317", preview.getLines().get(0).getMatchedPartNumber());
    }

    @Test
    @DisplayName("the MPN is tried before the value")
    void prefersMpnOverValue() {
        Part real = part(101L, "LM317T-STM");
        when(partRepository.findByOrganisationIdAndMpnIgnoreCase(ORG_ID, "LM317T"))
                .thenReturn(List.of(real));

        BomImportPreviewDTO preview = service.preview(PROJECT_ID, file("""
                Reference,Value,MPN
                U1,LM317,LM317T
                """), null);

        assertEquals("LM317T-STM", preview.getLines().get(0).getMatchedPartNumber());
    }

    @Test
    @DisplayName("an ambiguous term matches nothing at all rather than guessing")
    void ambiguousTermDoesNotAutoMatch() {
        // Two parts legitimately answer to "10k". Picking either silently attaches the wrong one to
        // every resistor on the board, and nothing in the UI would say so.
        when(partRepository.findByOrganisationIdAndPartNumberIgnoreCase(ORG_ID, "10k"))
                .thenReturn(List.of(part(200L, "10k"), part(201L, "10K")));

        BomImportPreviewDTO preview = service.preview(PROJECT_ID, file("""
                Reference,Value
                R1,10k
                """), null);

        assertEquals(0, preview.getAutoMatched());
        assertNull(preview.getLines().get(0).getMatchedPartNumber());
    }

    @Test
    @DisplayName("a fuzzy near-miss is never auto-accepted — those are suggestions, not matches")
    void doesNotAutoMatchFuzzily() {
        // The catalogue holds SN74163N; the BOM says SN7416. Trigram-similar, entirely different
        // parts — this is the shape of failure the datasheet re-sourcing work already paid for.
        when(partRepository.findByOrganisationIdAndPartNumberIgnoreCase(ORG_ID, "SN7416"))
                .thenReturn(List.of());

        BomImportPreviewDTO preview = service.preview(PROJECT_ID, file("""
                Reference,Value
                U1,SN7416
                """), null);

        assertEquals(0, preview.getAutoMatched());
    }

    // ------------------------------------------------------------------
    // Re-import — the point of the whole design
    // ------------------------------------------------------------------

    @Test
    @DisplayName("re-importing keeps a confirmed match and does not re-add the line")
    void reimportKeepsConfirmedMatch() {
        givenStoredBom(matchedLine(1, "C1,C2", "100nF", "C_0805", part(300L, "CAP-100N")));

        BomImportPreviewDTO preview = service.preview(PROJECT_ID, file("""
                Reference,Value,Footprint,Qty
                "C1,C2",100nF,C_0805,2
                """), null);

        assertEquals(0, preview.getAdded());
        assertEquals(0, preview.getRemoved());
        assertEquals(1, preview.getUnchanged());
        assertEquals("CAP-100N", preview.getLines().get(0).getMatchedPartNumber());
    }

    @Test
    @DisplayName("a re-designated line carries its match across on value and footprint")
    void reimportCarriesMatchAcrossRenumbering() {
        // The schematic was tidied and C7 became C12. Pairing on designators alone would call that
        // a deletion and an addition, losing the match on a part that never changed.
        givenStoredBom(matchedLine(1, "C7", "100nF", "C_0805", part(301L, "CAP-100N")));

        BomImportPreviewDTO preview = service.preview(PROJECT_ID, file("""
                Reference,Value,Footprint,Qty
                C12,100nF,C_0805,1
                """), null);

        assertEquals(0, preview.getAdded());
        assertEquals(0, preview.getRemoved());
        assertEquals(1, preview.getUpdated());
        assertEquals("CAP-100N", preview.getLines().get(0).getMatchedPartNumber());
    }

    @Test
    @DisplayName("a value change under an existing match keeps the match but flags it for review")
    void valueChangeFlagsTheLine() {
        givenStoredBom(matchedLine(1, "R1", "10k", "R_0805", part(302L, "RES-10K")));

        BomImportPreviewDTO preview = service.preview(PROJECT_ID, file("""
                Reference,Value,Footprint,Qty
                R1,4k7,R_0805,1
                """), null);

        assertEquals(1, preview.getChanged());
        assertEquals(1, preview.getUpdated());
        BomImportLinePreviewDTO line = preview.getLines().get(0);
        assertTrue(line.isChanged());
        assertEquals("RES-10K", line.getMatchedPartNumber(),
                "the match is kept — the user decides whether it is still right");
    }

    @Test
    @DisplayName("a line that left the schematic is reported as removed, not silently dropped")
    void removedLineIsReported() {
        givenStoredBom(
                matchedLine(1, "R1", "10k", "R_0805", part(303L, "RES-10K")),
                matchedLine(2, "R2", "1k", "R_0805", part(304L, "RES-1K")));

        BomImportPreviewDTO preview = service.preview(PROJECT_ID, file("""
                Reference,Value,Footprint,Qty
                R1,10k,R_0805,1
                """), null);

        assertEquals(1, preview.getRemoved());
        assertEquals(1, preview.getUnchanged());
        assertTrue(preview.getLines().stream()
                .anyMatch(l -> "REMOVED".equals(l.getAction()) && "R2".equals(l.getDesignators())));
    }

    @Test
    @DisplayName("a preview writes nothing — no save, no delete")
    void previewWritesNothing() {
        givenStoredBom(matchedLine(1, "R2", "1k", "R_0805", part(305L, "RES-1K")));

        service.preview(PROJECT_ID, file("""
                Reference,Value,Footprint,Qty
                R1,10k,R_0805,1
                """), null);

        verify(bomRepository, never()).save(any());
        verify(lineRepository, never()).saveAll(any());
        verify(lineRepository, never()).deleteAll(any());
    }

    @Test
    @DisplayName("a preview detaches the stored lines before touching them")
    void previewDetachesStoredLines() {
        // Not ceremony. The merge refreshes the loaded entities in place; left attached, Hibernate
        // flushes those edits and the "preview" silently performs the import. That was measured
        // against a running instance — a dry run moved a matched line's value and set its review
        // flag — and `@Transactional(readOnly = true)` did not stop it, because open-in-view keeps
        // the EntityManager alive past the transaction that set the flush mode. Mocks cannot
        // reproduce a flush, so what is pinned here is the mechanism that prevents it.
        ProjectBomLine stored = matchedLine(1, "R1", "10k", "R_0805", part(310L, "RES-10K"));
        givenStoredBom(stored);

        service.preview(PROJECT_ID, file("""
                Reference,Value,Footprint,Qty
                R1,4k7,R_0805,1
                """), null);

        verify(entityManager).detach(stored);
    }

    @Test
    @DisplayName("a commit does not detach — those edits are meant to be written")
    void commitKeepsLinesAttached() {
        ProjectBomLine stored = matchedLine(1, "R1", "10k", "R_0805", part(311L, "RES-10K"));
        givenStoredBom(stored);

        service.commit(PROJECT_ID, file("""
                Reference,Value,Footprint,Qty
                R1,4k7,R_0805,1
                """), null);

        verify(entityManager, never()).detach(any());
    }

    @Test
    @DisplayName("committing deletes the departed lines and saves the rest")
    void commitWritesTheMerge() {
        givenStoredBom(
                matchedLine(1, "R1", "10k", "R_0805", part(306L, "RES-10K")),
                matchedLine(2, "R2", "1k", "R_0805", part(307L, "RES-1K")));

        BomImportPreviewDTO result = service.commit(PROJECT_ID, file("""
                Reference,Value,Footprint,Qty
                R1,10k,R_0805,1
                C1,100nF,C_0805,1
                """), null);

        assertTrue(result.isCommitted());
        assertEquals(1, result.getAdded());
        assertEquals(1, result.getRemoved());
        verify(lineRepository).deleteAll(argThat((List<ProjectBomLine> lines) ->
                lines.size() == 1 && "R2".equals(lines.get(0).getDesignators())));
        verify(bomRepository).save(any(ProjectBom.class));
    }

    @Test
    @DisplayName("an unmatched line gets another go at auto-match on re-import")
    void retriesAutoMatchOnUnmatchedLines() {
        // The part was catalogued between the two imports; the line should pick it up rather than
        // waiting for the user to notice.
        givenStoredBom(unmatchedLine(1, "U1", "LM317", "TO-220"));
        when(partRepository.findByOrganisationIdAndPartNumberIgnoreCase(ORG_ID, "LM317"))
                .thenReturn(List.of(part(308L, "LM317")));

        BomImportPreviewDTO preview = service.preview(PROJECT_ID, file("""
                Reference,Value,Footprint,Qty
                U1,LM317,TO-220,1
                """), null);

        assertEquals(1, preview.getAutoMatched());
    }

    @Test
    @DisplayName("a line the user marked provided is left alone by a re-import")
    void doesNotOverrideAUserDecision() {
        ProjectBomLine provided = unmatchedLine(1, "R1", "10k", "R_0805");
        provided.setStatus(BomLineStatus.PROVIDED);
        givenStoredBom(provided);
        when(partRepository.findByOrganisationIdAndPartNumberIgnoreCase(ORG_ID, "10k"))
                .thenReturn(List.of(part(309L, "10k")));

        service.commit(PROJECT_ID, file("""
                Reference,Value,Footprint,Qty
                R1,10k,R_0805,1
                """), null);

        assertEquals(BomLineStatus.PROVIDED, provided.getStatus());
        assertNull(provided.getPart());
    }

    @Test
    @DisplayName("the remembered column mapping is reused when the file still has those columns")
    void reusesRememberedMapping() {
        ProjectBom stored = new ProjectBom();
        stored.setId(1L);
        stored.setColumnMapping(Map.of("REFERENCES", "Designator", "VALUE", "Comment"));
        bom = stored;

        BomImportPreviewDTO preview = service.preview(PROJECT_ID, file("""
                Designator,Comment,Extra
                R1,10k,x
                """), null);

        assertEquals("Designator", preview.getMapping().get("REFERENCES"));
        assertEquals("Comment", preview.getMapping().get("VALUE"));
    }

    @Test
    @DisplayName("a user-supplied mapping wins, and naming a column the file lacks is rejected")
    void honoursAndValidatesTheOverride() {
        BomImportPreviewDTO preview = service.preview(PROJECT_ID, file("""
                Bestellnummer,Menge,Bezeichnung
                R1,2,10k
                """), Map.of("REFERENCES", "Bestellnummer", "QUANTITY", "Menge", "VALUE", "Bezeichnung"));

        assertEquals(2, preview.getLines().get(0).getQuantity());
        assertEquals("10k", preview.getLines().get(0).getValue());

        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> service.preview(PROJECT_ID, file("A,B\n1,2\n"), Map.of("REFERENCES", "Nope")));
    }

    @Test
    @DisplayName("unmapped columns are kept rather than thrown away")
    void keepsUnmappedColumns() {
        service.commit(PROJECT_ID, file("""
                Reference,Value,LCSC,Supplier
                R1,10k,C25804,JLCPCB
                """), null);

        assertEquals("C25804", savedLine("R1").getExtra().get("LCSC"));
        assertEquals("JLCPCB", savedLine("R1").getExtra().get("Supplier"));
    }

    @Test
    @DisplayName("a file with no designators still produces unique keys per line")
    void synthesisesKeysWithoutDesignators() {
        BomImportPreviewDTO preview = service.preview(PROJECT_ID, file("""
                MPN,Qty
                LM317,2
                LM317,3
                """), null);

        assertEquals(2, preview.getTotalLines());
        assertEquals(2, preview.getAdded());
        assertTrue(preview.getWarnings().stream().anyMatch(w -> w.contains("designator")));
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private MockMultipartFile file(String csv) {
        return new MockMultipartFile("file", "bom.csv", "text/csv",
                csv.getBytes(StandardCharsets.UTF_8));
    }

    private void givenStoredBom(ProjectBomLine... lines) {
        bom = new ProjectBom();
        bom.setId(1L);
        bom.setProject(project);
        storedLines = new ArrayList<>(List.of(lines));
        storedLines.forEach(l -> l.setBom(bom));
    }

    private final AtomicLong lineIds = new AtomicLong(1000);

    private ProjectBomLine unmatchedLine(int lineNo, String designators, String value, String footprint) {
        ProjectBomLine line = new ProjectBomLine();
        line.setId(lineIds.incrementAndGet());
        line.setLineNo(lineNo);
        line.setReferenceKey(DesignatorKey.normalize(designators));
        line.setDesignators(designators);
        line.setValue(value);
        line.setFootprint(footprint);
        line.setQuantity(DesignatorKey.count(designators));
        line.setStatus(BomLineStatus.UNMATCHED);
        return line;
    }

    private ProjectBomLine matchedLine(int lineNo, String designators, String value,
                                       String footprint, Part part) {
        ProjectBomLine line = unmatchedLine(lineNo, designators, value, footprint);
        line.setPart(part);
        line.setStatus(BomLineStatus.MATCHED);
        line.setMatchSource(BomMatchSource.MANUAL);
        return line;
    }

    private Part part(Long id, String partNumber) {
        Part part = new Part();
        part.setId(id);
        part.setPartNumber(partNumber);
        return part;
    }

    /** Finds a line among everything that was saved, by its designators. */
    @SuppressWarnings("unchecked")
    private ProjectBomLine savedLine(String designators) {
        var captor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(lineRepository, atLeastOnce()).saveAll(captor.capture());
        return captor.getAllValues().stream()
                .flatMap(list -> ((List<ProjectBomLine>) list).stream())
                .filter(l -> designators.equals(l.getDesignators()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No saved line with designators " + designators));
    }
}
