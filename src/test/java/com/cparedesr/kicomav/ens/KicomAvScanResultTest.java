package com.cparedesr.kicomav.ens;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

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