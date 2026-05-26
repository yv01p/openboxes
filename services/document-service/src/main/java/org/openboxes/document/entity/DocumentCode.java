package org.openboxes.document.entity;

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
    REQUISITION_TEMPLATE
}
