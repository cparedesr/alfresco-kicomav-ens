package com.cparedesr.kicomav.ens;

import org.alfresco.model.ContentModel;
import org.alfresco.repo.policy.JavaBehaviour;
import org.alfresco.repo.policy.PolicyComponent;
import org.alfresco.repo.policy.Behaviour.NotificationFrequency;
import org.alfresco.repo.content.ContentServicePolicies;
import org.alfresco.service.cmr.repository.*;
import org.alfresco.service.namespace.QName;
import org.alfresco.util.PropertyCheck;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;

/**
 * Behaviour que escanea contenido en onContentUpdate.
 *
 * Cambio clave:
 *  - failOpen configurable: si KicomAV falla (caído / timeout / respuesta rara),
 *    puedes elegir si bloquear la subida (fail-closed) o permitirla (fail-open).
 */
public class KicomAvContentScanBehaviour implements ContentServicePolicies.OnContentUpdatePolicy {

    private static final Logger LOG = LoggerFactory.getLogger(KicomAvContentScanBehaviour.class);

    private PolicyComponent policyComponent;
    private ContentService contentService;
    private NodeService nodeService;
    private KicomAvRestClient kicomAvClient;

    private QName classQName = ContentModel.TYPE_CONTENT;

    /**
     * Si true: si el AV falla, NO bloquea la subida.
     * Si false: si el AV falla, bloquea (comportamiento más seguro).
     */
    private boolean failOpen = false;

    public void init() {
        PropertyCheck.mandatory(this, "policyComponent", policyComponent);
        PropertyCheck.mandatory(this, "contentService", contentService);
        PropertyCheck.mandatory(this, "nodeService", nodeService);
        PropertyCheck.mandatory(this, "kicomAvClient", kicomAvClient);

        JavaBehaviour behaviour = new JavaBehaviour(this, "onContentUpdate", NotificationFrequency.EVERY_EVENT);

        policyComponent.bindClassBehaviour(
                ContentServicePolicies.OnContentUpdatePolicy.QNAME,
                classQName,
                behaviour
        );

        LOG.info("[KicomAV] Behaviour inicializado y bind a {} (failOpen={})", classQName, failOpen);
    }

    @Override
    public void onContentUpdate(NodeRef nodeRef, boolean newContent) {
        if (nodeRef == null) return;

        if (!nodeService.exists(nodeRef)) {
            LOG.debug("[KicomAV] node no existe: {}", nodeRef);
            return;
        }

        ContentReader reader = contentService.getReader(nodeRef, ContentModel.PROP_CONTENT);
        if (reader == null || !reader.exists()) {
            LOG.debug("[KicomAV] sin contenido para escanear: {}", nodeRef);
            return;
        }

        String name = (String) nodeService.getProperty(nodeRef, ContentModel.PROP_NAME);

        LOG.debug("[KicomAV] onContentUpdate node={} name={} newContent={} size={}",
                nodeRef, name, newContent, reader.getSize());

        try (InputStream in = reader.getContentInputStream()) {

            KicomAvScanResult result = kicomAvClient.scan(in, name);

            if (result.isInfected()) {
                String creator = (String) nodeService.getProperty(nodeRef, ContentModel.PROP_CREATOR);

                // Según lo que hablamos: infección como INFO (aunque en producción suele ser WARN/ERROR)
                LOG.info("[KicomAV] INFECCIÓN detectada: signature='{}' node={} name={} creator={}",
                        result.getSignature(), nodeRef, name, creator);

                // Bloquea subida
                throw new KicomAvException("Fichero infectado: " + result.getSignature());
            }

            LOG.info("[KicomAV] Nodo limpio: node={} name={}", nodeRef, name);

        } catch (KicomAvException e) {
            // Si viene de infección o del cliente, decidir failOpen/failClosed
            if (failOpen) {
                LOG.warn("[KicomAV] AV falló pero failOpen=true, permitiendo subida. node={} name={} cause={}",
                        nodeRef, name, e.toString());
                return;
            }
            throw e;

        } catch (Exception e) {
            // Errores inesperados de Alfresco/IO/etc.
            if (failOpen) {
                LOG.warn("[KicomAV] Error inesperado escaneando pero failOpen=true, permitiendo subida. node={} name={} cause={}",
                        nodeRef, name, e.toString(), e);
                return;
            }

            LOG.info("[KicomAV] Error escaneando. Bloqueando subida. node={} name={} cause={}",
                    nodeRef, name, e.toString(), e);
            throw new KicomAvException("Error al escanear con KicomAV: " + e.getMessage(), e);
        }
    }

    // Setters Spring
    public void setPolicyComponent(PolicyComponent policyComponent) {
        this.policyComponent = policyComponent;
    }

    public void setContentService(ContentService contentService) {
        this.contentService = contentService;
    }

    public void setNodeService(NodeService nodeService) {
        this.nodeService = nodeService;
    }

    public void setKicomAvClient(KicomAvRestClient kicomAvClient) {
        this.kicomAvClient = kicomAvClient;
    }

    public void setClassQName(QName classQName) {
        this.classQName = classQName;
    }

    public void setFailOpen(boolean failOpen) {
        this.failOpen = failOpen;
    }
}