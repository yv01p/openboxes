/**
* Copyright (c) 2012 Partners In Health.  All rights reserved.
* The use and distribution terms for this software are covered by the
* Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
* which can be found in the file epl-v10.html at the root of this distribution.
* By using this software in any fashion, you are agreeing to be bound by
* the terms of this license.
* You must not remove this notice, or any other, from this software.
**/
package org.pih.warehouse.core

import groovy.text.Template
import org.grails.gsp.GroovyPagesTemplateEngine

class TemplateService {

    GroovyPagesTemplateEngine groovyPagesTemplateEngine

    /**
     * Renders a template document. Accepts either:
     *   - a Map with keys `fileContents` (byte[]) and `name` (String), or
     *   - a domain Document (legacy in-process callers; Groovy property access works the same).
     *
     * Map-based callers fetch content via DocumentClient.fetchContent(id) and pre-populate the
     * Map before invoking this method.
     */
    String renderTemplate(def document, Map model) {
        String templateContent = new String(document.fileContents)
        renderTemplate(templateContent, document.name, model)
    }

    String renderTemplate(String templateContent, String pageName, Map model) {
        Template template =
                groovyPagesTemplateEngine.createTemplate(templateContent, pageName)

        Writable renderedTemplate = template.make(model)
        StringWriter stringWriter = new StringWriter()
        renderedTemplate.writeTo(stringWriter)
        return stringWriter.toString()
    }
}
