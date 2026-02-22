package com.cparedesr.kicomav.ens;

import org.alfresco.model.ContentModel;
import org.alfresco.service.cmr.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.ByteArrayInputStream;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link KicomAvContentScanBehaviour}.
 * <p>
 * This test class verifies the behaviour of the content scanning logic,
 * ensuring that:
 * <ul>
 *   <li>No scan occurs if the node does not exist.</li>
 *   <li>No scan occurs if there is no content reader.</li>
 *   <li>No scan occurs if the content reader does not exist.</li>
 *   <li>Clean content does not throw exceptions and triggers a scan.</li>
 *   <li>Infected content throws {@link KicomAvException} to block upload.</li>
 *   <li>Client failures throw {@link KicomAvException} and fail closed.</li>
 *   <li>Unexpected exceptions are wrapped and thrown as {@link KicomAvException}.</li>
 * </ul>
 * <p>
 * Mocks are used for dependencies: {@code ContentService}, {@code NodeService},
 * {@code ContentReader}, and {@code KicomAvRestClient}.
 * <p>
 * Test coverage includes:
 * <ul>
 *   <li>Node existence checks</li>
 *   <li>Content reader availability</li>
 *   <li>Scan result handling (clean/infected)</li>
 *   <li>Exception handling and fail-closed logic</li>
 * </ul>
 */

@ExtendWith(org.mockito.junit.jupiter.MockitoExtension.class)
class KicomAvContentScanBehaviourTest {

    @org.mockito.Mock private ContentService contentService;
    @org.mockito.Mock private NodeService nodeService;
    @org.mockito.Mock private ContentReader contentReader;
    @org.mockito.Mock private KicomAvRestClient kicomAvClient;

    private KicomAvContentScanBehaviour behaviour;
    private final NodeRef nodeRef =
            new NodeRef("workspace://SpacesStore/00000000-0000-0000-0000-000000000000");

    @BeforeEach
    void setup() {
        behaviour = new KicomAvContentScanBehaviour();
        behaviour.setContentService(contentService);
        behaviour.setNodeService(nodeService);
        behaviour.setKicomAvClient(kicomAvClient);

        when(nodeService.exists(nodeRef)).thenReturn(true);
    }

    @Test
    void whenNodeDoesNotExist_shouldReturnWithoutScanning() {
        when(nodeService.exists(nodeRef)).thenReturn(false);

        behaviour.onContentUpdate(nodeRef, true);
        verifyNoInteractions(contentService);
        verifyNoInteractions(kicomAvClient);
    }

    @Test
    void whenNoContentReader_shouldReturnWithoutScanning() {
        when(contentService.getReader(nodeRef, ContentModel.PROP_CONTENT)).thenReturn(null);

        behaviour.onContentUpdate(nodeRef, true);

        verifyNoInteractions(kicomAvClient);
    }

    @Test
    void whenReaderDoesNotExist_shouldReturnWithoutScanning() {
        when(contentService.getReader(nodeRef, ContentModel.PROP_CONTENT)).thenReturn(contentReader);
        when(contentReader.exists()).thenReturn(false);

        behaviour.onContentUpdate(nodeRef, true);

        verifyNoInteractions(kicomAvClient);
    }

    @Test
    void whenClean_shouldNotThrow() {
        when(contentService.getReader(nodeRef, ContentModel.PROP_CONTENT)).thenReturn(contentReader);
        when(contentReader.exists()).thenReturn(true);
        when(contentReader.getSize()).thenReturn(3L);
        when(contentReader.getContentInputStream()).thenReturn(new ByteArrayInputStream(new byte[]{1, 2, 3}));
        when(nodeService.getProperty(nodeRef, ContentModel.PROP_NAME)).thenReturn("doc.txt");
        when(kicomAvClient.scan(any(), eq("doc.txt"))).thenReturn(KicomAvScanResult.clean());
        assertThatCode(() -> behaviour.onContentUpdate(nodeRef, true)).doesNotThrowAnyException();
        verify(kicomAvClient, times(1)).scan(any(), eq("doc.txt"));
    }

    @Test
    void whenInfected_shouldThrowToBlockUpload() {
        when(contentService.getReader(nodeRef, ContentModel.PROP_CONTENT)).thenReturn(contentReader);
        when(contentReader.exists()).thenReturn(true);
        when(contentReader.getContentInputStream()).thenReturn(new ByteArrayInputStream("x".getBytes()));

        when(nodeService.getProperty(nodeRef, ContentModel.PROP_NAME)).thenReturn("doc.txt");
        when(kicomAvClient.scan(any(), eq("doc.txt")))
                .thenReturn(KicomAvScanResult.infected("Eicar-Test-Signature"));
        assertThatThrownBy(() -> behaviour.onContentUpdate(nodeRef, true))
                .isInstanceOf(KicomAvException.class)
                .hasMessageContaining("infectado");

        verify(kicomAvClient, times(1)).scan(any(), eq("doc.txt"));
    }

    @Test
    void whenClientThrows_shouldFailClosedAndThrow() {
        when(contentService.getReader(nodeRef, ContentModel.PROP_CONTENT)).thenReturn(contentReader);
        when(contentReader.exists()).thenReturn(true);
        when(contentReader.getContentInputStream()).thenReturn(new ByteArrayInputStream("x".getBytes()));

        when(nodeService.getProperty(nodeRef, ContentModel.PROP_NAME)).thenReturn("doc.txt");
        when(kicomAvClient.scan(any(), eq("doc.txt")))
                .thenThrow(new KicomAvException("KicomAV caído"));
        assertThatThrownBy(() -> behaviour.onContentUpdate(nodeRef, true))
                .isInstanceOf(KicomAvException.class)
                .hasMessageContaining("KicomAV caído");

        verify(kicomAvClient, times(1)).scan(any(), eq("doc.txt"));
    }

    @Test
    void whenUnexpectedException_shouldWrapAndThrowFailClosed() {
        when(contentService.getReader(nodeRef, ContentModel.PROP_CONTENT)).thenReturn(contentReader);
        when(contentReader.exists()).thenReturn(true);
        when(nodeService.getProperty(nodeRef, ContentModel.PROP_NAME)).thenReturn("doc.txt");
        when(contentReader.getContentInputStream()).thenThrow(new RuntimeException("storage error"));

        assertThatThrownBy(() -> behaviour.onContentUpdate(nodeRef, true))
                .isInstanceOf(KicomAvException.class)
                .hasMessageContaining("Error al escanear");
        verifyNoInteractions(kicomAvClient);
    }
}