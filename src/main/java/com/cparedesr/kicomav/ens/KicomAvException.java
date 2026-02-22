package com.cparedesr.kicomav.ens;

/**
 * Custom exception class for KicomAv operations.
 * 
 * This is an unchecked exception that extends {@link RuntimeException} and is used
 * to wrap exceptions that occur during KicomAv processing.
 * 
 * @since 1.0
 */

public class KicomAvException extends RuntimeException {

    public KicomAvException(String message) {
        super(message);
    }

    public KicomAvException(String message, Throwable cause) {
        super(message, cause);
    }
}