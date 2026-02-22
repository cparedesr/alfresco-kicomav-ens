package com.cparedesr.kicomav.ens;

import org.alfresco.model.ContentModel;
import org.alfresco.service.cmr.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.ByteArrayInputStream;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

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

        // Stub mínimo común: la mayoría de tests asumen que el nodo existe.
        // Cada test puede sobreescribirlo si quiere el caso contrario.
        when(nodeService.exists(nodeRef)).thenReturn(true);
    }

    @Test
    void whenNodeDoesNotExist_shouldReturnWithoutScanning() {
        // Caso: el nodo no existe => early return
        when(nodeService.exists(nodeRef)).thenReturn(false);

        behaviour.onContentUpdate(nodeRef, true);

        // No debería ni intentar acceder al content service ni al AV
        verifyNoInteractions(contentService);
        verifyNoInteractions(kicomAvClient);
    }

    @Test
    void whenNoContentReader_shouldReturnWithoutScanning() {
        // Caso: no hay reader (p.ej. no hay content property)
        when(contentService.getReader(nodeRef, ContentModel.PROP_CONTENT)).thenReturn(null);

        behaviour.onContentUpdate(nodeRef, true);

        verifyNoInteractions(kicomAvClient);
    }

    @Test
    void whenReaderDoesNotExist_shouldReturnWithoutScanning() {
        // Caso: hay reader pero el contenido no existe (reader.exists() == false)
        when(contentService.getReader(nodeRef, ContentModel.PROP_CONTENT)).thenReturn(contentReader);
        when(contentReader.exists()).thenReturn(false);

        behaviour.onContentUpdate(nodeRef, true);

        verifyNoInteractions(kicomAvClient);
    }

    @Test
    void whenClean_shouldNotThrow() {
        // Configuramos reader válido con contenido
        when(contentService.getReader(nodeRef, ContentModel.PROP_CONTENT)).thenReturn(contentReader);
        when(contentReader.exists()).thenReturn(true);
        when(contentReader.getSize()).thenReturn(3L);
        when(contentReader.getContentInputStream()).thenReturn(new ByteArrayInputStream(new byte[]{1, 2, 3}));

        // En este test SÍ se llega a leer el nombre
        when(nodeService.getProperty(nodeRef, ContentModel.PROP_NAME)).thenReturn("doc.txt");

        // AV devuelve limpio
        when(kicomAvClient.scan(any(), eq("doc.txt"))).thenReturn(KicomAvScanResult.clean());

        // No debe lanzar excepción
        assertThatCode(() -> behaviour.onContentUpdate(nodeRef, true)).doesNotThrowAnyException();

        // Verificamos que escaneó exactamente 1 vez
        verify(kicomAvClient, times(1)).scan(any(), eq("doc.txt"));
    }

    @Test
    void whenInfected_shouldThrowToBlockUpload() {
        when(contentService.getReader(nodeRef, ContentModel.PROP_CONTENT)).thenReturn(contentReader);
        when(contentReader.exists()).thenReturn(true);
        when(contentReader.getContentInputStream()).thenReturn(new ByteArrayInputStream("x".getBytes()));

        when(nodeService.getProperty(nodeRef, ContentModel.PROP_NAME)).thenReturn("doc.txt");

        // AV detecta infección
        when(kicomAvClient.scan(any(), eq("doc.txt")))
                .thenReturn(KicomAvScanResult.infected("Eicar-Test-Signature"));

        // Debe lanzar para bloquear
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

        // Fallo del cliente AV (servicio caído, timeout, etc.)
        when(kicomAvClient.scan(any(), eq("doc.txt")))
                .thenThrow(new KicomAvException("KicomAV caído"));

        // El behaviour debe propagar el error (fail-closed)
        assertThatThrownBy(() -> behaviour.onContentUpdate(nodeRef, true))
                .isInstanceOf(KicomAvException.class)
                .hasMessageContaining("KicomAV caído");

        verify(kicomAvClient, times(1)).scan(any(), eq("doc.txt"));
    }

    @Test
    void whenUnexpectedException_shouldWrapAndThrowFailClosed() {
        when(contentService.getReader(nodeRef, ContentModel.PROP_CONTENT)).thenReturn(contentReader);
        when(contentReader.exists()).thenReturn(true);

        // ✅ CORRECCIÓN: aquí también se leerá el nombre para el log,
        // así evitamos que aparezca "name=null" en la salida del test.
        when(nodeService.getProperty(nodeRef, ContentModel.PROP_NAME)).thenReturn("doc.txt");

        // Simulamos error leyendo el contenido (IO / storage / etc.)
        when(contentReader.getContentInputStream()).thenThrow(new RuntimeException("storage error"));

        assertThatThrownBy(() -> behaviour.onContentUpdate(nodeRef, true))
                .isInstanceOf(KicomAvException.class)
                .hasMessageContaining("Error al escanear");

        // Como falló antes de llamar al AV, no debe interactuar con el cliente
        verifyNoInteractions(kicomAvClient);
    }
}