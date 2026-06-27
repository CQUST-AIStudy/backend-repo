package com.tap.backend.service;

public class ClassDeletionBlockedException extends RuntimeException {

    public ClassDeletionBlockedException(String message) {
        super(message);
    }
}
