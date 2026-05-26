package org.openboxes.document.entity;

import java.util.Set;

/**
 * Mirrors {@code org.pih.warehouse.core.DocumentCode} from the Grails monolith.
 * Order and names must stay in sync with
 * {@code src/main/groovy/org/pih/warehouse/core/DocumentCode.groovy} because the
 * value is persisted as {@code @Enumerated(EnumType.STRING)} in {@code document_type.document_code}
 * and both ORMs read/write the same column.
 */
public enum DocumentCode {
    IMAGE,
    THUMBNAIL,
    PRODUCT_MANUAL,
    PURCHASE_ORDER_TEMPLATE,
    SHIPPING_DOCUMENT,
    SHIPPING_TEMPLATE,
    ZEBRA_TEMPLATE,
    EMAIL_TEMPLATE,
    DATA_EXPORT,
    INVOICE_TEMPLATE,
    REQUISITION_TEMPLATE;

    /**
     * Mirrors Grails {@code DocumentCode.templateList()} — codes that designate template-bearing
     * {@code DocumentType}s. Used by {@code DocumentTypeRepository} to identify non-template types
     * via {@code documentCode IS NULL OR documentCode NOT IN TEMPLATE_CODES}.
     */
    public static final Set<DocumentCode> TEMPLATE_CODES = Set.of(
            PURCHASE_ORDER_TEMPLATE,
            SHIPPING_TEMPLATE,
            ZEBRA_TEMPLATE,
            EMAIL_TEMPLATE,
            INVOICE_TEMPLATE,
            REQUISITION_TEMPLATE
    );
}
