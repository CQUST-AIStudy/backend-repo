package com.tap.backend.domain.grading;

import jakarta.persistence.*;
import java.math.BigDecimal;

@Entity
@Table(name = "rubric_dimension")
public class RubricDimensionEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "rubric_id", nullable = false)
    private GradingRubricEntity rubric;

    @Column(name = "rubric_id", insertable = false, updatable = false)
    private Long rubricId;

    @Column(name = "name", nullable = false, length = 256)
    private String name;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    @Column(name = "max_score", nullable = false, precision = 5, scale = 1)
    private BigDecimal maxScore;

    @Column(name = "weight", nullable = false)
    private Integer weight;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 0;

    public Long getId() { return id; }
    public GradingRubricEntity getRubric() { return rubric; }
    public void setRubric(GradingRubricEntity rubric) { this.rubric = rubric; }
    public Long getRubricId() { return rubricId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public BigDecimal getMaxScore() { return maxScore; }
    public void setMaxScore(BigDecimal maxScore) { this.maxScore = maxScore; }
    public Integer getWeight() { return weight; }
    public void setWeight(Integer weight) { this.weight = weight; }
    public Integer getSortOrder() { return sortOrder; }
    public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }
}
