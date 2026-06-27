package com.tap.backend.domain.grading;

public enum SubmissionMatchStatus {
    UNMATCHED,
    AUTO_CONFIRMED,
    MANUAL_CONFIRMED,
    AMBIGUOUS;

    public boolean isConfirmed() {
        return this == AUTO_CONFIRMED || this == MANUAL_CONFIRMED;
    }
}
