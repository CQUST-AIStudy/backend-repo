package com.tap.backend.service;

public class InvalidClassPasswordException extends RuntimeException {
    public InvalidClassPasswordException() {
        super("班级密码错误");
    }
}
