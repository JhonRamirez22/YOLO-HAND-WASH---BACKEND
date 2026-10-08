package com.handwash.service;

import java.io.IOException;

/** Indicates that an uploaded image exceeds a configured byte or pixel limit. */
public class ImagePayloadTooLargeException extends IOException {
    public ImagePayloadTooLargeException(String message) {
        super(message);
    }
}
