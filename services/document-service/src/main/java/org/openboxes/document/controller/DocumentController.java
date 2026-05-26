package org.openboxes.document.controller;

import io.swagger.v3.oas.annotations.Operation;
import org.openboxes.document.entity.Document;
import org.openboxes.document.entity.DocumentCode;
import org.openboxes.document.entity.DocumentType;
import org.openboxes.document.service.DocumentService;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * REST surface for the Document slice. Mirrors the Grails-side endpoints called by
 * {@code DocumentClient.groovy} (Task 8b) and by nginx-routed external clients (Task 9).
 *
 * <p>Exception handlers are scoped to this controller (not a global {@code @ControllerAdvice})
 * to keep the Document slice's HTTP semantics self-contained.
 */
@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private final DocumentService docService;

    public DocumentController(DocumentService docService) {
        this.docService = docService;
    }

    @Operation(summary = "Fetch document metadata")
    @GetMapping("/{id}")
    public ResponseEntity<Document> getById(@PathVariable String id) {
        return docService.findById(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @Operation(summary = "Stream document content")
    @GetMapping("/{id}/content")
    public ResponseEntity<byte[]> getContent(@PathVariable String id) {
        return docService.findById(id)
                .map(d -> {
                    String contentType = d.getContentType() != null
                            ? d.getContentType()
                            : MediaType.APPLICATION_OCTET_STREAM_VALUE;
                    // Use ContentDisposition builder so filenames with quotes, CR/LF, or non-ASCII
                    // bytes are properly encoded (RFC 5987 / 6266) instead of injected raw into the
                    // header — minor hardening against malicious filenames.
                    String disposition = ContentDisposition.attachment()
                            .filename(d.getFilename() != null ? d.getFilename() : "download",
                                    StandardCharsets.UTF_8)
                            .build()
                            .toString();
                    return ResponseEntity.ok()
                            .header(HttpHeaders.CONTENT_TYPE, contentType)
                            .header(HttpHeaders.CONTENT_DISPOSITION, disposition)
                            .body(d.getFileContents());
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @Operation(summary = "List documents by document code")
    @GetMapping(params = "code")
    public List<Document> listByCode(@RequestParam DocumentCode code) {
        return docService.findByCode(code);
    }

    /**
     * Single-document lookup by name (scalar semantics — matches Grails' singular
     * {@code Document.findByName(String)} dynamic finder; OrderController:943 is the
     * only known caller and expects a single Document).
     */
    @Operation(summary = "Find document by name (first match)")
    @GetMapping(params = "name")
    public ResponseEntity<Document> getByName(@RequestParam String name) {
        // T3-M1: manual guard rejects empty/blank name with 400 (Spring already 400s on missing).
        if (name.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        return docService.findByName(name)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @Operation(summary = "List documents whose document_type is in the given set")
    @GetMapping(params = "typeIds")
    public List<Document> listByTypeIds(@RequestParam List<String> typeIds) {
        return docService.findByTypeIds(typeIds);
    }

    @Operation(summary = "Upload document (multipart)")
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Document create(@RequestParam("file") MultipartFile file,
                           @RequestParam(value = "name", required = false) String name,
                           @RequestParam(value = "documentTypeId", required = false) String documentTypeId)
            throws IOException {
        return docService.create(
                name != null ? name : file.getOriginalFilename(),
                file.getOriginalFilename(),
                file.getContentType(),
                file.getBytes(),
                documentTypeId);
    }

    @Operation(summary = "Delete document")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        docService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "List non-template document types (for caller forms)")
    @GetMapping("/types/non-template")
    public List<DocumentType> nonTemplateTypes() {
        return docService.getNonTemplateDocumentTypes();
    }

    // T4-M1: a delete() against a missing id throws EmptyResultDataAccessException from
    // Spring Data JPA. Map to 404 so callers see "the row is gone" rather than a 500.
    @ExceptionHandler(EmptyResultDataAccessException.class)
    public ResponseEntity<Void> handleNotFound() {
        return ResponseEntity.notFound().build();
    }

    // T4-M3: DocumentService.create() throws IllegalArgumentException on an unknown
    // documentTypeId (instead of silently dropping it like Grails). Surface as 400.
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> handleBadRequest(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(ex.getMessage());
    }
}
