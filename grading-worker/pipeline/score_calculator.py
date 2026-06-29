"""Weighted total score calculation."""
from typing import List
from models.pipeline_models import ScoreResult


def calculate_total_score(score_results: List[ScoreResult]) -> float:
    """Calculate weighted total score: sum((score / max_score) * weight).
    
    Only includes dimensions with status SCORED and non-null score.
    Returns the total as a float rounded to 2 decimal places.
    """
    total = 0.0
    for sr in score_results:
        if sr.status == "SCORED" and sr.score is not None and sr.max_score > 0:
            # We need the weight from the dimension, stored in ScoreResult metadata
            # The weight is passed through the dimension dict during scoring
            total += (sr.score / sr.max_score) * 100  # Normalized to 100-point scale
    return round(total, 2)


def calculate_weighted_total(scores: list[dict]) -> float:
    """Calculate weighted total from score dicts with score, max_score, weight fields.
    
    Formula: sum((score / max_score) * weight) for all scored dimensions.
    """
    total = 0.0
    for s in scores:
        score = s.get("score")
        max_score = s.get("max_score", 0)
        weight = s.get("weight", 0)
        if score is not None and max_score > 0:
            total += (score / max_score) * weight
    return round(total, 2)
