package com.synchros.common;

import java.util.NoSuchElementException;

/** Thrown when a requested resource does not exist. */
public class NotFoundException extends NoSuchElementException {
    public NotFoundException(String message) {
        super(message);
    }
}
