/**
 * Copyright (c) 2012 Partners In Health.  All rights reserved.
 * The use and distribution terms for this software are covered by the
 * Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
 * which can be found in the file epl-v10.html at the root of this distribution.
 * By using this software in any fashion, you are agreeing to be bound by
 * the terms of this license.
 * You must not remove this notice, or any other, from this software.
 **/
package org.pih.warehouse.shipping

import grails.gorm.transactions.Transactional
import grails.validation.Validateable
import org.pih.warehouse.core.Document
import org.springframework.web.multipart.MultipartFile

@Transactional
class DocumentUploadController {

    def documentClient

    def upload(DocumentUploadCommand command) {
        def shipment = Shipment.get(command.shipmentId)
        // Upload to document-service; re-attach via proxy id so the shipment_document join
        // (still Grails-managed this slice) records the row.
        Map created = documentClient.create(
                command.file?.originalFilename,
                command.file?.originalFilename,
                command.file?.contentType,
                command.file?.bytes)
        shipment.addToDocuments(Document.load(created.id))
        redirect(action: 'view', id: command.shipmentId)
    }
    def form() {

        [shipments: Shipment.list()]
    }
    def view() {}
}

class DocumentUploadCommand implements Validateable {
    String shipmentId
    MultipartFile file
}
