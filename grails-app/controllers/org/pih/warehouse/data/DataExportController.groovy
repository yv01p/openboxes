/**
* Copyright (c) 2012 Partners In Health.  All rights reserved.
* The use and distribution terms for this software are covered by the
* Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
* which can be found in the file epl-v10.html at the root of this distribution.
* By using this software in any fashion, you are agreeing to be bound by
* the terms of this license.
* You must not remove this notice, or any other, from this software.
**/
package org.pih.warehouse.data

import grails.converters.JSON

import java.nio.charset.Charset

class DataExportController {

    def dataService
    def documentClient

    def index() {
        List<Map> documents = documentClient.findByCode('DATA_EXPORT')
        [documents: documents]
    }

    def render() {
        Map document = documentClient.fetchById(params.id)
        byte[] fileContents = documentClient.fetchContent(params.id)
        String query = new String(fileContents, Charset.defaultCharset());
        if (query) {
            def data = dataService.executeQuery(query)
            if (params.format == "csv") {
                String csv = dataService.generateCsv(data)
                response.setHeader("Content-disposition", "attachment; filename=\"${document.name}.csv\"")
                render(contentType: "text/csv", text: csv.toString(), encoding: "UTF-8")
                return
            }
            render dataService.executeQuery(query) as JSON
            return
        }
        render document as JSON

    }

}
