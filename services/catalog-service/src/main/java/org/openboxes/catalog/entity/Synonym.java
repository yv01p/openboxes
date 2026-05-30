package org.openboxes.catalog.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.Locale;

@Entity
@Table(name = "synonym")
public class Synonym {
    @Id @Column(columnDefinition = "CHAR(38)") private String id;
    @Column(nullable = false) private String name;

    // synonym.locale is varchar(50) NULLABLE in DB; entity holds java.util.Locale
    // (Hibernate 6 has built-in LocaleJavaType → varchar mapping)
    private Locale locale;

    @Column(name = "synonym_type_code")
    private String synonymTypeCode;  // SynonymTypeCode enum stored as String

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false, columnDefinition = "CHAR(38)")
    private Product product;

    @Column(name = "date_created") private Instant dateCreated;
    @Column(name = "last_updated") private Instant lastUpdated;

    protected Synonym() {}

    public String getId() { return id; }
    public String getName() { return name; }
    public Locale getLocale() { return locale; }
    public String getSynonymTypeCode() { return synonymTypeCode; }
    public Product getProduct() { return product; }
    public Instant getDateCreated() { return dateCreated; }
    public Instant getLastUpdated() { return lastUpdated; }
}
