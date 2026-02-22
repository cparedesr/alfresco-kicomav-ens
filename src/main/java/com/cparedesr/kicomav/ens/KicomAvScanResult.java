package com.cparedesr.kicomav.ens;

/**
 * Represents the result of a scan performed by KicomAV.
 * <p>
 * This class is immutable and encapsulates whether a file is infected and, if so, the detected signature.
 * </p>
 *
 * <ul>
 *   <li>{@code infected}: Indicates if the scan detected an infection.</li>
 *   <li>{@code signature}: The name or identifier of the detected virus signature, or {@code null} if clean.</li>
 * </ul>
 *
 * <p>
 * Use {@link #clean()} to create a result representing a clean file, and {@link #infected(String)} to create a result for an infected file.
 * </p>
 *
 * @author cparedesr
 */

public final class KicomAvScanResult {

    private final boolean infected;
    private final String signature;

    private KicomAvScanResult(boolean infected, String signature) {
        this.infected = infected;
        this.signature = signature;
    }

    public boolean isInfected() {
        return infected;
    }

    public String getSignature() {
        return signature;
    }

    public static KicomAvScanResult clean() {
        return new KicomAvScanResult(false, null);
    }

    public static KicomAvScanResult infected(String signature) {
        String sig = (signature == null || signature.isBlank()) ? "INFECTED" : signature;
        return new KicomAvScanResult(true, sig);
    }

    @Override
    public String toString() {
        return infected ? ("INFECTED: " + signature) : "CLEAN";
    }
}