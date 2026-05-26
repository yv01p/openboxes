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
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.io.IOException;
import java.net.URI;
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

    /**
     * Unified list/find endpoint. Exactly one discriminator (code, name, typeIds) must be
     * present; passing zero or multiple yields 400. Consolidated into a single handler so
     * springdoc emits one OpenAPI operation (springdoc merges multiple Spring MVC handlers
     * at the same (path, method) into a single operation regardless of {@code params=}
     * discrimination, which produced misleading "all params required" docs previously).
     *
     * <p>Return shape varies by discriminator:
     * <ul>
     *   <li>{@code code} → 200 with {@code List<Document>}</li>
     *   <li>{@code typeIds} → 200 with {@code List<Document>}</li>
     *   <li>{@code name} → 200 with {@code Document} (first match) or 404</li>
     * </ul>
     * Scalar name semantics match Grails {@code Document.findByName(String)} (OrderController:943).
     */
    @Operation(
            summary = "List or find documents (exactly one of code/name/typeIds required)",
            description = "code or typeIds return a list of matching documents. name returns the "
                    + "first matching document (404 if none). Passing zero or multiple discriminators returns 400."
    )
    @GetMapping
    public ResponseEntity<?> listOrFind(
            @RequestParam(required = false) DocumentCode code,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) List<String> typeIds) {

        int discriminators = (code != null ? 1 : 0)
                + (name != null ? 1 : 0)
                + (typeIds != null ? 1 : 0);
        if (discriminators != 1) {
            return ResponseEntity.badRequest().build();
        }

        if (code != null) {
            return ResponseEntity.ok(docService.findByCode(code));
        }
        if (typeIds != null) {
            return ResponseEntity.ok(docService.findByTypeIds(typeIds));
        }
        // name path: T3-M1 blank guard, then scalar lookup with 404 fallback.
        if (name.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        return docService.findByName(name)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @Operation(summary = "Upload document (multipart)")
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Document> create(@RequestParam("file") MultipartFile file,
                                           @RequestParam(value = "name", required = false) String name,
                                           @RequestParam(value = "documentTypeId", required = false) String documentTypeId)
            throws IOException {
        Document saved = docService.create(
                name != null ? name : file.getOriginalFilename(),
                file.getOriginalFilename(),
                file.getContentType(),
                file.getBytes(),
                documentTypeId);
        // 201 Created + Location header per REST convention.
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(saved.getId())
                .toUri();
        return ResponseEntity.created(location).body(saved);
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
