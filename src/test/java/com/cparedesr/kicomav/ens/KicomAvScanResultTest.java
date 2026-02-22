package com.cparedesr.kicomav.ens;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for the {@link KicomAvScanResult} class.
 * <p>
 * Tests cover:
 * <ul>
 *   <li>{@code cleanFactory_shouldBeClean}: Verifies that the {@code clean()} factory method returns a result
 *       indicating no infection, with a null signature and a string representation of "CLEAN".</li>
 *   <li>{@code infectedFactory_shouldContainSignature}: Verifies that the {@code infected(String)} factory method
 *       returns a result indicating infection, with the provided signature and a string representation containing "INFECTED".</li>
 *   <li>{@code infectedFactory_blankSignature_shouldDefault}: Verifies that when a blank signature is provided to
 *       {@code infected(String)}, the signature defaults to "INFECTED".</li>
 * </ul>
 */

class KicomAvScanResultTest {

    @Test
    void cleanFactory_shouldBeClean() {
        KicomAvScanResult r = KicomAvScanResult.clean();
        assertThat(r.isInfected()).isFalse();
        assertThat(r.getSignature()).isNull();
        assertThat(r.toString()).isEqualTo("CLEAN");
    }

    @Test
    void infectedFactory_shouldContainSignature() {
        KicomAvScanResult r = KicomAvScanResult.infected("X");
        assertThat(r.isInfected()).isTrue();
        assertThat(r.getSignature()).isEqualTo("X");
        assertThat(r.toString()).contains("INFECTED");
    }

    @Test
    void infectedFactory_blankSignature_shouldDefault() {
        KicomAvScanResult r = KicomAvScanResult.infected("  ");
        assertThat(r.isInfected()).isTrue();
        assertThat(r.getSignature()).isEqualTo("INFECTED");
    }
}