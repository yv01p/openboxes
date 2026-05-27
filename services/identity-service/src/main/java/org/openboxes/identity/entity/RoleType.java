package org.openboxes.identity.entity;

/**
 * Enum mirroring Grails RoleType.groovy.
 * Identity-service uses names only for authorization checks.
 * Grails-side sortOrder and displayName are not ported (YAGNI).
 */
public enum RoleType {
    // Core roles
    ROLE_SUPERUSER,
    ROLE_ADMIN,
    ROLE_MANAGER,
    ROLE_ASSISTANT,
    ROLE_BROWSER,
    ROLE_AUTHENTICATED,
    ROLE_ANONYMOUS,

    // Complementary roles
    ROLE_FINANCE,
    ROLE_INVOICE,
    ROLE_PRODUCT_MANAGER,

    // Notification roles - general system events
    ROLE_ERROR_NOTIFICATION,
    ROLE_FEEDBACK_NOTIFICATION,
    ROLE_PRODUCT_NOTIFICATION,
    ROLE_ORDER_NOTIFICATION,
    ROLE_USER_NOTIFICATION,

    // Notification roles - shipments and receipts
    ROLE_SHIPMENT_NOTIFICATION,
    ROLE_SHIPMENT_INBOUND_CREATED_NOTIFICATION,
    ROLE_SHIPMENT_OUTBOUND_CREATED_NOTIFICATION,
    ROLE_SHIPMENT_INBOUND_SHIPPED_NOTIFICATION,
    ROLE_SHIPMENT_OUTBOUND_SHIPPED_NOTIFICATION,
    ROLE_SHIPMENT_INBOUND_RECEIVED_NOTIFICATION,
    ROLE_SHIPMENT_OUTBOUND_RECEIVED_NOTIFICATION,

    // Notification roles - stock alerts
    ROLE_ITEM_ALL_NOTIFICATION,
    ROLE_ITEM_EXPIRY_NOTIFICATION,
    ROLE_ITEM_OVERSTOCK_NOTIFICATION,
    ROLE_ITEM_REORDER_NOTIFICATION,
    ROLE_ITEM_LOW_STOCK_NOTIFICATION,
    ROLE_ITEM_OUT_OF_STOCK_NOTIFICATION,

    // Employee role types
    ROLE_EMPLOYEE,

    // Purchasing roles
    ROLE_PURCHASE_APPROVER,
    ROLE_BUYER,

    // Warehouse roles
    ROLE_ORDER_CLERK,
    ROLE_PICKER,
    ROLE_PACKER,
    ROLE_RECEIVER,
    ROLE_SHIPMENT_CLERK,
    ROLE_STOCKER,
    ROLE_WORKER,

    // Pharmacy roles
    ROLE_PHARMACIST,
    ROLE_PHARMACY_TECH,

    // Organization role types
    ROLE_ORGANIZATION,
    ROLE_CARRIER,
    ROLE_SUPPLIER,
    ROLE_MANUFACTURER,
    ROLE_DISTRIBUTOR,
    ROLE_DONOR,
    ROLE_SHIPPING_AGENT,
    ROLE_CLEARING_AGENT,
    ROLE_PURCHASER,

    // Customer role types
    ROLE_CUSTOMER,

    // Requestor role type
    ROLE_REQUESTOR,

    // Requisition approver role type
    ROLE_REQUISITION_APPROVER
}
